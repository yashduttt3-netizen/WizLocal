package com.local.wiz

import android.content.Context
import android.content.SharedPreferences
import android.net.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.*
import kotlin.math.PI
import kotlin.math.sin

object Wiz {
    @Volatile var network: Network? = null
    private const val PORT = 38899
    private const val GET = """{"method":"getPilot","params":{}}"""

    suspend fun send(ip: String, json: String, times: Int = 2) {
        withContext(Dispatchers.IO) {
            try {
                DatagramSocket().use { s ->
                    network?.bindSocket(s)
                    val d = json.toByteArray()
                    val addr = InetAddress.getByName(ip)
                    repeat(times) {
                        s.send(DatagramPacket(d, d.size, addr, PORT))
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    suspend fun query(ip: String): JSONObject? {
        return withContext(Dispatchers.IO) {
            try {
                DatagramSocket().use { s ->
                    network?.bindSocket(s)
                    s.soTimeout = 1200
                    val d = GET.toByteArray()
                    val addr = InetAddress.getByName(ip)
                    s.send(DatagramPacket(d, d.size, addr, PORT))
                    val p = DatagramPacket(ByteArray(2048), 2048)
                    s.receive(p)
                    val text = String(p.data, 0, p.length)
                    JSONObject(text).optJSONObject("result")
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun discover(): List<Pair<String, String>> {
        return withContext(Dispatchers.IO) {
            val found = linkedMapOf<String, String>()
            try {
                DatagramSocket().use { s ->
                    network?.bindSocket(s)
                    s.broadcast = true
                    s.soTimeout = 700
                    val d = GET.toByteArray()
                    val addr = InetAddress.getByName("255.255.255.255")
                    repeat(2) {
                        s.send(DatagramPacket(d, d.size, addr, PORT))
                    }
                    val end = System.currentTimeMillis() + 3000
                    while (System.currentTimeMillis() < end) {
                        try {
                            val p = DatagramPacket(ByteArray(2048), 2048)
                            s.receive(p)
                            val ip = p.address.hostAddress ?: continue
                            val text = String(p.data, 0, p.length)
                            val res = JSONObject(text).optJSONObject("result")
                            val mac = res?.optString("mac") ?: ip
                            found[mac] = ip
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: Exception) {
            }
            found.map { it.key to it.value }
        }
    }
}

fun pilot(p: String) = """{"method":"setPilot","params":{$p}}"""

fun rgbParams(c: Int, dim: Int): String {
    val r = android.graphics.Color.red(c)
    val g = android.graphics.Color.green(c)
    val b = android.graphics.Color.blue(c)
    return "\"state\":true,\"r\":$r,\"g\":$g,\"b\":$b,\"dimming\":$dim"
}

suspend fun setRgb(ip: String, c: Int, dim: Int, times: Int) {
    val d = dim.coerceIn(10, 100)
    Wiz.send(ip, pilot(rgbParams(c, d)), times)
}

fun mix(a: Int, b: Int, f: Float): Int {
    fun ch(x: Int, y: Int) = (x + (y - x) * f).toInt().coerceIn(0, 255)
    val r = ch(android.graphics.Color.red(a), android.graphics.Color.red(b))
    val g = ch(android.graphics.Color.green(a), android.graphics.Color.green(b))
    val bl = ch(android.graphics.Color.blue(a), android.graphics.Color.blue(b))
    return android.graphics.Color.rgb(r, g, bl)
}

data class ThemeSpec(
    val name: String,
    val colors: List<Int>,
    val seconds: Float,
    val mode: Int,
    val dim: Int,
    val offset: Boolean
)

val modeNames = listOf("Fade", "Snap", "Flicker", "Pulse")
val modeHelp = listOf(
    "Colors blend smoothly into the next one",
    "Jumps straight to the next color",
    "Flickers like a candle or fire",
    "Breathes brighter and dimmer"
)

fun enc(t: ThemeSpec): String {
    val off = if (t.offset) 1 else 0
    val cols = t.colors.joinToString(",")
    return listOf(t.name, t.mode, t.seconds, t.dim, off, cols).joinToString("~")
}

fun dec(s: String): ThemeSpec? {
    val p = s.split("~")
    if (p.size < 6) return null
    val cols = p[5].split(",").mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(-65536) }
    return ThemeSpec(
        p[0], cols,
        p[2].toFloatOrNull() ?: 5f,
        p[1].toIntOrNull() ?: 0,
        p[3].toIntOrNull() ?: 80,
        p[4] == "1"
    )
}

suspend fun runTheme(t: ThemeSpec, ips: () -> List<String>) {
    val n = t.colors.size
    val totalMs = (t.seconds * 1000).toLong().coerceAtLeast(150L)
    fun col(i: Int, s: Int): Int {
        val shift = if (t.offset) i else 0
        return t.colors[(s + shift) % n]
    }
    var step = 0
    while (true) {
        val list = ips()
        when (t.mode) {
            0 -> {
                val slices = (totalMs / 200).toInt().coerceAtLeast(1)
                for (k in 1..slices) {
                    list.forEachIndexed { i, ip ->
                        val c = mix(col(i, step), col(i, step + 1), k / slices.toFloat())
                        setRgb(ip, c, t.dim, 1)
                    }
                    delay(totalMs / slices)
                }
            }
            1 -> {
                list.forEachIndexed { i, ip -> setRgb(ip, col(i, step), t.dim, 2) }
                delay(totalMs)
            }
            2 -> {
                val end = System.currentTimeMillis() + totalMs
                while (System.currentTimeMillis() < end) {
                    list.forEachIndexed { i, ip ->
                        val b = (10..t.dim.coerceAtLeast(11)).random()
                        setRgb(ip, col(i, step), b, 1)
                    }
                    delay((60..220).random().toLong())
                }
            }
            else -> {
                val slices = (totalMs / 150).toInt().coerceAtLeast(2)
                for (k in 0 until slices) {
                    val range = (t.dim - 10).coerceAtLeast(0)
                    val b = (10 + range * sin(PI * k / slices)).toInt().coerceIn(10, 100)
                    list.forEachIndexed { i, ip -> setRgb(ip, col(i, step), b, 1) }
                    delay(totalMs / slices)
                }
            }
        }
        step = (step + 1) % n
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.requestNetwork(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) {
                    Wiz.network = n
                    cm.bindProcessToNetwork(n)
                }
            })
        } catch (e: Exception) {
        }
        val prefs = getSharedPreferences("bulbs", MODE_PRIVATE)
        setContent {
            val scheme = darkColorScheme(
                primary = Color(0xFF26C6DA),
                background = Color(0xFF0B0B0F),
                surface = Color(0xFF16161D),
                onBackground = Color(0xFFEAEAF0),
                onSurface = Color(0xFFEAEAF0)
            )
            MaterialTheme(colorScheme = scheme) {
                App(prefs)
            }
        }
    }
}

@Composable
fun App(prefs: SharedPreferences) {
    val bulbs = remember {
        val list = mutableStateListOf<Pair<String, String>>()
        prefs.getStringSet("bulbs2", emptySet())!!.forEach {
            val p = it.split("|")
            if (p.size == 2) list.add(p[0] to p[1])
        }
        list
    }
    val names = remember {
        val m = mutableStateMapOf<String, String>()
        prefs.getStringSet("names", emptySet())!!.forEach {
            val i = it.indexOf('=')
            if (i > 0) m[it.substring(0, i)] = it.substring(i + 1)
        }
        m
    }
    val favC = remember {
        val l = mutableStateListOf<String>()
        l.addAll(prefs.getString("favC", "")!!.split(";").filter { it.isNotBlank() })
        l
    }
    val favW = remember {
        val l = mutableStateListOf<String>()
        l.addAll(prefs.getString("favW", "")!!.split(";").filter { it.isNotBlank() })
        l
    }
    val themes = remember {
        val l = mutableStateListOf<ThemeSpec>()
        prefs.getString("themes", "")!!.split("\n").forEach {
            if (it.isNotBlank()) dec(it)?.let { t -> l.add(t) }
        }
        l
    }
    val online = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }
    var running by remember { mutableStateOf<ThemeSpec?>(null) }
    var editing by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf(-1) }

    fun saveAll() {
        prefs.edit()
            .putStringSet("bulbs2", bulbs.map { "${it.first}|${it.second}" }.toSet())
            .putStringSet("names", names.map { "${it.key}=${it.value}" }.toSet())
            .putString("favC", favC.joinToString(";"))
            .putString("favW", favW.joinToString(";"))
            .putString("themes", themes.joinToString("\n") { enc(it) })
            .apply()
    }
    fun add(mac: String, ip: String) {
        bulbs.removeAll { it.second == ip && it.first != mac }
        val i = bulbs.indexOfFirst { it.first == mac }
        if (i >= 0) bulbs[i] = mac to ip else bulbs.add(mac to ip)
        saveAll()
    }
    suspend fun scan() {
        scanning = true
        Wiz.discover().forEach { add(it.first, it.second) }
        scanning = false
    }
    LaunchedEffect(Unit) {
        delay(800)
        scan()
    }
    LaunchedEffect(running) {
        val t = running
        if (t != null) {
            runTheme(t) { bulbs.filter { online[it.first] != false }.map { it.second } }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding()) {
            Text(
                "WiZ Local",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp)
            )
            running?.let { r ->
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Running: ${r.name}", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { running = null }) { Text("Stop") }
                }
            }
            TabRow(selectedTabIndex = tab) {
                listOf("Bulbs", "Sync", "Themes", "Setup").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            val lm = Modifier.fillMaxWidth().weight(1f)
            when (tab) {
                0 -> LazyColumn(
                    lm,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { scope.launch { scan() } },
                                enabled = !scanning
                            ) {
                                Text(if (scanning) "Scanning..." else "Scan for bulbs")
                            }
                            if (online.values.any { !it }) {
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = {
                                    bulbs.removeAll { online[it.first] == false }
                                    saveAll()
                                }) { Text("Remove offline bulbs") }
                            }
                        }
                        Row(
                            Modifier.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                manual, { manual = it },
                                Modifier.weight(1f),
                                singleLine = true,
                                label = { Text("Manual IP") }
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                val ip = manual.trim()
                                manual = ""
                                if (ip.isNotBlank()) {
                                    scope.launch {
                                        val m = Wiz.query(ip)?.optString("mac")
                                        add(if (m.isNullOrBlank()) ip else m, ip)
                                    }
                                }
                            }) { Text("Add") }
                        }
                    }
                    items(bulbs.toList(), key = { it.first }) { (mac, ip) ->
                        BulbCard(
                            mac, ip, names[mac] ?: "", online[mac], favC, favW,
                            onSave = { saveAll() },
                            onRename = { n -> names[mac] = n; saveAll() },
                            onStatus = { ok -> online[mac] = ok },
                            onRemove = { bulbs.removeAll { it.first == mac }; saveAll() }
                        )
                    }
                }
                1 -> LazyColumn(lm, contentPadding = PaddingValues(16.dp)) {
                    item {
                        val ips = bulbs.filter { online[it.first] != false }.map { it.second }
                        SyncPanel(ips, favC, favW) { saveAll() }
                    }
                }
                3 -> SetupLab(lm)
                else -> LazyColumn(
                    lm,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (editing) {
                        item {
                            ThemeEditor(
                                themes.getOrNull(editIndex),
                                onSave = { t ->
                                    if (editIndex in themes.indices) themes[editIndex] = t
                                    else themes.add(t)
                                    saveAll()
                                    editing = false
                                },
                                onCancel = { editing = false }
                            )
                        }
                    } else {
                        item {
                            Button(onClick = { editIndex = -1; editing = true }) {
                                Text("+ New theme")
                            }
                        }
                        items(themes.toList()) { t ->
                            ThemeCard(
                                t, running == t,
                                onPlay = { running = if (running == t) null else t },
                                onEdit = { editIndex = themes.indexOf(t); editing = true },
                                onDelete = {
                                    if (running == t) running = null
                                    themes.remove(t)
                                    saveAll()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

val scenes = listOf(
    "Ocean" to 1, "Romance" to 2, "Sunset" to 3, "Party" to 4, "Fireplace" to 5,
    "Cozy" to 6, "Forest" to 7, "Pastel" to 8, "Warm white" to 11, "Daylight" to 12,
    "Cool white" to 13, "Night light" to 14, "Focus" to 16, "Relax" to 17
)

fun whiteColor(t: Float): Color {
    val f = ((t - 2200f) / 4300f).coerceIn(0f, 1f)
    return lerp(Color(0xFFFFB46B), Color(0xFFD6E6FF), f)
}

class Ctl {
    var on by mutableStateOf(true)
    var dim by mutableStateOf(80f)
    var hue by mutableStateOf(200f)
    var sat by mutableStateOf(1f)
    var temp by mutableStateOf(3000f)
    var fade by mutableStateOf(false)
}

@Composable
fun ColorPicker(hue: Float, sat: Float, onMove: (Float, Float) -> Unit, onDone: () -> Unit) {
    val move = rememberUpdatedState(onMove)
    val done = rememberUpdatedState(onDone)
    val hues = remember {
        (0..360 step 30).map {
            Color(android.graphics.Color.HSVToColor(floatArrayOf(it.toFloat(), 1f, 1f)))
        }
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val h = (o.x / size.width).coerceIn(0f, 1f) * 359f
                    val s = (o.y / size.height).coerceIn(0f, 1f)
                    move.value(h, s)
                    done.value()
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o ->
                        val h = (o.x / size.width).coerceIn(0f, 1f) * 359f
                        val s = (o.y / size.height).coerceIn(0f, 1f)
                        move.value(h, s)
                    },
                    onDragEnd = { done.value() },
                    onDrag = { c, _ ->
                        c.consume()
                        val h = (c.position.x / size.width).coerceIn(0f, 1f) * 359f
                        val s = (c.position.y / size.height).coerceIn(0f, 1f)
                        move.value(h, s)
                    }
                )
            }
    ) {
        drawRect(Brush.horizontalGradient(hues))
        drawRect(Brush.verticalGradient(listOf(Color.White, Color.Transparent)))
        val pos = Offset(hue / 359f * size.width, sat * size.height)
        drawCircle(Color.White, 14f, pos, style = Stroke(4f))
    }
}

@Composable
fun FavDot(key: String, color: Color, onTap: () -> Unit, onLong: () -> Unit) {
    val tap = rememberUpdatedState(onTap)
    val long = rememberUpdatedState(onLong)
    Box(
        Modifier
            .size(36.dp)
            .background(color, CircleShape)
            .pointerInput(key) {
                detectTapGestures(onTap = { tap.value() }, onLongPress = { long.value() })
            }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Controls(
    c: Ctl,
    send: (String) -> Unit,
    favC: SnapshotStateList<String>,
    favW: SnapshotStateList<String>,
    onSave: () -> Unit
) {
    fun sendColor() {
        val k = android.graphics.Color.HSVToColor(floatArrayOf(c.hue, c.sat, 1f))
        send(rgbParams(k, c.dim.toInt()))
    }
    fun sendWhite() = send("\"state\":true,\"temp\":${c.temp.toInt()},\"dimming\":${c.dim.toInt()}")
    LaunchedEffect(c.fade) {
        while (c.fade) {
            c.hue = (c.hue + 4) % 360
            sendColor()
            delay(200)
        }
    }

    Column {
        Text("Brightness", style = MaterialTheme.typography.labelMedium)
        Slider(
            c.dim, { c.dim = it },
            valueRange = 10f..100f,
            onValueChangeFinished = { send("\"dimming\":${c.dim.toInt()}") }
        )

        Text("Color", style = MaterialTheme.typography.labelMedium)
        ColorPicker(c.hue, c.sat, { h, s -> c.fade = false; c.hue = h; c.sat = s }, { sendColor() })
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                val f = "${c.hue.toInt()}|${(c.sat * 100).toInt()}|${c.dim.toInt()}"
                if (f !in favC) {
                    favC.add(f)
                    onSave()
                }
            }) { Text("♥ Save") }
            Spacer(Modifier.width(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(favC.toList(), key = { it }) { f ->
                    val p = f.split("|")
                    val h = p.getOrNull(0)?.toFloatOrNull() ?: 0f
                    val s = (p.getOrNull(1)?.toFloatOrNull() ?: 100f) / 100f
                    val d = p.getOrNull(2)?.toFloatOrNull() ?: 80f
                    val dotColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, 1f)))
                    FavDot(
                        f, dotColor,
                        onTap = { c.fade = false; c.hue = h; c.sat = s; c.dim = d; sendColor() },
                        onLong = { favC.remove(f); onSave() }
                    )
                }
            }
        }

        Text(
            "White temperature (${c.temp.toInt()}K)",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        Slider(
            c.temp, { c.temp = it },
            valueRange = 2200f..6500f,
            onValueChangeFinished = { sendWhite() }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                val f = "${c.temp.toInt()}|${c.dim.toInt()}"
                if (f !in favW) {
                    favW.add(f)
                    onSave()
                }
            }) { Text("♥ Save") }
            Spacer(Modifier.width(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(favW.toList(), key = { it }) { f ->
                    val p = f.split("|")
                    val t = p.getOrNull(0)?.toFloatOrNull() ?: 3000f
                    val d = p.getOrNull(1)?.toFloatOrNull() ?: 80f
                    FavDot(
                        f, whiteColor(t),
                        onTap = { c.fade = false; c.temp = t; c.dim = d; sendWhite() },
                        onLong = { favW.remove(f); onSave() }
                    )
                }
            }
        }

        Text(
            "Scenes",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(scenes) { (n, id) ->
                AssistChip(
                    onClick = { c.fade = false; send("\"state\":true,\"sceneId\":$id") },
                    label = { Text(n) }
                )
            }
        }
        FilterChip(
            c.fade, { c.fade = !c.fade },
            label = { Text("Rainbow fade") },
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
fun SyncPanel(
    ips: List<String>,
    favC: SnapshotStateList<String>,
    favW: SnapshotStateList<String>,
    onSave: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val c = remember { Ctl() }
    val send: (String) -> Unit = { p ->
        scope.launch { ips.forEach { Wiz.send(it, pilot(p)) } }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("All bulbs together", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Controlling ${ips.size} online bulb(s)",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Switch(c.on, { c.on = it; send("\"state\":$it") })
            }
            Controls(c, send, favC, favW, onSave)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulbCard(
    mac: String,
    ip: String,
    name: String,
    online: Boolean?,
    favC: SnapshotStateList<String>,
    favW: SnapshotStateList<String>,
    onSave: () -> Unit,
    onRename: (String) -> Unit,
    onStatus: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val c = remember { Ctl() }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(name) }

    LaunchedEffect(ip) {
        val r = Wiz.query(ip)
        onStatus(r != null)
        r?.let {
            c.on = it.optBoolean("state", true)
            if (it.has("dimming")) {
                c.dim = it.optInt("dimming", 80).toFloat().coerceIn(10f, 100f)
            }
            if (it.has("temp")) {
                c.temp = it.optInt("temp", 3000).toFloat().coerceIn(2200f, 6500f)
            }
            if (it.has("r")) {
                val hsv = FloatArray(3)
                android.graphics.Color.RGBToHSV(it.optInt("r"), it.optInt("g"), it.optInt("b"), hsv)
                c.hue = hsv[0]
                c.sat = hsv[1]
            }
        }
    }

    val send: (String) -> Unit = { p -> scope.launch { Wiz.send(ip, pilot(p)) } }
    val rgb = android.graphics.Color.HSVToColor(floatArrayOf(c.hue, c.sat, 1f))

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Name this bulb") },
            text = { OutlinedTextField(draft, { draft = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { onRename(draft.trim()); editing = false }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text("Cancel") }
            }
        )
    }

    Card(
        Modifier.alpha(if (online == false) 0.5f else 1f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp).background(Color(rgb), CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).clickable { draft = name; editing = true }) {
                    val title = if (name.isBlank()) "Tap to name this bulb" else name
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    val off = if (online == false) " · Offline" else ""
                    Text(
                        "$ip · ${mac.takeLast(6)}$off",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Switch(c.on, { c.on = it; send("\"state\":$it") })
            }
            Controls(c, send, favC, favW, onSave)
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch {
                        repeat(3) {
                            Wiz.send(ip, pilot("\"state\":false"))
                            delay(350)
                            Wiz.send(ip, pilot("\"state\":true"))
                            delay(350)
                        }
                        if (!c.on) Wiz.send(ip, pilot("\"state\":false"))
                    }
                }) { Text("Blink") }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}

@Composable
fun ThemeCard(
    t: ThemeSpec,
    playing: Boolean,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text(t.name, style = MaterialTheme.typography.titleMedium)
            val mode = modeNames[t.mode.coerceIn(0, 3)]
            val secs = "%.1f".format(t.seconds)
            Text(
                "${t.colors.size} colors · $mode · ${secs}s each",
                style = MaterialTheme.typography.labelSmall
            )
            LazyRow(
                Modifier.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(t.colors) {
                    Box(Modifier.size(22.dp).background(Color(it), CircleShape))
                }
            }
            Row {
                Button(onClick = onPlay) { Text(if (playing) "Stop" else "Play") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onEdit) { Text("Edit") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeEditor(initial: ThemeSpec?, onSave: (ThemeSpec) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "My theme") }
    val colors = remember {
        val l = mutableStateListOf<Int>()
        l.addAll(initial?.colors ?: emptyList())
        l
    }
    var secs by remember { mutableStateOf(initial?.seconds ?: 5f) }
    var mode by remember { mutableStateOf(initial?.mode ?: 0) }
    var dim by remember { mutableStateOf((initial?.dim ?: 80).toFloat()) }
    var offset by remember { mutableStateOf(initial?.offset ?: false) }
    var hue by remember { mutableStateOf(0f) }
    var sat by remember { mutableStateOf(1f) }
    val current = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, 1f))

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                name, { name = it },
                singleLine = true,
                label = { Text("Theme name") },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Pick a color, then tap Add (${colors.size}/12)",
                style = MaterialTheme.typography.labelMedium
            )
            ColorPicker(hue, sat, { h, s -> hue = h; sat = s }, {})
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).background(Color(current), CircleShape))
                Spacer(Modifier.width(10.dp))
                Button(onClick = { if (colors.size < 12) colors.add(current) }) {
                    Text("Add color")
                }
            }
            if (colors.isNotEmpty()) {
                Text("Tap a color to remove it", style = MaterialTheme.typography.labelSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(colors.toList()) { k ->
                        Box(
                            Modifier
                                .size(34.dp)
                                .background(Color(k), CircleShape)
                                .clickable { colors.remove(k) }
                        )
                    }
                }
            }
            Text("Transition", style = MaterialTheme.typography.labelMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(modeNames.indices.toList()) { i ->
                    FilterChip(mode == i, { mode = i }, label = { Text(modeNames[i]) })
                }
            }
            Text(modeHelp[mode.coerceIn(0, 3)], style = MaterialTheme.typography.labelSmall)
            Text(
                "Each color lasts ${"%.1f".format(secs)} seconds",
                style = MaterialTheme.typography.labelMedium
            )
            Slider(secs, { secs = it }, valueRange = 0.3f..30f)
            Text("Brightness ${dim.toInt()}%", style = MaterialTheme.typography.labelMedium)
            Slider(dim, { dim = it }, valueRange = 10f..100f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(offset, { offset = it })
                Spacer(Modifier.width(8.dp))
                Text(
                    "Offset bulbs (each starts on a different color)",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Row {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Spacer(Modifier.weight(1f))
                Button(enabled = colors.isNotEmpty(), onClick = {
                    val nm = name.trim().ifBlank { "Theme" }.replace("~", "").replace("\n", " ")
                    onSave(ThemeSpec(nm, colors.toList(), secs, mode, dim.toInt(), offset))
                }) { Text("Save theme") }
            }
        }
    }
}

suspend fun rawSend(ip: String, msg: String): String {
    return withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { s ->
                Wiz.network?.bindSocket(s)
                s.soTimeout = 2500
                val d = msg.toByteArray()
                val addr = InetAddress.getByName(ip)
                s.send(DatagramPacket(d, d.size, addr, 38899))
                val p = DatagramPacket(ByteArray(4096), 4096)
                s.receive(p)
                String(p.data, 0, p.length)
            }
        } catch (e: SocketTimeoutException) {
            "(no reply in 2.5s)"
        } catch (e: Exception) {
            "Error: ${e.javaClass.simpleName} ${e.message}"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupLab(modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var ip by remember { mutableStateOf("192.168.4.1") }
    var ssid by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("""{"method":"getSystemConfig","params":{}}""") }
    val log = remember { mutableStateListOf<String>() }
    fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    val presets = listOf(
        "Read config" to """{"method":"getSystemConfig","params":{}}""",
        "Read Wi-Fi" to """{"method":"getWifiConfig","params":{}}""",
        "Guess A" to """{"method":"setWifiConfig","params":{"ssid":"@S","psk":"@P"}}""",
        "Guess B" to """{"method":"setSystemConfig","params":{"ssid":"@S","pwd":"@P"}}""",
        "Guess C" to """{"method":"setWifiConfig","params":{"ssid":"@S","pwd":"@P"}}"""
    )
    val help = "1. Reset the bulb (5 power cycles).\n" +
        "2. In Android Wi-Fi settings, join the WiZ_xxxxxx network. " +
        "If asked, keep it connected even without internet.\n" +
        "3. Come back here, fill in the hotspot name and password, " +
        "tap a preset, then Send.\n" +
        "4. Start with the two Read presets."
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Setup lab (experimental)", style = MaterialTheme.typography.titleMedium)
            Text(help, style = MaterialTheme.typography.labelMedium)
        }
        item {
            OutlinedTextField(
                ip, { ip = it },
                singleLine = true,
                label = { Text("Bulb address") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            OutlinedTextField(
                ssid, { ssid = it },
                singleLine = true,
                label = { Text("Hotspot name") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            OutlinedTextField(
                pwd, { pwd = it },
                singleLine = true,
                label = { Text("Hotspot password") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(presets) { (n, t) ->
                    AssistChip(
                        onClick = { msg = t.replace("@S", esc(ssid)).replace("@P", esc(pwd)) },
                        label = { Text(n) }
                    )
                }
            }
        }
        item {
            OutlinedTextField(
                msg, { msg = it },
                label = { Text("Message to send") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Row {
                Button(onClick = {
                    scope.launch {
                        val sent = msg
                        val r = rawSend(ip.trim(), sent)
                        log.add(0, "SENT: $sent\nREPLY: $r")
                    }
                }) { Text("Send") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { log.clear() }) { Text("Clear log") }
            }
        }
        items(log.toList()) { entry ->
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(entry, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

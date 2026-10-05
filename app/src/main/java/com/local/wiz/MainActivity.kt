package com.local.wiz

import android.content.Context
import android.net.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.net.*

object Wiz {
    @Volatile var network: Network? = null
    private const val PORT = 38899

    suspend fun send(ip: String, json: String, times: Int = 2) = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { s ->
                network?.bindSocket(s)
                val d = json.toByteArray()
                repeat(times) { s.send(DatagramPacket(d, d.size, InetAddress.getByName(ip), PORT)) }
            }
        } catch (_: Exception) {}
    }

    suspend fun discover(): List<String> = withContext(Dispatchers.IO) {
        val found = linkedSetOf<String>()
        try {
            DatagramSocket().use { s ->
                network?.bindSocket(s); s.broadcast = true; s.soTimeout = 700
                val d = """{"method":"getPilot","params":{}}""".toByteArray()
                repeat(2) { s.send(DatagramPacket(d, d.size, InetAddress.getByName("255.255.255.255"), PORT)) }
                val end = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(ByteArray(2048), 2048); s.receive(p)
                        p.address.hostAddress?.let { found += it }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        found.toList()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.requestNetwork(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(n: Network) { Wiz.network = n; cm.bindProcessToNetwork(n) }
                })
        } catch (e: Exception) {}
        val prefs = getSharedPreferences("bulbs", MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF26C6DA), background = Color(0xFF0B0B0F),
                surface = Color(0xFF16161D), onBackground = Color(0xFFEAEAF0), onSurface = Color(0xFFEAEAF0))) {
                App(prefs.getStringSet("ips", emptySet())!!.toList()) { prefs.edit().putStringSet("ips", it.toSet()).apply() }
            }
        }
    }
}

@Composable
fun App(initial: List<String>, save: (List<String>) -> Unit) {
    val ips = remember { mutableStateListOf<String>().apply { addAll(initial) } }
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf("") }
    fun add(ip: String) { if (ip.isNotBlank() && ip !in ips) { ips.add(ip); save(ips.toList()) } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("WiZ Local", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    scanning = true
                    scope.launch { Wiz.discover().forEach { add(it) }; scanning = false }
                }, enabled = !scanning) { Text(if (scanning) "Scanning..." else "Scan for bulbs") }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(manual, { manual = it }, Modifier.weight(1f), singleLine = true, label = { Text("Manual IP") })
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { add(manual.trim()); manual = "" }) { Text("Add") }
                }
            }
            items(ips.toList(), key = { it }) { ip ->
                BulbCard(ip) { ips.remove(ip); save(ips.toList()) }
            }
        }
    }
}

val scenes = listOf("Ocean" to 1, "Romance" to 2, "Sunset" to 3, "Party" to 4, "Fireplace" to 5,
    "Cozy" to 6, "Forest" to 7, "Pastel" to 8, "Warm white" to 11, "Daylight" to 12,
    "Cool white" to 13, "Night light" to 14, "Focus" to 16, "Relax" to 17)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulbCard(ip: String, onRemove: () -> Unit) {
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(true) }
    var dim by remember { mutableStateOf(80f) }
    var hue by remember { mutableStateOf(200f) }
    var sat by remember { mutableStateOf(1f) }
    var temp by remember { mutableStateOf(3000f) }
    var fade by remember { mutableStateOf(false) }

    fun set(p: String) { scope.launch { Wiz.send(ip, """{"method":"setPilot","params":{$p}}""") } }
    val rgb = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, 1f))
    fun sendColor() = set("\"state\":true,\"r\":${android.graphics.Color.red(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, 1f)))},\"g\":${android.graphics.Color.green(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, 1f)))},\"b\":${android.graphics.Color.blue(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, 1f)))},\"dimming\":${dim.toInt()}")

    LaunchedEffect(fade) { while (fade) { hue = (hue + 4) % 360; sendColor(); delay(200) } }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp).background(Color(rgb), CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(ip, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(on, { on = it; set("\"state\":$it") })
            }
            Text("Brightness", style = MaterialTheme.typography.labelMedium)
            Slider(dim, { dim = it }, valueRange = 10f..100f, onValueChangeFinished = { set("\"dimming\":${dim.toInt()}") })
            Text("Color (hue)", style = MaterialTheme.typography.labelMedium)
            Slider(hue, { hue = it }, valueRange = 0f..359f, onValueChangeFinished = { sendColor() })
            Text("Saturation", style = MaterialTheme.typography.labelMedium)
            Slider(sat, { sat = it }, valueRange = 0f..1f, onValueChangeFinished = { sendColor() })
            Text("White temperature (${temp.toInt()}K)", style = MaterialTheme.typography.labelMedium)
            Slider(temp, { temp = it }, valueRange = 2200f..6500f, onValueChangeFinished = { set("\"state\":true,\"temp\":${temp.toInt()},\"dimming\":${dim.toInt()}") })
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(scenes) { (n, id) -> AssistChip(onClick = { fade = false; set("\"state\":true,\"sceneId\":$id") }, label = { Text(n) }) }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(fade, { fade = !fade }, label = { Text("Rainbow fade") })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}

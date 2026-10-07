package com.local.wiz

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.*
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

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

val modeNames = listOf(
    "Fade", "Snap", "Flicker", "Pulse", "Strobe", "Heartbeat",
    "Chase", "Random jump", "Ping-pong", "Sunrise", "Lightning"
)
val modeHelp = listOf(
    "Colors blend smoothly into the next one",
    "Jumps straight to the next color",
    "Flickers like a candle or fire",
    "Breathes brighter and dimmer",
    "Quick on-off flashes of each color",
    "Double beat, then a pause, like a pulse",
    "The color runs from bulb to bulb (needs 2 or more bulbs)",
    "A random color from your list each time",
    "Goes forward through the colors, then backward",
    "Slowly gets brighter across the colors, then repeats",
    "Dim glow with sudden bright flashes at random"
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
    fun ping(s: Int): Int {
        if (n < 2) return 0
        val m = s % (2 * n - 2)
        return if (m < n) m else 2 * n - 2 - m
    }
    fun pcol(i: Int, s: Int): Int {
        val shift = if (t.offset) i else 0
        return t.colors[ping(s + shift)]
    }
    suspend fun fadeStep(
        list: List<String>,
        a: (Int) -> Int,
        b: (Int) -> Int,
        bright: (Float) -> Int
    ) {
        val slices = (totalMs / 200).toInt().coerceAtLeast(1)
        for (k in 1..slices) {
            val f = k / slices.toFloat()
            list.forEachIndexed { i, ip ->
                setRgb(ip, mix(a(i), b(i), f), bright(f), 1)
            }
            delay(totalMs / slices)
        }
    }
    var step = 0
    while (true) {
        val list = ips()
        if (list.isEmpty()) {
            delay(500)
            continue
        }
        val s = step
        when (t.mode) {
            0 -> fadeStep(list, { i -> col(i, s) }, { i -> col(i, s + 1) }, { _ -> t.dim })
            1 -> {
                list.forEachIndexed { i, ip -> setRgb(ip, col(i, s), t.dim, 2) }
                delay(totalMs)
            }
            2 -> {
                val end = System.currentTimeMillis() + totalMs
                while (System.currentTimeMillis() < end) {
                    list.forEachIndexed { i, ip ->
                        val b = (10..t.dim.coerceAtLeast(11)).random()
                        setRgb(ip, col(i, s), b, 1)
                    }
                    delay((60..220).random().toLong())
                }
            }
            3 -> {
                val slices = (totalMs / 150).toInt().coerceAtLeast(2)
                for (k in 0 until slices) {
                    val range = (t.dim - 10).coerceAtLeast(0)
                    val b = (10 + range * sin(PI * k / slices)).toInt().coerceIn(10, 100)
                    list.forEachIndexed { i, ip -> setRgb(ip, col(i, s), b, 1) }
                    delay(totalMs / slices)
                }
            }
            4 -> {
                val end = System.currentTimeMillis() + totalMs
                while (System.currentTimeMillis() < end) {
                    list.forEachIndexed { i, ip -> setRgb(ip, col(i, s), t.dim, 1) }
                    delay(70)
                    list.forEach { Wiz.send(it, pilot("\"state\":false"), 1) }
                    delay(110)
                }
            }
            5 -> {
                val t0 = System.currentTimeMillis()
                repeat(2) {
                    list.forEachIndexed { i, ip -> setRgb(ip, col(i, s), t.dim, 1) }
                    delay(110)
                    list.forEachIndexed { i, ip -> setRgb(ip, col(i, s), 10, 1) }
                    delay(130)
                }
                val left = totalMs - (System.currentTimeMillis() - t0)
                if (left > 0) delay(left)
            }
            6 -> {
                val sub = (totalMs / list.size).coerceAtLeast(100L)
                list.indices.forEach { j ->
                    list.forEachIndexed { i, ip ->
                        setRgb(ip, col(0, s), if (i == j) t.dim else 10, 1)
                    }
                    delay(sub)
                }
            }
            7 -> {
                list.forEach { ip -> setRgb(ip, t.colors.random(), t.dim, 2) }
                delay(totalMs)
            }
            8 -> fadeStep(list, { i -> pcol(i, s) }, { i -> pcol(i, s + 1) }, { _ -> t.dim })
            9 -> fadeStep(
                list,
                { i -> col(i, s) },
                { i -> col(i, s + 1) },
                { f ->
                    val range = (t.dim - 10).coerceAtLeast(0)
                    (10 + range * (((s % n) + f) / n)).toInt()
                }
            )
            10 -> {
                val end = System.currentTimeMillis() + totalMs
                list.forEachIndexed { i, ip -> setRgb(ip, col(i, s), 10, 1) }
                while (System.currentTimeMillis() < end) {
                    delay((300..1500).random().toLong())
                    repeat((1..3).random()) {
                        list.forEachIndexed { i, ip -> setRgb(ip, col(i, s), 100, 1) }
                        delay((60..120).random().toLong())
                        list.forEachIndexed { i, ip -> setRgb(ip, col(i, s), 10, 1) }
                        delay((60..140).random().toLong())
                    }
                }
            }
            else -> delay(500)
        }
        step++
    }
}

class Analysis(val hopMs: Float, val loud: FloatArray, val beat: BooleanArray)

fun analyzeAudio(ctx: Context, uri: Uri): Analysis? {
    val ex = MediaExtractor()
    var codec: MediaCodec? = null
    try {
        ex.setDataSource(ctx, uri, null)
        var track = -1
        for (i in 0 until ex.trackCount) {
            val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (m.startsWith("audio/")) {
                track = i
                break
            }
        }
        if (track < 0) return null
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
        val dec = MediaCodec.createDecoderByType(mime)
        codec = dec
        dec.configure(fmt, null, null, 0)
        dec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        if (ch < 1) ch = 1
        var hop = rate / 40
        val rms = ArrayList<Float>()
        val bass = ArrayList<Float>()
        var sumAll = 0f
        var sumLow = 0f
        var cnt = 0
        var lp = 0f
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false
        while (!outDone) {
            if (!inDone) {
                val ii = dec.dequeueInputBuffer(10000)
                if (ii >= 0) {
                    val ib = dec.getInputBuffer(ii)
                    val sz = if (ib != null) ex.readSampleData(ib, 0) else -1
                    if (sz < 0) {
                        dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inDone = true
                    } else {
                        dec.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val oi = dec.dequeueOutputBuffer(info, 10000)
            if (oi >= 0) {
                val ob = dec.getOutputBuffer(oi)
                if (ob != null && info.size > 0) {
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val frames = sb.remaining() / ch
                    for (f in 0 until frames) {
                        var m = 0f
                        for (c in 0 until ch) {
                            m += sb.get().toFloat()
                        }
                        m = m / ch / 32768f
                        lp += 0.05f * (m - lp)
                        sumAll += m * m
                        sumLow += lp * lp
                        cnt++
                        if (cnt >= hop) {
                            rms.add(sqrt(sumAll / cnt))
                            bass.add(sqrt(sumLow / cnt))
                            sumAll = 0f
                            sumLow = 0f
                            cnt = 0
                        }
                    }
                }
                dec.releaseOutputBuffer(oi, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outDone = true
                }
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val of = dec.outputFormat
                rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (ch < 1) ch = 1
                hop = rate / 40
            }
        }
        val n = rms.size
        if (n < 10) return null
        var maxRms = 0.0001f
        for (v in rms) {
            if (v > maxRms) maxRms = v
        }
        val loud = FloatArray(n) { sqrt((rms[it] / maxRms).coerceIn(0f, 1f)) }
        val beat = BooleanArray(n)
        val win = 40
        var run = 0f
        var lastBeat = -100
        for (i in 0 until n) {
            run += bass[i]
            if (i >= win) run -= bass[i - win]
            val mean = run / minOf(i + 1, win)
            val prev = if (i > 0) bass[i - 1] else 0f
            if (bass[i] > 1.3f * mean + 0.004f && bass[i] > prev && i - lastBeat >= 8) {
                beat[i] = true
                lastBeat = i
            }
        }
        val hopMs = 1000f * hop / rate
        return Analysis(hopMs, loud, beat)
    } catch (e: Exception) {
        return null
    } finally {
        try {
            codec?.stop()
        } catch (e: Exception) {
        }
        try {
            codec?.release()
        } catch (e: Exception) {
        }
        ex.release()
    }
}

val builtinPals: List<Pair<String, List<Int>>> = listOf(
    "Rainbow" to emptyList<Int>(),
    "Neon" to listOf(
        0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFF7CFF00.toInt(), 0xFFFFEE00.toInt()
    ),
    "Fire" to listOf(
        0xFFFF2200.toInt(), 0xFFFF6A00.toInt(), 0xFFFFB300.toInt(), 0xFFFFE066.toInt()
    ),
    "Ocean" to listOf(
        0xFF0033FF.toInt(), 0xFF00A6FF.toInt(), 0xFF00E5C8.toInt(), 0xFFB3F0FF.toInt()
    ),
    "Sunset" to listOf(
        0xFF7A00FF.toInt(), 0xFFFF0080.toInt(), 0xFFFF5A00.toInt(), 0xFFFFC400.toInt()
    ),
    "Ice" to listOf(
        0xFFFFFFFF.toInt(), 0xFFB3E5FF.toInt(), 0xFF4DA6FF.toInt()
    ),
    "Club" to listOf(
        0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0xFF00FF00.toInt(), 0xFFAA00FF.toInt()
    ),
    "Candy" to listOf(
        0xFFFF4DA6.toInt(), 0xFF4DFFEA.toInt(), 0xFFFFF04D.toInt(), 0xFFB48CFF.toInt()
    )
)

val styleNames = listOf(
    "Beats", "Loudness", "Disco blink", "Club alternate", "Random flash", "Flow"
)
val styleHelp = listOf(
    "The color changes on each beat, then fades down",
    "Brightness follows the volume",
    "Full flash on every beat, dark in between (disco)",
    "Bulbs take turns lighting up on each beat",
    "Random bulbs flash random colors on each beat",
    "Colors slowly flow, brightness follows the volume"
)

fun palColor(p: List<Int>, i: Int): Int {
    if (p.isEmpty()) {
        val h = ((i * 47) % 360).toFloat()
        return android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, 1f))
    }
    return p[((i % p.size) + p.size) % p.size]
}

class FxState {
    var step = 0
    var lastBeat = -100000L
    var off = false
}

suspend fun renderFx(
    style: Int,
    ips: List<String>,
    pal: List<Int>,
    st: FxState,
    beat: Boolean,
    now: Long,
    level: Float
) {
    if (ips.isEmpty()) return
    if (beat) {
        st.step++
        st.lastBeat = now
    }
    val since = (now - st.lastBeat).coerceAtLeast(0L)
    when (style) {
        0 -> {
            val b = (100 - since / 6).toInt().coerceIn(20, 100)
            val c = palColor(pal, st.step)
            ips.forEach { setRgb(it, c, b, 1) }
        }
        1 -> {
            val b = (10 + 90 * level).toInt().coerceIn(10, 100)
            val c = palColor(pal, st.step)
            ips.forEach { setRgb(it, c, b, 1) }
        }
        2 -> {
            if (beat) {
                val c = palColor(pal, st.step)
                ips.forEach { setRgb(it, c, 100, 1) }
                st.off = false
            } else if (since > 100 && !st.off) {
                ips.forEach { Wiz.send(it, pilot("\"state\":false"), 1) }
                st.off = true
            }
        }
        3 -> {
            if (beat) {
                val c1 = palColor(pal, st.step / 4)
                val c2 = palColor(pal, st.step / 4 + 1)
                ips.forEachIndexed { i, ip ->
                    if (i == st.step % ips.size) {
                        setRgb(ip, c1, 100, 1)
                    } else {
                        setRgb(ip, c2, 12, 1)
                    }
                }
            }
        }
        4 -> {
            if (beat) {
                ips.forEach { ip ->
                    if ((0..99).random() < 65) {
                        setRgb(ip, palColor(pal, (0..50).random()), 100, 1)
                    } else {
                        setRgb(ip, palColor(pal, st.step), 10, 1)
                    }
                }
            }
        }
        else -> {
            val c: Int
            if (pal.isEmpty()) {
                val h = ((now / 15) % 360).toFloat()
                c = android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, 1f))
            } else {
                val pos = now / 1500f
                val i = pos.toInt()
                c = mix(palColor(pal, i), palColor(pal, i + 1), pos - i)
            }
            val b = (15 + 85 * level).toInt().coerceIn(10, 100)
            ips.forEach { setRgb(it, c, b, 1) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MusicTab(modifier: Modifier, ips: List<String>, themes: List<ThemeSpec>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("") }
    var analysis by remember { mutableStateOf<Analysis?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var mic by remember { mutableStateOf(false) }
    var lead by remember { mutableStateOf(150f) }
    var style by remember { mutableStateOf(0) }
    var palIdx by remember { mutableStateOf(0) }
    var sens by remember { mutableStateOf(0.5f) }
    var msg by remember { mutableStateOf("") }
    val ipsNow = rememberUpdatedState(ips)
    val pals = builtinPals + themes.map { it.name to it.colors }
    val palNow = rememberUpdatedState(pals.getOrNull(palIdx)?.second ?: emptyList())

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { u: Uri? ->
        if (u != null) {
            playing = false
            uri = u
            fileName = u.lastPathSegment ?: "song"
            analysis = null
            analyzing = true
            msg = ""
            scope.launch {
                val r = withContext(Dispatchers.Default) { analyzeAudio(ctx, u) }
                analysis = r
                analyzing = false
                msg = if (r == null) "Could not read this audio file." else ""
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        if (ok) {
            playing = false
            mic = true
        } else {
            msg = "Microphone permission was denied."
        }
    }

    LaunchedEffect(playing) {
        val a = analysis
        val u = uri
        if (playing && a != null && u != null) {
            val mp = MediaPlayer()
            try {
                withContext(Dispatchers.IO) {
                    mp.setDataSource(ctx, u)
                    mp.prepare()
                }
                mp.start()
                val st = FxState()
                var lastIdx = -1
                while (isActive && playing && mp.isPlaying) {
                    val pos = mp.currentPosition + lead
                    val idx = (pos / a.hopMs).toInt().coerceIn(0, a.loud.size - 1)
                    var beat = false
                    var k = lastIdx + 1
                    while (k <= idx) {
                        if (a.beat[k]) beat = true
                        k++
                    }
                    lastIdx = idx
                    renderFx(style, ipsNow.value, palNow.value, st, beat, pos.toLong(), a.loud[idx])
                    delay(90)
                }
            } catch (e: Exception) {
                msg = "Playback problem: ${e.message}"
            } finally {
                try {
                    mp.release()
                } catch (e: Exception) {
                }
                playing = false
                withContext(NonCancellable) {
                    ipsNow.value.forEach { Wiz.send(it, pilot("\"state\":true"), 1) }
                }
            }
        }
    }

    LaunchedEffect(mic) {
        if (mic) {
            val rate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            var recRef: AudioRecord? = null
            try {
                val r = AudioRecord(
                    MediaRecorder.AudioSource.MIC, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, rate / 2)
                )
                recRef = r
                r.startRecording()
                val buf = ShortArray(rate / 20)
                val st = FxState()
                val t0 = System.currentTimeMillis()
                var peak = 0.02f
                var lp = 0f
                var meanBass = 0f
                var prevBass = 0f
                var lastBeatT = 0L
                var lastRender = 0L
                var pendBeat = false
                var lvl = 0f
                while (isActive && mic) {
                    val n = withContext(Dispatchers.IO) { r.read(buf, 0, buf.size) }
                    if (n <= 0) continue
                    var sa = 0f
                    var sl = 0f
                    for (i in 0 until n) {
                        val x = buf[i] / 32768f
                        lp += 0.06f * (x - lp)
                        sa += x * x
                        sl += lp * lp
                    }
                    val rms = sqrt(sa / n)
                    val bass = sqrt(sl / n)
                    peak = maxOf(rms, peak * 0.998f, 0.01f)
                    lvl = sqrt((rms / peak).coerceIn(0f, 1f))
                    val now = System.currentTimeMillis() - t0
                    val factor = 2.0f - sens
                    if (bass > factor * meanBass + 0.003f && bass > prevBass && now - lastBeatT > 250) {
                        pendBeat = true
                        lastBeatT = now
                    }
                    meanBass = meanBass * 0.97f + bass * 0.03f
                    prevBass = bass
                    if (now - lastRender >= 90) {
                        renderFx(style, ipsNow.value, palNow.value, st, pendBeat, now, lvl)
                        pendBeat = false
                        lastRender = now
                    }
                }
            } catch (e: Exception) {
                msg = "Microphone problem: ${e.message}"
            } finally {
                recRef?.let {
                    try {
                        it.stop()
                    } catch (e: Exception) {
                    }
                    it.release()
                }
                mic = false
                withContext(NonCancellable) {
                    ipsNow.value.forEach { Wiz.send(it, pilot("\"state\":true"), 1) }
                }
            }
        }
    }

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Music sync", style = MaterialTheme.typography.titleMedium)
            Text(
                "Stop any running theme first. Keep the app open and the screen on.",
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                "Disco blink and Club alternate flash fast, which can affect people sensitive to flashing lights.",
                style = MaterialTheme.typography.labelSmall
            )
        }
        item {
            Text("Style", style = MaterialTheme.typography.labelMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                styleNames.indices.forEach { i ->
                    FilterChip(style == i, { style = i }, label = { Text(styleNames[i]) })
                }
            }
            Text(
                styleHelp[style.coerceIn(0, styleHelp.size - 1)],
                style = MaterialTheme.typography.labelSmall
            )
        }
        item {
            Text("Colors", style = MaterialTheme.typography.labelMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pals.forEachIndexed { i, p ->
                    FilterChip(palIdx == i, { palIdx = i }, label = { Text(p.first) })
                }
            }
        }
        item {
            Text("Microphone (live, any music)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Listens to the room. Turn the music up so the phone can hear it.",
                style = MaterialTheme.typography.labelSmall
            )
            Button(
                onClick = {
                    if (mic) {
                        mic = false
                    } else if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        playing = false
                        mic = true
                    } else {
                        permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                enabled = ips.isNotEmpty()
            ) {
                Text(if (mic) "Stop microphone sync" else "Start microphone sync")
            }
            Text(
                "Mic sensitivity: ${(sens * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium
            )
            Slider(sens, { sens = it }, valueRange = 0f..1f)
        }
        item {
            Text("Song file (pre-analyzed, tighter timing)", style = MaterialTheme.typography.titleSmall)
            Button(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = !analyzing) {
                Text("Choose song")
            }
            if (fileName.isNotBlank()) {
                Text(fileName, style = MaterialTheme.typography.labelMedium)
            }
            if (analyzing) {
                Text("Analyzing the song, please wait...", color = MaterialTheme.colorScheme.primary)
            }
            Text(
                "Sync offset: ${lead.toInt()} ms",
                style = MaterialTheme.typography.labelMedium
            )
            Slider(lead, { lead = it }, valueRange = -400f..800f)
            Text(
                "Raise it if the lights come after the sound. Lower it if they come before, for example with Bluetooth speakers.",
                style = MaterialTheme.typography.labelSmall
            )
            Button(
                onClick = {
                    if (playing) {
                        playing = false
                    } else {
                        mic = false
                        playing = true
                    }
                },
                enabled = analysis != null && !analyzing && ips.isNotEmpty()
            ) {
                Text(if (playing) "Stop" else "Play with lights")
            }
        }
        item {
            if (msg.isNotBlank()) {
                Text(msg, style = MaterialTheme.typography.labelMedium)
            }
            if (ips.isEmpty()) {
                Text("No online bulbs found yet.", style = MaterialTheme.typography.labelSmall)
            }
        }
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
                listOf("Bulbs", "Sync", "Themes", "Music").forEachIndexed { i, t ->
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
                3 -> MusicTab(
                    lm,
                    bulbs.filter { online[it.first] != false }.map { it.second },
                    themes
                )
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
    "Ocean" to 1, "Romance" to 2, "Sunset" to 3, "Party" to 4,
    "Fireplace" to 5, "Cozy" to 6, "Forest" to 7, "Pastel colors" to 8,
    "Wake up" to 9, "Bedtime" to 10, "Warm white" to 11, "Daylight" to 12,
    "Cool white" to 13, "Night light" to 14, "Focus" to 15, "Relax" to 16,
    "True colors" to 17, "TV time" to 18, "Plantgrowth" to 19, "Spring" to 20,
    "Summer" to 21, "Fall" to 22, "Deepdive" to 23, "Jungle" to 24,
    "Mojito" to 25, "Club" to 26, "Christmas" to 27, "Halloween" to 28,
    "Candlelight" to 29, "Golden white" to 30, "Pulse" to 31,
    "Steampunk" to 32, "Diwali" to 33
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
    var scene by mutableStateOf(0)
    var speed by mutableStateOf(100f)
}

fun sceneParams(c: Ctl): String {
    return "\"state\":true,\"sceneId\":${c.scene},\"speed\":${c.speed.toInt()}"
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun Controls(
    c: Ctl,
    send: (String) -> Unit,
    favC: SnapshotStateList<String>,
    favW: SnapshotStateList<String>,
    onSave: () -> Unit
) {
    fun sendColor() {
        c.scene = 0
        val k = android.graphics.Color.HSVToColor(floatArrayOf(c.hue, c.sat, 1f))
        send(rgbParams(k, c.dim.toInt()))
    }
    fun sendWhite() {
        c.scene = 0
        send("\"state\":true,\"temp\":${c.temp.toInt()},\"dimming\":${c.dim.toInt()}")
    }
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
        FlowRow(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = {
                val f = "${c.hue.toInt()}|${(c.sat * 100).toInt()}|${c.dim.toInt()}"
                if (f !in favC) {
                    favC.add(f)
                    onSave()
                }
            }) { Text("♥ Save") }
            favC.toList().forEach { f ->
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
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = {
                val f = "${c.temp.toInt()}|${c.dim.toInt()}"
                if (f !in favW) {
                    favW.add(f)
                    onSave()
                }
            }) { Text("♥ Save") }
            favW.toList().forEach { f ->
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

        Text(
            "Scenes (the bulb runs these itself)",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            scenes.forEach { (n, id) ->
                FilterChip(
                    selected = c.scene == id,
                    onClick = {
                        c.fade = false
                        c.scene = id
                        send(sceneParams(c))
                    },
                    label = { Text(n) }
                )
            }
        }
        Text(
            "Scene speed (${c.speed.toInt()})",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        Slider(
            c.speed, { c.speed = it },
            valueRange = 10f..200f,
            onValueChangeFinished = { if (c.scene != 0) send(sceneParams(c)) }
        )
        FilterChip(
            c.fade, { c.fade = !c.fade },
            label = { Text("Rainbow fade") },
            modifier = Modifier.padding(top = 4.dp)
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
            if (it.has("speed")) {
                c.speed = it.optInt("speed", 100).toFloat().coerceIn(10f, 200f)
            }
            c.scene = it.optInt("sceneId", 0)
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

@OptIn(ExperimentalLayoutApi::class)
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
            val mode = modeNames[t.mode.coerceIn(0, modeNames.size - 1)]
            val secs = "%.1f".format(t.seconds)
            Text(
                "${t.colors.size} colors · $mode · ${secs}s each",
                style = MaterialTheme.typography.labelSmall
            )
            FlowRow(
                Modifier.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                t.colors.forEach {
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    colors.toList().forEach { k ->
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                modeNames.indices.forEach { i ->
                    FilterChip(mode == i, { mode = i }, label = { Text(modeNames[i]) })
                }
            }
            Text(
                modeHelp[mode.coerceIn(0, modeHelp.size - 1)],
                style = MaterialTheme.typography.labelSmall
            )
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

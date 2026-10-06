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
    val bl = ch(android.graphics.Color.blue(a), android.graphics.Color.blue(b

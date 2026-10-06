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

    suspend fun send(ip: String, json: String, times: Int = 2) = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { s ->
                network?.bindSocket(s)
                val d = json.toByteArray()
                repeat(times) { s.send(DatagramPacket(d, d.size, InetAddress.getByName(ip), PORT)) }
            }
        } catch (_: Exception) {}
    }

    suspend fun query(ip: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { s ->
                network?.bindSocket(s); s.soTimeout = 1200
                val d = GET.toByteArray()
                s.send(DatagramPacket(d, d.size, InetAddress.getByName(ip), PORT))
                val p = DatagramPacket(ByteArray(2048), 2048); s.receive(p)
                JSONObject(String(p.data, 0, p.length)).optJSONObject("result")
            }
        } catch (_: Exception) { null }
    }

    suspend fun discover(): List<Pair<String, String>> = w

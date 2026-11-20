package com.kalize.recorder

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Honest device diagnostics and a short repeatable CPU workload.
 * Android does not grant ordinary apps permission to overclock CPUs or kill
 * other apps; Game Session only keeps this screen awake while it is enabled.
 */
class DeviceToolsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val device = buildDeviceReport()
        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                var gameSession by remember { mutableStateOf(false) }
                var running by remember { mutableStateOf(false) }
                var score by remember { mutableStateOf<Int?>(null) }
                var benchmarkMessage by remember { mutableStateOf("Run the short CPU test when the phone is idle.") }
                var sessionMessage by remember { mutableStateOf("Game Session does not overclock the phone or terminate other apps.") }

                DisposableEffect(gameSession) {
                    if (gameSession) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        window.decorView.systemUiVisibility =
                            (android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
                        sessionMessage = "Game Session active: screen stays awake while this page is open."
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        window.decorView.systemUiVisibility = 0
                        sessionMessage = "Game Session is off."
                    }
                    onDispose {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        window.decorView.systemUiVisibility = 0
                    }
                }

                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("KALIZE DEVICE LAB", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Text("Performance & network", style = MaterialTheme.typography.headlineMedium)
                    Text("Check what your phone can support before choosing recording settings.")
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("DEVICE", style = MaterialTheme.typography.titleMedium)
                            device.forEach { (label, value) ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(value, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("NETWORK CHECK", style = MaterialTheme.typography.titleMedium)
                            Text(networkReport(), style = MaterialTheme.typography.bodyMedium)
                            Text("This checks connectivity and reported link capacity; it is not a measured download/upload speed test.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("CPU BENCHMARK", style = MaterialTheme.typography.titleMedium)
                            Text("A short local workload gives a repeatable comparison on this device. Close heavy apps and keep the phone cool for more consistent results.")
                            Button(
                                enabled = !running,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    running = true
                                    benchmarkMessage = "Running CPU workload…"
                                    scope.launch {
                                        val result = withContext(Dispatchers.Default) { runCpuBenchmark() }
                                        score = result
                                        benchmarkMessage = "Completed. Higher scores mean more loop iterations in the fixed test window; this is not a universal device ranking."
                                        running = false
                                    }
                                }
                            ) { Text(if (running) "Testing…" else "Run CPU benchmark") }
                            score?.let { Text("Score: $it iterations", style = MaterialTheme.typography.headlineSmall) }
                            Text(benchmarkMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("GAME SESSION", style = MaterialTheme.typography.titleMedium)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text("Keep screen awake")
                                    Text("Hides system bars while this page is active.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(gameSession, { gameSession = it })
                            }
                            Text(sessionMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Close Device Lab") }
                }
            }
        }
    }

    private fun buildDeviceReport(): List<Pair<String, String>> {
        val memory = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        memory.getMemoryInfo(info)
        val codecSummary = runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals("video/avc", true) } }
                .joinToString { codec -> codec.name + if (android.os.Build.VERSION.SDK_INT >= 29 && codec.isHardwareAccelerated) " (HW)" else " (SW)" }
                .ifBlank { "No H.264 encoder listed" }
        }.getOrDefault("Unavailable")
        return listOf(
            "Model" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "Android" to "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
            "CPU cores" to Runtime.getRuntime().availableProcessors().toString(),
            "Available RAM" to "${info.availMem / (1024L * 1024L)} MB",
            "H.264 encoders" to codecSummary
        )
    }

    private fun networkReport(): String {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return "No active network connection."
        val caps = manager.getNetworkCapabilities(network) ?: return "Network details are unavailable."
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other network"
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val down = caps.linkDownstreamBandwidthKbps
        val up = caps.linkUpstreamBandwidthKbps
        return buildString {
            append(transport).append(if (validated) " · Internet verified" else " · Internet not verified")
            if (down > 0) append("\nReported downstream capacity: ").append(down).append(" Kbps")
            if (up > 0) append("\nReported upstream capacity: ").append(up).append(" Kbps")
        }
    }

    private fun runCpuBenchmark(): Int {
        val end = System.nanoTime() + 2_000_000_000L
        var iterations = 0
        var value = 1.0001
        while (System.nanoTime() < end) {
            value = sqrt(value * 1.0000001 + (iterations % 97) * 0.000001)
            iterations++
        }
        // Fold the final value into the result so the workload remains observable.
        return if (value > 0.0) iterations else 0
    }
}

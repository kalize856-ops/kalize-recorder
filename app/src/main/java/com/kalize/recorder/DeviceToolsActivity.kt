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
import kotlinx.coroutines.delay
import android.net.TrafficStats

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
            KalizeTheme {
                val scope = rememberCoroutineScope()
                var gameSession by remember { mutableStateOf(false) }
                var running by remember { mutableStateOf(false) }
                var score by remember { mutableStateOf<CpuBenchmarkResult?>(null) }
                var benchmarkMessage by remember { mutableStateOf("Run the short CPU test when the phone is idle.") }
                var sessionMessage by remember { mutableStateOf("Game Session does not overclock the phone or terminate other apps.") }
                var liveDownloadMbps by remember { mutableStateOf(0.0) }
                var liveUploadMbps by remember { mutableStateOf(0.0) }
                var speedAvailable by remember { mutableStateOf(true) }

                // Passively samples device network counters while Device Lab is visible.
                // This creates no test traffic and therefore does not consume extra data.
                LaunchedEffect(Unit) {
                    var previousRx = TrafficStats.getTotalRxBytes()
                    var previousTx = TrafficStats.getTotalTxBytes()
                    var previousTime = android.os.SystemClock.elapsedRealtimeNanos()
                    if (previousRx < 0L || previousTx < 0L) speedAvailable = false
                    while (true) {
                        delay(1000)
                        val currentRx = TrafficStats.getTotalRxBytes()
                        val currentTx = TrafficStats.getTotalTxBytes()
                        val currentTime = android.os.SystemClock.elapsedRealtimeNanos()
                        val elapsedSeconds = (currentTime - previousTime).coerceAtLeast(1L) / 1_000_000_000.0
                        if (currentRx >= 0L && currentTx >= 0L && previousRx >= 0L && previousTx >= 0L) {
                            liveDownloadMbps = ((currentRx - previousRx).coerceAtLeast(0L) * 8.0 / elapsedSeconds) / 1_000_000.0
                            liveUploadMbps = ((currentTx - previousTx).coerceAtLeast(0L) * 8.0 / elapsedSeconds) / 1_000_000.0
                        } else {
                            speedAvailable = false
                        }
                        previousRx = currentRx
                        previousTx = currentTx
                        previousTime = currentTime
                    }
                }

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
                            Text("Android link-capacity estimates are not measured throughput.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text("↓ Download", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(if (speedAvailable) "%.2f Mbps".format(java.util.Locale.US, liveDownloadMbps) else "Unavailable", style = MaterialTheme.typography.headlineSmall)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("↑ Upload", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(if (speedAvailable) "%.2f Mbps".format(java.util.Locale.US, liveUploadMbps) else "Unavailable", style = MaterialTheme.typography.headlineSmall)
                                }
                            }
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Box(Modifier.size(8.dp).padding(0.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawCircle(color = if (speedAvailable) androidx.compose.ui.graphics.Color(0xFF20B26B) else androidx.compose.ui.graphics.Color.Gray) }
                                }
                                Text(if (speedAvailable) "Live · updates every second while this page is open" else "Live network counters are not supported on this device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("Measured from actual device network traffic. Other apps' downloads and uploads are included. No test data is downloaded or uploaded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                        benchmarkMessage = result.explanation
                                        running = false
                                    }
                                }
                            ) { Text(if (running) "Testing…" else "Run CPU benchmark") }
                            score?.let { result ->
                                Text("Kalize score: ${result.overallScore}", style = MaterialTheme.typography.headlineSmall)
                                Text(result.rating, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text("Single-core", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(result.singleCoreScore.toString(), style = MaterialTheme.typography.titleMedium)
                                    }
                                    Column {
                                        Text("Multi-core", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(result.multiCoreScore.toString(), style = MaterialTheme.typography.titleMedium)
                                    }
                                    Column {
                                        Text("Stability", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("${result.stabilityPercent}%", style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                                Text("Tested with ${result.coreCount} worker(s). This is a Kalize workload index, not a guaranteed game-FPS prediction.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
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

    private fun runCpuBenchmark(): CpuBenchmarkResult = CpuBenchmark.run()
}

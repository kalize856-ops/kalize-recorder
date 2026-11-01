
package com.kalize.recorder

import android.app.ActivityManager
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.sqrt

private val ToolsBackground = Color(0xFF0B0B14)
private val ToolsPanel = Color(0xFF171727)
private val ToolsPurple = Color(0xFF8B5CF6)
private val ToolsText = Color(0xFFF5F3FF)
private val ToolsMuted = Color(0xFFB7B4CC)
private val ToolsBlue = Color(0xFF365DD8)

class PerformanceToolsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ToolsBackground
                ) {
                    PerformanceToolsScreen(
                        onBack = { finish() },
                        batteryInfo = getBatteryInfo(),
                        deviceInfo = getDeviceInfo(),
                        onBatterySettings = {
                            openSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                        },
                        onDisplaySettings = {
                            openSettings(Settings.ACTION_DISPLAY_SETTINGS)
                        },
                        onWifiSettings = {
                            openSettings(Settings.ACTION_WIFI_SETTINGS)
                        },
                        onAppSettings = {
                            openSettings(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName")
                            )
                        }
                    )
                }
            }
        }
    }

    private fun openSettings(action: String, uri: Uri? = null) {
        try {
            val intent = Intent(action)
            if (uri != null) intent.data = uri
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
                // Settings are unavailable on this device.
            }
        }
    }

    private fun getDeviceInfo(): String {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)

        val totalRam = memory.totalMem / (1024.0 * 1024.0 * 1024.0)
        val availableRam = memory.availMem / (1024.0 * 1024.0 * 1024.0)

        return buildString {
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Device: ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE}")
            appendLine("SDK level: ${Build.VERSION.SDK_INT}")
            appendLine(
                "Total RAM: ${String.format(Locale.US, "%.2f", totalRam)} GB"
            )
            append(
                "Available RAM: ${
                    String.format(Locale.US, "%.2f", availableRam)
                } GB"
            )
        }
    }

    private fun getBatteryInfo(): String {
        return try {
            val intent = registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) ?: return "Battery information is unavailable."

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percent = if (level >= 0 && scale > 0) {
                level * 100 / scale
            } else -1

            val status = when (
                intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            ) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                BatteryManager.BATTERY_STATUS_FULL -> "Fully charged"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
                else -> "Unknown"
            }

            val temperature = intent.getIntExtra(
                BatteryManager.EXTRA_TEMPERATURE, -1
            )

            val health = when (
                intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
            ) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Failure reported"
                BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE ->
                    "Over-voltage reported"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE ->
                    "Issue reported"
                else -> "Not reported"
            }

            buildString {
                appendLine(
                    "Charge: ${if (percent >= 0) "$percent%" else "Unavailable"}"
                )
                appendLine("Status: $status")
                appendLine(
                    "Temperature: ${
                        if (temperature >= 0) {
                            String.format(
                                Locale.US, "%.1f°C", temperature / 10.0
                            )
                        } else "Unavailable"
                    }"
                )
                append("Reported condition: $health")
            }
        } catch (_: Exception) {
            "Could not read battery information."
        }
    }
}

@Composable
private fun PerformanceToolsScreen(
    onBack: () -> Unit,
    batteryInfo: String,
    deviceInfo: String,
    onBatterySettings: () -> Unit,
    onDisplaySettings: () -> Unit,
    onWifiSettings: () -> Unit,
    onAppSettings: () -> Unit
) {
    var networkRunning by remember { mutableStateOf(false) }
    var networkResult by remember {
        mutableStateOf("Run a test to estimate network latency and speed.")
    }

    var cpuRunning by remember { mutableStateOf(false) }
    var cpuResult by remember {
        mutableStateOf("Run a short calculation benchmark.")
    }

    val handler = remember { Handler(Looper.getMainLooper()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ToolsBackground)
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        Text(
            "KALIZE STUDIO  /  DEVICE TOOLS",
            color = ToolsPurple,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Performance Tools",
            color = ToolsText,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(6.dp))

        Text(
            "Battery • Game settings • Network • CPU",
            color = ToolsMuted,
            fontSize = 14.sp
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(
                containerColor = ToolsPanel,
                contentColor = ToolsText
            )
        ) {
            Text("← Back")
        }

        Spacer(Modifier.height(16.dp))

        ToolPanel("🔋 Battery Status", "Information reported by Android") {
            InfoText(batteryInfo)
            Spacer(Modifier.height(8.dp))
            InfoText(
                "Battery readings are estimates from Android, not a " +
                    "professional battery-health or lifespan test.",
                muted = true
            )
        }

        Spacer(Modifier.height(14.dp))

        ToolPanel("🎮 Game Booster", "Quick access to phone settings") {
            InfoText(
                "Use these shortcuts to adjust your phone before gaming. " +
                    "Available options depend on your device."
            )

            Spacer(Modifier.height(12.dp))

            ToolButton("Battery and power settings", onBatterySettings)
            ToolButton("Display settings", onDisplaySettings)
            ToolButton("Wi-Fi settings", onWifiSettings)
            ToolButton("Kalize Recorder app settings", onAppSettings)

            Spacer(Modifier.height(6.dp))

            InfoText(
                "These shortcuts do not overclock your processor or " +
                    "forcibly close other apps.",
                muted = true
            )
        }

        Spacer(Modifier.height(14.dp))

        ToolPanel("🌐 Network Speed Test", "Estimate connection performance") {
            InfoText(networkResult)

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    networkRunning = true
                    networkResult = "Testing connection…"

                    Thread {
                        val result = try {
                            val pingStart = SystemClock.elapsedRealtime()
                            val ping = URL(
                                "https://www.google.com/generate_204"
                            ).openConnection() as HttpURLConnection

                            ping.connectTimeout = 8000
                            ping.readTimeout = 8000
                            ping.useCaches = false

                            val code = ping.responseCode
                            val latency =
                                SystemClock.elapsedRealtime() - pingStart
                            ping.disconnect()

                            if (code !in 200..399) {
                                throw Exception("Connection unavailable")
                            }

                            val connection = URL(
                                "https://speed.cloudflare.com/__down?bytes=1000000"
                            ).openConnection() as HttpURLConnection

                            connection.connectTimeout = 10000
                            connection.readTimeout = 15000
                            connection.useCaches = false

                            val start = SystemClock.elapsedRealtime()
                            var total = 0L

                            connection.inputStream.use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count == -1) break
                                    total += count
                                }
                            }

                            connection.disconnect()

                            val elapsed = (
                                SystemClock.elapsedRealtime() - start
                            ).coerceAtLeast(1L)

                            val mbps = total * 8.0 / (elapsed / 1000.0) /
                                1_000_000.0

                            "Latency: ${latency} ms\n" +
                                "Estimated download: ${
                                    String.format(Locale.US, "%.2f", mbps)
                                } Mbps\n" +
                                "Data received: ${
                                    String.format(
                                        Locale.US, "%.2f", total / 1_000_000.0
                                    )
                                } MB"
                        } catch (_: Exception) {
                            "The test failed. Check your internet connection " +
                                "and try again."
                        }

                        handler.post {
                            networkResult = result
                            networkRunning = false
                        }
                    }.start()
                },
                enabled = !networkRunning,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ToolsPurple
                )
            ) {
                Text(if (networkRunning) "Testing…" else "Start Network Test")
            }

            Spacer(Modifier.height(8.dp))

            InfoText(
                "Results vary with signal strength, network traffic and " +
                    "server response.",
                muted = true
            )
        }

        Spacer(Modifier.height(14.dp))

        ToolPanel("⚡ CPU Benchmark", "A short local calculation test") {
            InfoText(cpuResult)

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    cpuRunning = true
                    cpuResult = "Running benchmark…"

                    Thread {
                        val start = SystemClock.elapsedRealtimeNanos()
                        val limit = 500_000_000L
                        var operations = 0L
                        var value = 1.0

                        while (
                            SystemClock.elapsedRealtimeNanos() - start < limit
                        ) {
                            value += sqrt((operations % 1000L + 1L).toDouble())
                            operations++
                        }

                        val elapsed = (
                            SystemClock.elapsedRealtimeNanos() - start
                        ).coerceAtLeast(1L)

                        val perSecond =
                            operations * 1_000_000_000.0 / elapsed

                        val result =
                            "Operations per second: ${
                                String.format(Locale.US, "%,.0f", perSecond)
                            }\nBenchmark score: ${
                                String.format(Locale.US, "%.1f", perSecond / 1000)
                            }\nCalculation result: ${
                                String.format(Locale.US, "%.2f", value)
                            }"

                        handler.post {
                            cpuResult = result
                            cpuRunning = false
                        }
                    }.start()
                },
                enabled = !cpuRunning,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ToolsBlue
                )
            ) {
                Text(if (cpuRunning) "Benchmarking…" else "Start CPU Test")
            }

            Spacer(Modifier.height(8.dp))

            InfoText(
                "This is a simple benchmark, not an official processor rating. " +
                    "Results vary with device temperature and background activity.",
                muted = true
            )
        }

        Spacer(Modifier.height(14.dp))

        ToolPanel("📱 Device Information", "Device details reported by Android") {
            InfoText(deviceInfo)
        }

        Spacer(Modifier.height(22.dp))

        Text(
            "Kalize Recorder • Performance Tools",
            color = ToolsMuted,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ToolPanel(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = ToolsPanel)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                title,
                color = ToolsText,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(4.dp))

            Text(subtitle, color = ToolsMuted, fontSize = 12.sp)

            Spacer(Modifier.height(14.dp))

            content()
        }
    }
}

@Composable
private fun InfoText(text: String, muted: Boolean = false) {
    Text(
        text = text,
        color = if (muted) ToolsMuted else ToolsText,
        fontSize = 14.sp,
        lineHeight = 22.sp
    )
}

@Composable
private fun ToolButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 7.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF292642),
            contentColor = ToolsText
        )
    ) {
        Text(text)
    }
}

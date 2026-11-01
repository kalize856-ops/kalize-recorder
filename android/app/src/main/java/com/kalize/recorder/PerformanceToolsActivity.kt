
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
import androidx.compose.foundation.layout.width
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
                        onBatterySettings = {
                            openSettings(
                                Settings.ACTION_BATTERY_SAVER_SETTINGS
                            )
                        },
                        onDisplaySettings = {
                            openSettings(
                                Settings.ACTION_DISPLAY_SETTINGS
                            )
                        },
                        onAppSettings = {
                            openSettings(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName")
                            )
                        },
                        onWifiSettings = {
                            openSettings(
                                Settings.ACTION_WIFI_SETTINGS
                            )
                        },
                        deviceInfo = getDeviceInfo(),
                        batteryInfo = getBatteryInfo()
                    )
                }
            }
        }
    }

    private fun openSettings(
        action: String,
        uri: Uri? = null
    ) {
        try {
            val intent = Intent(action)
            if (uri != null) {
                intent.data = uri
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
                // Settings could not be opened.
            }
        }
    }

    private fun getDeviceInfo(): String {
        val memoryManager =
            getSystemService(ACTIVITY_SERVICE) as ActivityManager

        val memoryInfo = ActivityManager.MemoryInfo()
        memoryManager.getMemoryInfo(memoryInfo)

        val totalRamGb =
            memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)

        val availableRamGb =
            memoryInfo.availMem / (1024.0 * 1024.0 * 1024.0)

        return buildString {
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Android version: ${Build.VERSION.RELEASE}")
            appendLine("SDK level: ${Build.VERSION.SDK_INT}")
            appendLine(
                "Total RAM: ${
                    String.format(Locale.US, "%.2f", totalRamGb)
                } GB"
            )
            append(
                "Available RAM: ${
                    String.format(Locale.US, "%.2f", availableRamGb)
                } GB"
            )
        }
    }

    private fun getBatteryInfo(): String {
        return try {
            val batteryIntent = registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )

            if (batteryIntent == null) {
                return "Battery information is unavailable."
            }

            val level = batteryIntent.getIntExtra(
                BatteryManager.EXTRA_LEVEL,
                -1
            )
            val scale = batteryIntent.getIntExtra(
                BatteryManager.EXTRA_SCALE,
                -1
            )

            val percentage = if (level >= 0 && scale > 0) {
                (level * 100) / scale
            } else {
                -1
            }

            val status = batteryIntent.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                -1
            )

            val chargingStatus = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                BatteryManager.BATTERY_STATUS_FULL -> "Fully charged"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING ->
                    "Not charging"
                else -> "Status unavailable"
            }

            val plugged = batteryIntent.getIntExtra(
                BatteryManager.EXTRA_PLUGGED,
                0
            )

            val powerSource = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC charger"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless charger"
                else -> "Not connected or unknown"
            }

            val temperatureTenths = batteryIntent.getIntExtra(
                BatteryManager.EXTRA_TEMPERATURE,
                -1
            )

            val temperature = if (temperatureTenths >= 0) {
                String.format(
                    Locale.US,
                    "%.1f°C",
                    temperatureTenths / 10.0
                )
            } else {
                "Unavailable"
            }

            val healthCode = batteryIntent.getIntExtra(
                BatteryManager.EXTRA_HEALTH,
                -1
            )

            val health = when (healthCode) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Battery failure reported"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE ->
                    "Over-voltage reported"
                BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE ->
                    "Issue reported by Android"
                else -> "Not reported"
            }

            buildString {
                appendLine(
                    "Battery level: ${
                        if (percentage >= 0) "$percentage%" else "Unavailable"
                    }"
                )
                appendLine("Charging status: $chargingStatus")
                appendLine("Power source: $powerSource")
                appendLine("Battery temperature: $temperature")
                append("Reported battery condition: $health")
            }
        } catch (_: Exception) {
            "Could not read battery information on this device."
        }
    }
}

@Composable
private fun PerformanceToolsScreen(
    onBack: () -> Unit,
    onBatterySettings: () -> Unit,
    onDisplaySettings: () -> Unit,
    onAppSettings: () -> Unit,
    onWifiSettings: () -> Unit,
    deviceInfo: String,
    batteryInfo: String
) {
    var networkRunning by remember { mutableStateOf(false) }
    var networkResult by remember {
        mutableStateOf(
            "Run a test to check connection latency and download speed."
        )
    }

    var cpuRunning by remember { mutableStateOf(false) }
    var cpuResult by remember {
        mutableStateOf(
            "Run the benchmark to measure a short CPU workload."
        )
    }

    var boosterMessage by remember {
        mutableStateOf(
            "Choose a shortcut to open your phone's performance settings."
        )
    }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ToolsBackground)
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        Text(
            text = "Performance Tools",
            color = ToolsText,
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Game Booster • Network Tester • CPU Benchmark",
            color = ToolsMuted,
            fontSize = 13.sp
        )

        Spacer(modifier = Modifier.height(18.dp))

        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(
                containerColor = ToolsPanel,
                contentColor = ToolsText
            )
        ) {
            Text("← Back")
        }

        Spacer(modifier = Modifier.height(18.dp))

        ToolPanel(
            title = "🔋 Battery Status",
            subtitle = "Battery details reported by Android"
        ) {
            Text(
                text = batteryInfo,
                color = ToolsText,
                fontSize = 15.sp,
                lineHeight = 24.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "This screen shows available battery readings. " +
                    "The reported condition is not a professional battery " +
                    "health or remaining-lifespan test.",
                color = ToolsMuted,
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        ToolPanel(
            title = "🎮 Game Booster",
            subtitle = "Quick access to useful phone settings"
        ) {
            Text(
                text = "Use these shortcuts to prepare your phone for gaming.",
                color = ToolsMuted,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            ToolButton(
                text = "Battery and power settings",
                onClick = onBatterySettings
            )

            ToolButton(
                text = "Display and refresh settings",
                onClick = onDisplaySettings
            )

            ToolButton(
                text = "Wi-Fi settings",
                onClick = onWifiSettings
            )

            ToolButton(
                text = "Kalize Recorder app settings",
                onClick = onAppSettings
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = boosterMessage,
                color = ToolsMuted,
                fontSize = 12.sp
            )

            Text(
                text = "Note: Android controls many performance options. " +
                    "These shortcuts do not overclock the CPU or forcibly " +
                    "close other apps.",
                color = ToolsMuted,
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        ToolPanel(
            title = "🌐 Network Speed Tester",
            subtitle = "Estimate latency and download speed"
        ) {
            Text(
                text = networkResult,
                color = ToolsText,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    if (!networkRunning) {
                        networkRunning = true
                        networkResult = "Testing connection…"

                        Thread {
                            var result: String

                            try {
                                val pingStart =
                                    SystemClock.elapsedRealtime()

                                val pingConnection =
                                    URL(
                                        "https://www.google.com/generate_204"
                                    ).openConnection()
                                        as HttpURLConnection

                                pingConnection.requestMethod = "GET"
                                pingConnection.connectTimeout = 8000
                                pingConnection.readTimeout = 8000
                                pingConnection.useCaches = false

                                val responseCode =
                                    pingConnection.responseCode

                                val latency =
                                    SystemClock.elapsedRealtime() - pingStart

                                pingConnection.disconnect()

                                if (responseCode !in 200..399) {
                                    throw Exception(
                                        "Connection returned HTTP $responseCode"
                                    )
                                }

                                val downloadUrl =
                                    URL(
                                        "https://speed.cloudflare.com/" +
                                            "__down?bytes=1000000"
                                    )

                                val downloadConnection =
                                    downloadUrl.openConnection()
                                        as HttpURLConnection

                                downloadConnection.requestMethod = "GET"
                                downloadConnection.connectTimeout = 10000
                                downloadConnection.readTimeout = 15000
                                downloadConnection.useCaches = false

                                val downloadStart =
                                    SystemClock.elapsedRealtime()

                                var bytesReadTotal = 0L

                                downloadConnection.inputStream.use { input ->
                                    val buffer = ByteArray(8192)
                                    var count: Int

                                    while (
                                        input.read(buffer).also {
                                            count = it
                                        } != -1
                                    ) {
                                        bytesReadTotal += count
                                    }
                                }

                                val elapsedMs = (
                                    SystemClock.elapsedRealtime() -
                                        downloadStart
                                    ).coerceAtLeast(1L)

                                downloadConnection.disconnect()

                                val seconds = elapsedMs / 1000.0
                                val mbps =
                                    (bytesReadTotal * 8.0) /
                                        seconds / 1_000_000.0

                                result = buildString {
                                    appendLine("Latency: ${latency} ms")
                                    appendLine(
                                        "Download speed: ${
                                            String.format(
                                                Locale.US,
                                                "%.2f",
                                                mbps
                                            )
                                        } Mbps"
                                    )
                                    append(
                                        "Data received: ${
                                            String.format(
                                                Locale.US,
                                                "%.2f",
                                                bytesReadTotal / 1_000_000.0
                                            )
                                        } MB"
                                    )
                                }
                            } catch (e: Exception) {
                                result =
                                    "Test failed. Check your internet " +
                                        "connection and try again.\n" +
                                        "Details: ${
                                            e.localizedMessage
                                                ?: "Connection error"
                                        }"
                            }

                            mainHandler.post {
                                networkResult = result
                                networkRunning = false
                            }
                        }.start()
                    }
                },
                enabled = !networkRunning,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ToolsPurple,
                    contentColor = Color.White
                )
            ) {
                Text(
                    if (networkRunning) "Testing…" else "Start Network Test"
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Results depend on your connection, signal, " +
                    "server response and network conditions. " +
                    "This is an estimate, not a guaranteed maximum speed.",
                color = ToolsMuted,
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        ToolPanel(
            title = "⚡ CPU Speed Tester",
            subtitle = "Run a short calculation benchmark"
        ) {
            Text(
                text = cpuResult,
                color = ToolsText,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    if (!cpuRunning) {
                        cpuRunning = true
                        cpuResult = "Running CPU benchmark…"

                        Thread {
                            val start =
                                SystemClock.elapsedRealtimeNanos()

                            val durationNs = 500_000_000L
                            var operations = 0L
                            var value = 1.0

                            while (
                                SystemClock.elapsedRealtimeNanos() - start <
                                durationNs
                            ) {
                                value += sqrt(
                                    (operations % 1000L + 1L).toDouble()
                                )
                                operations++
                            }

                            val elapsedNs = (
                                SystemClock.elapsedRealtimeNanos() - start
                                ).coerceAtLeast(1L)

                            val operationsPerSecond =
                                operations * 1_000_000_000.0 / elapsedNs

                            val score = operationsPerSecond / 1000.0

                            val result = buildString {
                                appendLine(
                                    "Operations per second: ${
                                        String.format(
                                            Locale.US,
                                            "%,.0f",
                                            operationsPerSecond
                                        )
                                    }"
                                )
                                appendLine(
                                    "Benchmark score: ${
                                        String.format(
                                            Locale.US,
                                            "%.1f",
                                            score
                                        )
                                    }"
                                )
                                append(
                                    "Calculation result: ${
                                        String.format(
                                            Locale.US,
                                            "%.2f",
                                            value
                                        )
                                    }"
                                )
                            }

                            mainHandler.post {
                                cpuResult = result
                                cpuRunning = false
                            }
                        }.start()
                    }
                },
                enabled = !cpuRunning,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ToolsPurple,
                    contentColor = Color.White
                )
            ) {
                Text(
                    if (cpuRunning) "Benchmarking…" else "Start CPU Test"
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "This is a simple local benchmark, not an official " +
                    "processor rating. Scores vary with temperature, " +
                    "battery level and background activity.",
                color = ToolsMuted,
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        ToolPanel(
            title = "📱 Device Information",
            subtitle = "Basic information reported by Android"
        ) {
            Text(
                text = deviceInfo,
                color = ToolsText,
                fontSize = 14.sp,
                lineHeight = 22.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Kalize Recorder • Performance Tools",
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
        colors = CardDefaults.cardColors(
            containerColor = ToolsPanel
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                color = ToolsText,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = subtitle,
                color = ToolsMuted,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            content()
        }
    }
}

@Composable
private fun ToolButton(
    text: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF292642),
            contentColor = ToolsText
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(text)
    }
}

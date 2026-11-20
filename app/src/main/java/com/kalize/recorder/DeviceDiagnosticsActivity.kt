package com.kalize.recorder

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale

class DeviceDiagnosticsActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var data by remember { mutableStateOf(readDiagnostics()) }
            var confirmClear by remember { mutableStateOf(false) }
            var status by remember { mutableStateOf("") }
            MaterialTheme {
                Scaffold(topBar = {
                    TopAppBar(
                        title = { Text("Device diagnostics", fontWeight = FontWeight.Bold) },
                        navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.Default.ArrowBack, "Back") } },
                        actions = { IconButton(onClick = { data = readDiagnostics() }) { Icon(Icons.Default.Refresh, "Refresh") } }
                    )
                }) { inset ->
                    Column(Modifier.fillMaxSize().padding(inset).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Live device information to help understand recording and storage conditions.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        InfoCard("Device", Icons.Default.Memory) {
                            InfoRow("Model", data.model); InfoRow("Android", data.android); InfoRow("Processor", data.processor)
                            InfoRow("CPU cores", data.cores); InfoRow("Supported ABIs", data.abis)
                        }
                        InfoCard("Memory", Icons.Default.Memory) {
                            InfoRow("Available RAM", data.ramFree); InfoRow("Total RAM", data.ramTotal); InfoRow("App cache", data.cache)
                            Text("RAM figures are system estimates and can change while apps are running.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        InfoCard("Storage", Icons.Default.Storage) {
                            InfoRow("Free device storage", data.storageFree); InfoRow("Total device storage", data.storageTotal); InfoRow("App cache", data.cache)
                            OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.CleaningServices, null); Text(" Clear temporary app cache")
                            }
                            Text("Only cache folders are cleaned. Recordings, exports and saved projects are kept.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        InfoCard("Battery", Icons.Default.BatteryFull) {
                            InfoRow("Charge", data.charge); InfoRow("Status", data.batteryStatus); InfoRow("Temperature", data.temperature)
                        }
                        InfoCard("Network", Icons.Default.NetworkCheck) {
                            InfoRow("Connection", data.connection); InfoRow("Transport", data.transport)
                            Text("This is connection status, not a measured internet speed test.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
                        FilledTonalButton(onClick = { data = readDiagnostics(); status = "Diagnostics refreshed." }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Refresh, null); Text(" Refresh diagnostics")
                        }
                    }
                }
                if (confirmClear) AlertDialog(
                    onDismissRequest = { confirmClear = false },
                    title = { Text("Clear temporary cache?") },
                    text = { Text("This removes temporary Kalize Recorder cache files only. Your recordings, exported videos and saved editing projects will remain.") },
                    confirmButton = {
                        FilledTonalButton(onClick = {
                            val ok = clearCache()
                            data = readDiagnostics()
                            status = if (ok) "Temporary cache cleared." else "Some temporary files could not be removed."
                            confirmClear = false
                        }) { Text("Clear cache") }
                    },
                    dismissButton = { OutlinedButton(onClick = { confirmClear = false }) { Text("Cancel") } }
                )
            }
        }
    }

    private fun readDiagnostics(): DeviceInfo {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val storage = StatFs(Environment.getDataDirectory().absolutePath)
        val battery = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val charge = if (level >= 0 && scale > 0) "${(level * 100f / scale).toInt()}%" else "Unavailable"
        val state = when (battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "Unavailable"
        }
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val tempText = if (temp >= 0) String.format(Locale.US, "%.1f °C", temp / 10f) else "Unavailable"
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile data"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
            caps != null -> "Other"
            else -> "No active network"
        }
        val processor = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() } ?: Build.HARDWARE else Build.HARDWARE
        return DeviceInfo(
            "${Build.MANUFACTURER} ${Build.MODEL}".trim(), "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            processor.ifBlank { Build.BOARD.ifBlank { "Unavailable" } }, Runtime.getRuntime().availableProcessors().toString(),
            Build.SUPPORTED_ABIS.joinToString(", "), bytes(mem.availMem), bytes(mem.totalMem),
            bytes(storage.availableBytes), bytes(storage.totalBytes), bytes(size(cacheDir) + size(externalCacheDir)),
            charge, state, tempText, if (caps == null) "Disconnected" else "Connected", transport
        )
    }

    private fun clearCache(): Boolean {
        var ok = true
        listOfNotNull(cacheDir, externalCacheDir).forEach { dir ->
            dir.listFiles()?.forEach { if (!it.deleteRecursively()) ok = false }
        }
        return ok
    }

    private fun size(file: File?): Long {
        if (file == null || !file.exists()) return 0
        return if (file.isFile) file.length() else file.listFiles()?.sumOf { size(it) } ?: 0
    }

    private fun bytes(input: Long): String {
        if (input <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var amount = input.toDouble()
        var index = 0
        while (amount >= 1024 && index < units.lastIndex) { amount /= 1024; index++ }
        return String.format(Locale.US, "%.1f %s", amount, units[index])
    }
}

private data class DeviceInfo(
    val model: String, val android: String, val processor: String, val cores: String, val abis: String,
    val ramFree: String, val ramTotal: String, val storageFree: String, val storageTotal: String,
    val cache: String, val charge: String, val batteryStatus: String, val temperature: String,
    val connection: String, val transport: String
)

@Composable
private fun InfoCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1.2f))
    }
}

package com.kalize.recorder

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("kalize_settings", Context.MODE_PRIVATE) }
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, RecorderService::class.java)
                .setAction(RecorderService.START)
                .putExtra(RecorderService.CODE, result.resultCode)
                .putExtra(RecorderService.DATA, result.data)
            ContextCompat.startForegroundService(this, serviceIntent)
        }
    }
    private val overlaySettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) {
            prefs.edit().putBoolean("bubble_enabled", true).apply()
            startBubbleService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var dark by remember { mutableStateOf(prefs.getBoolean("dark_theme", true)) }
            var page by remember { mutableStateOf("Recorder") }
            var resolution by remember { mutableIntStateOf(prefs.getInt("resolution", 1080)) }
            var fps by remember { mutableIntStateOf(prefs.getInt("fps", 30)) }
            var countdown by remember { mutableIntStateOf(prefs.getInt("countdown", 3)) }
            val card = if (dark) Color(0xFF171F32) else Color.White
            val background = if (dark) Color(0xFF0B1020) else Color(0xFFF3F5FA)
            val foreground = if (dark) Color.White else Color(0xFF172033)

            MaterialTheme(
                colorScheme = if (dark) darkColorScheme(primary = Color(0xFF9B88FF), surface = card)
                else lightColorScheme(primary = Color(0xFF6750D8), surface = card)
            ) {
                Surface(Modifier.fillMaxSize(), color = background) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("KALIZE RECORDER", color = Color(0xFF9B88FF), fontWeight = FontWeight.Black)
                                Text(page, color = foreground, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            }
                            IconButton(onClick = { page = if (page == "Settings") "Recorder" else "Settings" }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = foreground)
                            }
                        }
                        when (page) {
                            "Library" -> RecordingLibrary(onBack = { page = "Recorder" })
                            "Settings" -> SettingsPage(
                                dark = dark,
                                onDarkChange = {
                                    dark = it
                                    prefs.edit().putBoolean("dark_theme", it).apply()
                                },
                                resolution = resolution,
                                onResolution = {
                                    resolution = it
                                    prefs.edit().putInt("resolution", it).apply()
                                },
                                fps = fps,
                                onFps = {
                                    fps = it
                                    prefs.edit().putInt("fps", it).apply()
                                },
                                countdown = countdown,
                                onCountdown = {
                                    countdown = it
                                    prefs.edit().putInt("countdown", it).apply()
                                }
                            )
                            else -> RecorderHome(
                                foreground = foreground,
                                card = card,
                                onRecord = { requestCapture() },
                                onBubble = { enableBubble() },
                                onLibrary = { page = "Library" }
                            )
                        }
                        if (page != "Settings") {
                            NavigationBar(containerColor = card) {
                                NavigationBarItem(
                                    selected = page == "Recorder",
                                    onClick = { page = "Recorder" },
                                    icon = { Icon(Icons.Default.Videocam, contentDescription = null) },
                                    label = { Text("Recorder") }
                                )
                                NavigationBarItem(
                                    selected = page == "Library",
                                    onClick = { page = "Library" },
                                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                                    label = { Text("Library") }
                                )
                            }
                        }
                    }
                }
            }
        }
        if (intent?.action == ACTION_RECORD) requestCapture()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_RECORD) requestCapture()
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this) && prefs.getBoolean("bubble_enabled", false)) startBubbleService()
    }

    private fun requestCapture() {
        val delaySeconds = prefs.getInt("countdown", 3).coerceIn(0, 10)
        if (delaySeconds == 0) launchCapturePermission()
        else Handler(Looper.getMainLooper()).postDelayed({ launchCapturePermission() }, delaySeconds * 1000L)
    }

    private fun launchCapturePermission() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capture.launch(manager.createScreenCaptureIntent())
    }

    private fun enableBubble() {
        if (!Settings.canDrawOverlays(this)) {
            overlaySettings.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            prefs.edit().putBoolean("bubble_enabled", true).apply()
            startBubbleService()
        }
    }

    private fun startBubbleService() = startService(Intent(this, FloatingBubbleService::class.java))

    companion object { const val ACTION_RECORD = "com.kalize.recorder.ACTION_RECORD" }
}

@Composable
private fun RecorderHome(
    foreground: Color,
    card: Color,
    onRecord: () -> Unit,
    onBubble: () -> Unit,
    onLibrary: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().weight(1f).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = card)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("SCREEN STUDIO", color = Color(0xFF9B88FF), fontWeight = FontWeight.Bold)
                Text("Capture every moment.", color = foreground, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Record gameplay, tutorials and app demonstrations. Android will ask for screen-capture permission before recording.",
                    color = foreground
                )
                Button(onClick = onRecord, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                    Icon(Icons.Default.Videocam, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start screen recording")
                }
            }
        }
        OutlinedButton(onClick = onBubble, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Enable floating controls")
        }
        OutlinedButton(onClick = onLibrary, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Default.Folder, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Open recording library")
        }
        Text("No watermark is added. Recording performance and maximum quality depend on the device.", color = foreground, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingsPage(
    dark: Boolean,
    onDarkChange: (Boolean) -> Unit,
    resolution: Int,
    onResolution: (Int) -> Unit,
    fps: Int,
    onFps: (Int) -> Unit,
    countdown: Int,
    onCountdown: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth().weight(1f).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Dark appearance", fontWeight = FontWeight.SemiBold)
                        Text("Choose the studio theme", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = dark, onCheckedChange = onDarkChange)
                }
                HorizontalDivider()
                Text("Recording resolution", fontWeight = FontWeight.SemiBold)
                SettingChoice(resolution, listOf(720, 1080, 1440, 2160), { "${it}p" }, onResolution)
                Text("Frame-rate target", fontWeight = FontWeight.SemiBold)
                SettingChoice(fps, listOf(30, 60, 120, 240), { "${it} FPS" }, onFps)
                Text("Countdown before permission prompt", fontWeight = FontWeight.SemiBold)
                SettingChoice(countdown, listOf(0, 3, 5, 10), { if (it == 0) "Off" else "${it} seconds" }, onCountdown)
            }
        }
        Text(
            "Resolution and frame-rate choices are targets. Android hardware, display refresh rate and encoder support determine the actual result.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingChoice(value: Int, options: List<Int>, label: (Int) -> String, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(label(value)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(label(option)) }, onClick = {
                    onSelect(option)
                    expanded = false
                })
            }
        }
    }
}

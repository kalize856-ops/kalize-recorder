package com.kalize.recorder

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
        if (Settings.canDrawOverlays(this)) startBubbleService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var dark by remember { mutableStateOf(prefs.getBoolean("dark_theme", true)) }
            var settings by remember { mutableStateOf(false) }
            var resolution by remember { mutableStateOf(prefs.getInt("resolution", 1080)) }
            var fps by remember { mutableStateOf(prefs.getInt("fps", 30)) }
            val foreground = if (dark) Color.White else Color(0xFF172033)
            val background = if (dark) Color(0xFF0B1020) else Color(0xFFF3F5FA)
            val card = if (dark) Color(0xFF171F32) else Color.White

            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF927BFF), surface = card) else lightColorScheme(primary = Color(0xFF6750D8), surface = card)) {
                Surface(Modifier.fillMaxSize(), color = background) {
                    Column(
                        Modifier.fillMaxSize().padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("KALIZE", color = Color(0xFF9B88FF), fontWeight = FontWeight.Black)
                                Text(if (settings) "Settings" else "Recorder", color = foreground, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            }
                            IconButton(onClick = { settings = !settings }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = foreground)
                            }
                        }

                        if (!settings) {
                            Card(shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = card)) {
                                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text("SCREEN STUDIO", color = Color(0xFF9B88FF), fontWeight = FontWeight.Bold)
                                    Text("Capture every moment.", color = foreground, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    Text("Record gameplay, tutorials and app demonstrations. Android will ask for screen-capture permission each time.", color = foreground)
                                    Button(onClick = { requestCapture() }, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                                        Icon(Icons.Default.Videocam, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Start screen recording")
                                    }
                                }
                            }
                            Button(onClick = { enableBubble() }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                                Text("Enable floating controls")
                            }
                            Text("Your recordings are saved in Kalize Recorder's app-specific Movies folder.", color = foreground)
                            Text("No watermark is added by this app.", color = foreground, fontWeight = FontWeight.SemiBold)
                        } else {
                            Card(colors = CardDefaults.cardColors(containerColor = card)) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Dark appearance", color = foreground, fontWeight = FontWeight.SemiBold)
                                            Text("Choose the studio theme", color = foreground, style = MaterialTheme.typography.bodySmall)
                                        }
                                        Switch(checked = dark, onCheckedChange = {
                                            dark = it
                                            prefs.edit().putBoolean("dark_theme", it).apply()
                                        })
                                    }
                                    HorizontalDivider()
                                    Text("Recording resolution", color = foreground, fontWeight = FontWeight.SemiBold)
                                    SettingChoice(
                                        value = resolution,
                                        options = listOf(720, 1080, 1440, 2160),
                                        label = { "${it}p" },
                                        onSelect = {
                                            resolution = it
                                            prefs.edit().putInt("resolution", it).apply()
                                        }
                                    )
                                    Text("Frame-rate target", color = foreground, fontWeight = FontWeight.SemiBold)
                                    SettingChoice(
                                        value = fps,
                                        options = listOf(30, 60, 120, 240),
                                        label = { "${it} FPS" },
                                        onSelect = {
                                            fps = it
                                            prefs.edit().putInt("fps", it).apply()
                                        }
                                    )
                                    Text("Actual resolution and frame rate depend on the phone display, encoder and Android version. Unsupported settings will fall back automatically.", color = foreground, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            OutlinedButton(onClick = { settings = false }, modifier = Modifier.fillMaxWidth()) { Text("Back to recorder") }
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

    private fun startBubbleService() {
        startService(Intent(this, FloatingBubbleService::class.java))
    }

    companion object { const val ACTION_RECORD = "com.kalize.recorder.ACTION_RECORD" }
}

@Composable
private fun SettingChoice(value: Int, options: List<Int>, label: (Int) -> String, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label(value))
        }
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

package com.kalize.recorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class ProStudioActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("kalize_settings", MODE_PRIVATE) }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        prefs.edit().putBoolean("microphone_enabled", granted).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var microphone by remember { mutableStateOf(prefs.getBoolean("microphone_enabled", false)) }
                var bitrate by remember { mutableIntStateOf(prefs.getInt("bitrate_preset", 1)) }
                var profile by remember { mutableStateOf(prefs.getString("capture_profile", "Gameplay") ?: "Gameplay") }
                Column(
                    Modifier.fillMaxSize().padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("KALIZE PRO STUDIO", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Text("Capture controls", style = MaterialTheme.typography.headlineMedium)
                    Text("Choose a capture profile")
                    listOf("Gameplay", "Tutorial", "High quality").forEach { option ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(selected = profile == option, onClick = {
                                profile = option
                                prefs.edit().putString("capture_profile", option).apply()
                                bitrate = when (option) { "Gameplay" -> 1; "Tutorial" -> 0; else -> 2 }
                                prefs.edit().putInt("bitrate_preset", bitrate).apply()
                            })
                            Text(option)
                        }
                    }
                    HorizontalDivider()
                    Text("Video bitrate")
                    listOf("Balanced · 8 Mbps", "High · 16 Mbps", "Maximum · 28 Mbps").forEachIndexed { index, label ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(selected = bitrate == index, onClick = {
                                bitrate = index
                                prefs.edit().putInt("bitrate_preset", index).apply()
                            })
                            Text(label)
                        }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Microphone audio")
                            Text("Records your microphone alongside screen video.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = microphone, onCheckedChange = { enabled ->
                            if (enabled && ContextCompat.checkSelfPermission(this@ProStudioActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                microphone = enabled
                                prefs.edit().putBoolean("microphone_enabled", enabled).apply()
                            }
                        })
                    }
                    Text("Audio recording requires microphone permission. It records microphone input only; internal game audio capture is not included in this build.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Save and close") }
                }
            }
        }
    }
}

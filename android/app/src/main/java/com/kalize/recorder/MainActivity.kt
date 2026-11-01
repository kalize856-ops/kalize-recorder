package com.kalize.recorder

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val Night = Color(0xFF090D18)
private val Panel = Color(0xFF151E31)
private val Violet = Color(0xFF9874FF)
private val Muted = Color(0xFF9BA9C7)

class MainActivity : ComponentActivity() {
    private lateinit var captureLauncher: ActivityResultLauncher<Intent>
    private var requestedQuality = "1080p"
    private var requestedFps = 30
    private var requestResultHandler: ((Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        captureLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                val serviceIntent = Intent(this, ScreenRecordingService::class.java).apply {
                    putExtra(ScreenRecordingService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenRecordingService.EXTRA_RESULT_DATA, data)
                    putExtra(ScreenRecordingService.EXTRA_QUALITY, requestedQuality)
                    putExtra(ScreenRecordingService.EXTRA_FPS, requestedFps)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                requestResultHandler?.invoke(true)
            } else {
                requestResultHandler?.invoke(false)
            }
        }

        setContent {
            KalizeHome(
                onStartRecording = { quality, fps, onResult ->
                    requestedQuality = quality
                    requestedFps = fps
                    requestResultHandler = onResult
                    val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    captureLauncher.launch(manager.createScreenCaptureIntent())
                },
                onStopRecording = {
                    startService(Intent(this, ScreenRecordingService::class.java).apply {
                        action = ScreenRecordingService.ACTION_STOP
                    })
                }
            )
        }
    }
}

@Composable
private fun KalizeHome(
    onStartRecording: (String, Int, (Boolean) -> Unit) -> Unit,
    onStopRecording: () -> Unit
) {
    var recording by remember { mutableStateOf(false) }
    var requesting by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf("1080p") }
    var fps by remember { mutableStateOf("30 FPS") }
    var status by remember { mutableStateOf("Ready. Approve Android's prompt to begin capture.") }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Night, surface = Panel, primary = Violet,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Column(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF171532), Night)))
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(15.dp), color = Violet) {
                    Text("Ƙ", Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                        color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Ƙalize Recorder", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Screen recording · v0.2.0", color = Muted, fontSize = 12.sp)
                }
            }
            Text("Capture every moment.", fontSize = 29.sp, fontWeight = FontWeight.ExtraBold)
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        when {
                            recording -> "Recording active"
                            requesting -> "Waiting for permission"
                            else -> "Recording studio"
                        },
                        fontSize = 20.sp, fontWeight = FontWeight.Bold
                    )
                    Text(status, color = Muted)
                    Button(
                        onClick = {
                            if (recording || requesting) {
                                onStopRecording()
                                recording = false
                                requesting = false
                                status = "Stop requested. Check Movies/Kalize Recorder for the saved MP4."
                            } else {
                                requesting = true
                                status = "Approve the Android screen-capture prompt to start recording."
                                onStartRecording(quality, fps.removeSuffix(" FPS").toIntOrNull() ?: 30) { approved ->
                                    requesting = false
                                    recording = approved
                                    status = if (approved)
                                        "Capture requested. Use Stop to finish and save the MP4."
                                    else
                                        "Screen capture was cancelled. Tap Start to try again."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (recording || requesting) "■ Stop recording" else "● Start recording")
                    }
                    Text("Resolution target", color = Muted)
                    var qualityExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { qualityExpanded = true }) { Text(quality) }
                        DropdownMenu(expanded = qualityExpanded, onDismissRequest = { qualityExpanded = false }) {
                            listOf("360p", "480p", "720p", "1080p", "1440p", "4K").forEach {
                                DropdownMenuItem(text = { Text(it) }, onClick = {
                                    quality = it
                                    qualityExpanded = false
                                })
                            }
                        }
                    }
                    Text("Frame-rate target", color = Muted)
                    var fpsExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { fpsExpanded = true }) { Text(fps) }
                        DropdownMenu(expanded = fpsExpanded, onDismissRequest = { fpsExpanded = false }) {
                            listOf("30 FPS", "60 FPS").forEach {
                                DropdownMenuItem(text = { Text(it) }, onClick = {
                                    fps = it
                                    fpsExpanded = false
                                })
                            }
                        }
                    }
                    Text("This first milestone records video only. Audio, floating bubble and editor are next.",
                        color = Muted, fontSize = 12.sp)
                }
            }
            Text("Coming next", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            listOf(
                "◉  Floating bubble" to "Draggable overlay and miniature action bubbles",
                "▣  Screenshot" to "One-tap screen capture",
                "✂  Video editor" to "Trim, crop, mute and export",
                "⚡  Game tools" to "Safe performance checks and benchmark"
            ).forEach { (title, desc) ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(title, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(desc, color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}


package com.kalize.recorder

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
    private var requestedFolderUri: String? = null
    private var requestResultHandler: ((Boolean) -> Unit)? = null
    private var screenshotRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        captureLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data

            if (result.resultCode == Activity.RESULT_OK && data != null) {
                if (screenshotRequested) {
                    screenshotRequested = false

                    val screenshotIntent = Intent(
                        this, ScreenshotService::class.java
                    ).apply {
                        putExtra(ScreenshotService.EXTRA_RESULT_CODE, result.resultCode)
                        putExtra(ScreenshotService.EXTRA_RESULT_DATA, data)
                    }

                    ContextCompat.startForegroundService(this, screenshotIntent)
                } else {
                    val serviceIntent = Intent(
                        this, ScreenRecordingService::class.java
                    ).apply {
                        putExtra(ScreenRecordingService.EXTRA_RESULT_CODE, result.resultCode)
                        putExtra(ScreenRecordingService.EXTRA_RESULT_DATA, data)
                        putExtra(ScreenRecordingService.EXTRA_QUALITY, requestedQuality)
                        putExtra(ScreenRecordingService.EXTRA_FPS, requestedFps)

                        requestedFolderUri?.let {
                            putExtra(ScreenRecordingService.EXTRA_OUTPUT_TREE_URI, it)
                        }
                    }

                    ContextCompat.startForegroundService(this, serviceIntent)

                    if (Settings.canDrawOverlays(this)) {
                        startService(Intent(this, FloatingBubbleService::class.java))
                    }

                    requestResultHandler?.invoke(true)
                }
            } else {
                val wasScreenshot = screenshotRequested
                screenshotRequested = false

                if (!wasScreenshot) {
                    requestResultHandler?.invoke(false)
                }
            }
        }

        setContent {
            KalizeHome(
                initialFolderUri = getSharedPreferences(
                    "kalize_settings", MODE_PRIVATE
                ).getString("save_tree_uri", null),

                onStartRecording = { quality, fps, folderUri, onResult ->
                    requestedQuality = quality
                    requestedFps = fps
                    requestedFolderUri = folderUri
                    requestResultHandler = onResult
                    screenshotRequested = false

                    val manager = getSystemService(
                        MEDIA_PROJECTION_SERVICE
                    ) as MediaProjectionManager

                    captureLauncher.launch(manager.createScreenCaptureIntent())
                },

                onTakeScreenshot = {
                    screenshotRequested = true

                    val manager = getSystemService(
                        MEDIA_PROJECTION_SERVICE
                    ) as MediaProjectionManager

                    captureLauncher.launch(manager.createScreenCaptureIntent())
                },

                onStopRecording = {
                    startService(
                        Intent(this, ScreenRecordingService::class.java).apply {
                            action = ScreenRecordingService.ACTION_STOP
                        }
                    )

                    stopService(Intent(this, FloatingBubbleService::class.java))
                },

                onEnableBubble = {
                    if (!Settings.canDrawOverlays(this)) {
                        val overlayIntent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(overlayIntent)
                    }
                }
            )
        }
    }
}

@Composable
private fun KalizeHome(
    initialFolderUri: String?,
    onStartRecording: (String, Int, String?, (Boolean) -> Unit) -> Unit,
    onTakeScreenshot: () -> Unit,
    onStopRecording: () -> Unit,
    onEnableBubble: () -> Unit
) {
    var recording by remember { mutableStateOf(false) }
    var requesting by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf("1080p") }
    var fps by remember { mutableStateOf("30 FPS") }

    var status by remember {
        mutableStateOf("Ready. Approve Android's prompt to begin capture.")
    }

    var saveFolderUri by remember { mutableStateOf(initialFolderUri) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val prefs = remember {
        context.getSharedPreferences(
            "kalize_settings",
            android.content.Context.MODE_PRIVATE
        )
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

                prefs.edit().putString("save_tree_uri", uri.toString()).apply()
                saveFolderUri = uri.toString()
                status = "Save folder selected. New recordings will use it."
            } catch (_: Exception) {
                status = "Could not access that folder. Please choose it again."
            }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Night,
            surface = Panel,
            primary = Violet,
            onBackground = Color.White,
            onSurface = Color.White
        )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF171532), Night)))
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(15.dp),
                    color = Violet
                ) {
                    Text(
                        "Ƙ",
                        Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column {
                    Text("Ƙalize Recorder", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Screen recording · v0.4.0", color = Muted, fontSize = 12.sp)
                }
            }

            Text("Capture every moment.", fontSize = 29.sp, fontWeight = FontWeight.ExtraBold)

            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(
                    Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        when {
                            recording -> "Recording active"
                            requesting -> "Waiting for permission"
                            else -> "Recording studio"
                        },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(status, color = Muted)

                    Button(
                        onClick = {
                            if (recording || requesting) {
                                onStopRecording()
                                recording = false
                                requesting = false
                                status = "Stop requested. Check your selected save folder."
                            } else {
                                requesting = true
                                status = "Approve Android's screen-capture prompt to start."

                                onStartRecording(
                                    quality,
                                    fps.removeSuffix(" FPS").toIntOrNull() ?: 30,
                                    saveFolderUri
                                ) { approved ->
                                    requesting = false
                                    recording = approved
                                    status = if (approved) {
                                        "Capture requested. Use the floating STOP bubble or this button to finish."
                                    } else {
                                        "Screen capture cancelled. Tap Start to try again."
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (recording || requesting) "■ Stop recording" else "● Start recording")
                    }

                    OutlinedButton(
                        onClick = {
                            onTakeScreenshot()
                            status = "Approve Android's prompt to capture a screenshot."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("▣ Take screenshot")
                    }

                    OutlinedButton(
                        onClick = {
                            onEnableBubble()
                            status = "Allow display over other apps in Android Settings, then return here."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Enable floating recording bubble")
                    }

                    Text(
                        "Android asks for screen-capture permission before recording or taking a screenshot.",
                        color = Muted,
                        fontSize = 12.sp
                    )

                    HorizontalDivider(color = Muted.copy(alpha = 0.3f))

                    Text("Save location", fontSize = 18.sp, fontWeight = FontWeight.Bold)

                    Text(
                        if (saveFolderUri == null) {
                            "Videos: Movies/Kalize Recorder. Screenshots: Pictures/Kalize Recorder."
                        } else {
                            "Your selected folder is used for recordings. Screenshots are saved in Pictures/Kalize Recorder."
                        },
                        color = Muted
                    )

                    Button(
                        onClick = {
                            folderPicker.launch(saveFolderUri?.let { Uri.parse(it) })
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Choose phone or SD-card folder")
                    }

                    OutlinedButton(
                        onClick = {
                            saveFolderUri = null
                            prefs.edit().remove("save_tree_uri").apply()
                            status = "Default phone Movies folder selected."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Use default phone storage")
                    }

                    Text(
                        "Choose a folder on the SD card if your phone supports one. Android will ask you to grant access.",
                        color = Muted,
                        fontSize = 12.sp
                    )

                    HorizontalDivider(color = Muted.copy(alpha = 0.3f))

                    Text("Resolution target", color = Muted)
                    var qualityExpanded by remember { mutableStateOf(false) }

                    Box {
                        OutlinedButton(onClick = { qualityExpanded = true }) {
                            Text(quality)
                        }

                        DropdownMenu(
                            expanded = qualityExpanded,
                            onDismissRequest = { qualityExpanded = false }
                        ) {
                            listOf("360p", "480p", "720p", "1080p", "1440p", "4K").forEach {
                                DropdownMenuItem(
                                    text = { Text(it) },
                                    onClick = {
                                        quality = it
                                        qualityExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Text("Frame-rate target", color = Muted)
                    var fpsExpanded by remember { mutableStateOf(false) }

                    Box {
                        OutlinedButton(onClick = { fpsExpanded = true }) {
                            Text(fps)
                        }

                        DropdownMenu(
                            expanded = fpsExpanded,
                            onDismissRequest = { fpsExpanded = false }
                        ) {
                            listOf("30 FPS", "60 FPS").forEach {
                                DropdownMenuItem(
                                    text = { Text(it) },
                                    onClick = {
                                        fps = it
                                        fpsExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Text(
                        "Recording quality depends on your device's display and capabilities.",
                        color = Muted,
                        fontSize = 12.sp
                    )
                }
            }

            Text("Video tools", fontSize = 20.sp, fontWeight = FontWeight.Bold)

            Button(
                onClick = {
                    context.startActivity(
                        Intent(context, VideoEditorActivity::class.java)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("✂ Open Video Editor")
            }

            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(14.dp)) {
                    Text("◉ Floating bubble", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Draggable recording controls", color = Muted, fontSize = 12.sp)
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(14.dp)) {
                    Text("⚡ Game tools", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Safe performance checks and benchmark", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

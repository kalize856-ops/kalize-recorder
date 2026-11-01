
package com.kalize.recorder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
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

private val Night = Color(0xFF080A12)
private val Panel = Color(0xFF141827)
private val Violet = Color(0xFF9B7BFF)
private val VioletBright = Color(0xFFB4A0FF)
private val Muted = Color(0xFF9AA4BC)
private val Border = Color(0xFF2B3146)

class MainActivity : ComponentActivity() {
    private lateinit var captureLauncher: ActivityResultLauncher<Intent>
    private lateinit var microphoneLauncher: ActivityResultLauncher<String>

    private var requestedQuality = "1080p"
    private var requestedFps = 30
    private var requestedFolderUri: String? = null
    private var requestedCountdownSeconds = 0
    private var requestedAudio = false
    private var requestResultHandler: ((Boolean) -> Unit)? = null
    private var screenshotRequested = false
    private var waitingForMicrophone = false

    private val countdownHandler = Handler(Looper.getMainLooper())
    private var pendingRecordingStart: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        microphoneLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (waitingForMicrophone) {
                waitingForMicrophone = false
                if (granted) {
                    launchScreenCapture()
                } else {
                    requestResultHandler?.invoke(false)
                }
            }
        }

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
                    val approvedResultCode = result.resultCode
                    val approvedData = data
                    val countdown = requestedCountdownSeconds

                    val startRecording = Runnable {
                        val serviceIntent = Intent(
                            this, ScreenRecordingService::class.java
                        ).apply {
                            putExtra(ScreenRecordingService.EXTRA_RESULT_CODE, approvedResultCode)
                            putExtra(ScreenRecordingService.EXTRA_RESULT_DATA, approvedData)
                            putExtra(ScreenRecordingService.EXTRA_QUALITY, requestedQuality)
                            putExtra(ScreenRecordingService.EXTRA_FPS, requestedFps)
                            putExtra(ScreenRecordingService.EXTRA_AUDIO_ENABLED, requestedAudio)
                            requestedFolderUri?.let {
                                putExtra(ScreenRecordingService.EXTRA_OUTPUT_TREE_URI, it)
                            }
                        }

                        ContextCompat.startForegroundService(this, serviceIntent)

                        if (Settings.canDrawOverlays(this)) {
                            startService(Intent(this, FloatingBubbleService::class.java))
                        }

                        pendingRecordingStart = null
                        requestResultHandler?.invoke(true)
                    }

                    if (countdown > 0) {
                        pendingRecordingStart = startRecording
                        countdownHandler.postDelayed(startRecording, countdown * 1000L)
                    } else {
                        startRecording.run()
                    }
                }
            } else {
                val wasScreenshot = screenshotRequested
                screenshotRequested = false
                if (!wasScreenshot) {
                    pendingRecordingStart = null
                    requestResultHandler?.invoke(false)
                }
            }
        }

        val settings = getSharedPreferences("kalize_settings", MODE_PRIVATE)

        setContent {
            KalizeHome(
                initialFolderUri = settings.getString("save_tree_uri", null),
                initialQuality = settings.getString("recording_quality", "1080p") ?: "1080p",
                initialFps = settings.getString("recording_fps", "30 FPS") ?: "30 FPS",
                initialCountdownSeconds = settings.getInt("recording_countdown_seconds", 0),
                initialAudio = settings.getBoolean("recording_audio_enabled", false),
                onStartRecording = { quality, fps, folderUri, countdown, audio, onResult ->
                    requestedQuality = quality
                    requestedFps = fps
                    requestedFolderUri = folderUri
                    requestedCountdownSeconds = countdown
                    requestedAudio = audio
                    requestResultHandler = onResult
                    screenshotRequested = false

                    if (audio && ContextCompat.checkSelfPermission(
                            this, Manifest.permission.RECORD_AUDIO
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        waitingForMicrophone = true
                        microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        launchScreenCapture()
                    }
                },
                onTakeScreenshot = {
                    screenshotRequested = true
                    val manager = getSystemService(MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager
                    captureLauncher.launch(manager.createScreenCaptureIntent())
                },
                onStopRecording = {
                    pendingRecordingStart?.let { countdownHandler.removeCallbacks(it) }
                    pendingRecordingStart = null
                    waitingForMicrophone = false
                    requestResultHandler = null
                    startService(Intent(this, ScreenRecordingService::class.java).apply {
                        action = ScreenRecordingService.ACTION_STOP
                    })
                    stopService(Intent(this, FloatingBubbleService::class.java))
                },
                onEnableBubble = {
                    if (!Settings.canDrawOverlays(this)) {
                        startActivity(Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        ))
                    }
                }
            )
        }
    }

    private fun launchScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        pendingRecordingStart?.let { countdownHandler.removeCallbacks(it) }
        pendingRecordingStart = null
        super.onDestroy()
    }
}

@Composable
private fun KalizeHome(
    initialFolderUri: String?,
    initialQuality: String,
    initialFps: String,
    initialCountdownSeconds: Int,
    initialAudio: Boolean,
    onStartRecording: (String, Int, String?, Int, Boolean, (Boolean) -> Unit) -> Unit,
    onTakeScreenshot: () -> Unit,
    onStopRecording: () -> Unit,
    onEnableBubble: () -> Unit
) {
    var recording by remember { mutableStateOf(false) }
    var requesting by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf(initialQuality) }
    var fps by remember { mutableStateOf(initialFps) }
    var countdown by remember { mutableIntStateOf(initialCountdownSeconds) }
    var audioEnabled by remember { mutableStateOf(initialAudio) }
    var status by remember { mutableStateOf("Ready when you are. Start a capture to begin.") }
    var saveFolderUri by remember { mutableStateOf(initialFolderUri) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("kalize_settings", android.content.Context.MODE_PRIVATE)
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                prefs.edit().putString("save_tree_uri", uri.toString()).apply()
                saveFolderUri = uri.toString()
                status = "Your new recording folder is selected."
            } catch (_: Exception) {
                status = "Could not access that folder. Please try again."
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Night, surface = Panel, primary = Violet,
        secondary = VioletBright, onBackground = Color.White,
        onSurface = Color.White, onPrimary = Color.White
    )) {
        Column(
            modifier = Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF17132D), Night, Color(0xFF090D17))))
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text("ƘALIZE RECORDER", color = VioletBright, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Text("YOUR SCREEN.\nYOUR STORY.", color = Color.White, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold)
            Text("Capture gameplay, tutorials and moments worth keeping.", color = Muted, fontSize = 14.sp)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Panel),
                border = BorderStroke(1.dp, Border)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        when {
                            recording -> "Recording active"
                            requesting -> "Waiting for approval or countdown"
                            else -> "Ready to record"
                        },
                        color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold
                    )
                    Text(status, color = Muted, fontSize = 13.sp)

                    Button(
                        onClick = {
                            if (recording || requesting) {
                                onStopRecording()
                                recording = false
                                requesting = false
                                status = "Stop requested. Check your save folder."
                            } else {
                                requesting = true
                                status = if (audioEnabled) {
                                    "Approve microphone access if asked, then approve screen capture."
                                } else {
                                    "Approve Android's screen-capture prompt to start."
                                }
                                onStartRecording(
                                    quality, fps.removeSuffix(" FPS").toIntOrNull() ?: 30,
                                    saveFolderUri, countdown, audioEnabled
                                ) { approved ->
                                    requesting = false
                                    recording = approved
                                    status = if (approved) "Recording started. Use STOP to finish."
                                    else "Capture cancelled or permission denied. You can try again."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (recording || requesting) Color(0xFFB9475B) else Violet
                        )
                    ) {
                        Text(if (recording || requesting) "■  STOP CAPTURE" else "●  START RECORDING", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            onTakeScreenshot()
                            status = "Approve Android's prompt to capture a screenshot."
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text("▣  TAKE SCREENSHOT") }

                    HorizontalDivider(color = Border)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Microphone audio", color = Color.White, fontWeight = FontWeight.Bold)
                            Text(
                                "Include your voice in screen recordings",
                                color = Muted, fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = audioEnabled,
                            onCheckedChange = {
                                audioEnabled = it
                                prefs.edit().putBoolean("recording_audio_enabled", it).apply()
                                status = if (it) "Microphone audio enabled. Android will ask for permission."
                                else "Microphone audio disabled."
                            },
                            enabled = !recording && !requesting
                        )
                    }

                    Text(
                        "Microphone audio records your surroundings too. Get permission before recording other people.",
                        color = Muted, fontSize = 11.sp
                    )
                }
            }

            SectionTitle("Capture settings", "Choose how recording starts and looks")
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Panel), border = BorderStroke(1.dp, Border)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SettingDropdown("Resolution", "Target recording resolution", quality,
                        listOf("360p", "480p", "720p", "1080p", "1440p", "4K")) {
                        quality = it
                        prefs.edit().putString("recording_quality", it).apply()
                        status = "Resolution saved: $it"
                    }
                    HorizontalDivider(color = Border)
                    SettingDropdown("Frame rate", "Target frames per second", fps, listOf("30 FPS", "60 FPS")) {
                        fps = it
                        prefs.edit().putString("recording_fps", it).apply()
                        status = "Frame rate saved: $it"
                    }
                    HorizontalDivider(color = Border)
                    SettingDropdown("Recording countdown", "Delay after Android approval",
                        if (countdown == 0) "Off" else "$countdown seconds",
                        listOf("Off", "3 seconds", "5 seconds", "10 seconds", "15 seconds", "30 seconds")) { selected ->
                        countdown = if (selected == "Off") 0 else selected.substringBefore(" ").toIntOrNull() ?: 0
                        prefs.edit().putInt("recording_countdown_seconds", countdown).apply()
                        status = if (countdown == 0) "Countdown turned off." else "Countdown saved: $countdown seconds."
                    }
                    Text("Settings are saved for next time. Actual quality depends on your phone.", color = Muted, fontSize = 11.sp)
                }
            }

            SectionTitle("Storage", "Choose where recordings are saved")
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Panel), border = BorderStroke(1.dp, Border)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (saveFolderUri == null) "Default save location" else "Custom folder selected", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(
                        if (saveFolderUri == null) "Videos: Movies/Kalize Recorder\nScreenshots: Pictures/Kalize Recorder"
                        else "Recordings use your selected folder. Screenshots are saved separately.",
                        color = Muted, fontSize = 13.sp
                    )
                    Button(onClick = { folderPicker.launch(saveFolderUri?.let { Uri.parse(it) }) }, modifier = Modifier.fillMaxWidth()) {
                        Text("CHOOSE PHONE OR SD-CARD FOLDER")
                    }
                    OutlinedButton(onClick = {
                        saveFolderUri = null
                        prefs.edit().remove("save_tree_uri").apply()
                        status = "Default phone storage selected."
                    }, modifier = Modifier.fillMaxWidth()) { Text("USE DEFAULT STORAGE") }
                }
            }

            SectionTitle("Your creative tools", "Open your saved content and controls")
            ToolButton("▣  Saved videos", "Browse, play, share or delete recordings") {
                context.startActivity(Intent(context, SavedVideosActivity::class.java))
            }
            ToolButton("▧  Screenshot Gallery", "Find, view, share or delete screenshots") {
                context.startActivity(Intent(context, ScreenshotGalleryActivity::class.java))
            }
            ToolButton("✂  Video editor", "Trim clips and export edits") {
                context.startActivity(Intent(context, VideoEditorActivity::class.java))
            }
            ToolButton("⚡  Performance Tools", "Game Booster, network test and CPU benchmark") {
                context.startActivity(Intent(context, PerformanceToolsActivity::class.java))
            }
            ToolButton("◉  Floating controls", "Enable the on-screen recording bubble") {
                onEnableBubble()
                status = "If needed, allow display over other apps in Android Settings."
            }

            OutlinedButton(onClick = { showAbout = true }, modifier = Modifier.fillMaxWidth()) {
                Text("ABOUT KALIZE RECORDER")
            }
            Text("Capture responsibly. Respect other people's privacy.", color = Muted, fontSize = 11.sp)
        }

        if (showAbout) {
            AlertDialog(
                onDismissRequest = { showAbout = false },
                title = { Text("About Kalize Recorder") },
                text = {
                    Text(
                        "Features\n• Screen recording with optional microphone audio\n• Screenshot capture and gallery\n• Saved video library\n• Video trimming and export\n• Resolution and frame-rate settings\n• Optional recording countdown\n• Custom recording folders\n• Performance tools and device information\n\nAndroid asks for screen-capture approval before capturing."
                    )
                },
                confirmButton = { TextButton(onClick = { showAbout = false }) { Text("CLOSE") } },
                containerColor = Panel,
                titleContentColor = Color.White,
                textContentColor = Color.White
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun SettingDropdown(
    title: String,
    description: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(description, color = Muted, fontSize = 11.sp)
        }
        Box {
            OutlinedButton(onClick = { expanded = true }, shape = RoundedCornerShape(12.dp)) {
                Text(value, color = VioletBright, fontWeight = FontWeight.Bold)
                Text("  ⌄", color = VioletBright)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = {
                        onSelect(option)
                        expanded = false
                    })
                }
            }
        }
    }
}

@Composable
private fun ToolButton(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = BorderStroke(1.dp, Border)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
    }
}

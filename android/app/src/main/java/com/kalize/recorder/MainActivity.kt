
package com.kalize.recorder

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.foundation.isSystemInDarkTheme
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
import kotlinx.coroutines.delay

private val DarkBackground = Color(0xFF080A12)
private val DarkPanel = Color(0xFF141827)
private val DarkPrimary = Color(0xFF9B7BFF)
private val DarkSecondary = Color(0xFFB4A0FF)
private val DarkMuted = Color(0xFF9AA4BC)
private val DarkBorder = Color(0xFF2B3146)

private val LightBackground = Color(0xFFF4F2FA)
private val LightPanel = Color(0xFFFFFFFF)
private val LightPrimary = Color(0xFF6846C6)
private val LightSecondary = Color(0xFF8063D8)
private val LightMuted = Color(0xFF666477)
private val LightBorder = Color(0xFFDDD8EA)

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
                        this,
                        ScreenshotService::class.java
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
                            this,
                            ScreenRecordingService::class.java
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
                initialTheme = settings.getString("theme_mode", "System Default") ?: "System Default",
                onStartRecording = { quality, fps, folderUri, countdown, audio, onResult ->
                    requestedQuality = quality
                    requestedFps = fps
                    requestedFolderUri = folderUri
                    requestedCountdownSeconds = countdown
                    requestedAudio = audio
                    requestResultHandler = onResult
                    screenshotRequested = false

                    if (
                        audio &&
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.RECORD_AUDIO
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
                    val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    captureLauncher.launch(manager.createScreenCaptureIntent())
                },
                onStopRecording = {
                    pendingRecordingStart?.let {
                        countdownHandler.removeCallbacks(it)
                    }

                    pendingRecordingStart = null
                    waitingForMicrophone = false
                    requestResultHandler = null

                    startService(
                        Intent(this, ScreenRecordingService::class.java).apply {
                            action = ScreenRecordingService.ACTION_STOP
                        }
                    )

                    stopService(Intent(this, FloatingBubbleService::class.java))
                },
                onEnableBubble = {
                    if (!Settings.canDrawOverlays(this)) {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } else {
                        startService(Intent(this, FloatingBubbleService::class.java))
                    }
                }
            )
        }
    }

    private fun launchScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        pendingRecordingStart?.let {
            countdownHandler.removeCallbacks(it)
        }
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
    initialTheme: String,
    onStartRecording: (String, Int, String?, Int, Boolean, (Boolean) -> Unit) -> Unit,
    onTakeScreenshot: () -> Unit,
    onStopRecording: () -> Unit,
    onEnableBubble: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("kalize_settings", Context.MODE_PRIVATE)
    }
    val recordingPrefs = remember {
        context.getSharedPreferences("kalize_recording_state", Context.MODE_PRIVATE)
    }

    var recording by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var requesting by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }

    var quality by remember { mutableStateOf(initialQuality) }
    var fps by remember { mutableStateOf(initialFps) }
    var countdown by remember { mutableIntStateOf(initialCountdownSeconds) }
    var audioEnabled by remember { mutableStateOf(initialAudio) }
    var saveFolderUri by remember { mutableStateOf(initialFolderUri) }
    var themeMode by remember { mutableStateOf(initialTheme) }
    var currentScreen by remember { mutableStateOf("home") }

    var status by remember {
        mutableStateOf("Ready when you are. Start a capture to begin.")
    }

    val systemDark = isSystemInDarkTheme()
    val darkMode = when (themeMode) {
        "Dark" -> true
        "Light" -> false
        else -> systemDark
    }

    val background = if (darkMode) DarkBackground else LightBackground
    val panel = if (darkMode) DarkPanel else LightPanel
    val primary = if (darkMode) DarkPrimary else LightPrimary
    val secondary = if (darkMode) DarkSecondary else LightSecondary
    val muted = if (darkMode) DarkMuted else LightMuted
    val border = if (darkMode) DarkBorder else LightBorder
    val mainText = if (darkMode) Color.White else Color(0xFF191526)

    LaunchedEffect(recording, paused) {
        while (recording && !paused) {
            delay(1000L)
            elapsedSeconds += 1
        }
    }

    val formattedTime = remember(elapsedSeconds) {
        val hours = elapsedSeconds / 3600
        val minutes = (elapsedSeconds % 3600) / 60
        val seconds = elapsedSeconds % 60
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    DisposableEffect(context) {
        fun refreshRecordingState(isRecording: Boolean, isPaused: Boolean) {
            if (isRecording && !recording) elapsedSeconds = 0
            recording = isRecording
            paused = isPaused
            requesting = false

            status = when {
                isPaused -> "Recording paused. Resume from the floating controls or notification."
                isRecording -> "Recording active. Use the floating controls or STOP to finish."
                else -> "No recording is currently active."
            }
        }

        refreshRecordingState(
            recordingPrefs.getBoolean("is_recording", false),
            recordingPrefs.getBoolean("is_paused", false)
        )

        val stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action == ScreenRecordingService.ACTION_STATE_CHANGED) {
                    refreshRecordingState(
                        intent.getBooleanExtra(ScreenRecordingService.EXTRA_IS_RECORDING, false),
                        intent.getBooleanExtra(ScreenRecordingService.EXTRA_IS_PAUSED, false)
                    )
                }
            }
        }

        ContextCompat.registerReceiver(
            context,
            stateReceiver,
            IntentFilter(ScreenRecordingService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose {
            try {
                context.unregisterReceiver(stateReceiver)
            } catch (_: Exception) {
            }
        }
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
                status = "Your new recording folder is selected."
            } catch (_: Exception) {
                status = "Could not access that folder. Please try again."
            }
        }
    }

    MaterialTheme(
        colorScheme = if (darkMode) {
            darkColorScheme(
                background = background,
                surface = panel,
                primary = primary,
                secondary = secondary,
                onBackground = mainText,
                onSurface = mainText,
                onPrimary = Color.White,
                outline = border
            )
        } else {
            lightColorScheme(
                background = background,
                surface = panel,
                primary = primary,
                secondary = secondary,
                onBackground = mainText,
                onSurface = mainText,
                onPrimary = Color.White,
                outline = border
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        if (darkMode) {
                            listOf(Color(0xFF17132D), background, Color(0xFF090D17))
                        } else {
                            listOf(Color(0xFFEAE4FF), background, Color(0xFFFFFFFF))
                        }
                    )
                )
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (currentScreen == "home") {
                Text(
                    "ƘALIZE RECORDER",
                    color = secondary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black
                )

                Text(
                    "YOUR SCREEN.\nYOUR STORY.",
                    color = mainText,
                    fontSize = 30.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Text(
                    "Capture gameplay, tutorials and moments worth keeping.",
                    color = muted,
                    fontSize = 14.sp
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = panel),
                    border = BorderStroke(1.dp, border)
                ) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            when {
                                recording && paused -> "Recording paused"
                                recording -> "Recording active"
                                requesting -> "Waiting for approval or countdown"
                                else -> "Ready to record"
                            },
                            color = mainText,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (darkMode) Color(0xFF0B0D17) else Color(0xFFEDEAF5)
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (recording && !paused) primary else border
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    "RECORDING TIME",
                                    color = muted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Text(
                                    formattedTime,
                                    color = if (recording && !paused) secondary else mainText,
                                    fontSize = 34.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )

                                Text(
                                    when {
                                        paused -> "PAUSED"
                                        recording -> "● LIVE"
                                        requesting -> "STARTING"
                                        else -> "00:00:00 READY"
                                    },
                                    color = when {
                                        paused -> Color(0xFFFFC857)
                                        recording -> Color(0xFFFF6B78)
                                        else -> muted
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Text(status, color = muted, fontSize = 13.sp)

                        Button(
                            onClick = {
                                if (recording || requesting) {
                                    onStopRecording()
                                    recording = false
                                    paused = false
                                    requesting = false
                                    elapsedSeconds = 0
                                    status = "Stop requested. Check your save folder."
                                } else {
                                    elapsedSeconds = 0
                                    requesting = true
                                    status = if (audioEnabled) {
                                        "Approve microphone access if asked, then approve screen capture."
                                    } else {
                                        "Approve Android's screen-capture prompt to start."
                                    }

                                    onStartRecording(
                                        quality,
                                        fps.removeSuffix(" FPS").toIntOrNull() ?: 30,
                                        saveFolderUri,
                                        countdown,
                                        audioEnabled
                                    ) { approved ->
                                        requesting = false
                                        recording = approved
                                        paused = false
                                        status = if (approved) {
                                            "Recording started. Use STOP to finish."
                                        } else {
                                            "Capture cancelled or permission denied. You can try again."
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (recording || requesting) {
                                    Color(0xFFB9475B)
                                } else {
                                    primary
                                }
                            )
                        ) {
                            Text(
                                if (recording || requesting) "■  STOP CAPTURE" else "●  START RECORDING",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                onTakeScreenshot()
                                status = "Approve Android's prompt to capture a screenshot."
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("▣  TAKE SCREENSHOT")
                        }

                        HorizontalDivider(color = border)

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Microphone audio", color = mainText, fontWeight = FontWeight.Bold)
                                Text(
                                    "Current setting: ${if (audioEnabled) "On" else "Off"}",
                                    color = muted,
                                    fontSize = 12.sp
                                )
                            }
                            TextButton(onClick = { currentScreen = "settings" }) {
                                Text("SETTINGS")
                            }
                        }
                    }
                }

                SectionTitle("Your creative tools", "Open your saved content and controls", mainText, muted)

                ToolButton("▣  Saved videos", "Browse, play, share or delete recordings", panel, border, mainText, muted) {
                    context.startActivity(Intent(context, SavedVideosActivity::class.java))
                }

                ToolButton("▧  Screenshot Gallery", "Find, view, share or delete screenshots", panel, border, mainText, muted) {
                    context.startActivity(Intent(context, ScreenshotGalleryActivity::class.java))
                }

                ToolButton("✂  Video editor", "Trim clips and export edits", panel, border, mainText, muted) {
                    context.startActivity(Intent(context, VideoEditorActivity::class.java))
                }

                ToolButton("⚡  Performance Tools", "Game Booster, network test and CPU benchmark", panel, border, mainText, muted) {
                    context.startActivity(Intent(context, PerformanceToolsActivity::class.java))
                }

                ToolButton("◉  Floating controls", "Enable the on-screen recording bubble", panel, border, mainText, muted) {
                    onEnableBubble()
                    status = if (Settings.canDrawOverlays(context)) {
                        "Floating recording controls requested."
                    } else {
                        "Allow display over other apps in Android Settings."
                    }
                }

                ToolButton("⚙  Settings", "Recording quality, storage, microphone and app theme", panel, border, mainText, muted) {
                    currentScreen = "settings"
                }

                OutlinedButton(
                    onClick = { showAbout = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("ABOUT KALIZE RECORDER")
                }

                Text(
                    "Capture responsibly. Respect other people's privacy.",
                    color = muted,
                    fontSize = 11.sp
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { currentScreen = "home" }) {
                        Text("‹  BACK", color = primary, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("SETTINGS", color = secondary, fontWeight = FontWeight.Black, fontSize = 19.sp)
                }

                Text(
                    "Make Kalize yours.",
                    color = mainText,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Text(
                    "Manage your recording, storage and appearance preferences.",
                    color = muted,
                    fontSize = 13.sp
                )

                SectionTitle("Appearance", "Choose how Kalize Recorder looks", mainText, muted)

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = panel),
                    border = BorderStroke(1.dp, border)
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("App theme", color = mainText, fontWeight = FontWeight.Bold)

                        listOf("System Default", "Dark", "Light").forEach { option ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = themeMode == option,
                                    onClick = {
                                        themeMode = option
                                        prefs.edit().putString("theme_mode", option).apply()
                                    }
                                )
                                Column {
                                    Text(option, color = mainText, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        when (option) {
                                            "System Default" -> "Follow your phone's appearance setting"
                                            "Dark" -> "Use the dark Kalize appearance"
                                            else -> "Use the light Kalize appearance"
                                        },
                                        color = muted,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }

                SectionTitle("Capture settings", "Choose how recording starts and looks", mainText, muted)

                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = panel),
                    border = BorderStroke(1.dp, border)
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SettingDropdown(
                            "Resolution",
                            "Target recording resolution",
                            quality,
                            listOf("360p", "480p", "720p", "1080p", "1440p", "4K"),
                            mainText,
                            muted,
                            primary
                        ) {
                            quality = it
                            prefs.edit().putString("recording_quality", it).apply()
                            status = "Resolution saved: $it"
                        }

                        HorizontalDivider(color = border)

                        SettingDropdown(
                            "Frame rate",
                            "Target frames per second",
                            fps,
                            listOf("30 FPS", "60 FPS"),
                            mainText,
                            muted,
                            primary
                        ) {
                            fps = it
                            prefs.edit().putString("recording_fps", it).apply()
                            status = "Frame rate saved: $it"
                        }

                        HorizontalDivider(color = border)

                        SettingDropdown(
                            "Recording countdown",
                            "Delay after Android approval",
                            if (countdown == 0) "Off" else "$countdown seconds",
                            listOf("Off", "3 seconds", "5 seconds", "10 seconds", "15 seconds", "30 seconds"),
                            mainText,
                            muted,
                            primary
                        ) { selected ->
                            countdown = if (selected == "Off") {
                                0
                            } else {
                                selected.substringBefore(" ").toIntOrNull() ?: 0
                            }

                            prefs.edit().putInt("recording_countdown_seconds", countdown).apply()
                            status = if (countdown == 0) {
                                "Countdown turned off."
                            } else {
                                "Countdown saved: $countdown seconds."
                            }
                        }

                        Text(
                            "Settings are saved for next time. Actual quality depends on your phone.",
                            color = muted,
                            fontSize = 11.sp
                        )
                    }
                }

                SectionTitle("Audio", "Control microphone recording", mainText, muted)

                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = panel),
                    border = BorderStroke(1.dp, border)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Microphone audio", color = mainText, fontWeight = FontWeight.Bold)
                                Text("Include your voice in screen recordings", color = muted, fontSize = 12.sp)
                            }
                            Switch(
                                checked = audioEnabled,
                                onCheckedChange = {
                                    audioEnabled = it
                                    prefs.edit().putBoolean("recording_audio_enabled", it).apply()
                                },
                                enabled = !recording && !requesting
                            )
                        }

                        Text(
                            "Microphone audio can also capture nearby conversations. Get permission before recording other people.",
                            color = muted,
                            fontSize = 11.sp
                        )
                    }
                }

                SectionTitle("Storage", "Choose where recordings are saved", mainText, muted)

                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = panel),
                    border = BorderStroke(1.dp, border)
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            if (saveFolderUri == null) "Default save location" else "Custom folder selected",
                            color = mainText,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            if (saveFolderUri == null) {
                                "Videos: Movies/Kalize Recorder\nScreenshots: Pictures/Kalize Recorder"
                            } else {
                                "Recordings use your selected folder. Screenshots are saved separately."
                            },
                            color = muted,
                            fontSize = 13.sp
                        )

                        Button(
                            onClick = { folderPicker.launch(saveFolderUri?.let { Uri.parse(it) }) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("CHOOSE PHONE OR SD-CARD FOLDER")
                        }

                        OutlinedButton(
                            onClick = {
                                saveFolderUri = null
                                prefs.edit().remove("save_tree_uri").apply()
                                status = "Default phone storage selected."
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("USE DEFAULT STORAGE")
                        }
                    }
                }

                OutlinedButton(
                    onClick = { currentScreen = "home" },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text("SAVE AND RETURN HOME")
                }

                Text(
                    "Your preferences are saved automatically.",
                    color = muted,
                    fontSize = 11.sp
                )
            }
        }

        if (showAbout) {
            AlertDialog(
                onDismissRequest = { showAbout = false },
                title = { Text("About Kalize Recorder") },
                text = {
                    Text(
                        "Features\n" +
                            "• Screen recording with optional microphone audio\n" +
                            "• Screenshot capture and gallery\n" +
                            "• Saved video library\n" +
                            "• Video trimming and export\n" +
                            "• Resolution and frame-rate settings\n" +
                            "• Optional recording countdown\n" +
                            "• Custom recording folders\n" +
                            "• Performance tools and device information\n\n" +
                            "Android asks for screen-capture approval before capturing."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showAbout = false }) {
                        Text("CLOSE")
                    }
                },
                containerColor = panel,
                titleContentColor = mainText,
                textContentColor = mainText
            )
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    subtitle: String,
    textColor: Color,
    mutedColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            color = textColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(subtitle, color = mutedColor, fontSize = 12.sp)
    }
}

@Composable
private fun SettingDropdown(
    title: String,
    description: String,
    value: String,
    options: List<String>,
    textColor: Color,
    mutedColor: Color,
    accentColor: Color,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = textColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(description, color = mutedColor, fontSize = 11.sp)
        }

        Box {
            OutlinedButton(
                onClick = { expanded = true },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(value, color = accentColor, fontWeight = FontWeight.Bold)
                Text("  ⌄", color = accentColor)
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolButton(
    title: String,
    subtitle: String,
    panelColor: Color,
    borderColor: Color,
    textColor: Color,
    mutedColor: Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = panelColor),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                title,
                color = textColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(subtitle, color = mutedColor, fontSize = 12.sp)
        }
    }
}

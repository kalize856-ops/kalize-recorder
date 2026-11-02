
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

private val KalizePurple = Color(0xFF9270F5)
private val KalizeDark = Color(0xFF090B14)
private val KalizeDarkCard = Color(0xFF171B2A)
private val KalizeLight = Color(0xFFF5F3FA)
private val KalizeLightCard = Color.White

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
                if (granted) launchScreenCapture()
                else requestResultHandler?.invoke(false)
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
                    val approvedCode = result.resultCode
                    val approvedData = data
                    val countdown = requestedCountdownSeconds

                    val startRecording = Runnable {
                        val serviceIntent = Intent(
                            this,
                            ScreenRecordingService::class.java
                        ).apply {
                            putExtra(ScreenRecordingService.EXTRA_RESULT_CODE, approvedCode)
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
                onStartRecording = { quality, fps, folder, countdown, audio, callback ->
                    requestedQuality = quality
                    requestedFps = fps
                    requestedFolderUri = folder
                    requestedCountdownSeconds = countdown
                    requestedAudio = audio
                    requestResultHandler = callback
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
                    val manager = getSystemService(MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager
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
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
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

    var page by remember { mutableStateOf("Home") }
    var recording by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var requesting by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var showAbout by remember { mutableStateOf(false) }

    var quality by remember { mutableStateOf(initialQuality) }
    var fps by remember { mutableStateOf(initialFps) }
    var countdown by remember { mutableIntStateOf(initialCountdownSeconds) }
    var audioEnabled by remember { mutableStateOf(initialAudio) }
    var folderUri by remember { mutableStateOf(initialFolderUri) }
    var themeMode by remember { mutableStateOf(initialTheme) }
    var status by remember { mutableStateOf("Ready when you are.") }

    val systemDark = isSystemInDarkTheme()
    val darkMode = when (themeMode) {
        "Dark" -> true
        "Light" -> false
        else -> systemDark
    }

    val background = if (darkMode) KalizeDark else KalizeLight
    val panel = if (darkMode) KalizeDarkCard else KalizeLightCard
    val textColor = if (darkMode) Color.White else Color(0xFF191526)
    val muted = if (darkMode) Color(0xFF9AA4BC) else Color(0xFF6E687C)
    val border = if (darkMode) Color(0xFF30364A) else Color(0xFFE2DDED)
    val accent = if (darkMode) Color(0xFFB7A0FF) else Color(0xFF6846C6)

    LaunchedEffect(recording, paused) {
        while (recording && !paused) {
            delay(1000)
            elapsedSeconds++
        }
    }

    DisposableEffect(context) {
        fun refresh(isRecording: Boolean, isPaused: Boolean) {
            if (isRecording && !recording) elapsedSeconds = 0
            recording = isRecording
            paused = isPaused
            requesting = false
        }

        refresh(
            recordingPrefs.getBoolean("is_recording", false),
            recordingPrefs.getBoolean("is_paused", false)
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == ScreenRecordingService.ACTION_STATE_CHANGED) {
                    refresh(
                        intent.getBooleanExtra(ScreenRecordingService.EXTRA_IS_RECORDING, false),
                        intent.getBooleanExtra(ScreenRecordingService.EXTRA_IS_PAUSED, false)
                    )
                }
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ScreenRecordingService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                folderUri = uri.toString()
                prefs.edit().putString("save_tree_uri", folderUri).apply()
                status = "Recording folder updated."
            } catch (_: Exception) {
                status = "Could not access that folder."
            }
        }
    }

    MaterialTheme(
        colorScheme = if (darkMode) darkColorScheme(
            primary = accent,
            background = background,
            surface = panel,
            onBackground = textColor,
            onSurface = textColor
        ) else lightColorScheme(
            primary = accent,
            background = background,
            surface = panel,
            onBackground = textColor,
            onSurface = textColor
        )
    ) {
        Scaffold(
            containerColor = background,
            bottomBar = {
                NavigationBar(
                    containerColor = panel,
                    contentColor = accent
                ) {
                    listOf(
                        Triple("Home", "⌂", "Capture"),
                        Triple("Library", "▣", "Media"),
                        Triple("Tools", "⚡", "Utilities"),
                        Triple("Settings", "⚙", "Preferences")
                    ).forEach { item ->
                        NavigationBarItem(
                            selected = page == item.first,
                            onClick = { page = item.first },
                            icon = {
                                Text(item.second, fontSize = 21.sp)
                            },
                            label = {
                                Text(item.first, fontSize = 11.sp)
                            },
                            alwaysShowLabel = true
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                if (darkMode) Color(0xFF17132D) else Color(0xFFECE7FA),
                                background
                            )
                        )
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "KALIZE RECORDER",
                            color = accent,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            when (page) {
                                "Home" -> "Your screen. Your story."
                                "Library" -> "Your captured content"
                                "Tools" -> "Performance and utilities"
                                else -> "Personalise your experience"
                            },
                            color = muted,
                            fontSize = 12.sp
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (recording) Color(0xFFB9475B) else panel,
                        border = BorderStroke(1.dp, border)
                    ) {
                        Text(
                            if (recording && paused) " PAUSED " else if (recording) " ● LIVE " else " READY ",
                            color = if (recording) Color.White else accent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                        )
                    }
                }

                when (page) {
                    "Home" -> {
                        SectionHeading("Capture studio", "Start a recording or take a screenshot", textColor, muted)

                        CardPanel(panel, border) {
                            Text(
                                when {
                                    recording && paused -> "Recording paused"
                                    recording -> "Recording in progress"
                                    requesting -> "Waiting for Android approval"
                                    else -> "Ready to capture"
                                },
                                color = textColor,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                "%02d:%02d:%02d".format(
                                    elapsedSeconds / 3600,
                                    (elapsedSeconds % 3600) / 60,
                                    elapsedSeconds % 60
                                ),
                                color = accent,
                                fontSize = 36.sp,
                                fontWeight = FontWeight.ExtraBold
                            )

                            Text(
                                status,
                                color = muted,
                                fontSize = 12.sp
                            )

                            Button(
                                onClick = {
                                    if (recording || requesting) {
                                        onStopRecording()
                                        recording = false
                                        paused = false
                                        requesting = false
                                        elapsedSeconds = 0
                                        status = "Stop requested. Check your library."
                                    } else {
                                        elapsedSeconds = 0
                                        requesting = true
                                        status = "Approve Android's screen-capture prompt."
                                        onStartRecording(
                                            quality,
                                            fps.removeSuffix(" FPS").toIntOrNull() ?: 30,
                                            folderUri,
                                            countdown,
                                            audioEnabled
                                        ) { approved ->
                                            requesting = false
                                            recording = approved
                                            paused = false
                                            status = if (approved) {
                                                "Recording started."
                                            } else {
                                                "Capture cancelled or permission denied."
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                shape = RoundedCornerShape(15.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (recording || requesting) {
                                        Color(0xFFB9475B)
                                    } else accent
                                )
                            ) {
                                Text(
                                    if (recording || requesting) "■  STOP RECORDING"
                                    else "●  START RECORDING",
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    onTakeScreenshot()
                                    status = "Approve Android's screenshot prompt."
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("▣  TAKE SCREENSHOT")
                            }
                        }

                        SectionHeading("Quick access", "Your most-used recording tools", textColor, muted)
                        NavigationCard("Saved videos", "Browse and manage recordings", "▣", panel, border, textColor, muted) {
                            page = "Library"
                        }
                        NavigationCard("Video editor", "Edit, trim and export your clips", "✂", panel, border, textColor, muted) {
                            context.startActivity(Intent(context, VideoEditorActivity::class.java))
                        }
                        NavigationCard("Performance tools", "Game Booster, benchmark and network test", "⚡", panel, border, textColor, muted) {
                            page = "Tools"
                        }
                    }

                    "Library" -> {
                        SectionHeading("Media library", "Everything you capture, in one place", textColor, muted)
                        NavigationCard("Saved videos", "Play, share or delete recordings", "▣", panel, border, textColor, muted) {
                            context.startActivity(Intent(context, SavedVideosActivity::class.java))
                        }
                        NavigationCard("Screenshot gallery", "View and manage your screenshots", "▧", panel, border, textColor, muted) {
                            context.startActivity(Intent(context, ScreenshotGalleryActivity::class.java))
                        }
                        NavigationCard("Video editor", "Trim and prepare videos for export", "✂", panel, border, textColor, muted) {
                            context.startActivity(Intent(context, VideoEditorActivity::class.java))
                        }
                    }

                    "Tools" -> {
                        SectionHeading("Performance centre", "Utilities for your phone and recordings", textColor, muted)
                        NavigationCard("Game Booster & device monitor", "Device information and performance tools", "⚡", panel, border, textColor, muted) {
                            context.startActivity(Intent(context, PerformanceToolsActivity::class.java))
                        }
                        NavigationCard("Floating recording controls", "Enable the on-screen recording bubble", "◉", panel, border, textColor, muted) {
                            onEnableBubble()
                            status = if (Settings.canDrawOverlays(context)) {
                                "Floating controls requested."
                            } else {
                                "Allow display over other apps in Android Settings."
                            }
                        }
                        NavigationCard("Take a screenshot", "Capture your current screen", "▧", panel, border, textColor, muted) {
                            onTakeScreenshot()
                        }
                    }

                    "Settings" -> {
                        SectionHeading("Settings centre", "Preferences are grouped by category", textColor, muted)

                        SettingsGroup("Appearance", "Theme and visual preferences", panel, border, textColor, muted) {
                            listOf("System Default", "Dark", "Light").forEach { option ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = themeMode == option,
                                        onClick = {
                                            themeMode = option
                                            prefs.edit().putString("theme_mode", option).apply()
                                        }
                                    )
                                    Text(option, color = textColor)
                                }
                            }
                        }

                        SettingsGroup("Recording quality", "Resolution and frame rate", panel, border, textColor, muted) {
                            SettingDropdown(
                                "Resolution",
                                quality,
                                listOf("360p", "480p", "720p", "1080p", "1440p", "4K"),
                                textColor,
                                accent
                            ) {
                                quality = it
                                prefs.edit().putString("recording_quality", it).apply()
                                status = "Resolution saved: $it"
                            }

                            HorizontalDivider(color = border)

                            SettingDropdown(
                                "Frame rate",
                                fps,
                                listOf("30 FPS", "60 FPS", "90 FPS", "120 FPS", "144 FPS", "165 FPS", "180 FPS", "200 FPS", "240 FPS"),
                                textColor,
                                accent
                            ) {
                                fps = it
                                prefs.edit().putString("recording_fps", it).apply()
                                status = "Frame rate saved: $it"
                            }
                            Text(
                                "Available quality and frame rate depend on your phone.",
                                color = muted,
                                fontSize = 11.sp
                            )
                        }

                        SettingsGroup("Recording behaviour", "Audio and countdown options", panel, border, textColor, muted) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Microphone audio", color = textColor, fontWeight = FontWeight.SemiBold)
                                    Text("Include your voice in recordings", color = muted, fontSize = 11.sp)
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

                            HorizontalDivider(color = border)

                            SettingDropdown(
                                "Countdown",
                                if (countdown == 0) "Off" else "$countdown seconds",
                                listOf("Off", "3 seconds", "5 seconds", "10 seconds", "15 seconds", "30 seconds"),
                                textColor,
                                accent
                            ) { selected ->
                                countdown = if (selected == "Off") 0
                                else selected.substringBefore(" ").toIntOrNull() ?: 0
                                prefs.edit().putInt("recording_countdown_seconds", countdown).apply()
                            }
                        }

                        SettingsGroup("Storage", "Choose where recordings are saved", panel, border, textColor, muted) {
                            Text(
                                if (folderUri == null) {
                                    "Default: Movies/Kalize Recorder"
                                } else {
                                    "A custom recording folder is selected."
                                },
                                color = muted,
                                fontSize = 12.sp
                            )

                            Button(
                                onClick = {
                                    folderPicker.launch(folderUri?.let { Uri.parse(it) })
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("CHOOSE PHONE OR SD-CARD FOLDER")
                            }

                            OutlinedButton(
                                onClick = {
                                    folderUri = null
                                    prefs.edit().remove("save_tree_uri").apply()
                                    status = "Default storage selected."
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("USE DEFAULT STORAGE")
                            }
                        }

                        SettingsGroup("Floating controls", "On-screen bubble permission", panel, border, textColor, muted) {
                            Text(
                                "Enable the floating bubble to control recording while using other apps.",
                                color = muted,
                                fontSize = 12.sp
                            )
                            Button(
                                onClick = onEnableBubble,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("ENABLE FLOATING CONTROLS")
                            }
                        }

                        SettingsGroup("About", "App information and privacy", panel, border, textColor, muted) {
                            Text("Kalize Recorder • Version 0.2.0", color = textColor)
                            Text(
                                "Screen recording requires Android's capture approval. Always respect other people's privacy.",
                                color = muted,
                                fontSize = 12.sp
                            )
                            OutlinedButton(
                                onClick = { showAbout = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("ABOUT KALIZE RECORDER")
                            }
                        }
                    }
                }
            }
        }

        if (showAbout) {
            AlertDialog(
                onDismissRequest = { showAbout = false },
                title = { Text("About Kalize Recorder") },
                text = {
                    Text(
                        "Kalize Recorder brings screen recording, screenshots, video editing, a media library and performance tools together in one app."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showAbout = false }) {
                        Text("CLOSE")
                    }
                }
            )
        }
    }
}

@Composable
private fun SectionHeading(
    title: String,
    subtitle: String,
    textColor: Color,
    muted: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = textColor, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Text(subtitle, color = muted, fontSize = 12.sp)
    }
}

@Composable
private fun CardPanel(
    panel: Color,
    border: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(23.dp),
        colors = CardDefaults.cardColors(containerColor = panel),
        border = BorderStroke(1.dp, border)
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
            content = content
        )
    }
}

@Composable
private fun NavigationCard(
    title: String,
    subtitle: String,
    symbol: String,
    panel: Color,
    border: Color,
    textColor: Color,
    muted: Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = panel),
        border = BorderStroke(1.dp, border)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(13.dp),
                color = KalizePurple.copy(alpha = 0.14f)
            ) {
                Text(
                    symbol,
                    color = KalizePurple,
                    fontSize = 22.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = textColor, fontWeight = FontWeight.Bold)
                Text(subtitle, color = muted, fontSize = 11.sp)
            }
            Text("›", color = KalizePurple, fontSize = 25.sp)
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    subtitle: String,
    panel: Color,
    border: Color,
    textColor: Color,
    muted: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(19.dp),
        colors = CardDefaults.cardColors(containerColor = panel),
        border = BorderStroke(1.dp, border)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, color = textColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(subtitle, color = muted, fontSize = 11.sp)
            HorizontalDivider(color = border)
            content()
        }
    }
}

@Composable
private fun SettingDropdown(
    title: String,
    value: String,
    options: List<String>,
    textColor: Color,
    accent: Color,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )

        Box {
            OutlinedButton(
                onClick = { expanded = true },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(value, color = accent)
                Text("  ⌄", color = accent)
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

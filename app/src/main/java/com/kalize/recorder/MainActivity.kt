package com.kalize.recorder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("kalize_settings", Context.MODE_PRIVATE) }
    private var screenshotPending = false

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val action = if (screenshotPending) RecorderService.SCREENSHOT else RecorderService.START
            screenshotPending = false
            val serviceIntent = Intent(this, RecorderService::class.java)
                .setAction(action)
                .putExtra(RecorderService.CODE, result.resultCode)
                .putExtra(RecorderService.DATA, result.data)
            ContextCompat.startForegroundService(this, serviceIntent)
        } else screenshotPending = false
    }
    private val overlaySettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) {
            prefs.edit().putBoolean("bubble_enabled", true).apply()
            startBubbleService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RecordingRecovery.recoverPendingVideos(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        MobileAds.initialize(this) { }

        setContent {
            var dark by remember { mutableStateOf(prefs.getBoolean("dark_theme", true)) }
            var page by remember { mutableStateOf("Home") }
            var resolution by remember { mutableIntStateOf(prefs.getInt("resolution", 1080)) }
            var fps by remember { mutableIntStateOf(prefs.getInt("fps", 30)) }
            var countdown by remember { mutableIntStateOf(prefs.getInt("countdown", 3)) }
            var bubbleEnabled by remember { mutableStateOf(prefs.getBoolean("bubble_enabled", false)) }
            var bubbleSize by remember { mutableIntStateOf(prefs.getInt("bubble_size", 58)) }
            var bubbleOpacity by remember { mutableIntStateOf(prefs.getInt("bubble_opacity", 100)) }
            var networkMonitor by remember { mutableStateOf(prefs.getBoolean("network_monitor_enabled", false)) }
            val accent = if (dark) Color(0xFF9B86FF) else Color(0xFF6952D8)
            val bg = if (dark) Color(0xFF0B1020) else Color(0xFFF4F5FA)
            val surface = if (dark) Color(0xFF171F32) else Color.White
            val ink = if (dark) Color.White else Color(0xFF172033)
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = accent, background = bg, surface = surface)
                else lightColorScheme(primary = accent, background = bg, surface = surface)) {
                Surface(Modifier.fillMaxSize(), color = bg) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                                Surface(shape = RoundedCornerShape(14.dp), color = accent.copy(alpha = .16f)) {
                                    Icon(Icons.Default.Videocam, null, tint = accent, modifier = Modifier.padding(10.dp).size(23.dp))
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text("KALIZE RECORDER", color = accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black)
                                    Text(page, color = ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                }
                            }
                            IconButton(onClick = { page = "Settings" }) { Icon(Icons.Default.Settings, "Settings", tint = ink) }
                        }

                        Box(Modifier.weight(1f)) {
                            when (page) {
                                "Home" -> RecorderDashboard(
                                    ink, surface, accent,
                                    onRecord = { requestCapture() },
                                    onBubble = { enableBubble() },
                                    onLibrary = { page = "Library" },
                                    onEdit = { startActivity(Intent(this@MainActivity, VideoEditorActivity::class.java)) },
                                    onStudio = { startActivity(Intent(this@MainActivity, ProStudioActivity::class.java)) },
                                    onScreenshot = { requestScreenshot() },
                                    onDeviceLab = { startActivity(Intent(this@MainActivity, DeviceToolsActivity::class.java)) }
                                )
                                "Edit" -> EditorDashboard(ink, surface, accent) {
                                    startActivity(Intent(this@MainActivity, VideoEditorActivity::class.java))
                                }
                                "Library" -> RecordingLibrary(onBack = { page = "Home" })
                                "Settings" -> SettingsDashboard(
                                    dark, { dark = it; prefs.edit().putBoolean("dark_theme", it).apply() },
                                    resolution, { resolution = it; prefs.edit().putInt("resolution", it).apply() },
                                    fps, { fps = it; prefs.edit().putInt("fps", it).apply() },
                                    countdown, { countdown = it; prefs.edit().putInt("countdown", it).apply() },
                                    bubbleEnabled, { enabled ->
                                        bubbleEnabled = enabled
                                        prefs.edit().putBoolean("bubble_enabled", enabled).apply()
                                        if (enabled) enableBubble() else stopService(Intent(this@MainActivity, FloatingBubbleService::class.java))
                                    },
                                    bubbleSize, { value ->
                                        bubbleSize = value
                                        prefs.edit().putInt("bubble_size", value).apply()
                                        if (bubbleEnabled) { stopService(Intent(this@MainActivity, FloatingBubbleService::class.java)); startBubbleService() }
                                    },
                                    bubbleOpacity, { value ->
                                        bubbleOpacity = value
                                        prefs.edit().putInt("bubble_opacity", value).apply()
                                        if (bubbleEnabled) { stopService(Intent(this@MainActivity, FloatingBubbleService::class.java)); startBubbleService() }
                                    },
                                    { startActivity(Intent(this@MainActivity, ProStudioActivity::class.java)) },
                                    networkMonitor, { enabled ->
                                        networkMonitor = enabled
                                        prefs.edit().putBoolean("network_monitor_enabled", enabled).apply()
                                        syncNetworkSampler()
                                    }
                                )
                            }
                        }
                        if (page == "Home" || page == "Library") KalizeAdBanner()
                        NavigationBar(containerColor = surface) {
                            NavigationBarItem(page == "Home", { page = "Home" }, icon = { Icon(Icons.Default.Videocam, null) }, label = { Text("Recorder") })
                            NavigationBarItem(page == "Edit", { page = "Edit" }, icon = { Icon(Icons.Default.ContentCut, null) }, label = { Text("Edit") })
                            NavigationBarItem(page == "Library", { page = "Library" }, icon = { Icon(Icons.Default.VideoLibrary, null) }, label = { Text("Library") })
                            NavigationBarItem(page == "Settings", { page = "Settings" }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                        }
                    }
                }
            }
        }
        handleExternalAction(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalAction(intent)
    }

    private fun handleExternalAction(intent: Intent?) {
        when (intent?.action) {
            ACTION_RECORD -> requestCapture()
            ACTION_SCREENSHOT -> requestScreenshot()
        }
    }

    override fun onStart() {
        super.onStart()
        syncNetworkSampler()
    }

    override fun onStop() {
        NetworkTrafficSampler.release(this)
        super.onStop()
    }

    private fun syncNetworkSampler() {
        if (prefs.getBoolean("network_monitor_enabled", false)) {
            NetworkTrafficSampler.acquire(this)
        } else {
            NetworkTrafficSampler.release(this)
        }
    }

    override fun onResume() {
        super.onResume()
        applyKeepScreenAwake()
        RecordingRecovery.recoverPendingVideos(this)
        if (Settings.canDrawOverlays(this) && prefs.getBoolean("bubble_enabled", false)) startBubbleService()
    }

    override fun onPause() {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    private fun applyKeepScreenAwake() {
        if (prefs.getBoolean("keep_screen_awake", false)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun requestCapture() {
        screenshotPending = false
        val delay = prefs.getInt("countdown", 3).coerceIn(0, 10)
        if (delay == 0) launchCapturePermission()
        else Handler(Looper.getMainLooper()).postDelayed({ launchCapturePermission() }, delay * 1000L)
    }

    private fun requestScreenshot() {
        // Reuse the active MediaProjection session while recording. Android does not
        // permit opening a second screen-capture consent session for the same task.
        if (prefs.getBoolean("recording_active", false)) {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.SCREENSHOT))
            return
        }
        screenshotPending = true
        launchCapturePermission()
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

    companion object {
        const val ACTION_RECORD = "com.kalize.recorder.ACTION_RECORD"
        const val ACTION_SCREENSHOT = "com.kalize.recorder.ACTION_SCREENSHOT"
    }
}

@Composable
private fun RecorderDashboard(
    ink: Color, surface: Color, accent: Color,
    onRecord: () -> Unit, onBubble: () -> Unit, onLibrary: () -> Unit,
    onEdit: () -> Unit, onStudio: () -> Unit, onScreenshot: () -> Unit, onDeviceLab: () -> Unit
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(17.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("SCREEN RECORDER", color = accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text("Capture your screen", color = ink, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Surface(color = Color(0xFF1EAD78).copy(alpha = .13f), shape = CircleShape) {
                Row(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(7.dp).background(Color(0xFF1EAD78), CircleShape))
                    Text("READY", color = Color(0xFF1EAD78), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = surface)
        ) {
            Box(
                Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF30265E), Color(0xFF171F32), Color(0xFF111827))))
                    .padding(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("KALIZE CAPTURE", color = Color(0xFFC4B8FF), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Surface(color = Color.White.copy(alpha = .10f), shape = CircleShape) {
                            Text("MP4  •  NO WATERMARK", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(142.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(116.dp).background(Color(0xFF9B86FF).copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(82.dp).background(Color(0xFF9B86FF).copy(alpha = .20f), CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Smartphone, null, tint = Color.White, modifier = Modifier.size(47.dp))
                            }
                        }
                    }
                    Text("Ready when you are.", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Record gameplay, tutorials and app demos in clear quality.", color = Color(0xFFB9C0D0), style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        FilledTonalButton(
                            onClick = onRecord,
                            shape = RoundedCornerShape(50.dp),
                            contentPadding = PaddingValues(horizontal = 17.dp, vertical = 10.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFFE94B55), contentColor = Color.White)
                        ) {
                            Icon(Icons.Default.FiberManualRecord, null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Record", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("QUICK TOOLS", color = ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("TOOLS", color = accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            DashboardTool("Floating ball", "Quick controls", Icons.Default.BubbleChart, accent, Modifier.weight(1f), onBubble)
            DashboardTool("Screenshot", "Capture screen", Icons.Default.PhotoCamera, accent, Modifier.weight(1f), onScreenshot)
            DashboardTool("My videos", "Saved clips", Icons.Default.VideoLibrary, accent, Modifier.weight(1f), onLibrary)
        }

        Text("CREATE & MANAGE", color = ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            FeatureCard("Video editor", "Trim, merge and compress", Icons.Default.ContentCut, accent, surface, Modifier.weight(1f), onEdit)
            FeatureCard("Pro capture", "Audio and quality presets", Icons.Default.Tune, accent, surface, Modifier.weight(1f), onStudio)
        }
        FeatureCard("Device Lab", "CPU benchmark, network check and game session", Icons.Default.Speed, accent, surface, Modifier.fillMaxWidth(), onDeviceLab)
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = surface)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.HighQuality, null, tint = accent, modifier = Modifier.size(27.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Recording quality", color = ink, fontWeight = FontWeight.SemiBold)
                    Text("Choose resolution, FPS and countdown in Settings.", color = muted, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.ChevronRight, null, tint = muted)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DashboardTool(title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.height(112.dp), shape = RoundedCornerShape(19.dp)) {
        Column(Modifier.fillMaxSize().padding(11.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.Start) {
            Surface(color = accent.copy(alpha = .14f), shape = RoundedCornerShape(12.dp)) {
                Icon(icon, null, tint = accent, modifier = Modifier.padding(9.dp).size(21.dp))
            }
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun FeatureCard(title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color, surface: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = surface)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(27.dp))
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EditorDashboard(ink: Color, surface: Color, accent: Color, onOpen: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("KALIZE STUDIO", color = accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Text("Make every clip count.", color = ink, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Edit and prepare your recordings for sharing.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        FeatureCard("Open video editor", "Trim clips, merge compatible videos, compress and adjust export options.", Icons.Default.ContentCut, accent, surface, Modifier.fillMaxWidth(), onOpen)
        listOf(
            "Trim and cut" to "Choose the exact start and end of a clip.",
            "Merge clips" to "Combine compatible recordings into one video.",
            "Video compression" to "Create a smaller copy while keeping the original.",
            "Audio and speed" to "Choose whether to keep audio and adjust playback speed."
        ).forEach { (title, desc) ->
            Card(onClick = onOpen, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = surface)) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Edit, null, tint = accent)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, fontWeight = FontWeight.SemiBold, color = ink)
                        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
        }
    }
}

@Composable
private fun SettingsDashboard(
    dark: Boolean, onDark: (Boolean) -> Unit,
    resolution: Int, onResolution: (Int) -> Unit,
    fps: Int, onFps: (Int) -> Unit,
    countdown: Int, onCountdown: (Int) -> Unit,
    bubbleEnabled: Boolean, onBubble: (Boolean) -> Unit,
    bubbleSize: Int, onBubbleSize: (Int) -> Unit,
    bubbleOpacity: Int, onBubbleOpacity: (Int) -> Unit,
    onStudio: () -> Unit,
    networkMonitor: Boolean, onNetworkMonitor: (Boolean) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showTouches by remember { mutableStateOf(Settings.System.getInt(context.contentResolver, "show_touches", 0) == 1) }
    val touchLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.System.canWrite(context)) {
            Settings.System.putInt(context.contentResolver, "show_touches", 1)
            showTouches = true
        }
    }
    val prefs = remember { context.getSharedPreferences("kalize_settings", Context.MODE_PRIVATE) }
    var keepScreenAwake by remember { mutableStateOf(prefs.getBoolean("keep_screen_awake", false)) }
    var vibrateControls by remember { mutableStateOf(prefs.getBoolean("control_vibration", true)) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("PERSONALIZE YOUR RECORDER", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Everything organized by what it controls. Changes are saved on this device.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SettingsCard("Appearance & experience") {
            SwitchSetting("Dark theme", "Use the dark theme throughout Kalize screens", dark, onDark)
            SwitchSetting("Keep screen awake", "Prevent the display from sleeping while Kalize is open", keepScreenAwake) {
                keepScreenAwake = it; prefs.edit().putBoolean("keep_screen_awake", it).apply()
                if (it) (context as? Activity)?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else (context as? Activity)?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            SwitchSetting("Control vibration", "Haptic feedback for supported controls", vibrateControls) {
                vibrateControls = it; prefs.edit().putBoolean("control_vibration", it).apply()
            }
        }

        SettingsCard("Recording") {
            Text("Video resolution", style = MaterialTheme.typography.labelMedium)
            ChoiceSetting(resolution, listOf(240, 360, 480, 720, 1080, 1440, 2160), { "${it}p" }, onResolution)
            Text("Frame rate", style = MaterialTheme.typography.labelMedium)
            ChoiceSetting(fps, listOf(30, 60, 120, 240), { "${it} FPS" }, onFps)
            Text("Start countdown", style = MaterialTheme.typography.labelMedium)
            ChoiceSetting(countdown, listOf(0, 3, 5, 10), { if (it == 0) "Off" else "${it} seconds" }, onCountdown)
            Text("Recording controls and the live recording notification are managed by Android while capture is active. Countdown is configured above.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("The phone's display refresh rate and hardware encoder determine the actual frame rate. Kalize records at the highest supported rate up to your selection.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SettingsCard("Audio") {
            Text("Choose microphone or eligible internal app/game audio, and configure capture quality in Pro Studio.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onStudio, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.MusicNote, null); Spacer(Modifier.width(8.dp)); Text("Open audio & capture settings")
            }
        }

        SettingsCard("Floating controls") {
            SwitchSetting("Floating bubble", "Show quick recording tools over other apps", bubbleEnabled, onBubble)
            Text("Bubble size · ${bubbleSize} dp", style = MaterialTheme.typography.labelMedium)
            Slider(value = bubbleSize.toFloat(), onValueChange = { onBubbleSize(it.toInt()) },
                valueRange = 44f..68f, steps = 5, enabled = bubbleEnabled)
            Text("Bubble opacity · ${bubbleOpacity}%", style = MaterialTheme.typography.labelMedium)
            Slider(value = bubbleOpacity.toFloat(), onValueChange = { onBubbleOpacity(it.toInt()) },
                valueRange = 50f..100f, steps = 9, enabled = bubbleEnabled)
            Text("Android requires the display-over-other-apps permission for the floating controls.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SettingsCard("Screenshot & touch controls") {
            SwitchSetting("Touch indicators", "Show Android touch dots during screen capture", showTouches) { enabled ->
                if (!enabled || Settings.System.canWrite(context)) {
                    Settings.System.putInt(context.contentResolver, "show_touches", if (enabled) 1 else 0)
                    showTouches = enabled
                } else touchLauncher.launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
            }
            Text("Use the screenshot action from the floating bubble or the Recorder home screen. Screenshots are saved to the device media library.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SettingsCard("Performance & network") {
            SwitchSetting("Live network speed", "Continuously measure this device's download and upload traffic while Kalize is open. This is traffic monitoring, not a speed-test download.", networkMonitor, onNetworkMonitor)
            OutlinedButton(onClick = {
                context.startActivity(Intent(context, DeviceToolsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Speed, null); Spacer(Modifier.width(8.dp)); Text("Open Device Lab & CPU benchmark")
            }
        }

        SettingsCard("Storage & saved recordings") {
            Text("Automatic saving is enabled. Completed recordings are published to Movies/Kalize Recorder and appear in your device media library. This build does not yet support choosing a custom folder or SD-card destination from this screen.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Recordings are stored in Movies/Kalize Recorder and are visible in your device's media library. Available storage and Android permissions can affect saving.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SettingsCard("Editing & export") {
            Text("Open the editor for trimming, merging, compression and available export controls. Editing tools are separate from recording preferences.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                context.startActivity(Intent(context, VideoEditorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, modifier = Modifier.fillMaxWidth()) { Text("Open video editor") }
            OutlinedButton(onClick = onStudio, modifier = Modifier.fillMaxWidth()) { Text("Open Pro Studio") }
        }

        SettingsCard("Notifications & permissions") {
            Text("Android controls notification permission, microphone access, screen capture consent and overlay access. You can review app permissions in Android Settings.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }, modifier = Modifier.fillMaxWidth()) { Text("Open Android app permissions") }
        }

        SettingsCard("About Kalize Recorder") {
            Text("Kalize Recorder · Version 1.0.8", fontWeight = FontWeight.SemiBold)
            Text("A no-watermark screen recorder. Features that depend on Android version, device hardware or app playback-capture permissions may not be available on every phone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun SwitchSetting(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun ChoiceSetting(value: Int, options: List<Int>, label: (Int) -> String, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(label(value)) }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(label(option)) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

@Composable
private fun KalizeAdBanner() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val width = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val ad = remember(context, width) {
        AdView(context).apply {
            adUnitId = "ca-app-pub-3940256099942544/9214589741"
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, width))
            loadAd(AdRequest.Builder().build())
        }
    }
    AndroidView(factory = { ad }, modifier = Modifier.fillMaxWidth().wrapContentHeight())
    DisposableEffect(ad) { onDispose { ad.destroy() } }
}

/** Best-effort recovery for complete videos left pending if Android terminated the app. */
private object RecordingRecovery {
    fun recoverPendingVideos(context: Context) {
        if (Build.VERSION.SDK_INT < 29) return
        val collection = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(android.provider.MediaStore.Video.Media._ID, android.provider.MediaStore.Video.Media.SIZE)
        val selection = "${android.provider.MediaStore.Video.Media.IS_PENDING} = 1 AND ${android.provider.MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        try {
            context.contentResolver.query(collection, projection, selection, arrayOf("%Movies/Kalize Recorder%"), null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID)
                val sizeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.SIZE)
                while (cursor.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                    if (cursor.getLong(sizeCol) >= 65536L) {
                        val values = android.content.ContentValues().apply { put(android.provider.MediaStore.Video.Media.IS_PENDING, 0) }
                        context.contentResolver.update(uri, values, null, null)
                    } else context.contentResolver.delete(uri, null, null)
                }
            }
        } catch (_: Exception) { }
    }
}

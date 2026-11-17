package com.kalize.recorder

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.ContentValues
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
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import androidx.core.content.ContextCompat

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
        } else {
            screenshotPending = false
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
        RecordingRecovery.recoverPendingVideos(this)
        requestNotificationPermissionIfNeeded()
        MobileAds.initialize(this) { }
        setContent {
            var dark by remember { mutableStateOf(prefs.getBoolean("dark_theme", true)) }
            var page by remember { mutableStateOf("Recorder") }
            var resolution by remember { mutableIntStateOf(prefs.getInt("resolution", 1080)) }
            var fps by remember { mutableIntStateOf(prefs.getInt("fps", 30)) }
            var countdown by remember { mutableIntStateOf(prefs.getInt("countdown", 3)) }
            var bubbleEnabled by remember { mutableStateOf(prefs.getBoolean("bubble_enabled", false)) }

            val card = if (dark) Color(0xFF171F32) else Color.White
            val background = if (dark) Color(0xFF0B1020) else Color(0xFFF3F5FA)
            val foreground = if (dark) Color.White else Color(0xFF172033)
            val accent = if (dark) Color(0xFF9B88FF) else Color(0xFF6750D8)

            MaterialTheme(
                colorScheme = if (dark) darkColorScheme(primary = accent, surface = card)
                else lightColorScheme(primary = accent, surface = card)
            ) {
                Surface(Modifier.fillMaxSize(), color = background) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("KALIZE RECORDER", color = accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black)
                                Text(page, color = foreground, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            }
                            Surface(shape = RoundedCornerShape(14.dp), color = accent.copy(alpha = 0.13f)) {
                                Icon(
                                    Icons.Default.Videocam,
                                    contentDescription = "Kalize Recorder",
                                    tint = accent,
                                    modifier = Modifier.padding(11.dp)
                                )
                            }
                        }

                        when (page) {
                            "Recorder" -> {
                                Box(Modifier.weight(1f)) {
                                    RecorderHome(
                                        foreground = foreground,
                                        card = card,
                                        onRecord = { requestCapture() },
                                        onBubble = { enableBubble() },
                                        onLibrary = { page = "Library" }
                                    )
                                }
                            }
                            "Edit" -> {
                                Box(Modifier.weight(1f)) {
                                    EditHome(
                                        foreground = foreground,
                                        card = card,
                                        onOpenEditor = {
                                            startActivity(Intent(this@MainActivity, VideoEditorActivity::class.java))
                                        }
                                    )
                                }
                            }
                            "Library" -> {
                                Box(Modifier.weight(1f)) {
                                    RecordingLibrary(onBack = { page = "Recorder" })
                                }
                            }
                            "Settings" -> {
                                Box(Modifier.weight(1f)) {
                                    SettingsPage(
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
                                        bubbleEnabled = bubbleEnabled,
                                        onBubbleChange = { enabled ->
                                            bubbleEnabled = enabled
                                            prefs.edit().putBoolean("bubble_enabled", enabled).apply()
                                            if (enabled) enableBubble()
                                            else stopService(Intent(this@MainActivity, FloatingBubbleService::class.java))
                                        },
                                        countdown = countdown,
                                        onCountdown = {
                                            countdown = it
                                            prefs.edit().putInt("countdown", it).apply()
                                        },
                                        onProStudio = {
                                            startActivity(Intent(this@MainActivity, ProStudioActivity::class.java))
                                        }
                                    )
                                }
                            }
                        }

                        if (page == "Recorder" || page == "Library") {
                            AdMobBanner(modifier = Modifier.fillMaxWidth())
                        }

                        NavigationBar(containerColor = card) {
                            NavigationBarItem(
                                selected = page == "Recorder",
                                onClick = { page = "Recorder" },
                                icon = { Icon(Icons.Default.Videocam, contentDescription = null) },
                                label = { Text("Recorder") }
                            )
                            NavigationBarItem(
                                selected = page == "Edit",
                                onClick = { page = "Edit" },
                                icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                label = { Text("Edit") }
                            )
                            NavigationBarItem(
                                selected = page == "Library",
                                onClick = { page = "Library" },
                                icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                                label = { Text("Library") }
                            )
                            NavigationBarItem(
                                selected = page == "Settings",
                                onClick = { page = "Settings" },
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                label = { Text("Settings") }
                            )
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

    override fun onResume() {
        super.onResume()
        RecordingRecovery.recoverPendingVideos(this)
        if (Settings.canDrawOverlays(this) && prefs.getBoolean("bubble_enabled", false)) startBubbleService()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestCapture() {
        screenshotPending = false
        val delaySeconds = prefs.getInt("countdown", 3).coerceIn(0, 10)
        if (delaySeconds == 0) launchCapturePermission()
        else Handler(Looper.getMainLooper()).postDelayed({ launchCapturePermission() }, delaySeconds * 1000L)
    }

    private fun requestScreenshot() {
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
private fun RecorderHome(
    foreground: Color,
    card: Color,
    onRecord: () -> Unit,
    onBubble: () -> Unit,
    onLibrary: () -> Unit
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, top = 8.dp, end = 18.dp, bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("SCREEN CAPTURE", color = accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text("Ready to record?", color = foreground, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Surface(shape = RoundedCornerShape(50), color = accent.copy(alpha = 0.13f)) {
                    Text("READY", modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = card)
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(accent.copy(alpha = 0.18f), card, card)
                            )
                        )
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("KALIZE SCREEN RECORDER", color = accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Capture every moment.",
                        color = foreground,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        "Record gameplay, tutorials, app demos and anything happening on your screen.",
                        color = muted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "MP4 recordings  •  No watermark  •  Device-dependent quality",
                        color = muted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Text("QUICK ACCESS", color = muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickActionCard(
                    "Floating controls",
                    "Record and capture from anywhere",
                    "◉",
                    Modifier.weight(1f),
                    onBubble,
                    accent,
                    foreground,
                    card
                )
                QuickActionCard(
                    "My recordings",
                    "Browse and share saved clips",
                    "▤",
                    Modifier.weight(1f),
                    onLibrary,
                    accent,
                    foreground,
                    card
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = card)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = accent)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Recording preferences", color = foreground, fontWeight = FontWeight.SemiBold)
                        Text("Resolution, frame rate and countdown are in Settings.", color = muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onRecord,
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp).size(58.dp),
            containerColor = Color(0xFFE94B45),
            contentColor = Color.White,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 10.dp,
                pressedElevation = 14.dp
            ),
            shape = RoundedCornerShape(50)
        ) {
            Icon(
                Icons.Default.Videocam,
                contentDescription = "Start recording",
                modifier = Modifier.size(27.dp)
            )
        }
    }
}

@Composable
private fun EditHome(
    foreground: Color,
    card: Color,
    onOpenEditor: () -> Unit
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary

    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("POST-PRODUCTION", color = accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text("Edit your videos", color = foreground, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("All video editing and export tools live here.", color = muted, style = MaterialTheme.typography.bodyMedium)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = card)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(shape = RoundedCornerShape(14.dp), color = accent.copy(alpha = 0.14f)) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = accent, modifier = Modifier.padding(12.dp))
                }
                Text("Video editor", color = foreground, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Trim clips, merge recordings, compress videos, mute or keep audio, rotate exports and adjust playback speed.",
                    color = muted,
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = onOpenEditor, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(15.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open video editor")
                }
            }
        }

        Text("EDITING TOOLS", color = muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        EditToolRow("Trim and cut", "Choose the start and end of a clip", onOpenEditor, foreground, muted, card, accent)
        EditToolRow("Merge videos", "Join compatible clips into one video", onOpenEditor, foreground, muted, card, accent)
        EditToolRow("Video compression", "Create a smaller copy of a recording", onOpenEditor, foreground, muted, card, accent)
        EditToolRow("Audio and playback", "Keep or mute source audio and change speed", onOpenEditor, foreground, muted, card, accent)
        Text(
            "Compression and merging exports currently use a rewarded-ad unlock. Original files are kept unchanged.",
            color = muted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun EditToolRow(
    title: String,
    description: String,
    onClick: () -> Unit,
    foreground: Color,
    muted: Color,
    card: Color,
    accent: Color
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = card)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = accent.copy(alpha = 0.13f)) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = accent, modifier = Modifier.padding(10.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = foreground, fontWeight = FontWeight.SemiBold)
                Text(description, color = muted, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.Edit, contentDescription = "Open tool", tint = muted)
        }
    }
}

@Composable
private fun QuickActionCard(
    title: String,
    subtitle: String,
    symbol: String,
    modifier: Modifier,
    onClick: () -> Unit,
    accent: Color,
    foreground: Color,
    card: Color
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = card)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = RoundedCornerShape(13.dp), color = accent.copy(alpha = 0.14f)) {
                Text(symbol, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = accent, style = MaterialTheme.typography.titleLarge)
            }
            Text(title, color = foreground, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
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
    onCountdown: (Int) -> Unit,
    bubbleEnabled: Boolean,
    onBubbleChange: (Boolean) -> Unit,
    onProStudio: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showTouches by remember { mutableStateOf(Settings.System.getInt(context.contentResolver, "show_touches", 0) == 1) }
    val touchSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.System.canWrite(context)) {
            Settings.System.putInt(context.contentResolver, "show_touches", 1)
            showTouches = true
        }
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("PERSONALISE YOUR STUDIO", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Card(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                SettingSwitchRow("Dark appearance", "A focused, low-glare studio theme", dark, onDarkChange)
                HorizontalDivider(color = muted.copy(alpha = 0.18f))
                Text("Capture controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                SettingSwitchRow("Floating controls", "Keep the recorder bubble available over other apps", bubbleEnabled, onBubbleChange)
                HorizontalDivider(color = muted.copy(alpha = 0.18f))
                SettingSwitchRow("Touch indicators", "Show Android touch dots while recording", showTouches) { enabled ->
                    if (!enabled || Settings.System.canWrite(context)) {
                        Settings.System.putInt(context.contentResolver, "show_touches", if (enabled) 1 else 0)
                        showTouches = enabled
                    } else {
                        touchSettingsLauncher.launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }
                }
            }
        }
        Card(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Recording quality", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Resolution", color = muted, style = MaterialTheme.typography.labelMedium)
                SettingChoice(resolution, listOf(720, 1080, 1440, 2160), { "${it}p" }, onResolution)
                Text("Frame-rate target", color = muted, style = MaterialTheme.typography.labelMedium)
                SettingChoice(fps, listOf(30, 60, 120, 240), { "${it} FPS" }, onFps)
                Text("Start delay", color = muted, style = MaterialTheme.typography.labelMedium)
                SettingChoice(countdown, listOf(0, 3, 5, 10), { if (it == 0) "Off" else "${it} seconds" }, onCountdown)
            }
        }
        Card(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Advanced capture", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Capture profiles and bitrate presets are managed separately from everyday settings.", color = muted, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onProStudio, modifier = Modifier.fillMaxWidth()) {
                    Text("Open Pro Studio")
                }
            }
        }
        Text("Your device determines the actual resolution and frame rate available. Changes are saved automatically.", color = muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingSwitchRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
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


/**
 * Makes completed MediaStore recordings visible again if Android killed the app
 * before the IS_PENDING flag could be cleared. Never touches a live recording.
 */
private object RecordingRecovery {
    fun recoverPendingVideos(context: Context) {
        if (Build.VERSION.SDK_INT < 29 || recorderServiceIsRunning(context)) return
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.SIZE)
        val selection = "${MediaStore.Video.Media.IS_PENDING} = 1 AND ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%Movies/Kalize Recorder%")
        val resolver = context.contentResolver
        val pending = mutableListOf<Pair<Uri, Long>>()
        try {
            resolver.query(collection, projection, selection, args, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val size = cursor.getLong(sizeColumn)
                    pending += android.content.ContentUris.withAppendedId(collection, id) to size
                }
            }
            pending.forEach { (uri, size) ->
                if (size >= 64 * 1024L) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    resolver.update(uri, values, null, null)
                } else {
                    resolver.delete(uri, null, null)
                }
            }
        } catch (_: Exception) {
            // Recovery is best-effort and must never prevent the recorder UI opening.
        }
    }

    @Suppress("DEPRECATION")
    private fun recorderServiceIsRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == RecorderService::class.java.name
        }
    }
}


/**
 * Anchored adaptive AdMob banner. Uses Google's official test ad unit until
 * Kalize's own AdMob ad unit is configured for production.
 */
@Composable
private fun AdMobBanner(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val widthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val adView = remember(context, widthDp) {
        AdView(context).apply {
            adUnitId = "ca-app-pub-3940256099942544/9214589741"
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp))
            loadAd(AdRequest.Builder().build())
        }
    }

    AndroidView(
        factory = { adView },
        modifier = modifier.wrapContentHeight()
    )

    DisposableEffect(adView) {
        onDispose { adView.destroy() }
    }
}

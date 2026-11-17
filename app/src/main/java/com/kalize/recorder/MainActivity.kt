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
                                            if (enabled) enableBubble() else stopService(Intent(this@MainActivity, FloatingBubbleService::class.java))
                                        },
                                        countdown = countdown,
                                        onCountdown = {
                                            countdown = it
                                            prefs.edit().putInt("countdown", it).apply()
                                        }
                                    )
                                }
                            }
                            else -> {
                                Box(Modifier.weight(1f)) {
                                    RecorderHome(
                                        foreground = foreground,
                                        card = card,
                                        onRecord = { requestCapture() },
                                        onBubble = { enableBubble() },
                                        onLibrary = { page = "Library" },
                                        onProStudio = { startActivity(Intent(this@MainActivity, ProStudioActivity::class.java)) },
                                        onEditor = { startActivity(Intent(this@MainActivity, VideoEditorActivity::class.java)) }
                                    )
                                }
                            }
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
    onLibrary: () -> Unit,
    onProStudio: () -> Unit,
    onEditor: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(20.dp),
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
        OutlinedButton(onClick = onEditor, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Open video editor")
        }
        OutlinedButton(onClick = onProStudio, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Pro Studio · capture profiles and audio")
        }
        Text(
            "No watermark is added. Recording performance and maximum quality depend on the device.",
            color = foreground,
            style = MaterialTheme.typography.bodySmall
        )
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
    onBubbleChange: (Boolean) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showTouches by remember { mutableStateOf(Settings.System.getInt(context.contentResolver, Settings.System.SHOW_TOUCHES, 0) == 1) }
    val touchSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.System.canWrite(context)) {
            Settings.System.putInt(context.contentResolver, Settings.System.SHOW_TOUCHES, 1)
            showTouches = true
        }
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Floating controls", fontWeight = FontWeight.SemiBold)
                        Text("Show the draggable Kalize bubble over other apps.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = bubbleEnabled, onCheckedChange = onBubbleChange)
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Touch indicators", fontWeight = FontWeight.SemiBold)
                        Text("Show Android touch dots system-wide. This changes an Android system setting.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = showTouches, onCheckedChange = { enabled ->
                        if (!enabled || Settings.System.canWrite(context)) {
                            Settings.System.putInt(context.contentResolver, Settings.System.SHOW_TOUCHES, if (enabled) 1 else 0)
                            showTouches = enabled
                        } else {
                            touchSettingsLauncher.launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
                        }
                    })
                }
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

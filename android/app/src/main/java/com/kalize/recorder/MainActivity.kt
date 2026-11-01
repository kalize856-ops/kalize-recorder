
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
import androidx.compose.foundation.shape.CircleShape
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
private val PanelLight = Color(0xFF1B2135)
private val Violet = Color(0xFF9B7BFF)
private val VioletBright = Color(0xFFB4A0FF)
private val Muted = Color(0xFF9AA4BC)
private val Border = Color(0xFF2B3146)
private val Green = Color(0xFF6DE2B0)

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
                        this,
                        ScreenshotService::class.java
                    ).apply {
                        putExtra(
                            ScreenshotService.EXTRA_RESULT_CODE,
                            result.resultCode
                        )
                        putExtra(
                            ScreenshotService.EXTRA_RESULT_DATA,
                            data
                        )
                    }

                    ContextCompat.startForegroundService(
                        this,
                        screenshotIntent
                    )
                } else {
                    val serviceIntent = Intent(
                        this,
                        ScreenRecordingService::class.java
                    ).apply {
                        putExtra(
                            ScreenRecordingService.EXTRA_RESULT_CODE,
                            result.resultCode
                        )
                        putExtra(
                            ScreenRecordingService.EXTRA_RESULT_DATA,
                            data
                        )
                        putExtra(
                            ScreenRecordingService.EXTRA_QUALITY,
                            requestedQuality
                        )
                        putExtra(
                            ScreenRecordingService.EXTRA_FPS,
                            requestedFps
                        )

                        requestedFolderUri?.let {
                            putExtra(
                                ScreenRecordingService.EXTRA_OUTPUT_TREE_URI,
                                it
                            )
                        }
                    }

                    ContextCompat.startForegroundService(
                        this,
                        serviceIntent
                    )

                    if (Settings.canDrawOverlays(this)) {
                        startService(
                            Intent(
                                this,
                                FloatingBubbleService::class.java
                            )
                        )
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
                    "kalize_settings",
                    MODE_PRIVATE
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

                    captureLauncher.launch(
                        manager.createScreenCaptureIntent()
                    )
                },

                onTakeScreenshot = {
                    screenshotRequested = true

                    val manager = getSystemService(
                        MEDIA_PROJECTION_SERVICE
                    ) as MediaProjectionManager

                    captureLauncher.launch(
                        manager.createScreenCaptureIntent()
                    )
                },

                onStopRecording = {
                    startService(
                        Intent(
                            this,
                            ScreenRecordingService::class.java
                        ).apply {
                            action = ScreenRecordingService.ACTION_STOP
                        }
                    )

                    stopService(
                        Intent(
                            this,
                            FloatingBubbleService::class.java
                        )
                    )
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
    onStartRecording: (
        String,
        Int,
        String?,
        (Boolean) -> Unit
    ) -> Unit,
    onTakeScreenshot: () -> Unit,
    onStopRecording: () -> Unit,
    onEnableBubble: () -> Unit
) {
    var recording by remember { mutableStateOf(false) }
    var requesting by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf("1080p") }
    var fps by remember { mutableStateOf("30 FPS") }
    var showAboutDialog by remember { mutableStateOf(false) }

    var status by remember {
        mutableStateOf(
            "Ready when you are. Start a capture to begin."
        )
    }

    var saveFolderUri by remember {
        mutableStateOf(initialFolderUri)
    }

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

                prefs.edit()
                    .putString("save_tree_uri", uri.toString())
                    .apply()

                saveFolderUri = uri.toString()
                status = "Your new recording folder is selected."
            } catch (_: Exception) {
                status = "Could not access that folder. Please try again."
            }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Night,
            surface = Panel,
            primary = Violet,
            secondary = VioletBright,
            onBackground = Color.White,
            onSurface = Color.White,
            onPrimary = Color.White
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF17132D),
                            Night,
                            Color(0xFF090D17)
                        )
                    )
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            // Premium brand header.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Violet,
                    shadowElevation = 8.dp
                ) {
                    Box(
                        modifier = Modifier.size(58.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Ƙ",
                            color = Color.White,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "ƘALIZE",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    )
                    Text(
                        "RECORDER STUDIO",
                        color = Muted,
                        fontSize = 10.sp,
                        letterSpacing = 2.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = PanelLight,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Border
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = 11.dp,
                            vertical = 8.dp
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(
                                    if (recording) Green else VioletBright,
                                    CircleShape
                                )
                        )
                        Text(
                            if (recording) "LIVE" else "READY",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Hero section.
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "YOUR SCREEN.\nYOUR STORY.",
                    color = Color.White,
                    fontSize = 32.sp,
                    lineHeight = 37.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.7).sp
                )

                Text(
                    "Capture gameplay, tutorials and the moments worth keeping.",
                    color = Muted,
                    fontSize = 14.sp,
                    lineHeight = 21.sp
                )
            }

            // Recording studio card.
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Panel
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Border
                ),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = 5.dp
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "CAPTURE STUDIO",
                                color = VioletBright,
                                fontSize = 10.sp,
                                letterSpacing = 1.8.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                when {
                                    recording -> "Recording active"
                                    requesting -> "Waiting for approval"
                                    else -> "Ready to record"
                                },
                                color = Color.White,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFF27213F)
                        ) {
                            Box(
                                modifier = Modifier.size(48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "◉",
                                    color = VioletBright,
                                    fontSize = 28.sp
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        color = Border
                    )

                    Text(
                        status,
                        color = Muted,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )

                    Button(
                        onClick = {
                            if (recording || requesting) {
                                onStopRecording()
                                recording = false
                                requesting = false
                                status =
                                    "Stop requested. Check your save folder for the recording."
                            } else {
                                requesting = true
                                status =
                                    "Approve Android's screen-capture prompt to start."

                                onStartRecording(
                                    quality,
                                    fps.removeSuffix(" FPS")
                                        .toIntOrNull() ?: 30,
                                    saveFolderUri
                                ) { approved ->
                                    requesting = false
                                    recording = approved
                                    status = if (approved) {
                                        "Capture requested. Use the floating STOP bubble or this button to finish."
                                    } else {
                                        "Capture was cancelled. You can try again whenever you're ready."
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(17.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (recording || requesting) {
                                Color(0xFFB9475B)
                            } else {
                                Violet
                            }
                        )
                    ) {
                        Text(
                            if (recording || requesting) {
                                "■   STOP CAPTURE"
                            } else {
                                "●   START RECORDING"
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            onTakeScreenshot()
                            status =
                                "Approve Android's prompt to capture a screenshot."
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            Border
                        )
                    ) {
                        Text(
                            "▣   TAKE SCREENSHOT",
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.4.sp
                        )
                    }

                    Text(
                        "Android will ask for screen-capture approval before capturing.",
                        color = Muted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            // Capture settings.
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SectionHeading(
                    title = "Capture settings",
                    subtitle = "Fine-tune your next recording"
                )

                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Panel
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Border
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        PremiumDropdown(
                            title = "Resolution",
                            description = "Target recording resolution",
                            value = quality,
                            options = listOf(
                                "360p",
                                "480p",
                                "720p",
                                "1080p",
                                "1440p",
                                "4K"
                            ),
                            onSelect = { quality = it }
                        )

                        HorizontalDivider(color = Border)

                        PremiumDropdown(
                            title = "Frame rate",
                            description = "Target frames per second",
                            value = fps,
                            options = listOf("30 FPS", "60 FPS"),
                            onSelect = { fps = it }
                        )

                        Text(
                            "Actual quality depends on your phone's display and hardware.",
                            color = Muted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Storage controls.
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SectionHeading(
                    title = "Storage",
                    subtitle = "Choose where new recordings go"
                )

                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Panel
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Border
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            if (saveFolderUri == null) {
                                "Default save location"
                            } else {
                                "Custom folder selected"
                            },
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            if (saveFolderUri == null) {
                                "Videos: Movies/Kalize Recorder\nScreenshots: Pictures/Kalize Recorder"
                            } else {
                                "Recordings use your selected folder. Screenshots are saved separately in Pictures/Kalize Recorder."
                            },
                            color = Muted,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )

                        Button(
                            onClick = {
                                folderPicker.launch(
                                    saveFolderUri?.let { Uri.parse(it) }
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(15.dp)
                        ) {
                            Text(
                                "CHOOSE PHONE OR SD-CARD FOLDER",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                saveFolderUri = null
                                prefs.edit()
                                    .remove("save_tree_uri")
                                    .apply()
                                status = "Default phone storage selected."
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(15.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                Border
                            )
                        ) {
                            Text("USE DEFAULT STORAGE")
                        }

                        Text(
                            "Android will ask you to grant access to a folder you choose.",
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Video tools.
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SectionHeading(
                    title = "Your creative tools",
                    subtitle = "Everything you need, in one place"
                )

                PremiumToolCard(
                    symbol = "▣",
                    title = "Saved videos",
                    subtitle = "Browse, play, share or delete recordings",
                    onClick = {
                        context.startActivity(
                            Intent(
                                context,
                                SavedVideosActivity::class.java
                            )
                        )
                    }
                )

                PremiumToolCard(
                    symbol = "✂",
                    title = "Video editor",
                    subtitle = "Trim clips and export your edits",
                    onClick = {
                        context.startActivity(
                            Intent(
                                context,
                                VideoEditorActivity::class.java
                            )
                        )
                    }
                )

                PremiumToolCard(
                    symbol = "◉",
                    title = "Floating controls",
                    subtitle = "Enable the on-screen recording bubble",
                    onClick = {
                        onEnableBubble()
                        status =
                            "If needed, allow display over other apps in Android Settings, then return here."
                    }
                )
            }

            // About card.
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF17142B)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Color(0xFF3B315F)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(17.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "ƘALIZE RECORDER",
                            color = VioletBright,
                            fontSize = 10.sp,
                            letterSpacing = 1.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "Made for your screen-capture needs.",
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }

                    TextButton(
                        onClick = { showAboutDialog = true }
                    ) {
                        Text("ABOUT")
                    }
                }
            }

            Text(
                "Capture responsibly. Respect other people's privacy.",
                color = Muted,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        if (showAboutDialog) {
            AlertDialog(
                onDismissRequest = {
                    showAboutDialog = false
                },
                title = {
                    Text(
                        "About Ƙalize Recorder",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "YOUR SCREEN-CAPTURE STUDIO",
                            color = VioletBright,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )

                        Text(
                            "Features\n" +
                                "• Screen recording\n" +
                                "• Screenshot capture\n" +
                                "• Saved video library\n" +
                                "• Video trimming and export\n" +
                                "• Resolution and frame-rate targets\n" +
                                "• Custom recording folders"
                        )

                        HorizontalDivider(color = Border)

                        Text(
                            "Privacy\n" +
                                "Android asks for screen-capture permission before capture begins. Review the system prompt before approving it. Your recordings are saved to the configured device folder."
                        )

                        Text(
                            "Use screen capture responsibly and respect other people's privacy.",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { showAboutDialog = false }
                    ) {
                        Text("CLOSE")
                    }
                },
                containerColor = Panel,
                titleContentColor = Color.White,
                textContentColor = Color.White
            )
        }
    }
}

@Composable
private fun SectionHeading(
    title: String,
    subtitle: String
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            subtitle,
            color = Muted,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun PremiumDropdown(
    title: String,
    description: String,
    value: String,
    options: List<String>,
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
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                color = Muted,
                fontSize = 11.sp
            )
        }

        Box {
            OutlinedButton(
                onClick = { expanded = true },
                shape = RoundedCornerShape(13.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Violet.copy(alpha = 0.7f)
                ),
                contentPadding = PaddingValues(
                    horizontal = 14.dp,
                    vertical = 8.dp
                )
            ) {
                Text(
                    value,
                    color = VioletBright,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(5.dp))
                Text("⌄", color = VioletBright)
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                option,
                                fontWeight = if (option == value) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                }
                            )
                        },
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
private fun PremiumToolCard(
    symbol: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Panel
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Border
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(15.dp),
                color = Color(0xFF29213F)
            ) {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        symbol,
                        color = VioletBright,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }

            Text(
                "›",
                color = VioletBright,
                fontSize = 28.sp,
                fontWeight = FontWeight.Light
            )
        }
    }
}

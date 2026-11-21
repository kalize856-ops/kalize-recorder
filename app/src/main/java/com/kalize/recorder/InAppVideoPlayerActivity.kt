package com.kalize.recorder

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class InAppVideoPlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: intent.getStringExtra("video_uri")?.let(Uri::parse)
        if (uri == null) { finish(); return }
        val name = intent.getStringExtra("video_name") ?: "Video"
        setContent {
            KalizeTheme {
                InAppVideoPlayer(uri, name, { finish() }, {
                    startActivity(Intent(this, VideoEditorActivity::class.java).setData(uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }, { finish() })
            }
        }
    }
}

@Composable
private fun InAppVideoPlayer(uri: Uri, name: String, onBack: () -> Unit, onEdit: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    var details by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF111522)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
            Text(name, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { details = true }) { Icon(Icons.Default.Info, "Details", tint = Color.White) }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(factory = { c ->
                PlayerView(c).apply {
                    this.player = exoPlayer
                    useController = true
                    controllerAutoShow = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            }, update = { it.player = exoPlayer }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        }
        Column(Modifier.fillMaxWidth().background(Color(0xFF111522)).navigationBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("PLAYBACK SPEED", color = Color(0xFFB9A9FF), style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                    FilterChip(
                        selected = playbackSpeed == speed,
                        onClick = {
                            playbackSpeed = speed
                            exoPlayer.playbackParameters = PlaybackParameters(speed)
                        },
                        label = { Text("${speed}x") }
                    )
                }
            }
            Text("VIDEO TOOLS", color = Color(0xFFB9A9FF), style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "video/*"; putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Share video"))
                }) { Icon(Icons.Default.Share, null); Text("Share") }
                TextButton(onClick = onEdit) { Icon(Icons.Default.Edit, null); Text("Edit") }
                TextButton(onClick = { details = true }) { Icon(Icons.Default.Info, null); Text("Details") }
                TextButton(onClick = { confirmDelete = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Default.Delete, null); Text("Delete")
                }
            }
        }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("Video details") },
        text = { Text("Name: $name\nLocation: $uri") },
        confirmButton = { TextButton(onClick = { details = false }) { Text("Close") } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete this video?") },
        text = { Text("This removes the selected video from your device media library. This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = {
                runCatching { context.contentResolver.delete(uri, null, null) }
                confirmDelete = false
                onDeleted()
            }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
        }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

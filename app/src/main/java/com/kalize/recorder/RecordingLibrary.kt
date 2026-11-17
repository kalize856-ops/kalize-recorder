package com.kalize.recorder

import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

private data class RecordingItem(
    val id: Long,
    val name: String,
    val uri: android.net.Uri,
    val date: Long,
    val duration: Long,
    val size: Long
)

private data class ScreenshotItem(
    val id: Long,
    val name: String,
    val uri: android.net.Uri,
    val date: Long
)

@Composable
fun RecordingLibrary(onBack: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var deleteTarget by remember { mutableStateOf<Pair<android.net.Uri, String>?>(null) }
    val recordings = remember(refresh) {
        val result = mutableListOf<RecordingItem>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%Movies/Kalize Recorder%")
        context.contentResolver.query(
            collection, projection, selection, args,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                result += RecordingItem(
                    id, cursor.getString(nameCol) ?: "Recording.mp4",
                    android.content.ContentUris.withAppendedId(collection, id),
                    cursor.getLong(dateCol), cursor.getLong(durationCol), cursor.getLong(sizeCol)
                )
            }
        }
        result
    }

    val screenshots = remember(refresh) {
        val result = mutableListOf<ScreenshotItem>()
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED
            )
            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("%Pictures/Kalize Recorder/Screenshots%")
            context.contentResolver.query(
                collection, projection, selection, args,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    result += ScreenshotItem(
                        id,
                        cursor.getString(nameCol) ?: "Screenshot.png",
                        android.content.ContentUris.withAppendedId(collection, id),
                        cursor.getLong(dateCol)
                    )
                }
            }
        }
        result
    }

    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("YOUR LIBRARY", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text("Media library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            Row {
                TextButton(onClick = { context.startActivity(Intent(context, VideoEditorActivity::class.java)) }) { Text("Edit video") }
                TextButton(onClick = { refresh++ }) { Text("Refresh") }
            }
        }
        Text(
            "${recordings.size} videos  •  ${screenshots.size} screenshots",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (recordings.isEmpty() && screenshots.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your library is empty", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Your Kalize recordings and screenshots will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onBack) { Text("Start recording") }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (screenshots.isNotEmpty()) {
                    item { Text("Screenshots", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                    items(screenshots, key = { "image_${it.id}" }) { item ->
                        Card {
                            Row(
                                Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = MaterialTheme.shapes.large
                                ) {
                                    Icon(Icons.Default.Image, null, Modifier.padding(13.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(
                                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.date * 1000)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(item.uri, "image/png")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "View screenshot"))
                                }) { Icon(Icons.Default.Image, contentDescription = "View screenshot") }
                                IconButton(onClick = {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "image/png"
                                        putExtra(Intent.EXTRA_STREAM, item.uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(send, "Share screenshot"))
                                }) { Icon(Icons.Default.Share, contentDescription = "Share screenshot") }
                                IconButton(onClick = { deleteTarget = item.uri to item.name }) {
                                    Icon(androidx.compose.material.icons.filled.Delete, contentDescription = "Delete screenshot")
                                }
                            }
                        }
                    }
                }
                if (recordings.isNotEmpty()) {
                    item { Text("Recordings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                    items(recordings, key = { "video_${it.id}" }) { item ->
                        Card {
                            Row(
                                Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = MaterialTheme.shapes.large
                                ) {
                                    Icon(Icons.Default.PlayArrow, null, Modifier.padding(13.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(
                                        "${formatDuration(item.duration)}  •  ${formatBytes(item.size)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.date * 1000)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(item.uri, "video/mp4")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Play recording"))
                                }) { Icon(Icons.Default.PlayArrow, contentDescription = "Play") }
                                IconButton(onClick = {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "video/mp4"
                                        putExtra(Intent.EXTRA_STREAM, item.uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(send, "Share recording"))
                                }) { Icon(Icons.Default.Share, contentDescription = "Share") }
                                IconButton(onClick = { deleteTarget = item.uri to item.name }) {
                                    Icon(androidx.compose.material.icons.filled.Delete, contentDescription = "Delete recording")
                                }
                            }
                        }
                    }
                }
            }
        }
        deleteTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("Delete media?") },
                text = { Text("Delete ${target.second}? This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        context.contentResolver.delete(target.first, null, null)
                        deleteTarget = null
                        refresh++
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
                }
            )
        }
    }
}

private fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    else -> "${bytes / 1000} KB"
}

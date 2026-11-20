package com.kalize.recorder

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
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

private data class LibraryMedia(
    val id: Long, val name: String, val uri: android.net.Uri, val date: Long,
    val duration: Long = 0L, val size: Long = 0L, val image: Boolean = false,
    val trashed: Boolean = false
)

@Composable
fun RecordingLibrary(onBack: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var recycleBin by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<LibraryMedia?>(null) }
    var operation by remember { mutableStateOf("trash") }
    val all = remember(refresh) { queryKalizeMedia(context) }
    val active = all.filterNot { it.trashed }
    val deleted = all.filter { it.trashed }
    val videos = active.filterNot { it.image }
    val images = active.filter { it.image }
    val shown = if (recycleBin) deleted else active
    val storage = remember {
        val stats = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        stats.availableBytes to stats.totalBytes
    }

    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (recycleBin) "RECYCLE BIN" else "YOUR LIBRARY", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(if (recycleBin) "Recently deleted" else "Media library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { recycleBin = !recycleBin }) { Text(if (recycleBin) "Library" else "Recycle bin (${deleted.size})") }
            TextButton(onClick = { refresh++ }) { Text("Refresh") }
        }
        if (!recycleBin) {
            Text("${videos.size} videos • ${images.size} screenshots", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("STORAGE OVERVIEW", fontWeight = FontWeight.Bold)
                    Text("Kalize videos: ${bytes(videos.sumOf { it.size })} • Phone free: ${bytes(storage.first)}")
                    LinearProgressIndicator(
                        progress = { if (storage.second > 0) (storage.first.toFloat() / storage.second).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            TextButton(onClick = { context.startActivity(Intent(context, VideoEditorActivity::class.java)) }) { Text("Open video editor") }
        } else {
            Text(if (Build.VERSION.SDK_INT >= 30) "Restore items or permanently delete them."
                else "Recycle Bin requires Android 11 or later.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (shown.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (recycleBin) "Recycle bin is empty" else "Your library is empty", style = MaterialTheme.typography.titleLarge)
                    if (!recycleBin) OutlinedButton(onClick = onBack) { Text("Start recording") }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (!recycleBin && images.isNotEmpty()) {
                    item { Text("Screenshots", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                    items(images, key = { "img${it.id}" }) { item ->
                        MediaRow(item, false, { openItem(context, item) }, { shareItem(context, item) },
                            { target = item; operation = "trash" }, {}, {})
                    }
                    item { Text("Recordings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                }
                val rows = if (recycleBin) deleted else videos
                items(rows, key = { "vid${it.id}" }) { item ->
                    MediaRow(item, recycleBin, { openItem(context, item) }, { shareItem(context, item) },
                        { target = item; operation = "trash" },
                        { target = item; operation = "restore" },
                        { target = item; operation = "permanent" })
                }
            }
        }
    }

    target?.let { item ->
        val title = when (operation) {
            "restore" -> "Restore item?"
            "permanent" -> "Delete permanently?"
            else -> if (Build.VERSION.SDK_INT >= 30) "Move to Recycle Bin?" else "Delete media?"
        }
        val message = when (operation) {
            "restore" -> "Restore ${item.name} to your library?"
            "permanent" -> "Permanently delete ${item.name}? This cannot be undone."
            else -> if (Build.VERSION.SDK_INT >= 30) "Move ${item.name} to the Recycle Bin? You can restore it later."
                else "This Android version does not support the in-app Recycle Bin. Delete ${item.name} permanently?"
        }
        AlertDialog(
            onDismissRequest = { target = null },
            title = { Text(title) }, text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        when (operation) {
                            "restore" -> updateTrash(context, item, false)
                            "permanent" -> context.contentResolver.delete(item.uri, null, null)
                            else -> if (Build.VERSION.SDK_INT >= 30) updateTrash(context, item, true)
                                else context.contentResolver.delete(item.uri, null, null)
                        }
                    }
                    target = null
                    refresh++
                }) { Text(if (operation == "restore") "Restore" else if (operation == "permanent") "Delete forever" else "Move to bin") }
            },
            dismissButton = { TextButton(onClick = { target = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun MediaRow(
    item: LibraryMedia, inBin: Boolean, open: () -> Unit, share: () -> Unit,
    trash: () -> Unit, restore: () -> Unit, permanent: () -> Unit
) {
    Card {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (item.image) Icons.Default.Image else Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(if (item.image) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.date * 1000))
                    else "${duration(item.duration)} • ${bytes(item.size)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (inBin) {
                IconButton(onClick = restore) { Icon(Icons.Default.Restore, "Restore") }
                IconButton(onClick = permanent) { Icon(Icons.Default.Delete, "Delete forever", tint = MaterialTheme.colorScheme.error) }
            } else {
                IconButton(onClick = open) { Icon(if (item.image) Icons.Default.Image else Icons.Default.PlayArrow, "Open") }
                IconButton(onClick = share) { Icon(Icons.Default.Share, "Share") }
                IconButton(onClick = trash) { Icon(Icons.Default.Delete, "Move to Recycle Bin", tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private fun queryKalizeMedia(context: android.content.Context): List<LibraryMedia> {
    val result = mutableListOf<LibraryMedia>()
    fun query(uri: android.net.Uri, projection: Array<String>, selection: String, args: Array<String>, image: Boolean) {
        val bundle = android.os.Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
            if (Build.VERSION.SDK_INT >= 30) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        context.contentResolver.query(uri, projection, bundle, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val durationCol = if (image) -1 else cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
            val trashCol = if (Build.VERSION.SDK_INT >= 30) cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED) else -1
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                result += LibraryMedia(id, cursor.getString(nameCol) ?: if (image) "Screenshot" else "Recording.mp4",
                    android.content.ContentUris.withAppendedId(uri, id), cursor.getLong(dateCol),
                    if (durationCol >= 0) cursor.getLong(durationCol) else 0L, cursor.getLong(sizeCol),
                    image, trashCol >= 0 && cursor.getInt(trashCol) != 0)
            }
        }
    }
    val videoProjection = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE, MediaStore.Video.Media.DURATION)
    val imageProjection = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE)
    if (Build.VERSION.SDK_INT >= 30) {
        videoProjection += MediaStore.MediaColumns.IS_TRASHED
        imageProjection += MediaStore.MediaColumns.IS_TRASHED
    }
    query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoProjection.toTypedArray(),
        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("%Movies/Kalize Recorder%"), false)
    if (Build.VERSION.SDK_INT >= 29) query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageProjection.toTypedArray(),
        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("%Pictures/Kalize Recorder/Screenshots%"), true)
    return result.sortedByDescending { it.date }
}

private fun updateTrash(context: android.content.Context, item: LibraryMedia, trash: Boolean) {
    check(Build.VERSION.SDK_INT >= 30)
    context.contentResolver.update(item.uri, ContentValues().apply {
        put(MediaStore.MediaColumns.IS_TRASHED, if (trash) 1 else 0)
    }, null, null)
}

private fun openItem(context: android.content.Context, item: LibraryMedia) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(item.uri, if (item.image) "image/*" else "video/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Open media"))
}

private fun shareItem(context: android.content.Context, item: LibraryMedia) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = if (item.image) "image/*" else "video/*"
        putExtra(Intent.EXTRA_STREAM, item.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share media"))
}

private fun duration(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
private fun bytes(value: Long): String = when {
    value >= 1_000_000_000 -> "%.1f GB".format(value / 1_000_000_000.0)
    value >= 1_000_000 -> "%.1f MB".format(value / 1_000_000.0)
    else -> "${value / 1000} KB"
}

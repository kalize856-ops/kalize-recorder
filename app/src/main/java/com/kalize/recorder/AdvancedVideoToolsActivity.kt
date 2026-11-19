package com.kalize.recorder

import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Media3-powered filters, crop and resize. Source videos are never modified. */
class AdvancedVideoToolsActivity : ComponentActivity() {
    private var sourceUri by mutableStateOf<Uri?>(null)
    private var durationMs by mutableLongStateOf(0L)
    private var exporting by mutableStateOf(false)
    private var status by mutableStateOf("Choose a video to apply effects and export a new copy.")

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            sourceUri = uri
            try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
            durationMs = try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, uri)
                val value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                retriever.release()
                value
            } catch (_: Exception) { 0L }
            status = if (durationMs > 0) "Video ready. Choose effects and export." else "Could not read this video's duration."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var filter by remember { mutableStateOf("Original") }
                var crop by remember { mutableStateOf("Full frame") }
                var resolution by remember { mutableIntStateOf(0) }
                var keepAudio by remember { mutableStateOf(true) }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("KALIZE STUDIO", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("Advanced video tools", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Apply a visual filter, crop the frame or resize the export. Your original video remains untouched.")
                    OutlinedButton(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (sourceUri == null) "Choose video" else "Choose a different video")
                    }
                    if (sourceUri != null) {
                        Text("FILTER", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        listOf("Original", "Black & white", "Negative").forEach { choice ->
                            FilterChip(selected = filter == choice, onClick = { filter = choice }, label = { Text(choice) })
                        }
                        HorizontalDivider()
                        Text("CROP", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        listOf("Full frame", "Center crop · 10%", "Center crop · 20%").forEach { choice ->
                            FilterChip(selected = crop == choice, onClick = { crop = choice }, label = { Text(choice) })
                        }
                        Text("Center crop removes equal amounts from the outer edges.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        Text("OUTPUT SIZE", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        listOf(
                            0 to "Keep source size",
                            240 to "240p",
                            360 to "360p",
                            480 to "480p",
                            720 to "720p",
                            1080 to "1080p",
                            1440 to "1440p · 2K",
                            2160 to "2160p · 4K"
                        ).forEach { item ->
                            FilterChip(selected = resolution == item.first, onClick = { resolution = item.first }, label = { Text(item.second) })
                        }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = keepAudio, onCheckedChange = { keepAudio = it })
                            Text("Keep original audio")
                        }
                        Button(enabled = !exporting && durationMs > 0, modifier = Modifier.fillMaxWidth(), onClick = {
                            val uri = sourceUri ?: return@Button
                            exporting = true
                            status = "Preparing export…"
                            lifecycleScope.launch {
                                val result = runCatching {
                                    withContext(Dispatchers.IO) { exportAdvanced(uri, filter, crop, resolution, keepAudio) }
                                }
                                exporting = false
                                status = result.fold(
                                    { "Export complete. Saved to Movies/Kalize Recorder/Edits." },
                                    { "Export failed: ${it.message ?: "unsupported video or encoder format"}" }
                                )
                            }
                        }) { Text(if (exporting) "Exporting…" else "Export edited video") }
                    }
                    if (exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                }
            }
        }
    }

    private suspend fun exportAdvanced(source: Uri, filter: String, crop: String, resolution: Int, keepAudio: Boolean): Uri {
        val effects = mutableListOf<Effect>()
        when (filter) {
            "Black & white" -> effects.add(RgbFilter.createGrayscaleFilter())
            "Negative" -> effects.add(RgbFilter.createInvertedFilter())
        }
        when (crop) {
            "Center crop · 10%" -> effects.add(Crop(-0.8f, 0.8f, -0.8f, 0.8f))
            "Center crop · 20%" -> effects.add(Crop(-0.6f, 0.6f, -0.6f, 0.6f))
        }
        if (resolution > 0) {
            val retriever = MediaMetadataRetriever()
            val dimensions = try {
                retriever.setDataSource(this, source)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                width to height
            } finally {
                retriever.release()
            }
            require(dimensions.first > 0 && dimensions.second > 0) {
                "Could not read the selected video's dimensions."
            }
            val shortEdge = minOf(dimensions.first, dimensions.second)
            val outputSize = minOf(resolution, shortEdge)
            // Media3 1.5.1 uses createForShortSide to preserve portrait and landscape aspect ratios.
            val scale = outputSize.toFloat() / shortEdge.toFloat()
            val outputWidth = (dimensions.first * scale).toInt().coerceAtLeast(1)
            val outputHeight = (dimensions.second * scale).toInt().coerceAtLeast(1)
            val presentation = Presentation.createForWidthAndHeight(
                outputWidth,
                outputHeight,
                Presentation.LAYOUT_SCALE_TO_FIT
            )
            effects.add(presentation)
        }
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(source))
            .setRemoveAudio(!keepAudio)
            .setEffects(Effects(emptyList(), effects))
            .build()
        val temporary = File(cacheDir, "kalize_advanced_${System.currentTimeMillis()}.mp4")
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                val transformer = Transformer.Builder(this).addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                }).build()
                continuation.invokeOnCancellation { transformer.cancel() }
                transformer.start(edited, temporary.absolutePath)
            }
            return saveToMediaStore(temporary)
        } finally {
            temporary.delete()
        }
    }

    private fun saveToMediaStore(file: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Kalize_Effect_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Kalize Recorder/Edits")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create the output video.")
        try {
            contentResolver.openOutputStream(uri, "w")!!.use { output ->
                FileInputStream(file).use { input -> input.copyTo(output) }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }
            return uri
        } catch (error: Exception) {
            contentResolver.delete(uri, null, null)
            throw error
        }
    }
}

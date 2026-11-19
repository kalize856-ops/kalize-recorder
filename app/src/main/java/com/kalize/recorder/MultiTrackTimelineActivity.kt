package com.kalize.recorder

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.DefaultGainProvider
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(UnstableApi::class)
class MultiTrackTimelineActivity : ComponentActivity() {
    private var videoClips by mutableStateOf<List<Uri>>(emptyList())
    private var audioClips by mutableStateOf<List<Uri>>(emptyList())
    private var exporting by mutableStateOf(false)
    private var status by mutableStateOf("Add video clips and optional music or voice tracks.")
    private var transition by mutableStateOf("Dip to black")
    private var originalVolume by mutableFloatStateOf(1f)
    private val audioVolumes = mutableStateListOf<Float>()

    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach(::keepPermission)
            videoClips = uris
            status = "${uris.size} video clips added to the main timeline."
        }
    }
    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach(::keepPermission)
            audioClips = uris
            audioVolumes.clear()
            repeat(uris.size) { audioVolumes.add(0.85f) }
            status = "${uris.size} independent audio track(s) added."
        }
    }

    private fun keepPermission(uri: Uri) {
        try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                var showTransitionMenu by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("KALIZE STUDIO", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("Multi-track timeline", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Arrange video clips, layer separate audio tracks, balance their levels and export a mixed MP4.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { videoPicker.launch(arrayOf("video/*")) }, modifier = Modifier.weight(1f)) { Text("Add video clips") }
                        OutlinedButton(onClick = { audioPicker.launch(arrayOf("audio/*")) }, modifier = Modifier.weight(1f)) { Text("Add audio") }
                    }
                    Card {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("TIMELINE TRACKS", fontWeight = FontWeight.Bold)
                            Text("VIDEO 1 · Main sequence", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            if (videoClips.isEmpty()) Text("No video clips yet.", style = MaterialTheme.typography.bodySmall)
                            LazyColumn(Modifier.heightIn(max = 210.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                itemsIndexed(videoClips) { index, uri ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("${index + 1}. ${uri.lastPathSegment ?: "Video clip"}", Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                                        TextButton(enabled = index > 0, onClick = { videoClips = videoClips.toMutableList().also { val v = it.removeAt(index); it.add(index - 1, v) } }) { Text("↑") }
                                        TextButton(enabled = index < videoClips.lastIndex, onClick = { videoClips = videoClips.toMutableList().also { val v = it.removeAt(index); it.add(index + 1, v) } }) { Text("↓") }
                                        TextButton(onClick = { videoClips = videoClips.toMutableList().also { it.removeAt(index) } }) { Text("×") }
                                    }
                                }
                            }
                            HorizontalDivider()
                            Text("AUDIO TRACKS · Layered mix", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            if (audioClips.isEmpty()) Text("No added audio tracks. Original clip audio can still be mixed.", style = MaterialTheme.typography.bodySmall)
                            audioClips.forEachIndexed { index, uri ->
                                Column {
                                    Text("AUDIO ${index + 1} · ${uri.lastPathSegment ?: "Audio clip"}", maxLines = 1, style = MaterialTheme.typography.bodySmall)
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Slider(value = audioVolumes.getOrElse(index) { 0.85f }, onValueChange = { if (index < audioVolumes.size) audioVolumes[index] = it }, valueRange = 0f..1.5f, modifier = Modifier.weight(1f))
                                        Text("${(audioVolumes.getOrElse(index) { 0.85f } * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                    Text("Original clip audio · ${(originalVolume * 100).toInt()}%", fontWeight = FontWeight.SemiBold)
                    Slider(value = originalVolume, onValueChange = { originalVolume = it }, valueRange = 0f..1.5f)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Transition between clips", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Box {
                            OutlinedButton(onClick = { showTransitionMenu = true }) { Text(transition) }
                            DropdownMenu(expanded = showTransitionMenu, onDismissRequest = { showTransitionMenu = false }) {
                                listOf("Hard cut", "Dip to black").forEach { choice ->
                                    DropdownMenuItem(text = { Text(choice) }, onClick = { transition = choice; showTransitionMenu = false })
                                }
                            }
                        }
                    }
                    Text(if (transition == "Dip to black") "Inserts a short black separator between clips (a dip-to-black transition)." else "Clips join directly with a clean cut.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Audio mixing", fontWeight = FontWeight.SemiBold)
                    Text("Every added audio file is placed on its own overlapping audio sequence. Set each track's level independently; each added track fades in and out automatically.", style = MaterialTheme.typography.bodySmall)
                    Button(
                        enabled = !exporting && videoClips.isNotEmpty(),
                        onClick = {
                            exporting = true
                            status = "Preparing timeline and mixing audio…"
                            val videos = videoClips.toList()
                            val audios = audioClips.toList()
                            val levels = audioVolumes.toList()
                            val original = originalVolume
                            val style = transition
                            scope.launch {
                                val result = runCatching { exportTimeline(videos, audios, levels, original, style) }
                                exporting = false
                                status = result.fold(
                                    { "Export complete. Mixed video saved in Movies/Kalize Recorder/Edits." },
                                    { "Export failed: ${it.message ?: "this media combination is not supported on this device"}" }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (exporting) "Exporting timeline…" else "Export mixed timeline") }
                    if (exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Back to video editor") }
                }
            }
        }
    }

    private suspend fun exportTimeline(videos: List<Uri>, audios: List<Uri>, levels: List<Float>, originalLevel: Float, transitionStyle: String): Uri {
        require(videos.isNotEmpty()) { "Add at least one video clip." }
        val videoItems = mutableListOf<EditedMediaItem>()
        videos.forEachIndexed { index, uri ->
            val originalGain = DefaultGainProvider.Builder(originalLevel).build()
            videoItems += EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setEffects(Effects(listOf(GainProcessor(originalGain)), emptyList()))
                .build()
            if (transitionStyle == "Dip to black" && index < videos.lastIndex) {
                val black = File(cacheDir, "kalize_black_transition.png")
                if (!black.exists()) {
                    val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).drawColor(Color.BLACK)
                    FileOutputStream(black).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                val still = MediaItem.Builder().setUri(black.toUri()).setImageDurationMs(450).build()
                videoItems += EditedMediaItem.Builder(still).build()
            }
        }
        val sequences = mutableListOf<EditedMediaItemSequence>()
        sequences += EditedMediaItemSequence.withAudioAndVideoFrom(videoItems)
        audios.forEachIndexed { index, uri ->
            val durationUs = readDurationUs(uri).coerceAtLeast(2_000_000L)
            val fadeUs = minOf(1_000_000L, durationUs / 3L).coerceAtLeast(1L)
            val gain = DefaultGainProvider.Builder(levels.getOrElse(index) { 0.85f })
                .addFadeAt(0L, fadeUs, DefaultGainProvider.FADE_IN_LINEAR)
                .addFadeAt((durationUs - fadeUs).coerceAtLeast(0L), fadeUs, DefaultGainProvider.FADE_OUT_LINEAR)
                .build()
            val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setRemoveVideo(true)
                .setEffects(Effects(listOf(GainProcessor(gain)), emptyList()))
                .build()
            sequences += EditedMediaItemSequence.withAudioFrom(listOf(audioItem))
        }
        val composition = Composition.Builder(sequences.first(), *sequences.drop(1).toTypedArray()).build()
        val temporary = File(cacheDir, "kalize_timeline_${System.currentTimeMillis()}.mp4")
        suspendCancellableCoroutine<Unit> { continuation ->
            val transformer = Transformer.Builder(this).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    if (continuation.isActive) continuation.resumeWithException(exportException)
                }
            }).build()
            transformer.start(composition, temporary.absolutePath)
        }
        require(temporary.exists() && temporary.length() > 0L) { "The export did not create a video file." }
        return withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "Kalize_Timeline_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Kalize Recorder/Edits")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val output = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create the output video.")
            try {
                contentResolver.openOutputStream(output)?.use { sink -> temporary.inputStream().use { it.copyTo(sink) } }
                    ?: throw IllegalStateException("Could not open the output video.")
                contentResolver.update(output, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                temporary.delete()
                output
            } catch (error: Exception) {
                contentResolver.delete(output, null, null)
                throw error
            }
        }
    }

    private fun readDurationUs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
        } finally { retriever.release() }
    }
}

package com.kalize.recorder

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.media.MediaMetadataRetriever
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

class VideoEditorActivity : ComponentActivity() {
    private var selectedUri by mutableStateOf<Uri?>(null)
    private var durationMs by mutableLongStateOf(0L)
    private var exporting by mutableStateOf(false)
    private var message by mutableStateOf("Choose a video to begin.")

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            selectedUri = uri
            durationMs = try {
                MediaMetadataRetriever().run {
                    setDataSource(this@VideoEditorActivity, uri)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        .also { release() }
                }
            } catch (_: Exception) { 0L }
            message = if (durationMs > 0) "Video ready to trim." else "Could not read this video's duration."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var range by remember { mutableStateOf(0f..1f) }
                var keepAudio by remember { mutableStateOf(true) }
                val scope = rememberCoroutineScope()
                Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("VIDEO EDITOR", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("Trim a recording", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Select a video, choose the section to keep, then export a new copy. The original file is not changed.")
                    Button(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (selectedUri == null) "Choose video" else "Choose another video")
                    }
                    if (selectedUri != null && durationMs > 0) {
                        Text("Video length: ${formatTime(durationMs)}")
                        Text("Start: ${formatTime((range.start * durationMs).toLong())}")
                        Text("End: ${formatTime((range.endInclusive * durationMs).toLong())}")
                        RangeSlider(
                            value = range,
                            onValueChange = { range = it },
                            valueRange = 0f..1f,
                            steps = 0
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("0:00")
                            Text(formatTime(durationMs))
                        }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = keepAudio, onCheckedChange = { keepAudio = it })
                            Text("Keep original audio")
                        }
                        Button(
                            enabled = !exporting && (range.endInclusive - range.start) > 0.02f,
                            onClick = {
                                val uri = selectedUri ?: return@Button
                                val start = (range.start * durationMs).toLong()
                                val end = (range.endInclusive * durationMs).toLong()
                                exporting = true
                                message = "Exporting edited video…"
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { BasicVideoTrimmer.export(this@VideoEditorActivity, uri, start, end, keepAudio) }
                                    }
                                    exporting = false
                                    message = result.fold(
                                        { "Export complete. The edited copy is in Movies/Kalize Recorder/Edits." },
                                        { "Export failed: ${it.message ?: "unsupported video format"}" }
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (exporting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("Export trimmed video")
                        }
                    }
                    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Close editor") }
                }
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }
}

private object BasicVideoTrimmer {
    fun export(context: Context, source: Uri, startMs: Long, endMs: Long, keepAudio: Boolean): Uri {
        require(endMs > startMs) { "Choose a valid trim range." }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Kalize_Edit_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Kalize Recorder/Edits")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val outputUri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create the edited video.")
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            context.contentResolver.openFileDescriptor(outputUri, "w")!!.use { descriptor ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(context, source, null)
                    val trackMap = mutableMapOf<Int, Int>()
                    var videoTrackFound = false
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        val isVideo = mime.startsWith("video/")
                        val isAudio = mime.startsWith("audio/")
                        if (!isVideo && !(keepAudio && isAudio)) continue
                        val muxerInstance = muxer ?: MediaMuxer(
                            descriptor.fileDescriptor,
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                        ).also { muxer = it }
                        trackMap[i] = muxerInstance.addTrack(format)
                        if (isVideo) videoTrackFound = true
                    }
                    require(videoTrackFound) { "The selected file has no supported video track." }
                    val activeMuxer = muxer ?: throw IllegalStateException("No export tracks found.")
                    activeMuxer.start()
                    muxerStarted = true
                    trackMap.keys.forEach { extractor.selectTrack(it) }
                    val startUs = startMs * 1000
                    val endUs = endMs * 1000
                    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    val baseUs = extractor.sampleTime.coerceAtLeast(0L)
                    val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
                    val info = MediaMuxer.BufferInfo()
                    while (true) {
                        val inputTrack = extractor.sampleTrackIndex
                        if (inputTrack < 0) break
                        val sampleTime = extractor.sampleTime
                        if (sampleTime > endUs) break
                        if (trackMap.containsKey(inputTrack)) {
                            buffer.clear()
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) break
                            info.set(0, size, (sampleTime - baseUs).coerceAtLeast(0L), extractor.sampleFlags)
                            activeMuxer.writeSampleData(trackMap.getValue(inputTrack), buffer, info)
                        }
                        if (!extractor.advance()) break
                    }
                } finally {
                    extractor.release()
                }
            }
            if (muxerStarted) muxer?.stop()
            muxer?.release()
            muxer = null
            context.contentResolver.update(outputUri, ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }, null, null)
            return outputUri
        } catch (error: Exception) {
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) { }
            try { muxer?.release() } catch (_: Exception) { }
            context.contentResolver.delete(outputUri, null, null)
            throw error
        }
    }
}

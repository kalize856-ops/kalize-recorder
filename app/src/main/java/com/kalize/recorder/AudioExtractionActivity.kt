package com.kalize.recorder

import android.content.ContentValues
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Extracts the original encoded audio track without re-encoding it. */
class AudioExtractionActivity : ComponentActivity() {
    private var source by mutableStateOf<Uri?>(null)
    private var details by mutableStateOf("Choose a video. Kalize will check whether it contains an audio track.")
    private var exporting by mutableStateOf(false)
    private var hasAudio by mutableStateOf(false)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            source = uri
            try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
            lifecycleScope.launch {
                val info = withContext(Dispatchers.IO) { inspect(uri) }
                hasAudio = info.second
                details = info.first
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("KALIZE STUDIO", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("Audio extraction", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Save the soundtrack from a video as a separate audio file. The original video is never changed.")
                    OutlinedButton(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (source == null) "Choose video" else "Choose another video")
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Text(details, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(
                        enabled = source != null && hasAudio && !exporting,
                        onClick = {
                            val uri = source ?: return@Button
                            exporting = true
                            details = "Extracting audio…"
                            lifecycleScope.launch {
                                val result = withContext(Dispatchers.IO) { runCatching { extract(uri) } }
                                exporting = false
                                details = result.fold(
                                    { "Audio saved to Music/Kalize Recorder/Extracted Audio." },
                                    { "Extraction failed: ${it.message ?: "unsupported audio format"}" }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (exporting) "Extracting…" else "Extract audio") }
                    if (exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("The audio stream is copied without quality-loss re-encoding when the source codec is supported by the MP4/M4A container.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                }
            }
        }
    }

    private fun inspect(uri: Uri): Pair<String, Boolean> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(this, uri, null)
            var audio = false
            var video = "Video"
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) audio = true
                if (mime.startsWith("video/")) {
                    val w = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
                    val h = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
                    video = if (w > 0 && h > 0) "${w} × ${h}" else "Video"
                }
            }
            val duration = try {
                val f = if (extractor.trackCount > 0) extractor.getTrackFormat(0) else null
                if (f != null && f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1_000_000 else 0L
            } catch (_: Exception) { 0L }
            val minutes = duration / 60
            val seconds = duration % 60
            ("${video} · ${minutes}:${seconds.toString().padStart(2, '0')} · " +
                if (audio) "Audio track found. Ready to extract." else "No audio track was found." ) to audio
        } catch (e: Exception) {
            ("Could not read this video: ${e.message ?: "unsupported file"}") to false
        } finally {
            extractor.release()
        }
    }

    private fun extract(uri: Uri) {
        val extractor = MediaExtractor()
        val temporary = File(cacheDir, "kalize_audio_${System.currentTimeMillis()}.m4a")
        try {
            extractor.setDataSource(this, uri, null)
            val audioTrack = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("This video has no audio track.")
            val inputFormat = extractor.getTrackFormat(audioTrack)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            require(mime == "audio/mp4a-latm") {
                "Only AAC audio can currently be copied directly into an M4A container. This video uses $mime."
            }
            val muxer = MediaMuxer(temporary.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val outputTrack = muxer.addTrack(inputFormat)
                muxer.start()
                extractor.selectTrack(audioTrack)
                val maxInput = if (inputFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    inputFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceIn(64 * 1024, 4 * 1024 * 1024)
                else 1024 * 1024
                val buffer = ByteBuffer.allocateDirect(maxInput)
                val info = MediaMuxer.BufferInfo()
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(outputTrack, buffer, info)
                    if (!extractor.advance()) break
                }
                muxer.stop()
            } finally {
                muxer.release()
            }
            saveAudio(temporary)
        } finally {
            extractor.release()
            temporary.delete()
        }
    }

    private fun saveAudio(file: File): Uri {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "Kalize_Audio_$stamp.m4a")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Kalize Recorder/Extracted Audio")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }
        val output = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create the audio file.")
        try {
            contentResolver.openOutputStream(output, "w")!!.use { stream ->
                FileInputStream(file).use { input -> input.copyTo(stream) }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                contentResolver.update(output, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            }
            return output
        } catch (error: Exception) {
            contentResolver.delete(output, null, null)
            throw error
        }
    }
}

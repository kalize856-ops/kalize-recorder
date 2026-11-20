package com.kalize.recorder

import android.content.ContentValues
import android.media.MediaCodec
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
import java.io.FileOutputStream
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
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
                                    { "Audio converted to AAC and saved to Music/Kalize Recorder/Extracted Audio." },
                                    { "Extraction failed: ${it.message ?: "the source audio could not be decoded"}" }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (exporting) "Extracting…" else "Extract audio") }
                    if (exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Kalize converts supported source audio tracks to AAC/M4A so videos using different audio codecs can be extracted. The original video remains unchanged.",
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

    /** Re-encodes any decodable source audio track to broadly compatible AAC/M4A. */
    private fun extract(uri: Uri) {
        val stamp = System.currentTimeMillis()
        val input = File(cacheDir, "kalize_audio_source_$stamp")
        val output = File(cacheDir, "kalize_audio_$stamp.m4a")
        try {
            contentResolver.openInputStream(uri)?.use { source ->
                FileOutputStream(input).use { target -> source.copyTo(target) }
            } ?: throw IllegalStateException("Could not open the selected video.")
            val command = "-y -i ${shellQuote(input.absolutePath)} -map 0:a:0 -vn -c:a aac -b:a 192k -movflags +faststart ${shellQuote(output.absolutePath)}"
            val session = FFmpegKit.execute(command)
            if (!ReturnCode.isSuccess(session.returnCode) || !output.exists() || output.length() == 0L) {
                val reason = session.failStackTrace ?: session.allLogsAsString
                throw IllegalStateException(reason?.take(500) ?: "The audio codec could not be converted.")
            }
            saveAudio(output)
        } finally {
            input.delete()
            output.delete()
        }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

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

package com.kalize.recorder

import android.content.ContentValues
import android.media.MediaExtractor
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
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import org.json.JSONArray

class MultiTrackTimelineActivity : ComponentActivity() {
    private var videoClips by mutableStateOf<List<Uri>>(emptyList())
    private var audioClips by mutableStateOf<List<Uri>>(emptyList())
    private var exporting by mutableStateOf(false)
    private var status by mutableStateOf("Add video clips and optional music or voice tracks.")
    private var transition by mutableStateOf("Crossfade · 1 second")
    private var exportHeight by mutableIntStateOf(720)
    private var exportFps by mutableIntStateOf(30)
    private var originalVolume by mutableFloatStateOf(1f)
    private val audioVolumes = mutableStateListOf<Float>()
    private val projectPrefs by lazy { getSharedPreferences("kalize_timeline_project", MODE_PRIVATE) }

    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach(::keepPermission)
            videoClips = videoClips + uris
            status = "${uris.size} video clips added to the main timeline."
        }
    }
    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach(::keepPermission)
            audioClips = audioClips + uris
            repeat(uris.size) { audioVolumes.add(0.85f) }
            status = "${uris.size} independent audio track(s) added."
        }
    }

    private fun restoreDraft() {
        fun readUris(key: String): List<Uri> = runCatching {
            val array = JSONArray(projectPrefs.getString(key, "[]") ?: "[]")
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.isNotBlank() }?.let(Uri::parse)
            }
        }.getOrDefault(emptyList())
        videoClips = readUris("video_uris")
        audioClips = readUris("audio_uris")
        audioVolumes.clear()
        runCatching {
            val values = JSONArray(projectPrefs.getString("audio_volumes", "[]") ?: "[]")
            repeat(audioClips.size) { index -> audioVolumes.add(values.optDouble(index, 0.85).toFloat().coerceIn(0f, 1.5f)) }
        }.onFailure { repeat(audioClips.size) { audioVolumes.add(0.85f) } }
        originalVolume = projectPrefs.getFloat("original_volume", 1f).coerceIn(0f, 1.5f)
        transition = projectPrefs.getString("transition", "Crossfade · 1 second") ?: "Crossfade · 1 second"
        exportHeight = projectPrefs.getInt("export_height", 720).let { if (it in listOf(480, 720, 1080)) it else 720 }
        exportFps = projectPrefs.getInt("export_fps", 30).let { if (it in listOf(30, 60)) it else 30 }
        (videoClips + audioClips).forEach(::keepPermission)
        if (videoClips.isNotEmpty() || audioClips.isNotEmpty()) {
            status = "Restored your saved timeline draft."
        }
    }

    private fun persistDraft() {
        val videos = JSONArray().apply { videoClips.forEach { put(it.toString()) } }
        val audios = JSONArray().apply { audioClips.forEach { put(it.toString()) } }
        val levels = JSONArray().apply { audioVolumes.forEach { put(it.toDouble()) } }
        projectPrefs.edit()
            .putString("video_uris", videos.toString())
            .putString("audio_uris", audios.toString())
            .putString("audio_volumes", levels.toString())
            .putFloat("original_volume", originalVolume)
            .putString("transition", transition)
            .putInt("export_height", exportHeight)
            .putInt("export_fps", exportFps)
            .putLong("last_saved_at", System.currentTimeMillis())
            .apply()
    }

    private fun keepPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreDraft()
        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                var showTransitionMenu by remember { mutableStateOf(false) }
                var confirmClearProject by remember { mutableStateOf(false) }
                val volumeSnapshot = audioVolumes.toList()
                LaunchedEffect(videoClips, audioClips, volumeSnapshot, originalVolume, transition, exportHeight, exportFps) {
                    persistDraft()
                }
                Column(
                    Modifier.fillMaxSize().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("KALIZE STUDIO", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("Multi-track timeline", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Arrange video clips, overlap transitions, layer music and voice tracks, then export a mixed MP4.")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            persistDraft()
                            status = "Project draft saved on this device. Your timeline will be restored when you reopen the editor."
                        }, modifier = Modifier.weight(1f)) { Text("Save project draft") }
                        TextButton(onClick = { confirmClearProject = true }) { Text("Clear project") }
                        Text("AUTO-SAVE ON", modifier = Modifier.padding(top = 13.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { videoPicker.launch(arrayOf("video/*")) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Add video clips") }
                        OutlinedButton(
                            onClick = { audioPicker.launch(arrayOf("audio/*")) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Add audio") }
                    }
                    Card {
                        Column(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Text("TIMELINE TRACKS", fontWeight = FontWeight.Bold)
                            Text("VIDEO 1 · Main sequence", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            if (videoClips.isEmpty()) Text("No video clips yet.", style = MaterialTheme.typography.bodySmall)
                            LazyColumn(
                                Modifier.heightIn(max = 190.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                itemsIndexed(videoClips) { index, uri ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            "${index + 1}. ${uri.lastPathSegment ?: "Video clip"}",
                                            Modifier.weight(1f),
                                            maxLines = 1,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        TextButton(
                                            enabled = index > 0,
                                            onClick = {
                                                videoClips = videoClips.toMutableList().also {
                                                    val item = it.removeAt(index)
                                                    it.add(index - 1, item)
                                                }
                                            }
                                        ) { Text("↑") }
                                        TextButton(
                                            enabled = index < videoClips.lastIndex,
                                            onClick = {
                                                videoClips = videoClips.toMutableList().also {
                                                    val item = it.removeAt(index)
                                                    it.add(index + 1, item)
                                                }
                                            }
                                        ) { Text("↓") }
                                        TextButton(onClick = {
                                            videoClips = videoClips.toMutableList().also { it.removeAt(index) }
                                        }) { Text("×") }
                                    }
                                }
                            }
                            HorizontalDivider()
                            Text("AUDIO TRACKS · Layered mix", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            if (audioClips.isEmpty()) {
                                Text("No added audio tracks. Original clip sound is retained when available.", style = MaterialTheme.typography.bodySmall)
                            }
                            audioClips.forEachIndexed { index, uri ->
                                Column {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Text(
                                            "AUDIO ${index + 1} · ${uri.lastPathSegment ?: "Audio clip"}",
                                            Modifier.weight(1f),
                                            maxLines = 1,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        TextButton(enabled = index > 0, onClick = {
                                            audioClips = audioClips.toMutableList().also {
                                                val moved = it.removeAt(index)
                                                it.add(index - 1, moved)
                                            }
                                            if (index < audioVolumes.size) {
                                                val movedLevel = audioVolumes.removeAt(index)
                                                audioVolumes.add(index - 1, movedLevel)
                                            }
                                        }) { Text("↑") }
                                        TextButton(enabled = index < audioClips.lastIndex, onClick = {
                                            audioClips = audioClips.toMutableList().also {
                                                val moved = it.removeAt(index)
                                                it.add(index + 1, moved)
                                            }
                                            if (index < audioVolumes.size) {
                                                val movedLevel = audioVolumes.removeAt(index)
                                                audioVolumes.add(index + 1, movedLevel)
                                            }
                                        }) { Text("↓") }
                                        TextButton(onClick = {
                                            audioClips = audioClips.toMutableList().also { it.removeAt(index) }
                                            if (index < audioVolumes.size) audioVolumes.removeAt(index)
                                        }) { Text("Remove") }
                                    }
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Slider(
                                            value = audioVolumes.getOrElse(index) { 0.85f },
                                            onValueChange = { if (index < audioVolumes.size) audioVolumes[index] = it },
                                            valueRange = 0f..1.5f,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            "${(audioVolumes.getOrElse(index) { 0.85f } * 100).toInt()}%",
                                            style = MaterialTheme.typography.labelSmall
                                        )
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
                                listOf(
                                    "Crossfade · 0.5 second",
                                    "Crossfade · 1 second",
                                    "Crossfade · 2 seconds",
                                    "Dip to black · 1 second",
                                    "Slide left · 1 second",
                                    "Wipe left · 1 second",
                                    "Smooth left · 1 second",
                                    "Zoom in · 1 second",
                                    "Hard cut"
                                ).forEach { choice ->
                                    DropdownMenuItem(
                                        text = { Text(choice) },
                                        onClick = { transition = choice; showTransitionMenu = false }
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        when {
                            transition.startsWith("Crossfade") ->
                                "Real overlapping transition: the outgoing image fades down while the incoming image fades up. Their audio crossfades too."
                            transition.startsWith("Dip") ->
                                "Overlapping fade through black, with a matching audio crossfade."
                            transition.startsWith("Slide") -> "The incoming clip slides over the outgoing clip during a true overlap."
                            transition.startsWith("Wipe") -> "The incoming clip reveals across the outgoing clip during the overlap."
                            transition.startsWith("Smooth") -> "A smooth directional reveal blends the two clips over the selected duration."
                            transition.startsWith("Zoom") -> "The incoming clip expands into view during the overlap."
                            else -> "Clips join directly. Audio is joined at the same edit point."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("Export settings", fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf(480, 720, 1080).forEach { height ->
                            FilterChip(selected = exportHeight == height, onClick = { exportHeight = height }, label = { Text("${height}p") })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf(30, 60).forEach { rate ->
                            FilterChip(selected = exportFps == rate, onClick = { exportFps = rate }, label = { Text("${rate} FPS") })
                        }
                    }
                    Text("Higher resolution and frame rates take longer and may not be supported by every device encoder.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Audio mixing", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Each added music or voice file has its own level control and is mixed over the video soundtrack. Export runs locally on this device.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        enabled = !exporting && videoClips.isNotEmpty(),
                        onClick = {
                            exporting = true
                            status = "Preparing clips and rendering overlapping transitions…"
                            val videos = videoClips.toList()
                            val audios = audioClips.toList()
                            val levels = audioVolumes.toList()
                            val original = originalVolume
                            val style = transition
                            val height = exportHeight
                            val fps = exportFps
                            scope.launch {
                                val result = runCatching {
                                    exportTimeline(videos, audios, levels, original, style, height, fps)
                                }
                                exporting = false
                                status = result.fold(
                                    { "Export complete. Video saved in Movies/Kalize Recorder/Edits." },
                                    { "Export failed: ${it.message ?: "unsupported media combination"}" }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (exporting) "Rendering timeline…" else "Export mixed timeline") }
                    if (exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (confirmClearProject) {
                        AlertDialog(
                            onDismissRequest = { confirmClearProject = false },
                            title = { Text("Clear timeline project?") },
                            text = { Text("This removes clips and audio references from the saved draft. Original media files on your phone will not be deleted.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    videoClips = emptyList()
                                    audioClips = emptyList()
                                    audioVolumes.clear()
                                    originalVolume = 1f
                                    transition = "Crossfade · 1 second"
                                    exportHeight = 720
                                    exportFps = 30
                                    projectPrefs.edit().clear().apply()
                                    confirmClearProject = false
                                    status = "Timeline project cleared. Original media files were kept."
                                }) { Text("Clear draft") }
                            },
                            dismissButton = { TextButton(onClick = { confirmClearProject = false }) { Text("Cancel") } }
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Back to video editor")
                    }
                }
            }
        }
    }

    private data class ClipInfo(
        val file: File,
        val durationSeconds: Double,
        val hasAudio: Boolean
    )

    private suspend fun exportTimeline(
        videos: List<Uri>,
        audios: List<Uri>,
        levels: List<Float>,
        originalLevel: Float,
        transitionStyle: String,
        outputHeight: Int,
        outputFps: Int
    ): Uri = withContext(Dispatchers.IO) {
        require(videos.isNotEmpty()) { "Add at least one video clip." }

        val videoFiles = videos.mapIndexed { index, uri -> copyToCache(uri, "kalize_video_${index}_", ".mp4") }
        val audioFiles = audios.mapIndexed { index, uri -> copyToCache(uri, "kalize_audio_${index}_", ".audio") }
        val clipInfo = videos.mapIndexed { index, uri ->
            val duration = readDurationSeconds(uri)
            require(duration > 0.1) { "Could not read the duration of video clip ${index + 1}." }
            require(hasVideoTrack(uri)) { "Clip ${index + 1} does not contain a video track." }
            ClipInfo(videoFiles[index], duration, hasAudioTrack(uri))
        }

        val transitionSeconds = when {
            transitionStyle.contains("0.5") -> 0.5
            transitionStyle.contains("2 seconds") -> 2.0
            transitionStyle.startsWith("Hard cut") -> 0.0
            else -> 1.0
        }
        if (transitionSeconds > 0.0) {
            clipInfo.forEachIndexed { index, clip ->
                require(clip.durationSeconds > transitionSeconds) {
                    "Clip ${index + 1} must be longer than the selected ${transitionSeconds}s transition."
                }
            }
        }

        val outputDuration = clipInfo.sumOf { it.durationSeconds } -
            transitionSeconds * (clipInfo.size - 1)
        require(outputDuration > 0.2) { "The selected clips are too short for this transition." }

        val inputPaths = mutableListOf<String>()
        clipInfo.forEach { inputPaths += it.file.absolutePath }
        audioFiles.forEach { inputPaths += it.absolutePath }

        val outputFile = File(cacheDir, "kalize_crossfade_${System.currentTimeMillis()}.mp4")
        val outputWidth = ((outputHeight * 16 / 9) / 2 * 2).coerceAtLeast(2)
        val videoBitrate = when (outputHeight) { 480 -> "2M"; 1080 -> "10M"; else -> "5M" }
        val command = buildString {
            append("-y ")
            inputPaths.forEach { append("-i ${shellQuote(it)} ") }
            append("-filter_complex_threads 1 ")
            append("-filter_complex ${shellQuote(buildFilterGraph(clipInfo, audioFiles, levels, originalLevel, transitionStyle, transitionSeconds, outputDuration, outputWidth, outputHeight, outputFps))} ")
            append("-map [vout] -map [aout] -t ${decimal(outputDuration)} ")
            append("-c:v h264_mediacodec -b:v $videoBitrate -pix_fmt yuv420p -r $outputFps -c:a aac -b:a 192k -movflags +faststart ")
            append("${shellQuote(outputFile.absolutePath)}")
        }

        var session = FFmpegKit.execute(command)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            val hardwareError = session.allLogsAsString
            val fallbackCommand = command
                .replace("-c:v h264_mediacodec -b:v $videoBitrate", "-c:v mpeg4 -q:v 3")
            session = FFmpegKit.execute(fallbackCommand)
            if (!ReturnCode.isSuccess(session.returnCode)) {
                throw IllegalStateException(
                    (session.failStackTrace ?: session.allLogsAsString ?: hardwareError)
                        .takeLast(1800)
                        .ifBlank { "The video encoder could not render this timeline." }
                )
            }
        }

        require(outputFile.exists() && outputFile.length() > 0L) {
            "FFmpeg finished without creating the output video."
        }

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Kalize_Timeline_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Kalize Recorder/Edits")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val outputUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create the output video.")
        try {
            contentResolver.openOutputStream(outputUri)?.use { sink ->
                outputFile.inputStream().use { source -> source.copyTo(sink) }
            } ?: throw IllegalStateException("Could not open the output video.")
            contentResolver.update(
                outputUri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null
            )
            outputFile.delete()
            videoFiles.forEach { it.delete() }
            audioFiles.forEach { it.delete() }
            outputUri
        } catch (error: Exception) {
            contentResolver.delete(outputUri, null, null)
            throw error
        }
    }

    private fun buildFilterGraph(
        clips: List<ClipInfo>,
        audioFiles: List<File>,
        levels: List<Float>,
        originalLevel: Float,
        transitionStyle: String,
        transitionSeconds: Double,
        outputDuration: Double,
        outputWidth: Int,
        outputHeight: Int,
        outputFps: Int
    ): String {
        val filters = mutableListOf<String>()
        clips.forEachIndexed { index, clip ->
            filters += "[$index:v:0]setpts=PTS-STARTPTS,scale=${outputWidth}:${outputHeight}:force_original_aspect_ratio=decrease," +
                "pad=${outputWidth}:${outputHeight}:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,fps=${outputFps},format=yuv420p,settb=AVTB[v$index]"
        }

        if (clips.size == 1) {
            filters += "[v0]null[vout]"
        } else if (transitionSeconds == 0.0) {
            val inputs = clips.indices.joinToString("") { "[v$it]" }
            filters += "${inputs}concat=n=${clips.size}:v=1:a=0[vout]"
        } else {
            var accumulated = clips.first().durationSeconds
            var previous = "v0"
            for (index in 1 until clips.size) {
                val outputLabel = if (index == clips.lastIndex) "vout" else "vx$index"
                val transitionName = when {
                    transitionStyle.startsWith("Dip") -> "fadeblack"
                    transitionStyle.startsWith("Slide") -> "slideleft"
                    transitionStyle.startsWith("Wipe") -> "wipeleft"
                    transitionStyle.startsWith("Smooth") -> "smoothleft"
                    transitionStyle.startsWith("Zoom") -> "zoomin"
                    else -> "fade"
                }
                val offset = (accumulated - transitionSeconds).coerceAtLeast(0.0)
                filters += "[$previous][v$index]xfade=transition=$transitionName:duration=${decimal(transitionSeconds)}:" +
                    "offset=${decimal(offset)}[$outputLabel]"
                accumulated += clips[index].durationSeconds - transitionSeconds
                previous = outputLabel
            }
        }

        val audioLabels = mutableListOf<String>()
        clips.forEachIndexed { index, clip ->
            val label = "orig$index"
            if (clip.hasAudio) {
                filters += "[$index:a:0]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo," +
                    "volume=${decimal(originalLevel.toDouble())},asetpts=PTS-STARTPTS[$label]"
            } else {
                filters += "anullsrc=r=48000:cl=stereo,atrim=duration=${decimal(clip.durationSeconds)}," +
                    "asetpts=PTS-STARTPTS[$label]"
            }
            audioLabels += label
        }

        var originalLabel = audioLabels.first()
        if (audioLabels.size > 1) {
            if (transitionSeconds == 0.0) {
                val inputs = audioLabels.joinToString("") { "[$it]" }
                filters += "${inputs}concat=n=${audioLabels.size}:v=0:a=1[origmix]"
            } else {
                var prior = audioLabels.first()
                for (index in 1 until audioLabels.size) {
                    val next = if (index == audioLabels.lastIndex) "origmix" else "ax$index"
                    filters += "[$prior][${audioLabels[index]}]acrossfade=d=${decimal(transitionSeconds)}:" +
                        "c1=tri:c2=tri[$next]"
                    prior = next
                }
            }
            originalLabel = "origmix"
        }

        val mixLabels = mutableListOf(originalLabel)
        audioFiles.forEachIndexed { index, _ ->
            val inputIndex = clips.size + index
            val label = "music$index"
            val duration = readDurationSecondsFromFile(audioFiles[index]).coerceAtLeast(0.2)
            val fade = minOf(1.0, duration / 3.0).coerceAtLeast(0.05)
            val fadeOutStart = (duration - fade).coerceAtLeast(0.0)
            filters += "[$inputIndex:a:0]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo," +
                "volume=${decimal(levels.getOrElse(index) { 0.85f }.toDouble())}," +
                "afade=t=in:st=0:d=${decimal(fade)},afade=t=out:st=${decimal(fadeOutStart)}:d=${decimal(fade)}," +
                "apad,atrim=duration=${decimal(outputDuration)}[$label]"
            mixLabels += label
        }

        if (mixLabels.size == 1) {
            filters += "[$originalLabel]apad,atrim=duration=${decimal(outputDuration)}[aout]"
        } else {
            val inputs = mixLabels.joinToString("") { "[$it]" }
            filters += "${inputs}amix=inputs=${mixLabels.size}:duration=first:dropout_transition=0:normalize=0," +
                "atrim=duration=${decimal(outputDuration)}[aout]"
        }
        return filters.joinToString(";")
    }

    private fun copyToCache(uri: Uri, prefix: String, suffix: String): File {
        val target = File.createTempFile(prefix, suffix, cacheDir)
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not read selected media: $uri")
        return target
    }

    private fun readDurationSeconds(uri: Uri): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0) / 1000.0
        } finally {
            retriever.release()
        }
    }

    private fun readDurationSecondsFromFile(file: File): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0) / 1000.0
        } finally {
            retriever.release()
        }
    }

    private fun hasVideoTrack(uri: Uri): Boolean = hasTrack(uri, "video/")
    private fun hasAudioTrack(uri: Uri): Boolean = hasTrack(uri, "audio/")

    private fun hasTrack(uri: Uri, prefix: String): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(this, uri, null)
            (0 until extractor.trackCount).any { index ->
                extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)?.startsWith(prefix) == true
            }
        } finally {
            extractor.release()
        }
    }

    private fun decimal(value: Double): String = String.format(Locale.US, "%.3f", value.coerceAtLeast(0.0))

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

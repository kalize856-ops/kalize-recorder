package com.kalize.recorder

import android.content.ContentValues
import android.content.Intent
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.media.MediaMetadataRetriever
import android.os.Bundle
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.AdError
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

class VideoEditorActivity : ComponentActivity() {
    private var selectedUri by mutableStateOf<Uri?>(null)
    private var mergeUris by mutableStateOf<List<Uri>>(emptyList())
    private var durationMs by mutableLongStateOf(0L)
    private var exporting by mutableStateOf(false)
    private var message by mutableStateOf("Choose a video to trim or select multiple clips to merge.")
    private var rewardedAd: RewardedAd? = null
    private var rewardAdLoading = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            persistReadPermission(uri)
            selectedUri = uri
            durationMs = readDuration(uri)
            message = if (durationMs > 0) "Video ready to trim. Export creates a separate copy." else "Could not read this video's duration."
        }
    }

    private val mergePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        mergeUris = uris
        uris.forEach(::persistReadPermission)
        message = if (uris.size >= 2) "${uris.size} clips selected. They will be joined in the order selected." else "Select at least two clips to merge."
    }

    private fun persistReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Temporary permission remains available for the current activity session.
        }
    }

    private fun readDuration(uri: Uri): Long = try {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    } catch (_: Exception) { 0L }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MobileAds.initialize(this) { loadCompressionRewardAd() }
        setContent {
            MaterialTheme {
                var range by remember { mutableStateOf(0f..1f) }
                var keepAudio by remember { mutableStateOf(true) }
                var rotation by remember { mutableIntStateOf(0) }
                var speed by remember { mutableFloatStateOf(1f) }
                var compressionPreset by remember { mutableStateOf(CompressionPreset.BALANCED) }
                val scope = rememberCoroutineScope()
                Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("KALIZE STUDIO", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("Video editor", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Trim a clip or combine compatible recordings. Every export is saved as a new file; your originals stay untouched.")
                    OutlinedButton(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (selectedUri == null) "Choose one video to trim" else "Choose another video to trim")
                    }
                    OutlinedButton(
                        onClick = { startActivity(Intent(this@VideoEditorActivity, AdvancedVideoToolsActivity::class.java)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Advanced effects · crop · resize") }
                    OutlinedButton(onClick = { startActivity(Intent(this@VideoEditorActivity, MultiTrackTimelineActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) { Text("Multi-track timeline · transitions · audio mixer") }
                    OutlinedButton(onClick = { mergePicker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (mergeUris.isEmpty()) "Select multiple clips to merge" else "Change clips (${mergeUris.size} selected)")
                    }
                    if (mergeUris.isNotEmpty()) {
                        Card {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("MERGE ORDER", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                                mergeUris.forEachIndexed { index, uri ->
                                    Text("${index + 1}. ${uri.lastPathSegment ?: "Selected video"}", maxLines = 1, style = MaterialTheme.typography.bodySmall)
                                }
                                Text("Only clips with compatible video/audio tracks can be combined without re-encoding.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(
                                    enabled = !exporting && mergeUris.size >= 2,
                                    onClick = {
                                        val clips = mergeUris.toList()
                                        showCompressionRewardAd {
                                            exporting = true
                                            message = "Reward earned. Combining clips…"
                                            scope.launch {
                                                val result = withContext(Dispatchers.IO) {
                                                    runCatching { BasicVideoMerger.export(this@VideoEditorActivity, clips) }
                                                }
                                                exporting = false
                                                message = result.fold(
                                                    { "Merge complete. The combined video is in Movies/Kalize Recorder/Edits." },
                                                    { "Merge failed: ${it.message ?: "unsupported or incompatible video formats"}" }
                                                )
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Merge selected clips") }
                            }
                        }
                    }
                    if (selectedUri != null && durationMs > 0) {
                        Card {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Text("VIDEO COMPRESS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                Text("Reduce file size by re-encoding at a lower resolution and bitrate. Your original stays unchanged.")
                                CompressionPreset.entries.forEach { preset ->
                                    FilterChip(selected = compressionPreset == preset, onClick = { compressionPreset = preset }, label = { Text("${preset.title} · ${preset.detail}") })
                                }
                                val estimatedBytes = ((compressionPreset.bitrate + 128_000L) * durationMs / 8_000L)
                                val estimatedMiB = estimatedBytes.toDouble() / (1024.0 * 1024.0)
                                Text(
                                    "Estimated output: about ${"%.1f".format(java.util.Locale.US, estimatedMiB)} MB. Actual size varies with motion, audio and encoder support; output resolution never exceeds the source video.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Watch one short rewarded ad to unlock this compression export. Basic recording and trimming remain free. Merging and compression exports require a rewarded ad.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(enabled = !exporting && !rewardAdLoading, onClick = {
                                    val source = selectedUri ?: return@Button
                                    val preset = compressionPreset
                                    showCompressionRewardAd {
                                        exporting = true
                                        message = "Reward earned. Compressing your video…"
                                        scope.launch {
                                            val result = runCatching { VideoCompressor.compress(this@VideoEditorActivity, source, preset) }
                                            exporting = false
                                            message = result.fold({ "Compression complete. The smaller copy is saved in Movies/Kalize Recorder/Compressed." }, { "Compression failed: ${it.message ?: "this video format may not be supported on this device"}" })
                                        }
                                    }
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (rewardAdLoading) "Loading reward ad…" else "Watch ad to compress video")
                                }
                            }
                        }
                        OutlinedButton(
                            enabled = !exporting,
                            onClick = {
                                val source = selectedUri ?: return@OutlinedButton
                                exporting = true
                                message = "Extracting the original audio…"
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { AudioTrackExtractor.export(this@VideoEditorActivity, source) }
                                    }
                                    exporting = false
                                    message = result.fold(
                                        { "Audio extracted. The M4A file is saved in Music/Kalize Recorder/Extracted Audio." },
                                        { "Audio extraction failed: " + (it.message ?: "this video may not contain a supported audio track") }
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Extract audio from video") }
                        OutlinedButton(
                            enabled = !exporting && durationMs >= 2_000L,
                            onClick = {
                                val source = selectedUri ?: return@OutlinedButton
                                val midpoint = durationMs / 2L
                                val retainAudio = keepAudio
                                exporting = true
                                message = "Splitting video into two clips…"
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val first = BasicVideoTrimmer.export(
                                                this@VideoEditorActivity, source, 0L, midpoint,
                                                retainAudio, 0, 1f
                                            )
                                            val second = BasicVideoTrimmer.export(
                                                this@VideoEditorActivity, source, midpoint, durationMs,
                                                retainAudio, 0, 1f
                                            )
                                            first to second
                                        }
                                    }
                                    exporting = false
                                    message = result.fold(
                                        { "Split complete. Both clips are saved in Movies/Kalize Recorder/Edits." },
                                        { "Split failed: ${it.message ?: "unsupported video format"}" }
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Split video into two clips") }
                        HorizontalDivider()
                        Text("Trim one video", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("Video length: ${formatTime(durationMs)}")
                        Text("Start: ${formatTime((range.start * durationMs).toLong())}")
                        Text("End: ${formatTime((range.endInclusive * durationMs).toLong())}")
                        RangeSlider(value = range, onValueChange = { range = it }, valueRange = 0f..1f, steps = 0)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("0:00")
                            Text(formatTime(durationMs))
                        }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = keepAudio, onCheckedChange = { keepAudio = it })
                            Text("Keep original audio (turn off to mute)")
                        }
                        Text("Rotate output", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0, 90, 180, 270).forEach { angle ->
                                FilterChip(selected = rotation == angle, onClick = { rotation = angle }, label = { Text(if (angle == 0) "0°" else "$angle°") })
                            }
                        }
                        Text("Playback speed", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0.5f, 1f, 1.5f, 2f).forEach { factor ->
                                FilterChip(selected = speed == factor, onClick = { speed = factor }, label = { Text("${factor}x") })
                            }
                        }
                        Text("Speed changes affect both picture and sound; faster or slower audio may change pitch.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(
                            enabled = !exporting && (range.endInclusive - range.start) > 0.02f,
                            onClick = {
                                val uri = selectedUri ?: return@Button
                                val start = (range.start * durationMs).toLong()
                                val end = (range.endInclusive * durationMs).toLong()
                                exporting = true
                                message = "Exporting trimmed video…"
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { BasicVideoTrimmer.export(this@VideoEditorActivity, uri, start, end, keepAudio, rotation, speed) }
                                    }
                                    exporting = false
                                    message = result.fold(
                                        { "Edit export complete. The rotated/speed-adjusted copy is in Movies/Kalize Recorder/Edits." },
                                        { "Trim export failed: ${it.message ?: "unsupported video format"}" }
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Export trimmed video") }
                    }
                    if (exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Close editor") }
                }
            }
        }
    }


    private fun loadCompressionRewardAd() {
        if (rewardAdLoading || rewardedAd != null) return
        rewardAdLoading = true
        RewardedAd.load(
            this,
            "ca-app-pub-3940256099942544/5224354917",
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    rewardAdLoading = false
                    message = "Reward ad ready. Watch it to unlock this export."
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    rewardAdLoading = false
                    message = "Reward ad is unavailable right now. Please try again when a connection is available."
                }
            }
        )
    }

    private fun showCompressionRewardAd(onRewardEarned: () -> Unit) {
        val ad = rewardedAd
        if (ad == null) {
            message = "The reward ad is still loading or unavailable. Please try again shortly."
            loadCompressionRewardAd()
            return
        }

        rewardedAd = null
        var earnedReward = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                if (!earnedReward) {
                    message = "The ad was closed before the reward was earned. Watch the ad to unlock this export."
                }
                loadCompressionRewardAd()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                message = "The reward ad could not be shown. Please try again."
                loadCompressionRewardAd()
            }
        }
        ad.show(this) {
            earnedReward = true
            onRewardEarned()
        }
    }

    private fun formatTime(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }
}

private fun createEditOutput(context: Context, prefix: String): Uri {
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "${prefix}_${System.currentTimeMillis()}.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Kalize Recorder/Edits")
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("Could not create the edited video.")
}

private fun publishEdit(context: Context, uri: Uri) {
    context.contentResolver.update(uri, ContentValues().apply {
        put(MediaStore.Video.Media.IS_PENDING, 0)
    }, null, null)
}

private object BasicVideoTrimmer {
    fun export(context: Context, source: Uri, startMs: Long, endMs: Long, keepAudio: Boolean, rotationDegrees: Int, speed: Float): Uri {
        require(rotationDegrees in listOf(0, 90, 180, 270)) { "Choose a supported rotation." }
        require(speed in listOf(0.5f, 1f, 1.5f, 2f)) { "Choose a supported playback speed." }
        require(endMs > startMs) { "Choose a valid trim range." }
        val outputUri = createEditOutput(context, "Kalize_Trim")
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
                        val active = muxer ?: MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
                        trackMap[i] = active.addTrack(format)
                        if (isVideo) videoTrackFound = true
                    }
                    require(videoTrackFound) { "The selected file has no supported video track." }
                    val active = muxer ?: throw IllegalStateException("No export tracks found.")
                    if (rotationDegrees != 0) active.setOrientationHint(rotationDegrees)
                    active.start()
                    muxerStarted = true
                    trackMap.keys.forEach { extractor.selectTrack(it) }
                    val startUs = startMs * 1000
                    val endUs = endMs * 1000
                    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    val baseUs = extractor.sampleTime.coerceAtLeast(0L)
                    val buffer = ByteBuffer.allocate(8 * 1024 * 1024)
                    val info = MediaCodec.BufferInfo()
                    while (true) {
                        val inputTrack = extractor.sampleTrackIndex
                        if (inputTrack < 0) break
                        val sampleTime = extractor.sampleTime
                        if (sampleTime > endUs) break
                        if (trackMap.containsKey(inputTrack)) {
                            buffer.clear()
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) break
                            val relativeTimeUs = (sampleTime - baseUs).coerceAtLeast(0L)
                            val adjustedTimeUs = (relativeTimeUs / speed).toLong()
                            info.set(0, size, adjustedTimeUs, extractor.sampleFlags)
                            active.writeSampleData(trackMap.getValue(inputTrack), buffer, info)
                        }
                        if (!extractor.advance()) break
                    }
                } finally {
                    extractor.release()
                }
                if (muxerStarted) {
                    muxer?.stop()
                    muxerStarted = false
                }
                muxer?.release()
                muxer = null
            }
            publishEdit(context, outputUri)
            return outputUri
        } catch (error: Exception) {
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) { }
            try { muxer?.release() } catch (_: Exception) { }
            context.contentResolver.delete(outputUri, null, null)
            throw error
        }
    }
}

private object BasicVideoMerger {
    private data class TrackSpec(val sourceIndex: Int, val outputIndex: Int, val format: MediaFormat)

    fun export(context: Context, sources: List<Uri>): Uri {
        require(sources.size >= 2) { "Select at least two clips." }
        val outputUri = createEditOutput(context, "Kalize_Merged")
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            context.contentResolver.openFileDescriptor(outputUri, "w")!!.use { descriptor ->
                val first = MediaExtractor()
                val specs = mutableListOf<TrackSpec>()
                try {
                    first.setDataSource(context, sources.first(), null)
                    val active = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    muxer = active
                    var hasVideo = false
                    for (index in 0 until first.trackCount) {
                        val format = first.getTrackFormat(index)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                        val outIndex = active.addTrack(format)
                        specs += TrackSpec(index, outIndex, format)
                        if (mime.startsWith("video/")) hasVideo = true
                    }
                    require(hasVideo) { "The first clip has no supported video track." }
                    active.start()
                    muxerStarted = true
                } finally {
                    first.release()
                }

                val active = muxer ?: throw IllegalStateException("Could not start the video merger.")
                val buffer = ByteBuffer.allocate(8 * 1024 * 1024)
                val info = MediaCodec.BufferInfo()
                var clipOffsetUs = 0L
                sources.forEachIndexed { clipIndex, uri ->
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(context, uri, null)
                        val mapping = mutableMapOf<Int, Int>()
                        val usedOutputs = mutableSetOf<Int>()
                        for (sourceTrack in 0 until extractor.trackCount) {
                            val format = extractor.getTrackFormat(sourceTrack)
                            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                            if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                            val match = specs.firstOrNull { spec ->
                                spec.outputIndex !in usedOutputs && compatible(spec.format, format)
                            } ?: throw IllegalArgumentException("Clip ${clipIndex + 1} has tracks that do not match the first clip.")
                            mapping[sourceTrack] = match.outputIndex
                            usedOutputs += match.outputIndex
                            extractor.selectTrack(sourceTrack)
                        }
                        require(mapping.values.containsAll(specs.map { it.outputIndex })) {
                            "Clip ${clipIndex + 1} is missing a matching audio or video track."
                        }
                        while (true) {
                            val sourceTrack = extractor.sampleTrackIndex
                            if (sourceTrack < 0) break
                            val sampleTime = extractor.sampleTime
                            val outputTrack = mapping[sourceTrack]
                            if (outputTrack != null) {
                                buffer.clear()
                                val size = extractor.readSampleData(buffer, 0)
                                if (size < 0) break
                                info.set(0, size, clipOffsetUs + sampleTime.coerceAtLeast(0L), extractor.sampleFlags)
                                active.writeSampleData(outputTrack, buffer, info)
                            }
                            if (!extractor.advance()) break
                        }
                    } finally {
                        extractor.release()
                    }
                    clipOffsetUs += readDurationUs(context, uri)
                }
                if (muxerStarted) {
                    active.stop()
                    muxerStarted = false
                }
                active.release()
                muxer = null
            }
            publishEdit(context, outputUri)
            return outputUri
        } catch (error: Exception) {
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) { }
            try { muxer?.release() } catch (_: Exception) { }
            context.contentResolver.delete(outputUri, null, null)
            throw error
        }
    }

    private fun compatible(a: MediaFormat, b: MediaFormat): Boolean {
        if (a.getString(MediaFormat.KEY_MIME) != b.getString(MediaFormat.KEY_MIME)) return false
        val keys = listOf(MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT, MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT)
        return keys.all { key ->
            val hasA = a.containsKey(key)
            val hasB = b.containsKey(key)
            hasA == hasB && (!hasA || a.getInteger(key) == b.getInteger(key))
        }
    }

    private fun readDurationUs(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
        } finally {
            retriever.release()
        }
    }
}

private object AudioTrackExtractor {
    fun export(context: Context, source: Uri): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "Kalize_Audio_" + System.currentTimeMillis() + ".m4a")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Kalize Recorder/Extracted Audio")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val outputUri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create the extracted audio file.")
        var muxer: MediaMuxer? = null
        var started = false
        try {
            context.contentResolver.openFileDescriptor(outputUri, "w")!!.use { descriptor ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(context, source, null)
                    val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
                        extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    } ?: throw IllegalArgumentException("This video does not contain a supported audio track.")
                    val format = extractor.getTrackFormat(audioTrack)
                    val active = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    muxer = active
                    val outputTrack = active.addTrack(format)
                    active.start()
                    started = true
                    extractor.selectTrack(audioTrack)
                    val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
                    val info = MediaCodec.BufferInfo()
                    while (true) {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        info.set(0, size, extractor.sampleTime.coerceAtLeast(0L), extractor.sampleFlags)
                        active.writeSampleData(outputTrack, buffer, info)
                        if (!extractor.advance()) break
                    }
                } finally {
                    extractor.release()
                }
                if (started) { muxer?.stop(); started = false }
                muxer?.release()
                muxer = null
            }
            context.contentResolver.update(outputUri, ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }, null, null)
            return outputUri
        } catch (error: Exception) {
            try { if (started) muxer?.stop() } catch (_: Exception) { }
            try { muxer?.release() } catch (_: Exception) { }
            context.contentResolver.delete(outputUri, null, null)
            throw error
        }
    }
}
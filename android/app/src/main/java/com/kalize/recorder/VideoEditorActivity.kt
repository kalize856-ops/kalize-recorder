package com.kalize.recorder

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class VideoEditorActivity : Activity() {

    companion object {
        private const val PICK_VIDEO_REQUEST = 4101
        private const val MAX_SAMPLE_BUFFER = 64 * 1024 * 1024
    }

    private val background = Color.rgb(8, 11, 22)
    private val card = Color.rgb(20, 25, 43)
    private val accent = Color.rgb(154, 105, 255)
    private val white = Color.WHITE
    private val muted = Color.rgb(165, 174, 199)

    private var selectedVideo: Uri? = null
    private var lastExportedVideo: Uri? = null

    private lateinit var selectedLabel: TextView
    private lateinit var durationLabel: TextView
    private lateinit var startInput: EditText
    private lateinit var endInput: EditText
    private lateinit var muteAudio: CheckBox
    private lateinit var exportButton: Button
    private lateinit var previewButton: Button
    private lateinit var exportStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var exportNameInput: EditText
    private lateinit var trimSummary: TextView

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
        }

        val scroll = ScrollView(this).apply {
            setPadding(dp(18), dp(12), dp(18), dp(24))
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(label("ƘALIZE  /  STUDIO", 13f, accent, true))
        content.addView(label("VIDEO EDITOR", 27f, white, true))
        content.addView(label(
            "Trim your clips, remove audio and export your videos.",
            13f, muted
        ))

        val sourceCard = createCard()
        sourceCard.addView(section("01  /  YOUR VIDEO"))

        selectedLabel = label("No video selected", 15f, white, true)
        sourceCard.addView(selectedLabel)

        durationLabel = label("Choose a video to begin.", 12f, muted)
        sourceCard.addView(durationLabel)

        sourceCard.addView(button("Choose video", true) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, PICK_VIDEO_REQUEST)
        })

        previewButton = button("Preview selected video", false) {
            selectedVideo?.let { openPreview(it) }
        }.apply {
            isEnabled = false
            alpha = 0.5f
        }
        sourceCard.addView(previewButton)
        content.addView(sourceCard)

        val trimCard = createCard()
        trimCard.addView(section("02  /  TRIM YOUR CLIP"))
        trimCard.addView(label(
            "Enter the start and end times in seconds.",
            12f, muted
        ))

        val presets = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        listOf(15, 30, 60).forEach { seconds ->
            presets.addView(
                button("$seconds sec", false) {
                    applyPreset(seconds)
                },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginEnd = dp(5)
                }
            )
        }
        trimCard.addView(presets)

        trimCard.addView(section("START TIME (SECONDS)"))
        startInput = timeInput("0")
        trimCard.addView(startInput)

        trimCard.addView(section("END TIME (SECONDS)"))
        endInput = timeInput("")
        trimCard.addView(endInput)

        trimSummary = label(
            "Choose a video to see the trim range.",
            12f, Color.rgb(190, 174, 255)
        )
        trimCard.addView(trimSummary)

        trimCard.addView(button("Use full clip duration", false) {
            val duration = durationLabel.tag as? Long ?: 0L
            if (duration > 0L) {
                startInput.setText("0")
                endInput.setText(
                    String.format(Locale.US, "%.2f", duration / 1000.0)
                )
            } else {
                toast("Choose a video first.")
            }
        })

        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?, start: Int, count: Int, after: Int
            ) {}

            override fun onTextChanged(
                s: CharSequence?, start: Int, before: Int, count: Int
            ) {
                updateTrimSummary()
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        startInput.addTextChangedListener(watcher)
        endInput.addTextChangedListener(watcher)
        content.addView(trimCard)

        val audioCard = createCard()
        audioCard.addView(section("03  /  AUDIO"))

        muteAudio = CheckBox(this).apply {
            text = "Mute original audio"
            textSize = 14f
            setTextColor(white)
            buttonTintList = ColorStateList.valueOf(accent)
        }
        audioCard.addView(muteAudio)
        content.addView(audioCard)

        val exportCard = createCard()
        exportCard.addView(section("04  /  EXPORT"))

        exportCard.addView(label(
            "Your edited MP4 will be saved in Movies/Kalize Recorder.",
            12f, muted
        ))

        exportCard.addView(section("FILE NAME"))
        exportNameInput = EditText(this).apply {
            setText("Kalize_Edit")
            hint = "Video file name"
            setTextColor(white)
            setHintTextColor(muted)
            textSize = 14f
            isSingleLine = true
            backgroundTintList = ColorStateList.valueOf(accent)
        }
        exportCard.addView(exportNameInput)

        exportStatus = label("Ready when you are.", 12f, muted)
        exportCard.addView(exportStatus)

        progressBar = ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(accent)
        }
        exportCard.addView(progressBar)

        exportButton = button("Export edited video", true) {
            exportVideo()
        }.apply {
            isEnabled = false
            alpha = 0.5f
        }
        exportCard.addView(exportButton)

        exportCard.addView(button("Share last exported video", false) {
            lastExportedVideo?.let {
                shareVideo(it)
            } ?: toast("Export a video first.")
        })

        content.addView(exportCard)

        content.addView(button("Open saved video library", false) {
            startActivity(Intent(this, SavedVideosActivity::class.java))
        })

        content.addView(button("Back to recorder", false) {
            finish()
        })

        content.addView(label(
            "ƘALIZE RECORDER  •  CREATOR STUDIO",
            10f, muted
        ))

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        setContentView(root)
    }

    private fun createCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(card)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(15)
            }
        }
    }

    private fun label(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(7), 0, dp(7))
        }
    }

    private fun section(value: String): TextView {
        return label(value, 11f, accent, true)
    }

    private fun button(
        value: String,
        primary: Boolean,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = value
            textSize = 13f
            isAllCaps = false
            setTextColor(white)
            backgroundTintList = ColorStateList.valueOf(
                if (primary) accent else Color.rgb(42, 47, 68)
            )
            minHeight = dp(46)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(7)
            }
        }
    }

    private fun timeInput(initial: String): EditText {
        return EditText(this).apply {
            setText(initial)
            hint = "Time in seconds"
            textSize = 14f
            isSingleLine = true
            inputType =
                android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(white)
            setHintTextColor(muted)
            backgroundTintList = ColorStateList.valueOf(accent)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun updateTrimSummary() {
        val duration = durationLabel.tag as? Long ?: 0L
        val start = startInput.text.toString().toDoubleOrNull()
        val end = endInput.text.toString().toDoubleOrNull()

        trimSummary.text = when {
            selectedVideo == null -> "Choose a video first."
            start == null || end == null -> "Enter both trim times."
            start < 0 || end <= start -> "Check your start and end times."
            end * 1000.0 > duration + 100 ->
                "End time exceeds the video duration."
            else -> {
                val seconds = end - start
                "Selected clip: ${String.format(Locale.US, "%.2f", seconds)} seconds"
            }
        }
    }

    private fun applyPreset(seconds: Int) {
        val duration = durationLabel.tag as? Long ?: 0L
        if (duration <= 0L) {
            toast("Choose a video first.")
            return
        }

        val selected = minOf(seconds * 1000L, duration)
        startInput.setText("0")
        endInput.setText(
            String.format(Locale.US, "%.2f", selected / 1000.0)
        )
    }

    @Deprecated("Uses Android activity result callback")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != PICK_VIDEO_REQUEST || resultCode != RESULT_OK) {
            return
        }

        val uri = data?.data ?: return
        selectedVideo = uri

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}

        selectedLabel.text = "Reading selected video..."
        durationLabel.text = "Loading video information..."
        exportButton.isEnabled = false
        previewButton.isEnabled = true
        previewButton.alpha = 1f

        executor.execute {
            var duration = 0L
            var width = 0
            var height = 0

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                duration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L

                width = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                )?.toIntOrNull() ?: 0

                height = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                )?.toIntOrNull() ?: 0
            } catch (_: Exception) {
            } finally {
                retriever.release()
            }

            runOnUiThread {
                if (selectedVideo == uri) {
                    durationLabel.tag = duration
                    durationLabel.text =
                        "Duration: ${formatDuration(duration)} • ${width}×${height}"
                    selectedLabel.text = "Video ready to edit"

                    if (duration > 0L) {
                        endInput.setText(
                            String.format(
                                Locale.US,
                                "%.2f",
                                duration / 1000.0
                            )
                        )
                    }

                    exportButton.isEnabled = duration > 0L
                    exportButton.alpha = if (duration > 0L) 1f else 0.5f
                    updateTrimSummary()
                }
            }
        }
    }

    private fun openPreview(uri: Uri) {
        try {
            startActivity(
                Intent(this, VideoPreviewActivity::class.java).apply {
                    putExtra(
                        VideoPreviewActivity.EXTRA_VIDEO_URI,
                        uri.toString()
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        } catch (_: Exception) {
            toast("Could not open video preview.")
        }
    }

    private fun shareVideo(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share video"))
        } catch (_: Exception) {
            toast("Could not share this video.")
        }
    }

    private fun exportVideo() {
        val uri = selectedVideo ?: return
        val start = startInput.text.toString().toDoubleOrNull()
        val end = endInput.text.toString().toDoubleOrNull()
        val duration = durationLabel.tag as? Long ?: 0L

        if (start == null || end == null ||
            start < 0 || end <= start ||
            end * 1000.0 > duration + 100
        ) {
            toast("Enter a valid trim range.")
            return
        }

        val startUs = (start * 1_000_000).toLong()
        val endUs = (end * 1_000_000).toLong()
        val mute = muteAudio.isChecked
        val fileName = exportNameInput.text.toString()

        exportButton.isEnabled = false
        exportButton.text = "Exporting..."
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        exportStatus.text = "Preparing export..."

        executor.execute {
            var temp: File? = null

            try {
                temp = File.createTempFile(
                    "kalize_export_", ".mp4", cacheDir
                )

                trimVideo(uri, temp, startUs, endUs, mute) { value ->
                    runOnUiThread {
                        progressBar.progress = value
                        exportStatus.text = "Exporting... $value%"
                    }
                }

                runOnUiThread {
                    exportStatus.text = "Saving video..."
                    progressBar.progress = 95
                }

                val saved = saveExport(temp, fileName)

                runOnUiThread {
                    if (saved != null) {
                        lastExportedVideo = saved
                        progressBar.progress = 100
                        exportStatus.text = "Export complete!"
                        toast("Your edited video has been saved.")
                    } else {
                        exportStatus.text = "Could not save exported video."
                    }
                    resetExportButton()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    exportStatus.text = "Export failed."
                    toast("Export failed: ${e.message ?: "Unsupported video"}")
                    resetExportButton()
                }
            } finally {
                temp?.delete()
            }
        }
    }

    private fun resetExportButton() {
        exportButton.isEnabled = true
        exportButton.alpha = 1f
        exportButton.text = "Export edited video"
    }

    private fun trimVideo(
        inputUri: Uri,
        outputFile: File,
        requestedStartUs: Long,
        endUs: Long,
        mute: Boolean,
        onProgress: (Int) -> Unit
    ) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            extractor.setDataSource(this, inputUri, null)

            var videoTrack = -1
            val selectedTracks = mutableListOf<Int>()

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                if (mime.startsWith("video/")) {
                    if (videoTrack == -1) videoTrack = i
                    selectedTracks.add(i)
                } else if (mime.startsWith("audio/") && !mute) {
                    selectedTracks.add(i)
                }
            }

            if (videoTrack == -1) {
                throw IllegalArgumentException("No video track found.")
            }

            // Find a video keyframe at or after the requested start.
            extractor.selectTrack(videoTrack)
            extractor.seekTo(
                requestedStartUs,
                MediaExtractor.SEEK_TO_NEXT_SYNC
            )

            var actualStartUs = -1L

            while (true) {
                val time = extractor.sampleTime
                if (time < 0 || time >= endUs) break

                if (extractor.sampleTrackIndex == videoTrack) {
                    val flags = extractor.sampleFlags
                    if ((flags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        actualStartUs = time
                        break
                    }
                }

                if (!extractor.advance()) break
            }

            extractor.unselectTrack(videoTrack)

            if (actualStartUs < 0L) {
                throw IllegalArgumentException(
                    "No video keyframe was found in the selected range. Try a longer clip."
                )
            }

            if (actualStartUs >= endUs) {
                throw IllegalArgumentException(
                    "The selected range is too short for a usable video keyframe."
                )
            }

            muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            // Preserve the source video's rotation metadata.
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, inputUri)
                val rotation = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                )?.toIntOrNull() ?: 0

                if (rotation == 90 || rotation == 180 || rotation == 270) {
                    muxer.setOrientationHint(rotation)
                }
            } finally {
                retriever.release()
            }

            val outputTracks = mutableMapOf<Int, Int>()

            for (track in selectedTracks) {
                outputTracks[track] =
                    muxer.addTrack(extractor.getTrackFormat(track))
                extractor.selectTrack(track)
            }

            muxer.start()
            muxerStarted = true

            extractor.seekTo(
                actualStartUs,
                MediaExtractor.SEEK_TO_PREVIOUS_SYNC
            )

            var buffer = ByteBuffer.allocate(16 * 1024 * 1024)
            val info = android.media.MediaCodec.BufferInfo()

            var videoSamplesWritten = 0
            var audioSamplesWritten = 0
            var lastProgressUpdate = 0L

            while (true) {
                val inputTrack = extractor.sampleTrackIndex
                if (inputTrack < 0) break

                val sampleTime = extractor.sampleTime
                if (sampleTime < 0 || sampleTime >= endUs) break

                val progress = (
                    ((sampleTime - actualStartUs).coerceAtLeast(0L) * 90L) /
                        (endUs - actualStartUs).coerceAtLeast(1L)
                    ).toInt().coerceIn(0, 90)

                val now = System.currentTimeMillis()
                if (now - lastProgressUpdate >= 250L) {
                    onProgress(progress)
                    lastProgressUpdate = now
                }

                val outputTrack = outputTracks[inputTrack]

                if (outputTrack != null && sampleTime >= actualStartUs) {
                    // Android 9+ reports the exact sample size.
                    if (Build.VERSION.SDK_INT >= 28) {
                        val required = extractor.sampleSize
                        if (required > MAX_SAMPLE_BUFFER) {
                            throw IllegalArgumentException(
                                "A video sample is too large for this export."
                            )
                        }
                        if (required > buffer.capacity()) {
                            buffer = ByteBuffer.allocate(
                                required.toInt().coerceAtLeast(1)
                            )
                        }
                    }

                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)

                    if (size > 0) {
                        if (size > buffer.capacity()) {
                            throw IllegalStateException(
                                "Video sample exceeded the export buffer."
                            )
                        }

                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = sampleTime - actualStartUs
                        info.flags = extractor.sampleFlags

                        muxer.writeSampleData(outputTrack, buffer, info)

                        if (inputTrack == videoTrack) {
                            videoSamplesWritten++
                        } else {
                            audioSamplesWritten++
                        }
                    }
                }

                if (!extractor.advance()) break
            }

            if (videoSamplesWritten == 0) {
                throw IllegalArgumentException(
                    "No video frames could be exported. Try another trim range."
                )
            }

            onProgress(94)

        } finally {
            if (muxerStarted) {
                try {
                    muxer?.stop()
                } catch (_: Exception) {}
            }

            try {
                muxer?.release()
            } catch (_: Exception) {}

            extractor.release()
        }
    }

    private fun saveExport(file: File, requestedName: String): Uri? {
        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

        val base = requestedName.trim()
            .substringBeforeLast('.', requestedName.trim())
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim()
            .replace(Regex("\\s+"), "_")
            .take(60)

        val name = (base.ifBlank { "Kalize_Edit_$timestamp" }) + ".mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/Kalize Recorder"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val uri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: return null

            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(file).use { input ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException("Could not open saved video.")

                val completed = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, completed, null, null)
                return uri
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
        }

        val directory = File(
            getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "Kalize Recorder"
        )

        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Could not create export folder.")
        }

        val destination = File(directory, name)

        FileInputStream(file).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }

        return Uri.fromFile(destination)
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = (durationMs / 1000).coerceAtLeast(0)
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainder = seconds % 60

        return if (hours > 0) {
            String.format(
                Locale.US, "%d:%02d:%02d",
                hours, minutes, remainder
            )
        } else {
            String.format(
                Locale.US, "%02d:%02d",
                minutes, remainder
            )
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}


package com.kalize.recorder

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
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
import android.text.Editable
import android.text.TextWatcher
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
    }

    private val backgroundColor = Color.rgb(8, 11, 22)
    private val cardColor = Color.rgb(20, 25, 43)
    private val accentColor = Color.rgb(154, 105, 255)
    private val textColor = Color.WHITE
    private val mutedColor = Color.rgb(165, 174, 199)

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
    private lateinit var exportProgressBar: ProgressBar
    private lateinit var exportNameInput: EditText
    private lateinit var trimSummaryLabel: TextView

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(18), dp(12), dp(18), dp(24))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        topRow.addView(
            TextView(this).apply {
                text = "ƘALIZE  /  STUDIO"
                textSize = 12f
                letterSpacing = 0.12f
                setTextColor(accentColor)
                setTypeface(null, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, dp(42), 1f)
        )

        topRow.addView(TextView(this).apply {
            text = "VIDEO EDITOR"
            textSize = 10f
            setTextColor(mutedColor)
            letterSpacing = 0.08f
        })

        content.addView(topRow)

        content.addView(TextView(this).apply {
            text = "Make every\nframe count."
            textSize = 32f
            setTextColor(textColor)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(8))
        })

        content.addView(TextView(this).apply {
            text = "Your clips. Your style. Your story."
            textSize = 14f
            setTextColor(mutedColor)
            setPadding(0, 0, 0, dp(22))
        })

        // VIDEO SELECTION
        val sourceCard = createCard()
        addSectionTitle(sourceCard, "01  /  YOUR VIDEO")

        selectedLabel = TextView(this).apply {
            text = "No video selected yet"
            textSize = 15f
            setTextColor(textColor)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(5))
        }
        sourceCard.addView(selectedLabel)

        durationLabel = TextView(this).apply {
            text = "Choose a clip to see its duration."
            textSize = 12f
            setTextColor(mutedColor)
            setPadding(0, 0, 0, dp(12))
        }
        sourceCard.addView(durationLabel)

        val chooseButton = makeButton("Choose video", true)
        chooseButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            @Suppress("DEPRECATION")
            startActivityForResult(intent, PICK_VIDEO_REQUEST)
        }
        sourceCard.addView(chooseButton, matchWrap())

        previewButton = makeButton("Preview selected video", false)
        previewButton.isEnabled = false
        previewButton.alpha = 0.5f
        previewButton.setOnClickListener {
            selectedVideo?.let { playVideo(it) }
        }
        sourceCard.addView(previewButton, matchWrap())
        content.addView(sourceCard)

        // TRIM CONTROLS
        val trimCard = createCard()
        addSectionTitle(trimCard, "02  /  TRIM YOUR CLIP")

        trimCard.addView(TextView(this).apply {
            text = "Choose a quick preset or enter the exact start and end times in seconds."
            textSize = 13f
            setTextColor(mutedColor)
            setPadding(0, dp(4), 0, dp(12))
        })

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val preset15 = makeButton("15 sec", false)
        preset15.setOnClickListener { applyTrimPreset(15) }

        val preset30 = makeButton("30 sec", false)
        preset30.setOnClickListener { applyTrimPreset(30) }

        val preset60 = makeButton("60 sec", false)
        preset60.setOnClickListener { applyTrimPreset(60) }

        presetRow.addView(
            preset15,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(5)
            }
        )
        presetRow.addView(
            preset30,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(5)
            }
        )
        presetRow.addView(
            preset60,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )
        trimCard.addView(presetRow)

        trimCard.addView(TextView(this).apply {
            text = "START TIME (SECONDS)"
            textSize = 10f
            letterSpacing = 0.08f
            setTextColor(mutedColor)
            setPadding(0, dp(14), 0, dp(4))
        })

        startInput = makeTimeInput("Start time", "0")
        trimCard.addView(startInput, matchWrap())

        trimCard.addView(TextView(this).apply {
            text = "END TIME (SECONDS)"
            textSize = 10f
            letterSpacing = 0.08f
            setTextColor(mutedColor)
            setPadding(0, dp(8), 0, dp(4))
        })

        endInput = makeTimeInput("End time", "")
        trimCard.addView(endInput, matchWrap())

        // NEW: LIVE TRIM SUMMARY
        trimSummaryLabel = TextView(this).apply {
            text = "Clip length: choose a video and set your trim range."
            textSize = 12f
            setTextColor(Color.rgb(190, 174, 255))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.rgb(34, 29, 57))
        }
        trimCard.addView(trimSummaryLabel, matchWrap())

        val trimWatcher = object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) {}

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) {
                refreshTrimSummary()
            }

            override fun afterTextChanged(s: Editable?) {}
        }

        startInput.addTextChangedListener(trimWatcher)
        endInput.addTextChangedListener(trimWatcher)

        val fullClipButton = makeButton("Use full clip duration", false)
        fullClipButton.setOnClickListener {
            val duration = durationLabel.tag as? Long
            if (duration != null && duration > 0L) {
                startInput.setText("0")
                endInput.setText(
                    String.format(Locale.US, "%.2f", duration / 1000.0)
                )
                showMessage("Full clip selected.")
            } else {
                showMessage("Choose a video first.")
            }
        }
        trimCard.addView(fullClipButton, matchWrap())

        trimCard.addView(TextView(this).apply {
            text = "Quick presets start at 0 seconds. For a custom section, enter your own start and end times."
            textSize = 11f
            setTextColor(mutedColor)
            setPadding(0, dp(8), 0, 0)
        })

        content.addView(trimCard)

        // AUDIO
        val audioCard = createCard()
        addSectionTitle(audioCard, "03  /  AUDIO")

        audioCard.addView(TextView(this).apply {
            text = "Keep the original audio or remove it from the exported clip."
            textSize = 13f
            setTextColor(mutedColor)
            setPadding(0, dp(4), 0, dp(10))
        })

        muteAudio = CheckBox(this).apply {
            text = "Mute original audio"
            textSize = 14f
            setTextColor(textColor)
            buttonTintList = ColorStateList.valueOf(accentColor)
        }
        audioCard.addView(muteAudio)
        content.addView(audioCard)

        // EXPORT
        val exportCard = createCard()
        addSectionTitle(exportCard, "04  /  EXPORT")

        exportCard.addView(TextView(this).apply {
            text = "Export a trimmed MP4 to Movies/Kalize Recorder. Your original file stays unchanged."
            textSize = 13f
            setTextColor(mutedColor)
            setPadding(0, dp(4), 0, dp(12))
        })

        exportCard.addView(TextView(this).apply {
            text = "FILE NAME"
            textSize = 10f
            letterSpacing = 0.08f
            setTextColor(mutedColor)
            setPadding(0, dp(4), 0, dp(4))
        })

        exportNameInput = EditText(this).apply {
            hint = "My gaming highlights"
            setText("Kalize_Edit")
            textSize = 14f
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            backgroundTintList = ColorStateList.valueOf(accentColor)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        exportCard.addView(exportNameInput, matchWrap())

        exportCard.addView(TextView(this).apply {
            text = "The .mp4 extension is added automatically. Leave the name blank to use a default name."
            textSize = 11f
            setTextColor(mutedColor)
            setPadding(0, dp(4), 0, dp(10))
        })

        exportStatus = TextView(this).apply {
            text = "Ready when you are."
            textSize = 12f
            setTextColor(mutedColor)
            setPadding(0, 0, 0, dp(10))
        }
        exportCard.addView(exportStatus)

        exportProgressBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(accentColor)
            progressBackgroundTintList =
                ColorStateList.valueOf(Color.rgb(42, 47, 68))
            visibility = View.GONE
        }
        exportCard.addView(
            exportProgressBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(8)
            ).apply {
                bottomMargin = dp(12)
            }
        )

        exportButton = makeButton("Export edited video", true)
        exportButton.isEnabled = false
        exportButton.alpha = 0.5f
        exportButton.setOnClickListener { exportSelectedVideo() }
        exportCard.addView(exportButton, matchWrap())

        val shareButton = makeButton("Share last exported video", false)
        shareButton.setOnClickListener {
            val uri = lastExportedVideo
            if (uri == null) {
                showMessage("Export a video first.")
            } else {
                shareVideo(uri)
            }
        }
        exportCard.addView(shareButton, matchWrap())
        content.addView(exportCard)

        val libraryButton = makeButton("Open saved video library", false)
        libraryButton.setOnClickListener {
            startActivity(Intent(this, SavedVideosActivity::class.java))
        }
        content.addView(libraryButton, matchWrap())

        val backButton = makeButton("Back to recorder", false)
        backButton.setOnClickListener { finish() }
        content.addView(backButton, matchWrap())

        content.addView(TextView(this).apply {
            text = "ƘALIZE RECORDER  •  CREATOR STUDIO"
            textSize = 10f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setTextColor(mutedColor)
            setPadding(0, dp(20), 0, dp(10))
        })

        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    // NEW: Updates the selected clip length as the times change.
    private fun refreshTrimSummary() {
        val durationMs = durationLabel.tag as? Long ?: 0L
        val start = startInput.text.toString().toDoubleOrNull()
        val end = endInput.text.toString().toDoubleOrNull()

        trimSummaryLabel.text = when {
            selectedVideo == null || durationMs <= 0L ->
                "Clip length: choose a video first."

            start == null || end == null ->
                "Clip length: enter start and end times."

            start < 0.0 || end <= start ->
                "Clip length: check that the end time is after the start time."

            end * 1000.0 > durationMs + 100 ->
                "Clip length: the end time exceeds the video duration."

            else -> {
                val clipMs = ((end - start) * 1000.0).toLong()
                val startText = String.format(Locale.US, "%.2f", start)
                val endText = String.format(Locale.US, "%.2f", end)

                "Selected clip: ${formatDuration(clipMs)}  •  From ${startText}s to ${endText}s"
            }
        }
    }

    private fun applyTrimPreset(seconds: Int) {
        val durationMs = durationLabel.tag as? Long

        if (selectedVideo == null || durationMs == null || durationMs <= 0L) {
            showMessage("Choose a video first.")
            return
        }

        val durationSeconds = durationMs / 1000.0
        val chosenSeconds = minOf(seconds.toDouble(), durationSeconds)

        if (chosenSeconds <= 0.0) {
            showMessage("This video has no usable duration.")
            return
        }

        startInput.setText("0")
        endInput.setText(
            String.format(Locale.US, "%.2f", chosenSeconds)
        )

        showMessage(
            "${formatDuration((chosenSeconds * 1000).toLong())} clip selected."
        )
    }

    private fun createCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(cardColor)

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(16)
            }
        }
    }

    private fun addSectionTitle(card: LinearLayout, text: String) {
        card.addView(TextView(this).apply {
            this.text = text
            textSize = 11f
            letterSpacing = 0.10f
            setTextColor(accentColor)
            setTypeface(null, Typeface.BOLD)
        })
    }

    private fun makeButton(textValue: String, primary: Boolean): Button {
        return Button(this).apply {
            text = textValue
            textSize = 13f
            isAllCaps = false
            setTextColor(textColor)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(9), dp(8), dp(9))
            backgroundTintList = ColorStateList.valueOf(
                if (primary) accentColor else Color.rgb(42, 47, 68)
            )
            minHeight = dp(44)
            minimumHeight = dp(44)
            stateListAnimator = null
        }
    }

    private fun makeTimeInput(hintText: String, initial: String): EditText {
        return EditText(this).apply {
            hint = hintText
            setText(initial)
            textSize = 14f
            isSingleLine = true
            inputType =
                android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            backgroundTintList = ColorStateList.valueOf(accentColor)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        bottomMargin = dp(4)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    @Deprecated("Uses the Android activity result callback")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_VIDEO_REQUEST && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            selectedVideo = uri

            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Some providers do not support persistent permissions.
            }

            selectedLabel.text = "Video selected successfully"
            durationLabel.text = "Reading video information..."
            exportButton.isEnabled = false
            exportButton.alpha = 0.5f
            previewButton.isEnabled = true
            previewButton.alpha = 1f

            executor.execute {
                var durationMs = 0L
                var width = 0
                var height = 0

                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this, uri)

                    durationMs = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L

                    width = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                    )?.toIntOrNull() ?: 0

                    height = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                    )?.toIntOrNull() ?: 0
                } catch (_: Exception) {
                    // Metadata may be unavailable for some files.
                } finally {
                    retriever.release()
                }

                runOnUiThread {
                    if (selectedVideo == uri) {
                        durationLabel.tag = durationMs

                        val dimensions = if (width > 0 && height > 0) {
                            " • ${width}×${height}"
                        } else {
                            ""
                        }

                        durationLabel.text =
                            "Duration: ${formatDuration(durationMs)}$dimensions"

                        selectedLabel.text = "Ready to edit"

                        if (durationMs > 0L) {
                            endInput.setText(
                                String.format(
                                    Locale.US,
                                    "%.2f",
                                    durationMs / 1000.0
                                )
                            )
                        }

                        exportButton.isEnabled = durationMs > 0L
                        exportButton.alpha =
                            if (durationMs > 0L) 1f else 0.5f

                        refreshTrimSummary()
                    }
                }
            }
        }
    }

    private fun playVideo(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Exception) {
            showMessage("No compatible video player was found.")
        }
    }

    private fun shareVideo(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share your video"))
        } catch (_: Exception) {
            showMessage("Could not open the sharing menu.")
        }
    }

    private fun exportSelectedVideo() {
        val inputUri = selectedVideo ?: return
        val startSeconds = startInput.text.toString().toDoubleOrNull()
        val endSeconds = endInput.text.toString().toDoubleOrNull()
        val durationMs = durationLabel.tag as? Long ?: 0L

        if (startSeconds == null || endSeconds == null ||
            startSeconds < 0 || endSeconds <= startSeconds
        ) {
            showMessage("Enter a valid start and end time.")
            return
        }

        if (endSeconds * 1000.0 > durationMs + 100) {
            showMessage("The end time cannot exceed the video duration.")
            return
        }

        val shouldMuteAudio = muteAudio.isChecked
        val requestedFileName = exportNameInput.text.toString()

        exportButton.isEnabled = false
        exportButton.text = "Exporting..."
        exportButton.alpha = 0.5f
        exportProgressBar.progress = 0
        exportProgressBar.visibility = View.VISIBLE
        exportStatus.text = "Preparing your clip... 0%"

        executor.execute {
            var temporaryFile: File? = null

            try {
                val cacheFile = File.createTempFile(
                    "kalize_edit_",
                    ".mp4",
                    cacheDir
                )
                temporaryFile = cacheFile

                trimVideo(
                    inputUri,
                    cacheFile,
                    (startSeconds * 1_000_000).toLong(),
                    (endSeconds * 1_000_000).toLong(),
                    shouldMuteAudio
                ) { progress ->
                    runOnUiThread {
                        exportProgressBar.progress = progress
                        exportStatus.text = "Exporting video... $progress%"
                    }
                }

                runOnUiThread {
                    exportStatus.text = "Saving exported video... 95%"
                    exportProgressBar.progress = 95
                }

                val savedUri = saveExport(cacheFile, requestedFileName)

                runOnUiThread {
                    if (savedUri != null) {
                        lastExportedVideo = savedUri
                        exportProgressBar.progress = 100
                        exportStatus.text = "Export complete. Your clip is saved. 100%"
                        showMessage("Export complete! Your video is saved.")
                    } else {
                        exportStatus.text = "Export could not be saved."
                        showMessage("Export failed while saving the file.")
                    }

                    exportButton.isEnabled = true
                    exportButton.alpha = 1f
                    exportButton.text = "Export edited video"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    exportStatus.text = "Export failed. Try another video."
                    showMessage(
                        "Export failed: ${e.message ?: "Unsupported video"}"
                    )
                    exportButton.isEnabled = true
                    exportButton.alpha = 1f
                    exportButton.text = "Export edited video"
                }
            } finally {
                temporaryFile?.delete()
            }
        }
    }

    private fun trimVideo(
        inputUri: Uri,
        outputFile: File,
        startUs: Long,
        endUs: Long,
        mute: Boolean,
        onProgress: (Int) -> Unit
    ) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            extractor.setDataSource(this, inputUri, null)

            val selectedTracks = mutableListOf<Int>()
            var videoFound = false

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                val isVideo = mime.startsWith("video/")
                val isAudio = mime.startsWith("audio/")

                if (isVideo || (isAudio && !mute)) {
                    selectedTracks.add(i)
                    if (isVideo) videoFound = true
                }
            }

            if (!videoFound) {
                throw IllegalArgumentException("No supported video track found.")
            }

            muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            val outputTracks = mutableMapOf<Int, Int>()

            for (inputTrack in selectedTracks) {
                outputTracks[inputTrack] =
                    muxer.addTrack(extractor.getTrackFormat(inputTrack))
                extractor.selectTrack(inputTrack)
            }

            muxer.start()
            muxerStarted = true

            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            var samplesWritten = 0
            var lastProgressUpdate = 0L

            while (true) {
                val inputTrack = extractor.sampleTrackIndex
                if (inputTrack < 0) break

                val sampleTime = extractor.sampleTime
                if (sampleTime < 0 || sampleTime >= endUs) break

                val progress = (
                    ((sampleTime - startUs).coerceAtLeast(0L) * 95L) /
                        (endUs - startUs).coerceAtLeast(1L)
                    ).toInt().coerceIn(0, 94)

                val now = System.currentTimeMillis()
                if (now - lastProgressUpdate >= 250L) {
                    onProgress(progress)
                    lastProgressUpdate = now
                }

                val outputTrack = outputTracks[inputTrack]

                if (outputTrack != null && sampleTime >= startUs) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)

                    if (size > 0) {
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = sampleTime - startUs
                        info.flags = extractor.sampleFlags

                        muxer.writeSampleData(outputTrack, buffer, info)
                        samplesWritten++
                    }
                }

                if (!extractor.advance()) break
            }

            if (samplesWritten == 0) {
                throw IllegalArgumentException(
                    "No video samples found in that trim range."
                )
            }
        } finally {
            if (muxerStarted) {
                try {
                    muxer?.stop()
                } catch (_: Exception) {
                }
            }

            try {
                muxer?.release()
            } catch (_: Exception) {
            }

            extractor.release()
        }
    }

    private fun saveExport(file: File, requestedFileName: String): Uri? {
        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

        val typedName = requestedFileName.trim()
        val nameWithoutExtension =
            typedName.substringBeforeLast('.', typedName)

        val safeBaseName = nameWithoutExtension
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim()
            .replace(Regex("\\s+"), "_")
            .take(60)

        val name =
            (safeBaseName.ifBlank { "Kalize_Edit_$timestamp" }) + ".mp4"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
                } ?: throw IllegalStateException(
                    "Could not open export file."
                )

                val completedValues = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }

                contentResolver.update(uri, completedValues, null, null)
                uri
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
        } else {
            val directory = File(
                getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "Kalize Recorder"
            )

            if (!directory.exists() && !directory.mkdirs()) {
                throw IllegalStateException(
                    "Could not create export folder."
                )
            }

            val destination = File(directory, name)

            FileInputStream(file).use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }

            Uri.fromFile(destination)
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format(
                Locale.US,
                "%d:%02d:%02d",
                hours,
                minutes,
                seconds
            )
        } else {
            String.format(
                Locale.US,
                "%02d:%02d",
                minutes,
                seconds
            )
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}

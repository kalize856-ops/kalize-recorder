
package com.kalize.recorder

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@UnstableApi
class VideoEditorActivity : Activity() {

    companion object {
        private const val PICK_VIDEO_REQUEST = 4101
    }

    private val background = Color.rgb(8, 11, 22)
    private val card = Color.rgb(20, 25, 43)
    private val accent = Color.rgb(154, 105, 255)
    private val white = Color.WHITE
    private val muted = Color.rgb(165, 174, 199)

    private var selectedVideo: Uri? = null
    private var lastExportedVideo: Uri? = null

    private var videoDurationMs = 0L
    private var videoWidth = 0
    private var videoHeight = 0
    private var rotationDegrees = 0f

    private var selectedCropFormat = "Original"

    private var engine: KalizeVideoEngine? = null
    private var exportInProgress = false

    private lateinit var selectedLabel: TextView
    private lateinit var durationLabel: TextView
    private lateinit var videoInfoLabel: TextView
    private lateinit var startInput: EditText
    private lateinit var endInput: EditText
    private lateinit var muteAudio: CheckBox
    private lateinit var cropFormatGroup: RadioGroup
    private lateinit var rotationLabel: TextView
    private lateinit var exportNameInput: EditText
    private lateinit var exportButton: Button
    private lateinit var previewButton: Button
    private lateinit var exportStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var trimSummary: TextView

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        engine = KalizeVideoEngine(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@VideoEditorActivity.background)
        }

        val scroll = ScrollView(this).apply {
            setPadding(dp(18), dp(12), dp(18), dp(24))
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(label("ƘALIZE  /  STUDIO", 13f, accent, true))
        content.addView(label("VIDEO EDITOR", 28f, white, true))
        content.addView(
            label(
                "Edit your videos and export clean, watermark-free clips.",
                13f,
                muted
            )
        )

        val sourceCard = createCard()
        sourceCard.addView(section("01  /  YOUR VIDEO"))

        selectedLabel = label("No video selected", 15f, white, true)
        sourceCard.addView(selectedLabel)

        durationLabel = label("Choose a video to begin.", 12f, muted)
        sourceCard.addView(durationLabel)

        videoInfoLabel = label(
            "VIDEO INFORMATION\nSelect a video to inspect its details.",
            12f,
            Color.rgb(190, 174, 255)
        )
        sourceCard.addView(videoInfoLabel)

        sourceCard.addView(button("Choose video", true) {
            chooseVideo()
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
        trimCard.addView(
            label(
                "Set the exact start and end times in seconds.",
                12f,
                muted
            )
        )

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        listOf(15, 30, 60).forEach { seconds ->
            presetRow.addView(
                button("$seconds sec", false) {
                    applyPreset(seconds)
                },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginEnd = dp(5)
                }
            )
        }

        trimCard.addView(presetRow)
        trimCard.addView(section("START TIME (SECONDS)"))
        startInput = timeInput("0")
        trimCard.addView(startInput)

        trimCard.addView(section("END TIME (SECONDS)"))
        endInput = timeInput("")
        trimCard.addView(endInput)

        trimSummary = label(
            "Choose a video to see the selected range.",
            12f,
            Color.rgb(190, 174, 255)
        )
        trimCard.addView(trimSummary)

        trimCard.addView(button("Use full video", false) {
            if (videoDurationMs > 0L) {
                startInput.setText("0")
                endInput.setText(secondsText(videoDurationMs))
            } else {
                toast("Choose a video first.")
            }
        })

        val trimWatcher = object : android.text.TextWatcher {
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
                updateTrimSummary()
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        }

        startInput.addTextChangedListener(trimWatcher)
        endInput.addTextChangedListener(trimWatcher)
        content.addView(trimCard)

        val rotationCard = createCard()
        rotationCard.addView(section("03  /  ROTATION"))

        rotationLabel = label("Rotation: 0°", 15f, white, true)
        rotationCard.addView(rotationLabel)

        val rotationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        listOf(0f, 90f, 180f, 270f).forEach { degrees ->
            rotationRow.addView(
                button("${degrees.toInt()}°", degrees == 0f) {
                    rotationDegrees = degrees
                    rotationLabel.text = "Rotation: ${degrees.toInt()}°"
                },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginEnd = dp(4)
                }
            )
        }

        rotationCard.addView(rotationRow)
        content.addView(rotationCard)

        val cropCard = createCard()
        cropCard.addView(section("04  /  CROP FORMAT"))
        cropCard.addView(
            label(
                "Choose the shape of your exported video.",
                12f,
                muted
            )
        )

        cropFormatGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }

        val cropFormats = listOf("Original", "16:9", "9:16", "4:3", "1:1")

        cropFormats.forEach { format ->
            val radioButton = RadioButton(this).apply {
                id = View.generateViewId()
                text = when (format) {
                    "Original" -> "Original video dimensions"
                    "16:9" -> "16:9  •  Widescreen"
                    "9:16" -> "9:16  •  Vertical"
                    "4:3" -> "4:3  •  Classic"
                    else -> "1:1  •  Square"
                }
                tag = format
                textSize = 14f
                setTextColor(white)
                buttonTintList = ColorStateList.valueOf(accent)
                isChecked = format == "Original"
                setPadding(dp(4), dp(5), dp(4), dp(5))
            }

            cropFormatGroup.addView(radioButton)
        }

        cropFormatGroup.setOnCheckedChangeListener { group, checkedId ->
            val selected = group.findViewById<RadioButton>(checkedId)
            selectedCropFormat = selected?.tag?.toString() ?: "Original"
        }

        cropCard.addView(cropFormatGroup)
        cropCard.addView(
            label(
                "The selected format is applied during video export. " +
                    "Original keeps the complete frame.",
                12f,
                muted
            )
        )
        content.addView(cropCard)

        val audioCard = createCard()
        audioCard.addView(section("05  /  AUDIO"))

        muteAudio = CheckBox(this).apply {
            text = "Remove original audio"
            textSize = 14f
            setTextColor(white)
            buttonTintList = ColorStateList.valueOf(accent)
        }

        audioCard.addView(muteAudio)
        content.addView(audioCard)

        val exportCard = createCard()
        exportCard.addView(section("06  /  EXPORT"))
        exportCard.addView(
            label(
                "Finished MP4 files are saved in Movies/Kalize Recorder.",
                12f,
                muted
            )
        )

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
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
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
            lastExportedVideo?.let { shareVideo(it) }
                ?: toast("Export a video first.")
        })

        content.addView(exportCard)

        content.addView(button("Open saved video library", false) {
            startActivity(Intent(this, SavedVideosActivity::class.java))
        })

        content.addView(button("Back to recorder", false) {
            finish()
        })

        content.addView(
            label(
                "KALIZE RECORDER  •  NO FORCED WATERMARKS",
                10f,
                muted
            )
        )

        scroll.addView(content)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun chooseVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        @Suppress("DEPRECATION")
        startActivityForResult(intent, PICK_VIDEO_REQUEST)
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

            if (bold) {
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

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
            textSize = 12f
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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun secondsText(milliseconds: Long): String {
        return String.format(
            Locale.US,
            "%.2f",
            milliseconds / 1000.0
        )
    }

    private fun formatDuration(milliseconds: Long): String {
        val seconds = (milliseconds / 1000).coerceAtLeast(0)
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainder = seconds % 60

        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, remainder)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, remainder)
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"

        val kb = bytes / 1024.0
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb)
        }

        val mb = kb / 1024.0
        return String.format(Locale.US, "%.2f MB", mb)
    }

    private fun updateTrimSummary() {
        val start = startInput.text.toString().toDoubleOrNull()
        val end = endInput.text.toString().toDoubleOrNull()

        trimSummary.text = when {
            selectedVideo == null -> "Choose a video first."
            start == null || end == null -> "Enter both trim times."
            start < 0 || end <= start -> "Check your start and end times."
            end * 1000.0 > videoDurationMs + 100 ->
                "End time exceeds the video duration."
            else -> "Selected clip: ${
                String.format(Locale.US, "%.2f", end - start)
            } seconds"
        }
    }

    private fun applyPreset(seconds: Int) {
        if (videoDurationMs <= 0L) {
            toast("Choose a video first.")
            return
        }

        val selected = minOf(seconds * 1000L, videoDurationMs)
        startInput.setText("0")
        endInput.setText(secondsText(selected))
    }

    @Deprecated("Uses legacy activity result callback")
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
        } catch (_: Exception) {
        }

        selectedLabel.text = "Reading video..."
        durationLabel.text = "Loading video information..."
        videoInfoLabel.text = "Reading file details..."
        exportButton.isEnabled = false
        previewButton.isEnabled = true
        previewButton.alpha = 1f

        executor.execute {
            var duration = 0L
            var width = 0
            var height = 0
            var bitrate = 0L
            var frameRate = 0f
            var fileSize = 0L

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

                bitrate = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_BITRATE
                )?.toLongOrNull() ?: 0L

                frameRate = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE
                )?.toFloatOrNull() ?: 0f

            } catch (_: Exception) {
            } finally {
                retriever.release()
            }

            try {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                    fileSize = it.length
                }
            } catch (_: Exception) {
            }

            runOnUiThread {
                if (selectedVideo != uri) return@runOnUiThread

                videoDurationMs = duration
                videoWidth = width
                videoHeight = height

                durationLabel.text =
                    "Duration: ${formatDuration(duration)} • ${width}×${height}"

                selectedLabel.text = "Video ready to edit"

                val bitrateText = if (bitrate > 0L) {
                    String.format(Locale.US, "%.2f Mbps", bitrate / 1_000_000.0)
                } else {
                    "Not provided"
                }

                val fpsText = if (frameRate > 0f) {
                    String.format(Locale.US, "%.1f FPS", frameRate)
                } else {
                    "Not provided"
                }

                videoInfoLabel.text =
                    "VIDEO INFORMATION\n" +
                    "Resolution: $width × $height\n" +
                    "Frame rate: $fpsText\n" +
                    "Bitrate: $bitrateText\n" +
                    "File size: ${formatFileSize(fileSize)}"

                startInput.setText("0")
                endInput.setText(secondsText(duration))

                exportButton.isEnabled = duration > 0L
                exportButton.alpha = if (duration > 0L) 1f else 0.5f

                updateTrimSummary()
            }
        }
    }

    private fun calculateCrop(format: String): FloatArray? {
        if (format == "Original") return null

        if (videoWidth <= 0 || videoHeight <= 0) {
            throw IllegalStateException(
                "Could not read the video's dimensions."
            )
        }

        val rotated = (
            rotationDegrees.toInt() == 90 ||
                rotationDegrees.toInt() == 270
            )

        val effectiveWidth =
            if (rotated) videoHeight.toFloat() else videoWidth.toFloat()

        val effectiveHeight =
            if (rotated) videoWidth.toFloat() else videoHeight.toFloat()

        val targetAspect = when (format) {
            "16:9" -> 16f / 9f
            "9:16" -> 9f / 16f
            "4:3" -> 4f / 3f
            "1:1" -> 1f
            else -> return null
        }

        val sourceAspect = effectiveWidth / effectiveHeight

        var left = -1f
        var right = 1f
        var bottom = -1f
        var top = 1f

        if (sourceAspect > targetAspect) {
            val halfWidth = targetAspect / sourceAspect
            left = -halfWidth
            right = halfWidth
        } else if (sourceAspect < targetAspect) {
            val halfHeight = sourceAspect / targetAspect
            bottom = -halfHeight
            top = halfHeight
        }

        return floatArrayOf(left, right, bottom, top)
    }

    private fun exportVideo() {
        if (exportInProgress) return

        val uri = selectedVideo ?: return
        val start = startInput.text.toString().toDoubleOrNull()
        val end = endInput.text.toString().toDoubleOrNull()

        if (
            start == null ||
            end == null ||
            start < 0 ||
            end <= start ||
            end * 1000.0 > videoDurationMs + 100
        ) {
            toast("Enter a valid trim range.")
            return
        }

        val crop = try {
            calculateCrop(selectedCropFormat)
        } catch (error: Exception) {
            toast(error.message ?: "Invalid crop format.")
            return
        }

        val settings = KalizeVideoEngine.EditSettings(
            startMs = (start * 1000.0).toLong(),
            endMs = (end * 1000.0).toLong(),
            muteAudio = muteAudio.isChecked,
            rotationDegrees = rotationDegrees,
            cropEnabled = crop != null,
            cropLeft = crop?.get(0) ?: -1f,
            cropRight = crop?.get(1) ?: 1f,
            cropBottom = crop?.get(2) ?: -1f,
            cropTop = crop?.get(3) ?: 1f
        )

        exportInProgress = true
        exportButton.isEnabled = false
        exportButton.text = "Processing video..."
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        exportStatus.text = "Exporting ${selectedCropFormat} video..."

        engine?.export(
            uri,
            settings,
            object : KalizeVideoEngine.ExportListener {

                override fun onProgress(progress: Int) {
                    runOnUiThread {
                        progressBar.progress = progress
                        exportStatus.text = "Processing video... $progress%"
                    }
                }

                override fun onCompleted(outputFile: File) {
                    runOnUiThread {
                        exportStatus.text = "Saving finished video..."
                    }

                    executor.execute {
                        try {
                            val requestedName = exportNameInput.text.toString()
                            val savedUri = saveExport(outputFile, requestedName)
                            outputFile.delete()

                            runOnUiThread {
                                lastExportedVideo = savedUri
                                exportInProgress = false
                                progressBar.progress = 100
                                exportStatus.text = "Export complete • No watermark"
                                toast("Your edited video has been saved.")
                                resetExportButton()
                            }

                        } catch (error: Exception) {
                            outputFile.delete()

                            runOnUiThread {
                                exportInProgress = false
                                exportStatus.text = "Could not save video."
                                toast(
                                    error.message
                                        ?: "Could not save exported video."
                                )
                                resetExportButton()
                            }
                        }
                    }
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        exportInProgress = false
                        exportStatus.text = "Export failed."
                        toast("Export failed: $message")
                        resetExportButton()
                    }
                }
            }
        )
    }

    private fun resetExportButton() {
        exportButton.isEnabled = selectedVideo != null
        exportButton.alpha = if (selectedVideo != null) 1f else 0.5f
        exportButton.text = "Export edited video"
    }

    private fun saveExport(file: File, requestedName: String): Uri {
        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

        val trimmedName = requestedName.trim()

        val cleanedName = trimmedName
            .substringBeforeLast('.', trimmedName)
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim()
            .replace(Regex("\\s+"), "_")
            .take(60)
            .ifBlank { "Kalize_Edit_$timestamp" }

        val name = "$cleanedName.mp4"

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
            ) ?: throw IllegalStateException("Could not create video entry.")

            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(file).use { input ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException("Could not write video file.")

                val completed = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }

                contentResolver.update(uri, completed, null, null)
                return uri

            } catch (error: Exception) {
                contentResolver.delete(uri, null, null)
                throw error
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
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        return Uri.fromFile(destination)
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
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(
                Intent.createChooser(shareIntent, "Share video")
            )
        } catch (_: Exception) {
            toast("Could not share this video.")
        }
    }

    override fun onDestroy() {
        if (exportInProgress) {
            engine?.cancelExport()
        }

        engine?.release()
        engine = null

        executor.shutdown()
        super.onDestroy()
    }
}

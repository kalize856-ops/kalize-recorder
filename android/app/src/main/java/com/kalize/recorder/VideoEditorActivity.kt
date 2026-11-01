package com.kalize.recorder

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
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

    private var selectedVideo: Uri? = null
    private lateinit var selectedLabel: TextView
    private lateinit var startInput: EditText
    private lateinit var endInput: EditText
    private lateinit var muteAudio: CheckBox
    private lateinit var exportButton: Button

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(android.graphics.Color.rgb(9, 13, 24))
        }

        fun label(text: String): TextView =
            TextView(this).apply {
                this.text = text
                textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
                setPadding(0, 12, 0, 12)
            }

        val title = label("Ƙalize Video Editor").apply {
            textSize = 25f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        root.addView(title)

        root.addView(
            label("Choose a video, set the trim range in seconds, and export.")
        )

        val chooseButton = Button(this).apply {
            text = "Choose video"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "video/*"
                }

                @Suppress("DEPRECATION")
                startActivityForResult(intent, PICK_VIDEO_REQUEST)
            }
        }
        root.addView(chooseButton)

        selectedLabel = label("No video selected")
        root.addView(selectedLabel)

        startInput = EditText(this).apply {
            hint = "Start time in seconds (e.g. 0)"
            inputType =
                android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("0")
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.LTGRAY)
        }
        root.addView(startInput)

        endInput = EditText(this).apply {
            hint = "End time in seconds (e.g. 30)"
            inputType =
                android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.LTGRAY)
        }
        root.addView(endInput)

        muteAudio = CheckBox(this).apply {
            text = "Mute audio in exported video"
            setTextColor(android.graphics.Color.WHITE)
        }
        root.addView(muteAudio)

        root.addView(
            label(
                "The editor copies supported media tracks without re-encoding. " +
                    "The start point may move to the previous video keyframe."
            )
        )

        exportButton = Button(this).apply {
            text = "Export edited video"
            isEnabled = false
            setOnClickListener {
                exportSelectedVideo()
            }
        }
        root.addView(exportButton)

        // NEW: Open the Saved Videos Library.
        val savedVideosButton = Button(this).apply {
            text = "Open Saved Videos"
            setOnClickListener {
                startActivity(
                    Intent(
                        this@VideoEditorActivity,
                        SavedVideosActivity::class.java
                    )
                )
            }
        }
        root.addView(savedVideosButton)

        val backButton = Button(this).apply {
            text = "Back to recorder"
            setOnClickListener { finish() }
        }
        root.addView(backButton)

        setContentView(root)
    }

    @Deprecated("Uses the Android activity result callback")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_VIDEO_REQUEST &&
            resultCode == RESULT_OK
        ) {
            val uri = data?.data

            if (uri != null) {
                selectedVideo = uri
                selectedLabel.text = "Video selected:\n$uri"
                exportButton.isEnabled = true
            }
        }
    }

    private fun exportSelectedVideo() {
        val inputUri = selectedVideo ?: return

        val startSeconds = startInput.text.toString().toDoubleOrNull()
        val endSeconds = endInput.text.toString().toDoubleOrNull()

        if (startSeconds == null || endSeconds == null ||
            startSeconds < 0 || endSeconds <= startSeconds
        ) {
            Toast.makeText(
                this,
                "Enter a valid start and end time.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val shouldMuteAudio = muteAudio.isChecked

        exportButton.isEnabled = false
        exportButton.text = "Exporting..."

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
                )

                val savedUri = saveExport(cacheFile)

                runOnUiThread {
                    if (savedUri != null) {
                        Toast.makeText(
                            this,
                            "Export complete. Saved video:\n$savedUri",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this,
                            "Export failed while saving the file.",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    exportButton.isEnabled = true
                    exportButton.text = "Export edited video"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Export failed: ${e.message ?: "Unsupported video"}",
                        Toast.LENGTH_LONG
                    ).show()

                    exportButton.isEnabled = true
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
        mute: Boolean
    ) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            extractor.setDataSource(this, inputUri, null)

            val trackMap = mutableMapOf<Int, Int>()
            var videoFound = false

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                val isVideo = mime.startsWith("video/")
                val isAudio = mime.startsWith("audio/")

                if (isVideo || (isAudio && !mute)) {
                    val outputTrack = trackMap.size
                    trackMap[i] = outputTrack
                    if (isVideo) videoFound = true
                }
            }

            if (!videoFound || trackMap.isEmpty()) {
                throw IllegalArgumentException("No supported video track found.")
            }

            muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            val outputTracks = mutableMapOf<Int, Int>()

            for ((inputTrack, _) in trackMap) {
                outputTracks[inputTrack] =
                    muxer.addTrack(extractor.getTrackFormat(inputTrack))
                extractor.selectTrack(inputTrack)
            }

            muxer.start()

            extractor.seekTo(
                startUs,
                MediaExtractor.SEEK_TO_PREVIOUS_SYNC
            )

            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = android.media.MediaCodec.BufferInfo()

            while (true) {
                val inputTrack = extractor.sampleTrackIndex
                if (inputTrack < 0) break

                val sampleTime = extractor.sampleTime
                if (sampleTime < 0 || sampleTime >= endUs) break

                val outputTrack = outputTracks[inputTrack]
                if (outputTrack != null && sampleTime >= startUs) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)

                    if (size > 0) {
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs =
                            (sampleTime - startUs).coerceAtLeast(0)
                        info.flags = extractor.sampleFlags

                        muxer.writeSampleData(outputTrack, buffer, info)
                    }
                }

                if (!extractor.advance()) break
            }

            muxer.stop()
        } finally {
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            extractor.release()
        }
    }

    private fun saveExport(file: File): Uri? {
        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

        val name = "Kalize_Edit_$timestamp.mp4"

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
                } ?: throw IllegalStateException("Could not open export file.")

                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
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
            if (!directory.exists()) directory.mkdirs()

            val destination = File(directory, name)
            FileInputStream(file).use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }

            Uri.fromFile(destination)
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}

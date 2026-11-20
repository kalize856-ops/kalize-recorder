package com.kalize.recorder

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class RecorderService : Service() {
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var codecPipeline: MediaCodecRecordingPipeline? = null
    private var display: VirtualDisplay? = null
    private var screenshotDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var imageThread: HandlerThread? = null
    private var outputFile: File? = null
    private var outputUri: Uri? = null
    private var outputDescriptor: ParcelFileDescriptor? = null
    private var stopping = false
    private var recordingActive = false
    private var screenshotOnlySession = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> stopCapture()
            PAUSE -> pauseRecording()
            RESUME -> resumeRecording()
            SCREENSHOT -> {
                // Reuse the current projection when a recording is active.
                if (projection != null) {
                    captureScreenshot()
                } else {
                    startCapture(intent, screenshotOnly = true)
                }
            }
            START -> startCapture(intent, screenshotOnly = false)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent, screenshotOnly: Boolean) {
        if (projection != null) {
            if (screenshotOnly) captureScreenshot()
            return
        }
        stopping = false
        screenshotOnlySession = screenshotOnly
        createNotificationChannel()
        val stopIntent = android.app.PendingIntent.getService(
            this, 2, Intent(this, RecorderService::class.java).setAction(STOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification = buildRecordingNotification(paused = false, screenshotOnly = screenshotOnly)

        if (Build.VERSION.SDK_INT >= 30) {
            val wantsMicrophone = getSharedPreferences("kalize_settings", MODE_PRIVATE)
                .getBoolean("microphone_enabled", false) &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val serviceTypes = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                if (wantsMicrophone) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            startForeground(NOTIFICATION_ID, notification, serviceTypes)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent.getIntExtra(CODE, Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(DATA) as? Intent
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopCapture()
            return
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val activeProjection = manager.getMediaProjection(resultCode, data)
            projection = activeProjection
            activeProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                }
            }, null)

            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            if (screenshotOnly) {
                setBubbleCaptureVisibility(hidden = true)
                captureScreenshot()
                return
            }

            val preferences = getSharedPreferences("kalize_settings", MODE_PRIVATE)
            // Resolution presets use the conventional short-edge value (240p, 360p, etc.).
            // Scaling the long edge made portrait 240p recordings far smaller than intended.
            val targetShortEdge = preferences.getInt("resolution", 1080).coerceIn(240, 2160)
            val sourceShortEdge = minOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
            val scale = minOf(1f, targetShortEdge.toFloat() / sourceShortEdge.toFloat())
            val width = ((metrics.widthPixels * scale).toInt() / 2) * 2
            val height = ((metrics.heightPixels * scale).toInt() / 2) * 2

            val requestedFps = preferences.getInt("fps", 30).coerceIn(30, 240)
            @Suppress("DEPRECATION")
            val displayRefresh = windowManager.defaultDisplay.refreshRate.toInt().coerceAtLeast(30)
            val frameRate = selectSupportedFrameRate(width.coerceAtLeast(2), height.coerceAtLeast(2), requestedFps, displayRefresh)
            preferences.edit().putInt("last_selected_fps", frameRate).apply()
            prepareVideoOutput()
            val microphoneEnabled = preferences.getBoolean("microphone_enabled", false) &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val presetBitrate = when (preferences.getInt("bitrate_preset", 1).coerceIn(0, 2)) {
                0 -> 8_000_000
                1 -> 16_000_000
                else -> 28_000_000
            }
            val resolutionLimit = when {
                maxOf(width, height) >= 2160 -> 35_000_000
                maxOf(width, height) >= 1440 -> 24_000_000
                maxOf(width, height) >= 1080 -> 16_000_000
                maxOf(width, height) >= 720 -> 10_000_000
                maxOf(width, height) >= 480 -> 4_000_000
                maxOf(width, height) >= 360 -> 2_500_000
                else -> 1_500_000
            }
            val outputBitrate = minOf(presetBitrate, resolutionLimit)

            // Prefer direct MediaCodec surface encoding for video-only sessions.
            // Microphone sessions retain MediaRecorder until a synchronized audio
            // encoder/muxer path is available.
            if (!microphoneEnabled) {
                val codecRecorder = runCatching {
                    MediaCodecRecordingPipeline(
                        width = width.coerceAtLeast(2),
                        height = height.coerceAtLeast(2),
                        frameRate = frameRate,
                        bitRate = outputBitrate,
                        outputDescriptor = outputDescriptor,
                        outputFile = outputFile
                    ).also { it.start() }
                }.getOrNull()

                if (codecRecorder != null) {
                    codecPipeline = codecRecorder
                    preferences.edit()
                        .putInt("last_selected_fps", codecRecorder.actualFrameRate)
                        .putString("last_recording_encoder", codecRecorder.encoderName)
                        .apply()
                    display = activeProjection.createVirtualDisplay(
                        "KalizeCapture", width, height, metrics.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        codecRecorder.inputSurface, null, null
                    )
                    recordingActive = true
                    getSharedPreferences("kalize_settings", MODE_PRIVATE)
                        .edit().putBoolean("recording_active", true).apply()
                    setBubbleCaptureVisibility(hidden = true)
                    return
                }
                // If a vendor codec fails during configure/start, use MediaRecorder.
            }

            val descriptor = outputDescriptor
            val fpsFallbacks = listOf(frameRate, 120, 90, 60, 30)
                .distinct()
                .filter { it in 30..frameRate }
                .sortedDescending()
            var preparedRecorder: MediaRecorder? = null
            var actualFrameRate = frameRate
            var lastPrepareError: Exception? = null

            // Some devices report a codec capability but MediaRecorder selects a
            // different encoder. Retry lower rates before giving up completely.
            for (candidateFps in fpsFallbacks) {
                val candidate = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
                try {
                    candidate.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    if (microphoneEnabled) candidate.setAudioSource(MediaRecorder.AudioSource.MIC)
                    candidate.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    candidate.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    if (microphoneEnabled) {
                        candidate.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        candidate.setAudioEncodingBitRate(128_000)
                        candidate.setAudioSamplingRate(44_100)
                    }
                    candidate.setVideoSize(width.coerceAtLeast(2), height.coerceAtLeast(2))
                    candidate.setVideoFrameRate(candidateFps)
                    candidate.setVideoEncodingBitRate(outputBitrate)
                    if (descriptor != null) candidate.setOutputFile(descriptor.fileDescriptor)
                    else candidate.setOutputFile(outputFile!!.absolutePath)
                    candidate.prepare()
                    preparedRecorder = candidate
                    actualFrameRate = candidateFps
                    break
                } catch (error: Exception) {
                    lastPrepareError = error
                    try { candidate.reset() } catch (_: Exception) { }
                    try { candidate.release() } catch (_: Exception) { }
                }
            }

            val mediaRecorder = preparedRecorder ?: throw IllegalStateException(
                "This device could not prepare screen recording at 30–${frameRate} FPS.",
                lastPrepareError
            )
            recorder = mediaRecorder
            preferences.edit().putInt("last_selected_fps", actualFrameRate).apply()

            display = activeProjection.createVirtualDisplay(
                "KalizeCapture", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.surface, null, null
            )
            mediaRecorder.start()
            recordingActive = true
            getSharedPreferences("kalize_settings", MODE_PRIVATE).edit().putBoolean("recording_active", true).apply()
            setBubbleCaptureVisibility(hidden = true)
        } catch (error: Exception) {
            Toast.makeText(this, "Recording could not start: ${error.localizedMessage ?: "unsupported device configuration"}", Toast.LENGTH_LONG).show()
            stopCapture()
        }
    }

    /**
     * Selects the highest H.264 surface-encoder rate that Android reports for this
     * exact capture size, never exceeding the user's request or display refresh.
     * MediaRecorder still chooses the platform encoder, so this is a capability
     * check rather than a promise of sustained real-world frame delivery.
     */
    private fun selectSupportedFrameRate(width: Int, height: Int, requested: Int, displayRefresh: Int): Int {
        val ceiling = minOf(requested, displayRefresh, 240)
        val candidates = listOf(240, 120, 90, 60, 30).filter { it <= ceiling }
        return try {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals("video/avc", ignoreCase = true) } }
            candidates.firstOrNull { fps ->
                codecs.any { codec ->
                    runCatching {
                        val caps = codec.getCapabilitiesForType("video/avc")
                        caps.colorFormats.contains(android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) &&
                            caps.videoCapabilities.areSizeAndRateSupported(width, height, fps.toDouble())
                    }.getOrDefault(false)
                }
            } ?: minOf(ceiling, 60).coerceAtLeast(30)
        } catch (_: Exception) {
            minOf(ceiling, 60).coerceAtLeast(30)
        }
    }

    private fun buildRecordingNotification(paused: Boolean, screenshotOnly: Boolean = false): android.app.Notification {
        val stopIntent = android.app.PendingIntent.getService(
            this, 2, Intent(this, RecorderService::class.java).setAction(STOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Kalize Recorder")
            .setContentText(when {
                screenshotOnly -> "Preparing screenshot"
                paused -> "Recording paused"
                else -> "Screen recording is active"
            })
            .setOngoing(true)
        if (!screenshotOnly) {
            val action = if (paused) RESUME else PAUSE
            val label = if (paused) "Resume" else "Pause"
            val controlIntent = android.app.PendingIntent.getService(
                this, 3, Intent(this, RecorderService::class.java).setAction(action),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, label, controlIntent)
        }
        builder.addAction(0, "Stop", stopIntent)
        return builder.build()
    }

    private fun pauseRecording() {
        if (!recordingActive || stopping) return
        if (codecPipeline != null) {
            Toast.makeText(this, "Pause is not supported in the dedicated high-FPS mode yet.", Toast.LENGTH_LONG).show()
            return
        }
        if (recorder == null) return
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                recorder?.pause()
                getSharedPreferences("kalize_bubble_position", MODE_PRIVATE).edit().putBoolean("recording_paused", true).apply()
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildRecordingNotification(paused = true))
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Pause is not supported by this device encoder.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resumeRecording() {
        if (!recordingActive || stopping || codecPipeline != null || recorder == null) return
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                recorder?.resume()
                getSharedPreferences("kalize_bubble_position", MODE_PRIVATE).edit().putBoolean("recording_paused", false).apply()
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildRecordingNotification(paused = false))
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Could not resume recording.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun captureScreenshot() {
        val activeProjection = projection ?: return
        if (imageReader != null) return
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)

        try {
            val thread = HandlerThread("KalizeScreenshot").apply { start() }
            imageThread = thread
            val handler = Handler(thread.looper)
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = reader
            val delivered = AtomicBoolean(false)
            reader.setOnImageAvailableListener({ source ->
                if (!delivered.compareAndSet(false, true)) return@setOnImageAvailableListener
                var image: android.media.Image? = null
                try {
                    image = source.acquireLatestImage()
                    if (image != null) {
                        val plane = image.planes[0]
                        val buffer: ByteBuffer = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val paddedWidth = rowStride / pixelStride
                        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                        padded.copyPixelsFromBuffer(buffer)
                        val bitmap = Bitmap.createBitmap(padded, 0, 0, width, height)
                        padded.recycle()
                        saveScreenshot(bitmap)
                        bitmap.recycle()
                    }
                } catch (_: Exception) {
                    // The screen capture session remains available if a screenshot cannot be saved.
                } finally {
                    try { image?.close() } catch (_: Exception) { }
                    releaseScreenshotCapture()
                    if (!recordingActive) stopCapture()
                }
            }, handler)

            screenshotDisplay = activeProjection.createVirtualDisplay(
                "KalizeScreenshot", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )
        } catch (_: Exception) {
            releaseScreenshotCapture()
            if (!recordingActive) stopCapture()
        }
    }

    private fun saveScreenshot(bitmap: Bitmap) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "Kalize_Screenshot_$stamp.png"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Kalize Recorder/Screenshots")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Unable to create screenshot")
            try {
                contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    ?: throw IllegalStateException("Unable to open screenshot output")
                val complete = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                contentResolver.update(uri, complete, null, null)
            } catch (error: Exception) {
                contentResolver.delete(uri, null, null)
                throw error
            }
        } else {
            val directory = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Kalize Recorder/Screenshots")
            if (!directory.exists()) directory.mkdirs()
            FileOutputStream(File(directory, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun releaseScreenshotCapture() {
        try { screenshotDisplay?.release() } catch (_: Exception) { }
        screenshotDisplay = null
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) { }
        try { imageReader?.close() } catch (_: Exception) { }
        imageReader = null
        try { imageThread?.quitSafely() } catch (_: Exception) { }
        imageThread = null
    }

    private fun prepareVideoOutput() {
        val fileName = "Kalize_${System.currentTimeMillis()}.mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Kalize Recorder")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Unable to create video in Movies")
            outputUri = uri
            outputDescriptor = contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("Unable to open output video")
        } else {
            val directory = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
            outputFile = File(directory, fileName)
        }
    }

    private fun stopCapture() {
        if (stopping) return
        stopping = true
        val activeRecorder = recorder
        val activeCodecPipeline = codecPipeline
        val activeDisplay = display
        val activeProjection = projection
        recorder = null
        codecPipeline = null
        display = null
        projection = null
        recordingActive = false
        getSharedPreferences("kalize_settings", MODE_PRIVATE).edit().putBoolean("recording_active", false).apply()
        getSharedPreferences("kalize_bubble_position", MODE_PRIVATE).edit().putBoolean("recording_paused", false).apply()
        setBubbleCaptureVisibility(hidden = false)

        var saved = false
        try {
            activeRecorder?.stop()
            saved = activeRecorder != null
        } catch (_: Exception) {
            outputFile?.delete()
            outputUri?.let { contentResolver.delete(it, null, null) }
        }
        // Release the projection surface before signalling encoder end-of-stream.
        try { activeDisplay?.release() } catch (_: Exception) { }
        if (activeCodecPipeline != null) {
            saved = runCatching { activeCodecPipeline.stop() }.getOrDefault(false)
            if (!saved) {
                outputFile?.delete()
                outputUri?.let { contentResolver.delete(it, null, null) }
            }
        }
        try { activeRecorder?.reset() } catch (_: Exception) { }
        try { activeRecorder?.release() } catch (_: Exception) { }
        try { activeProjection?.stop() } catch (_: Exception) { }
        releaseScreenshotCapture()

        try { outputDescriptor?.close() } catch (_: Exception) { }
        outputDescriptor = null
        if (saved && Build.VERSION.SDK_INT >= 29) {
            outputUri?.let { uri ->
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                contentResolver.update(uri, values, null, null)
            }
        }
        outputUri = null
        outputFile = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun setBubbleCaptureVisibility(hidden: Boolean) {
        if (!getSharedPreferences("kalize_settings", MODE_PRIVATE).getBoolean("bubble_enabled", false)) return
        val action = if (hidden) FloatingBubbleService.HIDE_FOR_CAPTURE else FloatingBubbleService.SHOW_AFTER_CAPTURE
        try {
            startService(Intent(this, FloatingBubbleService::class.java).setAction(action))
        } catch (_: Exception) {
            // Overlay visibility must never interrupt recording.
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val START = "kalize.start"
        const val STOP = "kalize.stop"
        const val PAUSE = "kalize.pause"
        const val RESUME = "kalize.resume"
        const val SCREENSHOT = "kalize.screenshot"
        const val CODE = "result_code"
        const val DATA = "projection_data"
        private const val CHANNEL = "kalize_capture"
        private const val NOTIFICATION_ID = 1
    }
}

package com.kalize.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class ScreenRecordingService : Service() {
    companion object {
        const val ACTION_STOP = "com.kalize.recorder.STOP_RECORDING"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val EXTRA_QUALITY = "recording_quality"
        const val EXTRA_FPS = "recording_fps"
        private const val CHANNEL_ID = "kalize_recording"
        private const val NOTIFICATION_ID = 7401
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outputUri: Uri? = null
    private var outputDescriptor: android.os.ParcelFileDescriptor? = null
    private var legacyOutputFile: File? = null
    private var isStopping = false
    private var recordingStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording(stopProjection = true)
            return START_NOT_STICKY
        }

        // Android 10+ needs the foreground notification before starting capture.
        createNotificationChannel()
        val notification = buildNotification("Preparing screen recording…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val quality = intent?.getStringExtra(EXTRA_QUALITY) ?: "1080p"
        val fps = intent?.getIntExtra(EXTRA_FPS, 30) ?: 30

        if (resultData == null || resultCode == 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startRecording(resultCode, resultData, quality, fps)
            updateNotification("Recording screen — tap the app's Stop button to finish")
        } catch (error: Exception) {
            Toast.makeText(this, "Could not start recording: ${error.localizedMessage ?: "unsupported device setting"}",
                Toast.LENGTH_LONG).show()
            stopRecording(stopProjection = true)
        }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, resultData: Intent, quality: String, fps: Int) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)

        val screenWidth = max(2, metrics.widthPixels)
        val screenHeight = max(2, metrics.heightPixels)
        val longEdgeTarget = when (quality) {
            "360p" -> 640
            "480p" -> 854
            "720p" -> 1280
            "1440p" -> 2560
            "4K" -> 3840
            else -> 1920
        }
        val scale = minOf(1f, longEdgeTarget.toFloat() / max(screenWidth, screenHeight))
        val outputWidth = even((screenWidth * scale).roundToInt())
        val outputHeight = even((screenHeight * scale).roundToInt())

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "Kalize_$timestamp.mp4"
        val outputFileDescriptor = prepareOutput(displayName)

        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        recorder = mediaRecorder
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mediaRecorder.setVideoSize(outputWidth, outputHeight)
        mediaRecorder.setVideoFrameRate(if (fps >= 60) 60 else 30) // Target only; actual capture rate is device-dependent.
        mediaRecorder.setVideoEncodingBitRate(8_000_000)
        if (outputFileDescriptor != null) {
            mediaRecorder.setOutputFile(outputFileDescriptor.fileDescriptor)
        } else {
            mediaRecorder.setOutputFile(legacyOutputFile!!.absolutePath)
        }
        mediaRecorder.prepare()

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = manager.getMediaProjection(resultCode, resultData)
        projection = mediaProjection
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopRecording(stopProjection = false)
            }
        }, Handler(Looper.getMainLooper()))

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "KalizeRecorderDisplay",
            outputWidth,
            outputHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder.surface,
            null,
            null
        )
        mediaRecorder.start()
        recordingStarted = true
    }

    private fun prepareOutput(displayName: String): android.os.ParcelFileDescriptor? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/Kalize Recorder")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create a video file")
            outputUri = uri
            return contentResolver.openFileDescriptor(uri, "w").also {
                if (it == null) throw IllegalStateException("Could not open the video file")
                outputDescriptor = it
            }
        }

        val movies = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: throw IllegalStateException("Phone storage is unavailable")
        if (!movies.exists()) movies.mkdirs()
        legacyOutputFile = File(movies, displayName)
        return null
    }

    private fun stopRecording(stopProjection: Boolean) {
        if (isStopping) return
        isStopping = true

        val oldProjection = projection
        projection = null
        if (stopProjection) {
            try { oldProjection?.stop() } catch (_: Exception) {}
        }
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null

        var saved = false
        try {
            if (recordingStarted) {
                recorder?.stop()
                saved = true
            }
        } catch (_: RuntimeException) {
            saved = false // A very short capture may not contain a valid MP4.
        } finally {
            try { recorder?.reset() } catch (_: Exception) {}
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null
            recordingStarted = false
        }

        try { outputDescriptor?.close() } catch (_: Exception) {}
        outputDescriptor = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = outputUri
            if (uri != null) {
                if (saved) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    contentResolver.update(uri, values, null, null)
                } else {
                    contentResolver.delete(uri, null, null)
                }
            }
        } else {
            val file = legacyOutputFile
            if (saved && file != null) {
                android.media.MediaScannerConnection.scanFile(
                    this, arrayOf(file.absolutePath), arrayOf("video/mp4"), null
                )
            } else {
                try { file?.delete() } catch (_: Exception) {}
            }
        }

        outputUri = null
        legacyOutputFile = null
        isStopping = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Screen recording", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows when Ƙalize Recorder is capturing the screen." }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Ƙalize Recorder")
            .setContentText(message)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun even(value: Int): Int = max(2, value - (value % 2))
}

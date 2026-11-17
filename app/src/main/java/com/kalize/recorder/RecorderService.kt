package com.kalize.recorder

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File

class RecorderService : Service() {
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var outputFile: File? = null
    private var outputUri: Uri? = null
    private var outputDescriptor: ParcelFileDescriptor? = null
    private var stopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> stopCapture()
            START -> startCapture(intent)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        if (projection != null) return
        stopping = false
        createNotificationChannel()
        val stopIntent = android.app.PendingIntent.getService(
            this, 2, Intent(this, RecorderService::class.java).setAction(STOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Kalize Recorder")
            .setContentText("Screen recording is active")
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
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

            val preferences = getSharedPreferences("kalize_settings", MODE_PRIVATE)
            val targetLongEdge = preferences.getInt("resolution", 1080).coerceIn(720, 2160)
            val sourceLongEdge = maxOf(metrics.widthPixels, metrics.heightPixels)
            val scale = minOf(1f, targetLongEdge.toFloat() / sourceLongEdge.toFloat())
            val width = ((metrics.widthPixels * scale).toInt() / 2) * 2
            val height = ((metrics.heightPixels * scale).toInt() / 2) * 2

            val requestedFps = preferences.getInt("fps", 30).coerceIn(30, 240)
            @Suppress("DEPRECATION")
            val displayRefresh = windowManager.defaultDisplay.refreshRate.toInt().coerceAtLeast(30)
            val frameRate = minOf(requestedFps, displayRefresh, 240)
            prepareOutput()
            val mediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder = mediaRecorder
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mediaRecorder.setVideoSize(width.coerceAtLeast(2), height.coerceAtLeast(2))
            mediaRecorder.setVideoFrameRate(frameRate)
            val bitrate = when {
                maxOf(width, height) >= 2160 -> 35_000_000
                maxOf(width, height) >= 1440 -> 16_000_000
                maxOf(width, height) >= 1080 -> 8_000_000
                else -> 5_000_000
            }
            mediaRecorder.setVideoEncodingBitRate(bitrate)
            val descriptor = outputDescriptor
            if (descriptor != null) mediaRecorder.setOutputFile(descriptor.fileDescriptor)
            else mediaRecorder.setOutputFile(outputFile!!.absolutePath)
            mediaRecorder.prepare()

            display = activeProjection.createVirtualDisplay(
                "KalizeCapture", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.surface, null, null
            )
            mediaRecorder.start()
        } catch (_: Exception) {
            stopCapture()
        }
    }

    private fun prepareOutput() {
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
        val activeDisplay = display
        val activeProjection = projection
        recorder = null
        display = null
        projection = null

        var saved = false
        try {
            activeRecorder?.stop()
            saved = true
        } catch (_: Exception) {
            outputFile?.delete()
            outputUri?.let { contentResolver.delete(it, null, null) }
        }
        try { activeDisplay?.release() } catch (_: Exception) { }
        try { activeRecorder?.reset() } catch (_: Exception) { }
        try { activeRecorder?.release() } catch (_: Exception) { }
        try { activeProjection?.stop() } catch (_: Exception) { }

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
        const val CODE = "result_code"
        const val DATA = "projection_data"
        private const val CHANNEL = "kalize_capture"
        private const val NOTIFICATION_ID = 1
    }
}

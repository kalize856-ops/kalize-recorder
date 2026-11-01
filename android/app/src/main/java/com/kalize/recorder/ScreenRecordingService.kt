
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
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
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
        const val EXTRA_OUTPUT_TREE_URI = "output_tree_uri"

        private const val CHANNEL_ID = "kalize_recording"
        private const val NOTIFICATION_ID = 7401
        private const val STOP_REQUEST_CODE = 7402
        private const val OPEN_REQUEST_CODE = 7403
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null

    private var outputUri: Uri? = null
    private var outputDescriptor: android.os.ParcelFileDescriptor? = null
    private var legacyOutputFile: File? = null

    private var outputIsMediaStore = false
    private var requestedTreeUri: Uri? = null
    private var isStopping = false
    private var recordingStarted = false

    // Used by the notification's elapsed-time counter.
    private var recordingStartTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording(stopProjection = true)
            return START_NOT_STICKY
        }

        requestedTreeUri = intent
            ?.getStringExtra(EXTRA_OUTPUT_TREE_URI)
            ?.let {
                try {
                    Uri.parse(it)
                } catch (_: Exception) {
                    null
                }
            }

        createNotificationChannel()

        val notification = buildNotification(
            "Preparing screen recording…"
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0

        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(
                EXTRA_RESULT_DATA,
                Intent::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        val quality = intent?.getStringExtra(EXTRA_QUALITY) ?: "1080p"
        val fps = intent?.getIntExtra(EXTRA_FPS, 30) ?: 30

        if (resultData == null || resultCode == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startRecording(resultCode, resultData, quality, fps)
            updateNotification(
                "Recording screen — tap STOP RECORDING to finish"
            )
        } catch (error: Exception) {
            Toast.makeText(
                this,
                "Could not start recording: ${
                    error.localizedMessage ?: "Check the selected folder"
                }",
                Toast.LENGTH_LONG
            ).show()

            stopRecording(stopProjection = true)
        }

        return START_NOT_STICKY
    }

    private fun startRecording(
        resultCode: Int,
        resultData: Intent,
        quality: String,
        fps: Int
    ) {
        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)

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

        val scale = minOf(
            1f,
            longEdgeTarget.toFloat() / max(screenWidth, screenHeight)
        )

        val outputWidth = even((screenWidth * scale).roundToInt())
        val outputHeight = even((screenHeight * scale).roundToInt())

        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

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
        mediaRecorder.setVideoFrameRate(if (fps >= 60) 60 else 30)
        mediaRecorder.setVideoEncodingBitRate(8_000_000)

        if (outputFileDescriptor != null) {
            mediaRecorder.setOutputFile(outputFileDescriptor.fileDescriptor)
        } else {
            val file = legacyOutputFile
                ?: throw IllegalStateException(
                    "Could not prepare the output file"
                )
            mediaRecorder.setOutputFile(file.absolutePath)
        }

        mediaRecorder.prepare()

        val manager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager

        val mediaProjection = manager.getMediaProjection(resultCode, resultData)
        projection = mediaProjection

        mediaProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopRecording(stopProjection = false)
                }
            },
            Handler(Looper.getMainLooper())
        )

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

        // Start the timer only after the recorder successfully starts.
        recordingStartTime = System.currentTimeMillis()
    }

    private fun prepareOutput(
        displayName: String
    ): android.os.ParcelFileDescriptor? {

        val treeUri = requestedTreeUri

        if (treeUri != null) {
            try {
                val parentDocumentUri =
                    DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri)
                    )

                val createdUri = DocumentsContract.createDocument(
                    contentResolver,
                    parentDocumentUri,
                    "video/mp4",
                    displayName
                ) ?: throw IllegalStateException(
                    "Android could not create a video in that folder"
                )

                outputUri = createdUri
                outputIsMediaStore = false

                val descriptor = contentResolver.openFileDescriptor(
                    createdUri,
                    "w"
                ) ?: throw IllegalStateException(
                    "Could not open the selected folder's video file"
                )

                outputDescriptor = descriptor
                return descriptor
            } catch (error: Exception) {
                outputUri?.let {
                    try {
                        contentResolver.delete(it, null, null)
                    } catch (_: Exception) {
                    }
                }

                outputUri = null
                outputIsMediaStore = false

                throw IllegalStateException(
                    "Could not save to the selected folder. Choose the folder again or use default phone storage.",
                    error
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/Kalize Recorder"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw IllegalStateException(
                "Could not create a video in phone storage"
            )

            outputUri = uri
            outputIsMediaStore = true

            return contentResolver.openFileDescriptor(uri, "w").also {
                if (it == null) {
                    throw IllegalStateException(
                        "Could not open the phone storage video file"
                    )
                }
                outputDescriptor = it
            }
        }

        val movies = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: throw IllegalStateException("Phone storage is unavailable")

        if (!movies.exists()) {
            movies.mkdirs()
        }

        legacyOutputFile = File(movies, displayName)
        outputIsMediaStore = false
        return null
    }

    private fun stopRecording(stopProjection: Boolean) {
        if (isStopping) return
        isStopping = true

        val oldProjection = projection
        projection = null

        if (stopProjection) {
            try {
                oldProjection?.stop()
            } catch (_: Exception) {
            }
        }

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        var saved = false

        try {
            if (recordingStarted) {
                recorder?.stop()
                saved = true
            }
        } catch (_: RuntimeException) {
            saved = false
        } finally {
            try {
                recorder?.reset()
            } catch (_: Exception) {
            }

            try {
                recorder?.release()
            } catch (_: Exception) {
            }

            recorder = null
            recordingStarted = false
        }

        // Clear the timer when recording finishes.
        recordingStartTime = 0L

        try {
            outputDescriptor?.close()
        } catch (_: Exception) {
        }
        outputDescriptor = null

        val uri = outputUri

        if (uri != null) {
            if (outputIsMediaStore) {
                if (saved) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }

                    try {
                        contentResolver.update(uri, values, null, null)
                    } catch (_: Exception) {
                    }
                } else {
                    try {
                        contentResolver.delete(uri, null, null)
                    } catch (_: Exception) {
                    }
                }
            } else if (!saved) {
                try {
                    DocumentsContract.deleteDocument(contentResolver, uri)
                } catch (_: Exception) {
                    try {
                        contentResolver.delete(uri, null, null)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        val file = legacyOutputFile

        if (file != null) {
            if (saved) {
                android.media.MediaScannerConnection.scanFile(
                    this,
                    arrayOf(file.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )
            } else {
                try {
                    file.delete()
                } catch (_: Exception) {
                }
            }
        }

        outputUri = null
        legacyOutputFile = null
        outputIsMediaStore = false
        requestedTreeUri = null
        isStopping = false

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Shows when Kalize Recorder is capturing the screen."
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            OPEN_REQUEST_CODE,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(
            this,
            ScreenRecordingService::class.java
        ).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Kalize Recorder")
            .setContentText(message)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_media_pause,
                "STOP RECORDING",
                stopPendingIntent
            )

        // Display a live elapsed-time counter while recording is active.
        if (recordingStarted && recordingStartTime > 0L) {
            builder
                .setWhen(recordingStartTime)
                .setUsesChronometer(true)
        } else {
            builder
                .setWhen(System.currentTimeMillis())
                .setUsesChronometer(false)
        }

        return builder.build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun even(value: Int): Int = max(2, value - (value % 2))
}


package com.kalize.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenshotService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "screenshot_result_code"
        const val EXTRA_RESULT_DATA = "screenshot_result_data"

        private const val CHANNEL_ID = "kalize_screenshot"
        private const val NOTIFICATION_ID = 7402
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var finished = false

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        createNotificationChannel()

        val notification: Notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Kalize Recorder")
                .setContentText("Taking screenshot…")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

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

        if (resultCode == 0 || resultData == null) {
            finishCapture()
            return START_NOT_STICKY
        }

        try {
            startCapture(resultCode, resultData)
        } catch (error: Exception) {
            android.util.Log.e(
                "ScreenshotService",
                "Could not start screenshot capture",
                error
            )
            finishCapture()
        }

        return START_NOT_STICKY
    }

    private fun startCapture(
        resultCode: Int,
        resultData: Intent
    ) {
        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels.coerceAtLeast(2)
        val height = metrics.heightPixels.coerceAtLeast(2)

        val reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )
        imageReader = reader

        reader.setOnImageAvailableListener(
            { source ->
                if (finished) return@setOnImageAvailableListener

                val image = try {
                    source.acquireLatestImage()
                } catch (error: Exception) {
                    null
                } ?: return@setOnImageAvailableListener

                try {
                    val plane = image.planes[0]
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val paddedBitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )

                    paddedBitmap.copyPixelsFromBuffer(
                        plane.buffer
                    )

                    val bitmap = Bitmap.createBitmap(
                        paddedBitmap,
                        0,
                        0,
                        width,
                        height
                    )

                    if (bitmap !== paddedBitmap) {
                        paddedBitmap.recycle()
                    }

                    val saved = saveScreenshot(bitmap)
                    bitmap.recycle()

                    if (!saved) {
                        android.util.Log.e(
                            "ScreenshotService",
                            "Screenshot could not be saved"
                        )
                    }
                } catch (error: Exception) {
                    android.util.Log.e(
                        "ScreenshotService",
                        "Screenshot capture failed",
                        error
                    )
                } finally {
                    image.close()
                    finishCapture()
                }
            },
            mainHandler
        )

        val manager = getSystemService(
            MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        val mediaProjection = manager.getMediaProjection(
            resultCode,
            resultData
        )
        projection = mediaProjection

        mediaProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    finishCapture()
                }
            },
            mainHandler
        )

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "KalizeScreenshot",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            mainHandler
        )
    }

    private fun saveScreenshot(bitmap: Bitmap): Boolean {
        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

        val filename = "Kalize_Screenshot_$timestamp.png"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/Kalize Recorder"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: return false

        return try {
            val output = contentResolver.openOutputStream(uri)
                ?: throw IOException("Could not open screenshot output")

            output.use {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                    throw IOException("PNG encoding failed")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val completed = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, completed, null, null)
            }

            true
        } catch (error: Exception) {
            try {
                contentResolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            throw error
        }
    }

    @Synchronized
    private fun finishCapture() {
        if (finished) return
        finished = true

        try {
            imageReader?.setOnImageAvailableListener(null, null)
        } catch (_: Exception) {
        }

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        val oldProjection = projection
        projection = null

        try {
            oldProjection?.stop()
        } catch (_: Exception) {
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screenshot capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification while Kalize Recorder takes a screenshot."
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}

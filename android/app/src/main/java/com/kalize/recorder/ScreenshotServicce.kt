
package com.kalize.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
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
    private var capturing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Ƙalize Recorder")
            .setContentText("Capturing screenshot…")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
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

            val resultCode = intent?.getIntExtra(
                EXTRA_RESULT_CODE, 0
            ) ?: 0

            val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            }

            if (resultCode != android.app.Activity.RESULT_OK ||
                resultData == null
            ) {
                finishCapture()
                return START_NOT_STICKY
            }

            val manager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            projection = manager.getMediaProjection(
                resultCode,
                resultData
            )

            val metrics = DisplayMetrics()

            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.getRealMetrics(metrics)

            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)

            val reader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )

            imageReader = reader

            projection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        finishCapture()
                    }
                },
                Handler(Looper.getMainLooper())
            )

            reader.setOnImageAvailableListener(
                { source ->
                    if (capturing) return@setOnImageAvailableListener

                    val image = try {
                        source.acquireLatestImage()
                    } catch (_: Exception) {
                        null
                    } ?: return@setOnImageAvailableListener

                    capturing = true

                    var bitmap: Bitmap? = null

                    try {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride -
                            pixelStride * width

                        val paddedWidth = width +
                            rowPadding / pixelStride

                        val paddedBitmap = Bitmap.createBitmap(
                            paddedWidth,
                            height,
                            Bitmap.Config.ARGB_8888
                        )

                        paddedBitmap.copyPixelsFromBuffer(buffer)

                        bitmap = Bitmap.createBitmap(
                            paddedBitmap,
                            0,
                            0,
                            width,
                            height
                        )

                        paddedBitmap.recycle()

                        saveScreenshot(bitmap!!)
                    } catch (error: Exception) {
                        android.util.Log.e(
                            "KalizeScreenshot",
                            "Screenshot could not be saved",
                            error
                        )
                    } finally {
                        image.close()
                        bitmap?.recycle()
                        finishCapture()
                    }
                },
                Handler(Looper.getMainLooper())
            )

            virtualDisplay = projection?.createVirtualDisplay(
                "KalizeScreenshot",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                Handler(Looper.getMainLooper())
            )

            if (virtualDisplay == null) {
                finishCapture()
            }

        } catch (error: Exception) {
            android.util.Log.e(
                "KalizeScreenshot",
                "Could not capture screenshot",
                error
            )
            finishCapture()
        }

        return START_NOT_STICKY
    }

    private fun saveScreenshot(bitmap: Bitmap) {
        val filename = "Kalize_Screenshot_${System.currentTimeMillis()}.png"

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
        ) ?: throw IOException("Could not create screenshot file")

        try {
            val output = contentResolver.openOutputStream(uri)
                ?: throw IOException("Could not open screenshot file")

            output.use {
                if (!bitmap.compress(
                        Bitmap.CompressFormat.PNG,
                        100,
                        it
                    )
                ) {
                    throw IOException("Could not encode screenshot")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val completed = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, completed, null, null)
            }
        } catch (error: Exception) {
            try {
                contentResolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            throw error
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screenshot capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when Ƙalize Recorder captures a screenshot."
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun finishCapture() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        finishCapture()
        super.onDestroy()
    }
}

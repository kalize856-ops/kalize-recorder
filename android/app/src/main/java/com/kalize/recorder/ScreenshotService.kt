
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
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenshotService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "kalize_screenshot_channel"
        private const val NOTIFICATION_ID = 2201
        private const val TAG = "ScreenshotService"
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var captureStarted = false

    private val projectionCallback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                releaseCaptureResources()
                stopSelf()
            }
        }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        workerThread = HandlerThread("KalizeScreenshotWorker").also {
            it.start()
        }
        workerHandler = Handler(workerThread!!.looper)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (captureStarted) {
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(
            EXTRA_RESULT_CODE,
            Int.MIN_VALUE
        ) ?: Int.MIN_VALUE

        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            }

        if (resultCode == Int.MIN_VALUE || resultData == null) {
            Log.e(TAG, "Missing screen-capture permission data.")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            startAsForegroundService()

            val manager = getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            projection = manager.getMediaProjection(
                resultCode,
                resultData
            )

            if (projection == null) {
                Log.e(TAG, "Unable to obtain MediaProjection.")
                stopSelf(startId)
                return START_NOT_STICKY
            }

            projection!!.registerCallback(
                projectionCallback,
                workerHandler
            )

            captureStarted = true
            startCapture()

        } catch (e: Exception) {
            Log.e(TAG, "Screenshot capture failed to start.", e)
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    private fun startAsForegroundService() {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Kalize Screenshot Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when screenshot capture is active."
            }

            val manager = getSystemService(
                NotificationManager::class.java
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

        return builder
            .setContentTitle("Kalize Recorder")
            .setContentText("Capturing your requested screenshot.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun getScreenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = getSystemService(
                WINDOW_SERVICE
            ) as WindowManager
            val bounds = manager.currentWindowMetrics.bounds

            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            val display = (getSystemService(
                WINDOW_SERVICE
            ) as WindowManager).defaultDisplay

            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
        }

        return metrics
    }

    private fun startCapture() {
        val metrics = getScreenMetrics()
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )

        imageReader!!.setOnImageAvailableListener(
            { reader ->
                val image = reader.acquireLatestImage()
                    ?: return@setOnImageAvailableListener

                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding =
                        rowStride - pixelStride * width

                    val paddedBitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )

                    paddedBitmap.copyPixelsFromBuffer(buffer)

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

                    if (saved) {
                        Log.i(TAG, "Screenshot saved successfully.")
                    } else {
                        Log.e(TAG, "Unable to save screenshot.")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error processing screenshot.", e)
                } finally {
                    image.close()
                    projection?.stop()
                }
            },
            workerHandler
        )

        virtualDisplay = projection!!.createVirtualDisplay(
            "KalizeScreenshotDisplay",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            workerHandler
        )
    }

    private fun saveScreenshot(bitmap: Bitmap): Boolean {
        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

        val filename = "Kalize_Screenshot_$timestamp.png"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES +
                            "/Kalize Recorder"
                    )
                    put(
                        MediaStore.Images.Media.IS_PENDING,
                        1
                    )
                }

                val resolver = contentResolver
                val uri: Uri? = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                ) ?: return false

                try {
                    val output = resolver.openOutputStream(uri)
                        ?: return false

                    output.use {
                        bitmap.compress(
                            Bitmap.CompressFormat.PNG,
                            100,
                            it
                        )
                    }

                    values.clear()
                    values.put(
                        MediaStore.Images.Media.IS_PENDING,
                        0
                    )
                    resolver.update(uri, values, null, null)
                    true

                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }

            } else {
                @Suppress("DEPRECATION")
                val pictures = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES
                )

                val folder = File(pictures, "Kalize Recorder")
                if (!folder.exists() && !folder.mkdirs()) {
                    return false
                }

                val file = File(folder, filename)

                FileOutputStream(file).use {
                    bitmap.compress(
                        Bitmap.CompressFormat.PNG,
                        100,
                        it
                    )
                }

                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot.", e)
            false
        }
    }

    private fun releaseCaptureResources() {
        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null

        projection?.unregisterCallback(projectionCallback)
        projection = null

        captureStarted = false
    }

    override fun onDestroy() {
        releaseCaptureResources()

        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

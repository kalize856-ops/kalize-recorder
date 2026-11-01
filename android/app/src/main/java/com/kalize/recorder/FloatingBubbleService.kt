
package com.kalize.recorder

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import kotlin.math.abs

class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var controlPanel: LinearLayout? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (controlPanel == null) {
            showControls()
        }

        return START_NOT_STICKY
    }

    private fun showControls() {
        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(6, 6, 6, 6)

            background = GradientDrawable().apply {
                setColor(Color.rgb(30, 30, 38))
                cornerRadius = 28f
                setStroke(2, Color.WHITE)
            }

            elevation = 12f
            contentDescription = "Floating recording controls"
        }

        val openButton = Button(this).apply {
            text = "OPEN"
            textSize = 12f
            isAllCaps = false
            contentDescription = "Open Kalize Recorder"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(45, 115, 210))
        }

        val stopButton = Button(this).apply {
            text = "■ STOP"
            textSize = 12f
            isAllCaps = false
            contentDescription = "Stop screen recording"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(210, 45, 60))
        }

        panel.addView(
            openButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        panel.addView(
            stopButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 250
        }

        openButton.setOnClickListener {
            val launchIntent =
                packageManager.getLaunchIntentForPackage(packageName)

            launchIntent?.apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                startActivity(this)
            }
        }

        stopButton.setOnClickListener {
            val stopIntent = Intent(
                this,
                ScreenRecordingService::class.java
            ).apply {
                action = ScreenRecordingService.ACTION_STOP
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(stopIntent)
                } else {
                    startService(stopIntent)
                }
            } catch (_: Exception) {
                // Avoid crashing if Android rejects the stop request.
            }

            stopSelf()
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        panel.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()

                    if (abs(dx) > 8 || abs(dy) > 8) {
                        moved = true
                    }

                    val screenWidth =
                        resources.displayMetrics.widthPixels
                    val screenHeight =
                        resources.displayMetrics.heightPixels

                    val maxX =
                        (screenWidth - view.width).coerceAtLeast(0)
                    val maxY =
                        (screenHeight - view.height).coerceAtLeast(0)

                    params.x = (startX + dx).coerceIn(0, maxX)
                    params.y = (startY + dy).coerceIn(0, maxY)

                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) {
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        val screenWidth =
                            resources.displayMetrics.widthPixels
                        val maxX =
                            (screenWidth - view.width).coerceAtLeast(0)

                        params.x =
                            if (params.x + view.width / 2 <
                                screenWidth / 2
                            ) {
                                0
                            } else {
                                maxX
                            }

                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (_: Exception) {
                        }
                    }

                    true
                }

                else -> false
            }
        }

        try {
            windowManager.addView(panel, params)
            controlPanel = panel
        } catch (_: Exception) {
            controlPanel = null
            stopSelf()
        }
    }

    override fun onDestroy() {
        controlPanel?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }

        controlPanel = null
        super.onDestroy()
    }
}

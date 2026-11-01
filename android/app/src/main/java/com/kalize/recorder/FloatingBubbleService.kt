
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
                setColor(Color.rgb(24, 26, 38))
                cornerRadius = 28f
                setStroke(2, Color.rgb(150, 125, 255))
            }

            elevation = 12f
            contentDescription = "Floating recording controls"
        }

        val openButton = makeControlButton(
            "OPEN",
            Color.rgb(45, 115, 210),
            "Open Kalize Recorder"
        )

        val stopButton = makeControlButton(
            "■ STOP",
            Color.rgb(210, 45, 60),
            "Stop screen recording"
        )

        val hideButton = makeControlButton(
            "HIDE",
            Color.rgb(80, 70, 110),
            "Hide floating controls without stopping recording"
        )

        panel.addView(openButton)
        panel.addView(stopButton)
        panel.addView(hideButton)

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
            x = 12
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
                // Keep the app from crashing if Android rejects the request.
            }

            stopSelf()
        }

        hideButton.setOnClickListener {
            // Hide only the floating panel.
            // The independent recording service keeps running.
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
                        // The overlay may already have been removed.
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
                            // Ignore layout updates after removal.
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

    private fun makeControlButton(
        label: String,
        color: Int,
        description: String
    ): Button {
        return Button(this).apply {
            text = label
            textSize = 10f
            isAllCaps = false
            contentDescription = description
            setTextColor(Color.WHITE)
            setBackgroundColor(color)
            minWidth = 0
            minimumWidth = 0
            setPadding(8, 4, 8, 4)
        }
    }

    override fun onDestroy() {
        controlPanel?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
                // Ignore if Android already removed the overlay.
            }
        }

        controlPanel = null
        super.onDestroy()
    }
}

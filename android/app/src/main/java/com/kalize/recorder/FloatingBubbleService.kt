
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
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubble: TextView? = null

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

        if (bubble == null) {
            showBubble()
        }

        return START_NOT_STICKY
    }

    private fun showBubble() {
        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        val button = TextView(this).apply {
            text = "■ STOP"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(18, 12, 18, 12)

            background = GradientDrawable().apply {
                setColor(Color.rgb(220, 45, 65))
                cornerRadius = 60f
                setStroke(2, Color.WHITE)
            }

            elevation = 12f
        }

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
            x = 40
            y = 250
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        button.setOnTouchListener { view, event ->
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

                    params.x = startX + dx
                    params.y = startY + dy

                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) {
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        val stopIntent = Intent(
                            this,
                            ScreenRecordingService::class.java
                        ).apply {
                            action =
                                ScreenRecordingService.ACTION_STOP
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(stopIntent)
                        } else {
                            startService(stopIntent)
                        }

                        stopSelf()
                    }
                    true
                }

                else -> false
            }
        }

        try {
            windowManager.addView(button, params)
            bubble = button
        } catch (_: Exception) {
            bubble = null
            stopSelf()
        }
    }

    override fun onDestroy() {
        bubble?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }

        bubble = null
        super.onDestroy()
    }
}

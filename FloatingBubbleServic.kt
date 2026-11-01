
package com.kalize.recorder

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

class FloatingBubbleService : Service() {
    private lateinit var windowManager: WindowManager
    private var bubbleView: TextView? = null

    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val bubble = TextView(this).apply {
            text = "■ STOP"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(22, 14, 22, 14)
            background = GradientDrawable().apply {
                setColor(Color.rgb(126, 87, 255))
                cornerRadius = 100f
                setStroke(2, Color.WHITE)
            }
            contentDescription = "Tap to stop recording or drag to move"
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
            x = 24
            y = 220
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        bubble.setOnTouchListener { view: View, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()

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
                        startService(
                            Intent(
                                this,
                                ScreenRecordingService::class.java
                            ).apply {
                                action = ScreenRecordingService.ACTION_STOP
                            }
                        )
                        stopSelf()
                    }
                    true
                }

                else -> true
            }
        }

        try {
            windowManager.addView(bubble, params)
            bubbleView = bubble
        } catch (_: Exception) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        bubbleView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

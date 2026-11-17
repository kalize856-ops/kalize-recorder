package com.kalize.recorder

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class FloatingBubbleService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var root: LinearLayout
    private lateinit var bubble: TextView
    private var expanded = false
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        params = WindowManager.LayoutParams(
            dp(58), dp(58), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val saved = getSharedPreferences("kalize_bubble_position", MODE_PRIVATE)
            x = saved.getInt("x", dp(12))
            y = saved.getInt("y", dp(220))
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(0xEE151B2B.toInt(), 22)
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }
        bubble = TextView(this).apply {
            text = "K"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(0xFF7659EA.toInt(), 30)
            setOnTouchListener { _, event ->
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
                        if (abs(dx) > dp(5) || abs(dy) > dp(5)) moved = true
                        if (moved) {
                            params.x = (startX + dx).coerceAtLeast(0)
                            params.y = (startY + dy).coerceAtLeast(0)
                            updateWindow()
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) togglePanel()
                        getSharedPreferences("kalize_bubble_position", MODE_PRIVATE).edit()
                            .putInt("x", params.x).putInt("y", params.y).apply()
                        true
                    }
                    else -> true
                }
            }
        }
        root.addView(bubble, LinearLayout.LayoutParams(dp(44), dp(44)))
        try {
            windowManager.addView(root, params)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun togglePanel() {
        expanded = !expanded
        root.removeAllViews()
        root.addView(bubble, LinearLayout.LayoutParams(dp(44), dp(44)))
        if (expanded) {
            addAction("Start recording", 0xFF7659EA.toInt()) {
                openMain(MainActivity.ACTION_RECORD)
            }
            addAction("Take screenshot", 0xFF29344A.toInt()) {
                openMain(MainActivity.ACTION_SCREENSHOT)
            }
            addAction("Stop recording", 0xFF29344A.toInt()) {
                startService(Intent(this, RecorderService::class.java).setAction(RecorderService.STOP))
            }
            addAction("Open Kalize", 0xFF29344A.toInt()) {
                openMain(null)
            }
            addAction("Collapse controls", 0xFF29344A.toInt()) {
                togglePanel()
            }
            params.width = dp(184)
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            params.width = dp(58)
            params.height = dp(58)
        }
        updateWindow()
    }

    private fun openMain(action: String?) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (action != null) intent.action = action
        startActivity(intent)
    }

    private fun addAction(label: String, color: Int, action: () -> Unit) {
        val item = TextView(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(color, 14)
            setPadding(dp(8), dp(11), dp(8), dp(11))
            setOnClickListener { action() }
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6)
        root.addView(item, lp)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun updateWindow() {
        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: Exception) {
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        try {
            windowManager.removeView(root)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

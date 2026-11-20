package com.kalize.recorder

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

/**
 * Small transparent FPS overlay. It samples Choreographer frame callbacks,
 * which is a device/UI frame-cadence estimate rather than private game FPS data.
 */
class FpsCounterOverlayService : Service(), Choreographer.FrameCallback {
    private lateinit var windowManager: WindowManager
    private lateinit var label: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var attached = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var frameCount = 0
    private var lastSampleNanos = 0L
    private var lastFrameNanos = 0L
    private var displayedFps = 0
    private var deleteMode = false
    private var deleteTarget: TextView? = null
    private val choreographer by lazy { Choreographer.getInstance() }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences(POSITION_PREFS, MODE_PRIVATE)
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("fps_x", dp(16))
            y = prefs.getInt("fps_y", dp(120))
        }
        label = TextView(this).apply {
            text = "FPS --"
            setTextColor(Color.WHITE)
            textSize = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
                .getInt("fps_counter_size", 12).coerceIn(9, 22).toFloat()
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
            alpha = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
                .getInt("fps_counter_opacity", 75).coerceIn(15, 100) / 100f
            setPadding(dp(2), dp(1), dp(2), dp(1))
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Draggable FPS counter"
            setOnTouchListener { _, event -> handleTouch(event) }
        }
        try {
            windowManager.addView(label, params)
            attached = true
            choreographer.postFrameCallback(this)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun doFrame(frameTimeNanos: Long) {
        if (!attached) return
        if (lastFrameNanos != 0L) frameCount++
        lastFrameNanos = frameTimeNanos
        if (lastSampleNanos == 0L) lastSampleNanos = frameTimeNanos
        val elapsed = frameTimeNanos - lastSampleNanos
        if (elapsed >= SAMPLE_INTERVAL_NANOS) {
            displayedFps = if (elapsed > 0L) ((frameCount * 1_000_000_000.0) / elapsed).toInt() else 0
            label.text = if (displayedFps > 0) "FPS $displayedFps" else "FPS --"
            frameCount = 0
            lastSampleNanos = frameTimeNanos
        }
        choreographer.postFrameCallback(this)
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = params.x
                startY = params.y
                deleteMode = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!deleteMode && event.eventTime - event.downTime >= android.view.ViewConfiguration.getLongPressTimeout()) {
                    deleteMode = true
                    showDeleteTarget()
                }
                params.x = (startX + (event.rawX - downRawX).toInt()).coerceAtLeast(0)
                params.y = (startY + (event.rawY - downRawY).toInt()).coerceAtLeast(0)
                try { windowManager.updateViewLayout(label, params) } catch (_: Exception) { }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (deleteMode && isCounterOverDeleteTarget()) {
                    hideDeleteTarget()
                    stopSelf()
                    return true
                }
                hideDeleteTarget()
                deleteMode = false
                getSharedPreferences(POSITION_PREFS, MODE_PRIVATE).edit()
                    .putInt("fps_x", params.x)
                    .putInt("fps_y", params.y)
                    .apply()
                return true
            }
        }
        return true
    }

    private fun showDeleteTarget() {
        if (deleteTarget != null) return
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val target = TextView(this).apply {
            text = "×"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFE53935.toInt())
                cornerRadius = dp(40).toFloat()
            }
            elevation = dp(12).toFloat()
            contentDescription = "Drop here to dismiss FPS counter"
        }
        val lp = WindowManager.LayoutParams(dp(64), dp(64), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - dp(64)) / 2
            y = (resources.displayMetrics.heightPixels - dp(150)).coerceAtLeast(0)
        }
        try { windowManager.addView(target, lp); deleteTarget = target } catch (_: Exception) { }
    }

    private fun hideDeleteTarget() {
        deleteTarget?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        deleteTarget = null
    }

    private fun isCounterOverDeleteTarget(): Boolean {
        val cx = params.x + label.width / 2
        val cy = params.y + label.height / 2
        val tx = resources.displayMetrics.widthPixels / 2
        val ty = resources.displayMetrics.heightPixels - dp(118)
        return kotlin.math.hypot((cx - tx).toDouble(), (cy - ty).toDouble()) < dp(82)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        hideDeleteTarget()
        choreographer.removeFrameCallback(this)
        if (attached) {
            try { windowManager.removeView(label) } catch (_: Exception) { }
        }
        attached = false
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Do not keep or recreate the FPS overlay after the app task is dismissed.
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val SETTINGS_PREFS = "kalize_settings"
        private const val POSITION_PREFS = "kalize_bubble_position"
        private const val SAMPLE_INTERVAL_NANOS = 1_000_000_000L
    }
}

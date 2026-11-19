package com.kalize.recorder

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

/**
 * On-screen annotation layer. Drawings are rendered by Android as a normal
 * overlay, so they can appear in screen captures when the device permits it.
 * Close the layer to return all touch input to the app underneath.
 */
class AnnotationOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var root: FrameLayout
    private lateinit var drawing: DrawingSurface
    private var erasing = false
    private var colorIndex = 0

    private val colors = intArrayOf(
        Color.RED, Color.YELLOW, Color.CYAN, Color.GREEN, Color.WHITE, Color.MAGENTA
    )

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        drawing = DrawingSurface()
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(drawing, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(createToolbar(), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ))
        }

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        try {
            windowManager.addView(root, params)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun createToolbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xEE171F32.toInt())
                cornerRadius = dp(18).toFloat()
            }
        }
        fun tool(label: String, action: () -> Unit) {
            bar.addView(Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)
            ))
        }
        tool("Color") {
            colorIndex = (colorIndex + 1) % colors.size
            erasing = false
            drawing.setBrush(colors[colorIndex], false)
        }
        tool("Eraser") {
            erasing = !erasing
            drawing.setBrush(colors[colorIndex], erasing)
        }
        tool("Undo") { drawing.undo() }
        tool("Clear") { drawing.clear() }
        tool("Done") { stopSelf() }
        return bar
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (::root.isInitialized) {
            try { windowManager.removeView(root) } catch (_: Exception) { }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private inner class DrawingSurface : View(this@AnnotationOverlayService) {
        private data class Stroke(val path: Path, val color: Int, val width: Float, val clear: Boolean)
        private val strokes = mutableListOf<Stroke>()
        private var activePath: Path? = null
        private var activeColor = Color.RED
        private var activeWidth = dp(4).toFloat()
        private var clearMode = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        init { setLayerType(View.LAYER_TYPE_SOFTWARE, null) }

        fun setBrush(color: Int, erase: Boolean) {
            activeColor = color
            clearMode = erase
            activeWidth = if (erase) dp(24).toFloat() else dp(4).toFloat()
        }

        fun undo() {
            if (strokes.isNotEmpty()) {
                strokes.removeAt(strokes.lastIndex)
                invalidate()
            }
        }

        fun clear() {
            strokes.clear()
            activePath = null
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            strokes.forEach { stroke ->
                paint.color = stroke.color
                paint.strokeWidth = stroke.width
                paint.xfermode = if (stroke.clear)
                    android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                else null
                canvas.drawPath(stroke.path, paint)
            }
            activePath?.let { path ->
                paint.color = activeColor
                paint.strokeWidth = activeWidth
                paint.xfermode = if (clearMode)
                    android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                else null
                canvas.drawPath(path, paint)
                paint.xfermode = null
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activePath = Path().apply { moveTo(event.x, event.y) }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    activePath?.lineTo(event.x, event.y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activePath?.let { path ->
                        strokes += Stroke(Path(path), activeColor, activeWidth, clearMode)
                    }
                    activePath = null
                    invalidate()
                    return true
                }
            }
            return true
        }
    }
}

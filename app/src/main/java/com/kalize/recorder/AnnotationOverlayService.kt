package com.kalize.recorder

import android.app.AlertDialog
import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar

private data class AnnotationStroke(
    val path: Path?, val color: Int, val width: Float, val clear: Boolean = false,
    val text: String? = null, val x: Float = 0f, val y: Float = 0f, val textSize: Float = 0f
)

class AnnotationOverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var root: FrameLayout
    private lateinit var drawing: DrawingSurface
    private var color = Color.RED
    private var mode = Mode.PEN
    private var widthIndex = 1
    private var highlight = false
    private var erase = false
    private var highlightAlpha = 102
    private var pendingText: String? = null
    private val widths = floatArrayOf(2f, 4f, 7f, 11f)
    private enum class Mode { PEN, LINE, ARROW, RECT, CIRCLE, TEXT }
    private val presets = intArrayOf(Color.RED, Color.YELLOW, Color.CYAN, Color.GREEN,
        Color.WHITE, Color.MAGENTA, Color.BLUE, Color.BLACK, 0xFFFF9800.toInt(),
        0xFF00BCD4.toInt(), 0xFF9C27B0.toInt(), 0xFFFF4081.toInt())

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        drawing = DrawingSurface()
        root = FrameLayout(this).apply {
            addView(drawing, FrameLayout.LayoutParams(-1, -1))
            addView(toolbar(), FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(-1, -1, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        try { wm.addView(root, lp) } catch (_: Exception) { stopSelf() }
    }

    private fun toolbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(5), dp(6), dp(5))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xF0182032.toInt()); cornerRadius = dp(16).toFloat()
            }
        }
        fun add(label: String, action: () -> Unit) {
            bar.addView(Button(this).apply {
                text = label; textSize = 11f; isAllCaps = false; minWidth = dp(48)
                setPadding(dp(7), 0, dp(7), 0); setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-2, dp(42)).apply { marginEnd = dp(3) })
        }
        add("Pen") { setMode(Mode.PEN) }
        add("Line") { setMode(Mode.LINE) }
        add("Arrow") { setMode(Mode.ARROW) }
        add("Rect") { setMode(Mode.RECT) }
        add("Circle") { setMode(Mode.CIRCLE) }
        add("Text") { askText() }
        add("Colour") { askColor() }
        add("Size") { widthIndex = (widthIndex + 1) % widths.size; drawing.configure(width = dp(widths[widthIndex]).toFloat()) }
        add("Highlight") { highlight = !highlight; erase = false; drawing.configure(highlight = highlight, alpha = highlightAlpha) }
        add("Opacity") { askOpacity() }
        add("Eraser") { erase = !erase; highlight = false; drawing.configure(erase = erase) }
        add("Undo") { drawing.undo() }
        add("Clear") { drawing.clear() }
        add("Done") { stopSelf() }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar, HorizontalScrollView.LayoutParams(-2, -2))
        }
    }

    private fun setMode(next: Mode) {
        mode = next; erase = false; highlight = false
        drawing.configure(erase = false, highlight = false)
    }

    private fun askText() {
        val input = EditText(this).apply {
            hint = "Type annotation"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        AlertDialog.Builder(this).setTitle("Add text").setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Place text") { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let {
                    pendingText = it; setMode(Mode.TEXT)
                }
            }.show()
    }

    private fun askColor() {
        val input = EditText(this).apply { hint = "#FF4081"; setText(String.format("#%06X", 0xFFFFFF and color)) }
        AlertDialog.Builder(this).setTitle("Custom brush colour")
            .setMessage("Enter a hex colour, or choose a preset.")
            .setView(input)
            .setItems(presets.mapIndexed { i, c -> "Preset ${i + 1}  #${String.format("%06X", 0xFFFFFF and c)}" }.toTypedArray()) { _, i ->
                color = presets[i]; drawing.configure(color = color)
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use colour") { _, _ ->
                runCatching { Color.parseColor(input.text.toString().trim()) }.onSuccess {
                    color = it; drawing.configure(color = color)
                }
            }.show()
    }

    private fun askOpacity() {
        val seek = SeekBar(this).apply { max = 100; progress = highlightAlpha * 100 / 255 }
        val box = LinearLayout(this).apply {
            setPadding(dp(22), dp(8), dp(22), 0); addView(seek)
        }
        AlertDialog.Builder(this).setTitle("Highlighter transparency").setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                highlightAlpha = (seek.progress * 255 / 100).coerceIn(10, 255)
                drawing.configure(highlight = highlight, alpha = highlightAlpha)
            }.show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        if (::root.isInitialized) try { wm.removeView(root) } catch (_: Exception) { }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private inner class DrawingSurface : View(this@AnnotationOverlayService) {
        private val strokes = mutableListOf<AnnotationStroke>()
        private var active: Path? = null
        private var sx = 0f; private var sy = 0f; private var ex = 0f; private var ey = 0f
        private var brushColor = Color.RED
        private var brushWidth = dp(4f).toFloat()
        private var clearing = false; private var highlighting = false; private var alpha = 102
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        init { setLayerType(View.LAYER_TYPE_SOFTWARE, null) }

        fun configure(color: Int = brushColor, erase: Boolean = clearing,
                      highlight: Boolean = highlighting, width: Float = brushWidth, alpha: Int = this.alpha) {
            brushColor = color; clearing = erase; highlighting = highlight
            brushWidth = if (erase) dp(24f).toFloat() else width; this.alpha = alpha; invalidate()
        }
        fun undo() { if (strokes.isNotEmpty()) { strokes.removeAt(strokes.lastIndex); invalidate() } }
        fun clear() { strokes.clear(); active = null; invalidate() }

        private fun shape(): Path {
            val p = Path()
            when (mode) {
                Mode.LINE -> { p.moveTo(sx, sy); p.lineTo(ex, ey) }
                Mode.ARROW -> {
                    p.moveTo(sx, sy); p.lineTo(ex, ey)
                    val a = kotlin.math.atan2((ey-sy).toDouble(), (ex-sx).toDouble())
                    val h = dp(18f).toFloat()
                    for (d in listOf(-Math.PI/6, Math.PI/6)) {
                        p.moveTo(ex, ey)
                        p.lineTo((ex-h*kotlin.math.cos(a+d)).toFloat(), (ey-h*kotlin.math.sin(a+d)).toFloat())
                    }
                }
                Mode.RECT -> p.addRect(RectF(sx, sy, ex, ey), Path.Direction.CW)
                Mode.CIRCLE -> p.addOval(RectF(sx, sy, ex, ey), Path.Direction.CW)
                else -> { p.moveTo(sx, sy); p.lineTo(ex, ey) }
            }
            return p
        }

        private fun draw(canvas: Canvas, s: AnnotationStroke) {
            paint.xfermode = if (s.clear) android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) else null
            paint.color = if (s.clear) Color.TRANSPARENT else s.color
            paint.strokeWidth = s.width; paint.style = Paint.Style.STROKE
            s.path?.let { canvas.drawPath(it, paint) }
            s.text?.let {
                paint.xfermode = null; paint.style = Paint.Style.FILL; paint.color = s.color
                paint.textSize = s.textSize; canvas.drawText(it, s.x, s.y, paint)
            }
            paint.style = Paint.Style.STROKE; paint.xfermode = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            strokes.forEach { draw(canvas, it) }
            active?.let {
                val p = if (mode == Mode.PEN) it else shape()
                draw(canvas, AnnotationStroke(p, if (highlighting) (brushColor and 0x00FFFFFF) or (alpha shl 24) else brushColor, brushWidth, clearing))
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.x; sy = e.y; ex = sx; ey = sy
                    if (mode == Mode.TEXT) {
                        pendingText?.let { strokes.add(AnnotationStroke(null, brushColor, brushWidth,
                            text = it, x = e.x, y = e.y, textSize = dp(22f).toFloat())) }
                        pendingText = null; mode = Mode.PEN; invalidate(); return true
                    }
                    active = Path().apply { moveTo(e.x, e.y) }; invalidate(); return true
                }
                MotionEvent.ACTION_MOVE -> {
                    ex = e.x; ey = e.y
                    if (mode == Mode.PEN) active?.lineTo(e.x, e.y)
                    invalidate(); return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ex = e.x; ey = e.y
                    active?.let { val p = if (mode == Mode.PEN) Path(it) else shape()
                        val c = if (highlighting) (brushColor and 0x00FFFFFF) or (alpha shl 24) else brushColor
                        strokes.add(AnnotationStroke(p, c, brushWidth, clearing)) }
                    active = null; invalidate(); return true
                }
            }
            return true
        }
    }
}
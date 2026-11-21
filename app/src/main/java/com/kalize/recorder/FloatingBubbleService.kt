package com.kalize.recorder

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Compact XRecorder-style radial controls for Kalize.
 *
 * The main K bubble expands into independent circular action bubbles.
 * The overlay is marked FLAG_SECURE so its controls are excluded from
 * screenshots and screen captures where Android honours secure overlays.
 */
class FloatingBubbleService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var root: FrameLayout
    private lateinit var bubble: TextView

    private var expanded = false
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false
    private var deleteMode = false
    private var deleteTarget: TextView? = null
    private var savedX = 0
    private var savedY = 0
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleRestTask = Runnable { restAtNearestCorner() }

    private val collapsedSize get() = dp(getSharedPreferences("kalize_settings", MODE_PRIVATE).getInt("bubble_size", 58).coerceIn(44, 68))
    private val expandedSize get() = dp(248)
    private val bubbleOpacity get() = getSharedPreferences("kalize_settings", MODE_PRIVATE).getInt("bubble_opacity", 100).coerceIn(50, 100) / 100f
    private val expandedOffset get() = (expandedSize - collapsedSize) / 2

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            HIDE_FOR_CAPTURE -> if (::root.isInitialized) root.visibility = android.view.View.GONE
            SHOW_AFTER_CAPTURE -> if (::root.isInitialized) root.visibility = android.view.View.VISIBLE
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val preferences = getSharedPreferences("kalize_bubble_position", MODE_PRIVATE)
        savedX = preferences.getInt("x", dp(12))
        savedY = preferences.getInt("y", dp(220))

        params = WindowManager.LayoutParams(
            collapsedSize,
            collapsedSize,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        root = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            background = transparentBackground()
        }

        bubble = TextView(this).apply {
            text = "K"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(getSharedPreferences("kalize_settings", MODE_PRIVATE).getInt("bubble_color", 0xFF7659EA.toInt()), 30)
            elevation = dp(8).toFloat()
            alpha = bubbleOpacity
            contentDescription = "Kalize floating controls"
            setOnTouchListener { _, event -> handleBubbleTouch(event) }
        }

        root.addView(
            bubble,
            FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER)
        )

        try {
            windowManager.addView(root, params)
            scheduleIdleRest()
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun handleBubbleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                idleHandler.removeCallbacks(idleRestTask)
                bubble.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
                downX = event.rawX
                downY = event.rawY
                startX = params.x
                startY = params.y
                moved = false
                deleteMode = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downX).toInt()
                val dy = (event.rawY - downY).toInt()
                if (kotlin.math.abs(dx) > dp(5) || kotlin.math.abs(dy) > dp(5)) {
                    moved = true
                }
                if (moved && !expanded && !deleteMode && event.eventTime - event.downTime >= android.view.ViewConfiguration.getLongPressTimeout()) {
                    deleteMode = true
                    showDeleteTarget()
                }
                if (moved && !expanded) {
                    params.x = (startX + dx).coerceAtLeast(0)
                    params.y = (startY + dy).coerceAtLeast(0)
                    savedX = params.x
                    savedY = params.y
                    updateWindow()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (deleteMode && isBubbleOverDeleteTarget()) {
                    hideDeleteTarget()
                    stopSelf()
                    return true
                }
                hideDeleteTarget()
                deleteMode = false
                if (!moved) {
                    if (expanded) collapseControls() else expandControls()
                } else {
                    snapToNearestEdge()
                    savePosition()
                    scheduleIdleRest()
                }
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
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(0xFFE53935.toInt(), 48)
            elevation = dp(12).toFloat()
            contentDescription = "Drop here to dismiss floating bubble"
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

    private fun isBubbleOverDeleteTarget(): Boolean {
        val cx = params.x + collapsedSize / 2
        val cy = params.y + collapsedSize / 2
        val tx = resources.displayMetrics.widthPixels / 2
        val ty = resources.displayMetrics.heightPixels - dp(118)
        return kotlin.math.hypot((cx - tx).toDouble(), (cy - ty).toDouble()) < dp(82)
    }

    private fun expandControls() {
        if (expanded) return
        expanded = true

        root.removeAllViews()
        params.width = expandedSize
        params.height = expandedSize
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        params.x = (savedX + collapsedSize / 2 - expandedSize / 2)
            .coerceIn(0, (screenWidth - expandedSize).coerceAtLeast(0))
        params.y = (savedY + collapsedSize / 2 - expandedSize / 2)
            .coerceIn(0, (screenHeight - expandedSize).coerceAtLeast(0))

        // Eight evenly spaced mini-bubbles surround the main K bubble.
        // Tapping the centre K collapses the controls, so a separate close bubble is unnecessary.
        val actions = listOf(
            BubbleAction("●", "Start recording", 270f) {
                openMain(MainActivity.ACTION_RECORD)
            },
            BubbleAction("▣", "Take screenshot", 315f) {
                openMain(MainActivity.ACTION_SCREENSHOT)
            },
            BubbleAction("Ⅱ", "Pause or resume", 0f) {
                togglePauseResume()
            },
            BubbleAction("■", "Stop recording", 45f) {
                startService(Intent(this, RecorderService::class.java).setAction(RecorderService.STOP))
                collapseControls()
            },
            BubbleAction("✂", "Open video editor", 90f) {
                collapseControls()
                startActivity(Intent(this, VideoEditorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            BubbleAction("⚙", "Pro Studio settings", 135f) {
                collapseControls()
                startActivity(Intent(this, ProStudioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            BubbleAction("✎", "Draw on screen", 180f) {
                collapseControls()
                startService(Intent(this, AnnotationOverlayService::class.java))
            },
            BubbleAction("⌂", "Open Kalize", 225f) {
                openMain(null)
            }
        )

        val center = expandedSize / 2
        val radius = dp(88)
        actions.forEach { action ->
            val radians = action.angle * PI / 180.0
            val x = (center + cos(radians) * radius).toInt() - dp(21)
            val y = (center + sin(radians) * radius).toInt() - dp(21)
            val mini = TextView(this).apply {
                text = action.glyph
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                background = rounded(
                    if (action.label == "Start recording") 0xFFE94B45.toInt()
                    else getSharedPreferences("kalize_settings", MODE_PRIVATE).getInt("bubble_color", 0xFF29344A.toInt()),
                    28
                )
                elevation = dp(7).toFloat()
                alpha = bubbleOpacity
                contentDescription = action.label
                setOnClickListener { action.run() }
            }
            val lp = FrameLayout.LayoutParams(dp(42), dp(42))
            lp.leftMargin = x
            lp.topMargin = y
            root.addView(mini, lp)
        }

        bubble.text = "K"
        root.addView(bubble, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER))
        updateWindow()
    }

    private fun collapseControls() {
        if (!expanded) return
        expanded = false
        root.removeAllViews()
        params.width = collapsedSize
        params.height = collapsedSize
        params.x = savedX
        params.y = savedY
        bubble.text = "K"
        root.addView(bubble, FrameLayout.LayoutParams((collapsedSize * 0.8f).toInt(), (collapsedSize * 0.8f).toInt(), Gravity.CENTER))
        updateWindow()
    }

    private fun togglePauseResume() {
        val preferences = getSharedPreferences("kalize_bubble_position", MODE_PRIVATE)
        val paused = preferences.getBoolean("recording_paused", false)
        val nextAction = if (paused) RecorderService.RESUME else RecorderService.PAUSE
        startService(Intent(this, RecorderService::class.java).setAction(nextAction))
        // RecorderService owns the authoritative paused state and updates this preference
        // only after the encoder successfully pauses or resumes.
        collapseControls()
    }

    private fun openMain(action: String?) {
        collapseControls()
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (action != null) intent.action = action
        startActivity(intent)
    }

    /** After five seconds without interaction, gently shrink and park the bubble in a corner. */
    private fun scheduleIdleRest() {
        idleHandler.removeCallbacks(idleRestTask)
        if (!expanded) idleHandler.postDelayed(idleRestTask, 5_000L)
    }

    private fun restAtNearestCorner() {
        if (expanded || !::root.isInitialized || !::bubble.isInitialized) return
        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - collapsedSize).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - collapsedSize).coerceAtLeast(0)
        val goRight = savedX + collapsedSize / 2 >= metrics.widthPixels / 2
        val goBottom = savedY + collapsedSize / 2 >= metrics.heightPixels / 2
        savedX = if (goRight) maxX else 0
        savedY = if (goBottom) maxY else 0
        params.x = savedX
        params.y = savedY
        savePosition()
        updateWindow()
        bubble.animate().scaleX(0.88f).scaleY(0.88f).setDuration(260).start()
    }

    /** Snap the collapsed bubble to the nearest screen edge and keep it fully visible. */
    private fun snapToNearestEdge() {
        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - collapsedSize).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - collapsedSize).coerceAtLeast(0)
        val bubbleCenterX = savedX + collapsedSize / 2
        savedX = if (bubbleCenterX < metrics.widthPixels / 2) 0 else maxX
        savedY = savedY.coerceIn(0, maxY)
        params.x = savedX
        params.y = savedY
        updateWindow()
    }

    private fun savePosition() {
        getSharedPreferences("kalize_bubble_position", MODE_PRIVATE).edit()
            .putInt("x", savedX)
            .putInt("y", savedY)
            .apply()
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun transparentBackground() = GradientDrawable().apply {
        setColor(0x00000000)
    }

    private fun updateWindow() {
        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: Exception) {
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        idleHandler.removeCallbacks(idleRestTask)
        hideDeleteTarget()
        try {
            windowManager.removeView(root)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // A removed app task is an explicit exit: never resurrect the floating overlay.
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val HIDE_FOR_CAPTURE = "com.kalize.recorder.HIDE_BUBBLE_FOR_CAPTURE"
        const val SHOW_AFTER_CAPTURE = "com.kalize.recorder.SHOW_BUBBLE_AFTER_CAPTURE"
    }

    private data class BubbleAction(
        val glyph: String,
        val label: String,
        val angle: Float,
        val run: () -> Unit
    )
}

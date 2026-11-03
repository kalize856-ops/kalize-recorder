
package com.kalize.recorder

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class FloatingBubbleService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams

    private var panel: LinearLayout? = null
    private var miniBubble: TextView? = null
    private var activeView: View? = null

    private var pauseButton: Button? = null
    private var statusText: TextView? = null
    private var timerText: TextView? = null

    private var recording = false
    private var paused = false
    private var receiverRegistered = false

    private var recordingStartedAt = 0L
    private var elapsedBeforePause = 0L

    private val positionPrefs by lazy {
        getSharedPreferences("kalize_bubble_position", Context.MODE_PRIVATE)
    }

    private fun saveBubblePosition() {
        positionPrefs.edit()
            .putInt("bubble_x", params.x)
            .putInt("bubble_y", params.y)
            .apply()
    }

    private val handler = Handler(Looper.getMainLooper())

    private val purple = Color.rgb(139, 92, 246)
    private val palePurple = Color.rgb(205, 185, 255)
    private val red = Color.rgb(225, 55, 70)
    private val green = Color.rgb(40, 160, 105)
    private val blue = Color.rgb(55, 120, 220)
    private val orange = Color.rgb(220, 130, 45)
    private val amber = Color.rgb(235, 165, 55)

    private val timerRunnable = object : Runnable {
        override fun run() {
            updateTimer()
            handler.postDelayed(this, 1000L)
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScreenRecordingService.ACTION_STATE_CHANGED) return

            val newRecording = intent.getBooleanExtra(
                ScreenRecordingService.EXTRA_IS_RECORDING, false
            )
            val newPaused = intent.getBooleanExtra(
                ScreenRecordingService.EXTRA_IS_PAUSED, false
            )
            val now = SystemClock.elapsedRealtime()

            if (intent.hasExtra(ScreenRecordingService.EXTRA_ELAPSED_MILLIS)) {
                elapsedBeforePause = intent.getLongExtra(
                    ScreenRecordingService.EXTRA_ELAPSED_MILLIS, 0L
                ).coerceAtLeast(0L)

                recordingStartedAt =
                    if (newRecording && !newPaused) now else 0L
            } else {
                val wasRecording = recording
                val wasPaused = paused

                if (!wasRecording && newRecording) {
                    elapsedBeforePause = 0L
                    recordingStartedAt = if (newPaused) 0L else now
                } else if (wasRecording && !wasPaused && newPaused) {
                    if (recordingStartedAt > 0L) {
                        elapsedBeforePause +=
                            (now - recordingStartedAt).coerceAtLeast(0L)
                    }
                    recordingStartedAt = 0L
                } else if (wasRecording && wasPaused && !newPaused) {
                    recordingStartedAt = now
                } else if (wasRecording && !newRecording) {
                    elapsedBeforePause = 0L
                    recordingStartedAt = 0L
                }
            }

            recording = newRecording
            paused = newRecording && newPaused

            if (!recording) {
                elapsedBeforePause = 0L
                recordingStartedAt = 0L
            }

            updateControls()
            updateTimer()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        val filter = IntentFilter(ScreenRecordingService.ACTION_STATE_CHANGED)

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }

        receiverRegistered = true

        val prefs = getSharedPreferences(
            "kalize_recording_state", Context.MODE_PRIVATE
        )

        recording = prefs.getBoolean("is_recording", false)
        paused = prefs.getBoolean("is_paused", false)

        val savedElapsed = prefs.getLong(
            "recording_elapsed_ms", 0L
        ).coerceAtLeast(0L)

        val savedRealtime = prefs.getLong(
            "recording_elapsed_realtime_ms", 0L
        )

        if (!recording) {
            paused = false
            elapsedBeforePause = 0L
            recordingStartedAt = 0L
        } else if (paused) {
            elapsedBeforePause = savedElapsed
            recordingStartedAt = 0L
        } else {
            elapsedBeforePause = savedElapsed
            recordingStartedAt =
                if (savedRealtime > 0L) savedRealtime
                else SystemClock.elapsedRealtime()
        }

        handler.post(timerRunnable)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (panel == null && miniBubble == null) {
            showControls()
        }

        return START_NOT_STICKY
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun rounded(
        color: Int,
        radius: Int = 20,
        stroke: Int? = null
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(1), Color.argb(75, 255, 255, 255))
    }

    private fun makeButton(
        symbol: String,
        color: Int,
        description: String
    ) = Button(this).apply {
        text = symbol
        textSize = 18f
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTypeface(null, Typeface.BOLD)
        contentDescription = description
        background = circle(color)
        stateListAnimator = null
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(38)
        minimumHeight = dp(38)
        setPadding(0, 0, 0, 0)
    }

    private fun sendRecordingAction(action: String) {
        val intent = Intent(this, ScreenRecordingService::class.java).apply {
            this.action = action
        }

        try {
            startService(intent)
        } catch (_: Exception) {
            Toast.makeText(
                this, "Recording command could not be sent.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun openScreenshotCapture() {
        try {
            val intent = Intent(this, QuickScreenshotActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(
                this, "Could not open screenshot permission prompt.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openScreenshotGallery() {
        try {
            val intent = Intent(this, ScreenshotGalleryActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(
                this, "Could not open screenshot gallery.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun openMainApp() {
        try {
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                it.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                startActivity(it)
            }
        } catch (_: Exception) {
            Toast.makeText(
                this, "Could not open Kalize Recorder.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showControls() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val overlayType =
            if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val panelWidth = (screenWidth - dp(20)).coerceAtMost(dp(286))
        val buttonWidth = ((panelWidth - dp(30)) / 6).coerceAtLeast(dp(32))

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START

            val savedX = positionPrefs.getInt("bubble_x", dp(12))
            val savedY = positionPrefs.getInt("bubble_y", dp(220))

            x = savedX.coerceIn(
                0, (screenWidth - dp(60)).coerceAtLeast(0)
            )
            y = savedY.coerceIn(
                0, (screenHeight - dp(60)).coerceAtLeast(0)
            )

            if (Build.VERSION.SDK_INT >= 31) {
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                setBlurBehindRadius(dp(28))
            }
        }

        val mainPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(9), dp(6), dp(10))
            background = rounded(
                Color.argb(238, 24, 22, 36), 23, purple
            )
            elevation = dp(12).toFloat()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val logo = TextView(this).apply {
            text = "K"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = circle(Color.rgb(105, 65, 195))
        }

        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(5), 0)
        }

        val title = TextView(this).apply {
            text = "KALIZE RECORDER"
            textSize = 11f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }

        val status = TextView(this).apply {
            text = "● READY"
            textSize = 10f
            setTextColor(palePurple)
        }

        val timer = TextView(this).apply {
            text = "00:00"
            textSize = 11f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }

        statusText = status
        timerText = timer

        titleColumn.addView(title)
        titleColumn.addView(status)
        titleColumn.addView(timer)

        val dragHandle = TextView(this).apply {
            text = "⠿"
            textSize = 25f
            setTextColor(palePurple)
            gravity = Gravity.CENTER
            contentDescription = "Drag floating controls"
        }

        header.addView(logo, LinearLayout.LayoutParams(dp(35), dp(35)))
        header.addView(
            titleColumn,
            LinearLayout.LayoutParams(0, dp(55), 1f)
        )
        header.addView(
            dragHandle,
            LinearLayout.LayoutParams(dp(35), dp(45))
        )

        val divider = View(this).apply {
            setBackgroundColor(Color.argb(55, 255, 255, 255))
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, 0)
        }

        val openButton = makeButton("⌂", blue, "Open Kalize Recorder")
        val screenshotButton = makeButton("📷", purple, "Capture screenshot")
        val galleryButton = makeButton("▧", orange, "Open screenshot gallery")
        val localPauseButton = makeButton("Ⅱ", green, "Pause or resume recording")
        val stopButton = makeButton("■", red, "Stop recording")
        val minimizeButton = makeButton(
            "−", Color.rgb(90, 70, 130), "Minimize controls"
        )

        pauseButton = localPauseButton

        listOf(
            openButton,
            screenshotButton,
            galleryButton,
            localPauseButton,
            stopButton,
            minimizeButton
        ).forEach { button ->
            controls.addView(
                button,
                LinearLayout.LayoutParams(
                    buttonWidth,
                    dp(38)
                ).apply {
                    marginStart = dp(1)
                    marginEnd = dp(1)
                }
            )
        }

        mainPanel.addView(
            header,
            LinearLayout.LayoutParams(panelWidth, dp(55))
        )

        mainPanel.addView(
            divider,
            LinearLayout.LayoutParams(panelWidth, dp(1)).apply {
                topMargin = dp(7)
            }
        )

        mainPanel.addView(controls)

        val bubble = TextView(this).apply {
            text = "K"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            elevation = dp(9).toFloat()
            contentDescription = "Expand Kalize controls"
        }

        panel = mainPanel
        miniBubble = bubble
        activeView = mainPanel

        openButton.setOnClickListener { openMainApp() }
        screenshotButton.setOnClickListener { openScreenshotCapture() }
        galleryButton.setOnClickListener { openScreenshotGallery() }

        localPauseButton.setOnClickListener {
            val prefs = getSharedPreferences(
                "kalize_recording_state", Context.MODE_PRIVATE
            )

            recording = prefs.getBoolean("is_recording", false)
            paused = prefs.getBoolean("is_paused", false)

            if (!recording) {
                updateControls()
                return@setOnClickListener
            }

            sendRecordingAction(
                if (paused) ScreenRecordingService.ACTION_RESUME
                else ScreenRecordingService.ACTION_PAUSE
            )
        }

        stopButton.setOnClickListener {
            sendRecordingAction(ScreenRecordingService.ACTION_STOP)
            stopSelf()
        }

        minimizeButton.setOnClickListener { minimizeControls() }

        attachDrag(dragHandle)
        attachDrag(bubble) { expandControls() }

        try {
            wm.addView(mainPanel, params)
        } catch (_: Exception) {
            stopSelf()
            return
        }

        updateControls()
        updateTimer()
    }

    private fun updateBubbleAppearance() {
        val bubble = miniBubble ?: return

        val stateColor = when {
            recording && !paused -> red
            recording && paused -> amber
            else -> Color.rgb(95, 58, 175)
        }

        val borderColor = when {
            recording && !paused -> Color.rgb(255, 155, 165)
            recording && paused -> Color.rgb(255, 220, 145)
            else -> palePurple
        }

        bubble.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(stateColor)
            setStroke(dp(2), borderColor)
        }

        bubble.contentDescription = when {
            recording && !paused ->
                "Recording. Tap to expand Kalize controls"
            recording && paused ->
                "Recording paused. Tap to expand Kalize controls"
            else ->
                "Kalize Recorder ready. Tap to expand controls"
        }

        if (bubble.parent != null) {
            try {
                wm.updateViewLayout(bubble, params)
            } catch (_: Exception) {
            }
        }
    }

    private fun attachDrag(
        view: View,
        onTap: (() -> Unit)? = null
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        view.setOnTouchListener { _, event ->
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

                    if (abs(dx) > dp(5) || abs(dy) > dp(5)) {
                        moved = true
                    }

                    val width = resources.displayMetrics.widthPixels
                    val height = resources.displayMetrics.heightPixels

                    params.x = (startX + dx).coerceIn(
                        0, (width - view.width).coerceAtLeast(0)
                    )
                    params.y = (startY + dy).coerceIn(
                        0, (height - view.height).coerceAtLeast(0)
                    )

                    try {
                        wm.updateViewLayout(view, params)
                    } catch (_: Exception) {
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        snapToSide(view)
                        saveBubblePosition()
                    } else {
                        onTap?.invoke()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun snapToSide(view: View) {
        val screenWidth = resources.displayMetrics.widthPixels

        params.x =
            if (params.x + view.width / 2 < screenWidth / 2) {
                0
            } else {
                (screenWidth - view.width).coerceAtLeast(0)
            }

        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {
        }

        saveBubblePosition()
    }

    private fun minimizeControls() {
        val currentPanel = panel ?: return
        val bubble = miniBubble ?: return

        updateBubbleAppearance()

        try {
            wm.removeView(currentPanel)
            wm.addView(bubble, params)
            activeView = bubble
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun expandControls() {
        val currentPanel = panel ?: return
        val bubble = miniBubble ?: return

        try {
            wm.removeView(bubble)
            wm.addView(currentPanel, params)
            activeView = currentPanel
            updateControls()
            updateTimer()
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun updateTimer() {
        val now = SystemClock.elapsedRealtime()

        val runningMillis =
            if (recording && !paused && recordingStartedAt > 0L) {
                (now - recordingStartedAt).coerceAtLeast(0L)
            } else {
                0L
            }

        val totalSeconds = (elapsedBeforePause + runningMillis) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L

        val formatted = if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }

        timerText?.text = formatted
    }

    private fun updateControls() {
        val button = pauseButton
        val status = statusText

        if (!recording) {
            button?.text = "Ⅱ"
            button?.isEnabled = false
            button?.alpha = 0.5f
            status?.text = "● READY"
            status?.setTextColor(palePurple)
        } else if (paused) {
            button?.text = "▶"
            button?.isEnabled = true
            button?.alpha = 1f
            status?.text = "● PAUSED"
            status?.setTextColor(amber)
        } else {
            button?.text = "Ⅱ"
            button?.isEnabled = true
            button?.alpha = 1f
            status?.text = "● RECORDING"
            status?.setTextColor(Color.rgb(255, 85, 100))
        }

        panel?.background = rounded(
            if (recording) Color.argb(235, 35, 22, 38)
            else Color.argb(238, 24, 22, 36),
            23,
            purple
        )

        updateBubbleAppearance()

        if (Build.VERSION.SDK_INT >= 31) {
            try {
                params.setBlurBehindRadius(
                    if (recording) dp(32) else dp(28)
                )
                activeView?.let {
                    wm.updateViewLayout(it, params)
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(timerRunnable)

        if (receiverRegistered) {
            try {
                unregisterReceiver(stateReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }

        try {
            panel?.let {
                if (it.parent != null) wm.removeView(it)
            }
        } catch (_: Exception) {
        }

        try {
            miniBubble?.let {
                if (it.parent != null) wm.removeView(it)
            }
        } catch (_: Exception) {
        }

        panel = null
        miniBubble = null
        activeView = null
        pauseButton = null
        statusText = null
        timerText = null

        super.onDestroy()
    }
}

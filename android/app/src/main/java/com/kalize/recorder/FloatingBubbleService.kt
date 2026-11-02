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
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var controlPanel: LinearLayout? = null
    private var pauseButton: Button? = null
    private var statusText: TextView? = null

    private var isMinimized = false
    private var isRecording = false
    private var isPaused = false
    private var receiverRegistered = false

    private val purple = Color.rgb(139, 92, 246)
    private val lightPurple = Color.rgb(205, 185, 255)
    private val darkBackground = Color.rgb(24, 22, 36)
    private val red = Color.rgb(225, 55, 70)
    private val green = Color.rgb(40, 160, 105)
    private val blue = Color.rgb(55, 120, 220)

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScreenRecordingService.ACTION_STATE_CHANGED) {
                return
            }

            isRecording = intent.getBooleanExtra(
                ScreenRecordingService.EXTRA_IS_RECORDING,
                false
            )

            isPaused = intent.getBooleanExtra(
                ScreenRecordingService.EXTRA_IS_PAUSED,
                false
            )

            if (!isRecording) isPaused = false

            updateControls()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        val filter = IntentFilter(
            ScreenRecordingService.ACTION_STATE_CHANGED
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }

        receiverRegistered = true

        val prefs = getSharedPreferences(
            "kalize_recording_state",
            Context.MODE_PRIVATE
        )

        isRecording = prefs.getBoolean("is_recording", false)
        isPaused = prefs.getBoolean("is_paused", false)

        if (!isRecording) isPaused = false
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

        if (controlPanel == null) {
            showControls()
        }

        return START_NOT_STICKY
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun rounded(
        color: Int,
        radius: Int = 18,
        stroke: Int? = null
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()

            if (stroke != null) {
                setStroke(dp(1), stroke)
            }
        }
    }

    private fun circle(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1), Color.argb(80, 255, 255, 255))
        }
    }

    private fun makeButton(
        symbol: String,
        color: Int,
        description: String
    ): Button {
        return Button(this).apply {
            text = symbol
            textSize = 21f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            contentDescription = description

            background = circle(color)
            stateListAnimator = null

            minWidth = 0
            minimumWidth = 0
            minHeight = dp(46)
            minimumHeight = dp(46)

            setPadding(0, 0, 0, 0)
        }
    }

    private fun sendRecordingAction(action: String) {
        val serviceIntent = Intent(
            this,
            ScreenRecordingService::class.java
        ).apply {
            this.action = action
        }

        try {
            startService(serviceIntent)
        } catch (_: Exception) {
        }
    }

    private fun showControls() {
        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(11), dp(9), dp(11), dp(10))
            background = rounded(
                Color.argb(235, 24, 22, 36),
                23,
                purple
            )
            elevation = dp(12).toFloat()
            contentDescription = "Kalize floating recording toolbar"
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val logo = TextView(this).apply {
            text = "K"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = circle(Color.rgb(105, 65, 195))
        }

        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), 0, dp(8), 0)
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
            setTextColor(lightPurple)
        }

        statusText = status

        titleColumn.addView(title)
        titleColumn.addView(status)

        val dragHandle = TextView(this).apply {
            text = "⠿"
            textSize = 25f
            setTextColor(lightPurple)
            gravity = Gravity.CENTER
            contentDescription = "Drag to move Kalize controls"
        }

        header.addView(
            logo,
            LinearLayout.LayoutParams(dp(35), dp(35))
        )

        header.addView(
            titleColumn,
            LinearLayout.LayoutParams(0, dp(40), 1f)
        )

        header.addView(
            dragHandle,
            LinearLayout.LayoutParams(dp(35), dp(40))
        )

        val divider = View(this).apply {
            setBackgroundColor(Color.argb(55, 255, 255, 255))
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }

        val openButton = makeButton(
            "⌂",
            blue,
            "Open Kalize Recorder"
        )

        val localPauseButton = makeButton(
            "Ⅱ",
            green,
            "Pause or resume recording"
        )

        pauseButton = localPauseButton

        val stopButton = makeButton(
            "■",
            red,
            "Stop screen recording"
        )

        val minimizeButton = makeButton(
            "−",
            Color.rgb(90, 70, 130),
            "Minimize floating controls"
        )

        fun addButton(button: Button) {
            controls.addView(
                button,
                LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                    marginStart = dp(5)
                    marginEnd = dp(5)
                }
            )
        }

        addButton(openButton)
        addButton(localPauseButton)
        addButton(stopButton)
        addButton(minimizeButton)

        panel.addView(
            header,
            LinearLayout.LayoutParams(
                dp(230),
                dp(40)
            )
        )

        panel.addView(
            divider,
            LinearLayout.LayoutParams(
                dp(230),
                dp(1)
            ).apply {
                topMargin = dp(7)
            }
        )

        panel.addView(controls)

        val miniBubble = TextView(this).apply {
            text = "K"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(95, 58, 175))
                setStroke(dp(2), lightPurple)
            }
            contentDescription = "Tap to expand Kalize controls"
        }

        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(220)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or
                    WindowManager.LayoutParams.FLAG_BLUR_BEHIND

                setBlurBehindRadius(dp(28))
            }
        }

        fun snapToSide(view: View) {
            val screenWidth = resources.displayMetrics.widthPixels
            val maxX = (screenWidth - view.width).coerceAtLeast(0)

            layoutParams.x =
                if (layoutParams.x + view.width / 2 < screenWidth / 2) {
                    0
                } else {
                    maxX
                }

            try {
                windowManager.updateViewLayout(view, layoutParams)
            } catch (_: Exception) {
            }
        }

        fun attachDrag(view: View, onTap: (() -> Unit)? = null) {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            var moved = false

            view.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = layoutParams.x
                        startY = layoutParams.y
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

                        val screenWidth =
                            resources.displayMetrics.widthPixels

                        val screenHeight =
                            resources.displayMetrics.heightPixels

                        val maxX =
                            (screenWidth - view.width).coerceAtLeast(0)

                        val maxY =
                            (screenHeight - view.height).coerceAtLeast(0)

                        layoutParams.x =
                            (startX + dx).coerceIn(0, maxX)

                        layoutParams.y =
                            (startY + dy).coerceIn(0, maxY)

                        try {
                            windowManager.updateViewLayout(
                                view,
                                layoutParams
                            )
                        } catch (_: Exception) {
                        }

                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            snapToSide(view)
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

        fun expand() {
            if (miniBubble.parent != null) {
                try {
                    windowManager.removeView(miniBubble)
                } catch (_: Exception) {
                }
            }

            isMinimized = false

            try {
                windowManager.addView(panel, layoutParams)
                controlPanel = panel
            } catch (_: Exception) {
                stopSelf()
                return
            }

            updateControls()
        }

        fun minimize() {
            isMinimized = true

            try {
                if (panel.parent != null) {
                    windowManager.removeView(panel)
                }

                windowManager.addView(miniBubble, layoutParams)
                controlPanel = miniBubble as? LinearLayout
            } catch (_: Exception) {
                stopSelf()
            }
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

        localPauseButton.setOnClickListener {
            val prefs = getSharedPreferences(
                "kalize_recording_state",
                Context.MODE_PRIVATE
            )

            isRecording = prefs.getBoolean("is_recording", false)
            isPaused = prefs.getBoolean("is_paused", false)

            if (!isRecording) {
                updateControls()
                return@setOnClickListener
            }

            sendRecordingAction(
                if (isPaused) {
                    ScreenRecordingService.ACTION_RESUME
                } else {
                    ScreenRecordingService.ACTION_PAUSE
                }
            )
        }

        stopButton.setOnClickListener {
            sendRecordingAction(ScreenRecordingService.ACTION_STOP)
            stopSelf()
        }

        minimizeButton.setOnClickListener {
            minimize()
        }

        attachDrag(dragHandle)
        attachDrag(miniBubble) {
            expand()
        }

        try {
            windowManager.addView(panel, layoutParams)
            controlPanel = panel
        } catch (_: Exception) {
            stopSelf()
            return
        }

        updateControls()
    }

    private fun updateControls() {
        val button = pauseButton
        val status = statusText

        if (!isRecording) {
            button?.text = "Ⅱ"
            button?.isEnabled = false
            button?.alpha = 0.5f

            status?.text = "● READY"
            status?.setTextColor(lightPurple)
        } else if (isPaused) {
            button?.text = "▶"
            button?.isEnabled = true
            button?.alpha = 1f

            status?.text = "● PAUSED"
            status?.setTextColor(Color.rgb(255, 190, 80))
        } else {
            button?.text = "Ⅱ"
            button?.isEnabled = true
            button?.alpha = 1f

            status?.text = "● RECORDING"
            status?.setTextColor(Color.rgb(255, 85, 100))
        }

        val panel = controlPanel

        if (panel != null && !isMinimized) {
            panel.background = rounded(
                if (isRecording) {
                    Color.argb(225, 35, 22, 38)
                } else {
                    Color.argb(235, 24, 22, 36)
                },
                23,
                purple
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                layoutParams.setBlurBehindRadius(
                    if (isRecording) dp(32) else dp(28)
                )

                panel?.let {
                    windowManager.updateViewLayout(it, layoutParams)
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(stateReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }

        try {
            controlPanel?.let {
                windowManager.removeView(it)
            }
        } catch (_: Exception) {
        }

        controlPanel = null
        pauseButton = null
        statusText = null

        super.onDestroy()
    }
}


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
    private var controlPanel: LinearLayout? = null
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var isMinimized = false
    private var isPaused = false
    private var receiverRegistered = false
    private var pauseButton: Button? = null

    private val backgroundColor = Color.rgb(20, 19, 37)
    private val purpleColor = Color.rgb(139, 92, 246)
    private val lightPurple = Color.rgb(190, 170, 255)
    private val redColor = Color.rgb(190, 48, 66)
    private val blueColor = Color.rgb(45, 105, 200)
    private val greenColor = Color.rgb(35, 130, 90)

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScreenRecordingService.ACTION_STATE_CHANGED) {
                return
            }

            val recording = intent.getBooleanExtra(
                ScreenRecordingService.EXTRA_IS_RECORDING,
                false
            )

            isPaused = intent.getBooleanExtra(
                ScreenRecordingService.EXTRA_IS_PAUSED,
                false
            )

            if (!recording) {
                isPaused = false
            }

            updatePauseButton()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        val filter = IntentFilter(
            ScreenRecordingService.ACTION_STATE_CHANGED
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                stateReceiver,
                filter,
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }

        receiverRegistered = true

        val prefs = getSharedPreferences(
            "kalize_recording_state",
            Context.MODE_PRIVATE
        )

        isPaused = prefs.getBoolean("is_paused", false)
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun roundedBackground(
        color: Int,
        radiusDp: Int = 16,
        strokeColor: Int? = null
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()

            if (strokeColor != null) {
                setStroke(dp(1), strokeColor)
            }
        }
    }

    private fun makeControlButton(
        label: String,
        color: Int,
        description: String
    ): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            contentDescription = description
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = roundedBackground(color, 12)
            stateListAnimator = null
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(42)
            minimumHeight = dp(42)
            setPadding(dp(9), dp(4), dp(9), dp(4))
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
            // Notification controls remain available as a fallback.
        }
    }

    private fun showControls() {
        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = roundedBackground(
                backgroundColor,
                22,
                purpleColor
            )
            elevation = dp(8).toFloat()
            contentDescription = "Floating recording controls"
        }

        val openButton = makeControlButton(
            "OPEN",
            blueColor,
            "Open Kalize Recorder"
        )

        val localPauseButton = makeControlButton(
            "Ⅱ PAUSE",
            greenColor,
            "Pause screen recording"
        )

        pauseButton = localPauseButton

        val stopButton = makeControlButton(
            "■ STOP",
            redColor,
            "Stop screen recording"
        )

        val hideButton = makeControlButton(
            "HIDE",
            Color.rgb(69, 60, 95),
            "Hide floating controls without stopping recording"
        )

        val minimizeButton = makeControlButton(
            "MINI",
            Color.rgb(75, 53, 125),
            "Minimize floating controls"
        )

        val restoreButton = TextView(this).apply {
            text = "K"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            contentDescription = "Expand floating controls"

            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(88, 55, 170))
                setStroke(dp(2), lightPurple)
            }
        }

        fun addControlButton(button: Button) {
            val params = LinearLayout.LayoutParams(
                ViewGroupSize.WRAP,
                dp(42)
            ).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }

            panel.addView(button, params)
        }

        fun showExpanded() {
            isMinimized = false
            panel.removeAllViews()
            panel.orientation = LinearLayout.HORIZONTAL
            panel.gravity = Gravity.CENTER
            panel.setPadding(dp(7), dp(7), dp(7), dp(7))
            panel.background = roundedBackground(
                backgroundColor,
                22,
                purpleColor
            )
            panel.contentDescription = "Floating recording controls"

            updatePauseButton()
            addControlButton(openButton)
            addControlButton(localPauseButton)
            addControlButton(stopButton)
            addControlButton(hideButton)
            addControlButton(minimizeButton)

            updatePanelPosition()
        }

        fun showCollapsed() {
            isMinimized = true
            panel.removeAllViews()
            panel.orientation = LinearLayout.HORIZONTAL
            panel.gravity = Gravity.CENTER
            panel.setPadding(0, 0, 0, 0)

            panel.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(65, 42, 125))
                setStroke(dp(2), lightPurple)
            }

            panel.contentDescription =
                "Minimized Kalize Recorder controls"

            panel.addView(
                restoreButton,
                LinearLayout.LayoutParams(dp(52), dp(52))
            )

            updatePanelPosition()
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

            val recording = prefs.getBoolean("is_recording", false)
            val paused = prefs.getBoolean("is_paused", false)

            if (!recording) {
                return@setOnClickListener
            }

            if (paused) {
                sendRecordingAction(
                    ScreenRecordingService.ACTION_RESUME
                )
            } else {
                sendRecordingAction(
                    ScreenRecordingService.ACTION_PAUSE
                )
            }
        }

        stopButton.setOnClickListener {
            sendRecordingAction(ScreenRecordingService.ACTION_STOP)
            stopSelf()
        }

        hideButton.setOnClickListener {
            // Hiding the bubble does not stop recording.
            stopSelf()
        }

        minimizeButton.setOnClickListener {
            showCollapsed()
        }

        restoreButton.setOnClickListener {
            showExpanded()
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(220)
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        panel.setOnTouchListener { view, event ->
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
                        windowManager.updateViewLayout(view, layoutParams)
                    } catch (_: Exception) {
                        // The overlay may already have been removed.
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        snapToNearestSide(view)
                    }
                    true
                }

                else -> false
            }
        }

        showExpanded()

        try {
            windowManager.addView(panel, layoutParams)
            controlPanel = panel
        } catch (_: Exception) {
            controlPanel = null
            stopSelf()
        }
    }

    private fun updatePauseButton() {
        val button = pauseButton ?: return

        if (isPaused) {
            button.text = "▶ RESUME"
            button.background = roundedBackground(greenColor, 12)
            button.contentDescription = "Resume screen recording"
        } else {
            button.text = "Ⅱ PAUSE"
            button.background = roundedBackground(
                Color.rgb(75, 53, 125),
                12
            )
            button.contentDescription = "Pause screen recording"
        }
    }

    private fun snapToNearestSide(view: View) {
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
            // Ignore if Android has removed the overlay.
        }
    }

    private fun updatePanelPosition() {
        val panel = controlPanel ?: return

        try {
            windowManager.updateViewLayout(panel, layoutParams)
        } catch (_: Exception) {
            // The panel may not be attached yet.
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

        controlPanel?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
                // Ignore if Android already removed the overlay.
            }
        }

        controlPanel = null
        pauseButton = null

        super.onDestroy()
    }

    private object ViewGroupSize {
        const val WRAP =
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

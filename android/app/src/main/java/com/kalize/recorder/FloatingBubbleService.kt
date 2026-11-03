
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
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

class FloatingBubbleService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams

    private lateinit var root: FrameLayout
    private lateinit var mainBubble: TextView

    private val actionBubbles = mutableListOf<TextView>()

    private var expanded = false
    private var recording = false
    private var paused = false
    private var receiverRegistered = false

    private var recordingStartedAt = 0L
    private var elapsedBeforePause = 0L
    private var currentTimer = "00:00"

    private val handler = Handler(Looper.getMainLooper())

    private val prefs by lazy {
        getSharedPreferences("kalize_bubble_position", MODE_PRIVATE)
    }

    private val purple = Color.rgb(125, 75, 220)
    private val orange = Color.rgb(255, 115, 25)
    private val red = Color.rgb(235, 55, 65)
    private val blue = Color.rgb(45, 125, 235)
    private val green = Color.rgb(35, 165, 105)
    private val dark = Color.rgb(30, 25, 40)
    private val white = Color.WHITE

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun circle(color: Int) =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(2), Color.argb(100, 255, 255, 255))
        }

    private fun makeBubble(
        symbol: String,
        color: Int,
        description: String,
        size: Int = 44
    ): TextView {
        return TextView(this).apply {
            text = symbol
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
            background = circle(color)
            elevation = dp(9).toFloat()
            contentDescription = description
            isClickable = true
        }
    }

    private fun addAction(
        symbol: String,
        color: Int,
        description: String,
        x: Int,
        y: Int,
        action: () -> Unit
    ) {
        val button = makeBubble(symbol, color, description)

        button.visibility = View.GONE

        root.addView(
            button,
            FrameLayout.LayoutParams(dp(44), dp(44)).apply {
                leftMargin = dp(x)
                topMargin = dp(y)
            }
        )

        button.setOnClickListener {
            collapseMenu()
            action()
        }

        actionBubbles.add(button)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        val filter = IntentFilter(
            ScreenRecordingService.ACTION_STATE_CHANGED
        )

        if (Build.VERSION.SDK_INT >= 33) {
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

        val state = getSharedPreferences(
            "kalize_recording_state",
            MODE_PRIVATE
        )

        recording = state.getBoolean("is_recording", false)
        paused = state.getBoolean("is_paused", false)

        elapsedBeforePause = state.getLong(
            "recording_elapsed_ms", 0L
        ).coerceAtLeast(0L)

        val savedRealtime = state.getLong(
            "recording_elapsed_realtime_ms", 0L
        )

        if (recording && !paused) {
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

        if (!::root.isInitialized) {
            createBubbleMenu()
        }

        return START_NOT_STICKY
    }

    private fun createBubbleMenu() {

        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            dp(196),
            dp(196),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("bubble_x", dp(12))
            y = prefs.getInt("bubble_y", dp(220))
        }

        root = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }

        // MAIN BUBBLE

        mainBubble = makeBubble(
            "K",
            orange,
            "Tap to expand Kalize Recorder controls",
            60
        ).apply {
            textSize = 25f
        }

        root.addView(
            mainBubble,
            FrameLayout.LayoutParams(
                dp(60),
                dp(60)
            ).apply {
                leftMargin = dp(68)
                topMargin = dp(68)
            }
        )

        // RECORDING BUTTON

        addAction(
            "▶",
            orange,
            "Open recording controls",
            76,
            0
        ) {
            openMainApp()
        }

        // BRUSH BUTTON

        addAction(
            "✎",
            purple,
            "Drawing tools",
            16,
            27
        ) {
            Toast.makeText(
                this,
                "Drawing tools are coming next.",
                Toast.LENGTH_SHORT
            ).show()
        }

        // SCREENSHOT BUTTON

        addAction(
            "📷",
            red,
            "Take screenshot",
            136,
            27
        ) {
            openScreenshot()
        }

        // LIVE BUTTON

        addAction(
            "LIVE",
            red,
            "Live streaming",
            16,
            125
        ) {
            Toast.makeText(
                this,
                "Live streaming is not connected yet.",
                Toast.LENGTH_SHORT
            ).show()
        }

        // PAUSE / RESUME BUTTON

        addAction(
            "Ⅱ",
            green,
            "Pause or resume recording",
            136,
            125
        ) {
            if (!recording) {
                Toast.makeText(
                    this,
                    "Start recording first.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                sendRecordingAction(
                    if (paused)
                        ScreenRecordingService.ACTION_RESUME
                    else
                        ScreenRecordingService.ACTION_PAUSE
                )
            }
        }

        // STOP BUTTON

        addAction(
            "■",
            red,
            "Stop recording",
            76,
            152
        ) {
            sendRecordingAction(
                ScreenRecordingService.ACTION_STOP
            )
        }

        // GALLERY BUTTON

        addAction(
            "▧",
            blue,
            "Open screenshot gallery",
            8,
            78
        ) {
            openGallery()
        }

        mainBubble.setOnTouchListener(mainTouchListener)

        try {
            wm.addView(root, params)
        } catch (error: Exception) {
            stopSelf()
            return
        }

        updateMainBubble()
    }

    private fun expandMenu() {
        if (expanded) return

        expanded = true

        actionBubbles.forEach { button ->
            button.visibility = View.VISIBLE
            button.scaleX = 0.2f
            button.scaleY = 0.2f
            button.alpha = 0f

            button.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(180)
                .start()
        }

        mainBubble.animate()
            .rotation(45f)
            .setDuration(180)
            .start()
    }

    private fun collapseMenu() {
        if (!expanded) return

        expanded = false

        actionBubbles.forEach { button ->
            button.animate()
                .scaleX(0.2f)
                .scaleY(0.2f)
                .alpha(0f)
                .setDuration(130)
                .withEndAction {
                    button.visibility = View.GONE
                }
                .start()
        }

        mainBubble.animate()
            .rotation(0f)
            .setDuration(180)
            .start()
    }

    private val mainTouchListener =
        View.OnTouchListener { _, event ->

            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            var moved = false

            when (event.action) {

                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false

                    mainBubble.setTag(
                        intArrayOf(
                            startX,
                            startY,
                            touchX.toInt(),
                            touchY.toInt(),
                            0
                        )
                    )

                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val saved = mainBubble.tag as? IntArray
                        ?: return@OnTouchListener true

                    val dx = (event.rawX - saved[2]).toInt()
                    val dy = (event.rawY - saved[3]).toInt()

                    if (kotlin.math.abs(dx) > dp(5) ||
                        kotlin.math.abs(dy) > dp(5)
                    ) {
                        saved[4] = 1
                    }

                    if (saved[4] == 1) {
                        params.x = (saved[0] + dx).coerceAtLeast(0)
                        params.y = (saved[1] + dy).coerceAtLeast(0)

                        try {
                            wm.updateViewLayout(root, params)
                        } catch (_: Exception) {
                        }
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {
                    val saved = mainBubble.tag as? IntArray

                    if (saved != null && saved[4] == 1) {
                        prefs.edit()
                            .putInt("bubble_x", params.x)
                            .putInt("bubble_y", params.y)
                            .apply()
                    } else {
                        if (expanded) collapseMenu()
                        else expandMenu()
                    }

                    true
                }

                else -> true
            }
        }

    private fun updateMainBubble() {
        if (!::mainBubble.isInitialized) return

        val color = when {
            recording && !paused -> red
            recording && paused -> Color.rgb(230, 165, 45)
            else -> orange
        }

        mainBubble.background = circle(color)

        if (recording) {
            mainBubble.text = currentTimer
            mainBubble.textSize = 10f
        } else {
            mainBubble.text = "K"
            mainBubble.textSize = 25f
        }

        mainBubble.contentDescription = when {
            recording && paused ->
                "Recording paused. $currentTimer"

            recording ->
                "Recording. $currentTimer"

            else ->
                "Kalize Recorder. Tap to expand"
        }

        if (root.parent != null) {
            try {
                wm.updateViewLayout(root, params)
            } catch (_: Exception) {
            }
        }
    }

    private fun openMainApp() {
        try {
            val intent = packageManager
                .getLaunchIntentForPackage(packageName)

            intent?.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )

            if (intent != null) startActivity(intent)

        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Could not open Kalize Recorder.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun openScreenshot() {
        try {
            startActivity(
                Intent(
                    this,
                    QuickScreenshotActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Could not open screenshot capture.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun openGallery() {
        try {
            startActivity(
                Intent(
                    this,
                    ScreenshotGalleryActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Could not open gallery.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun sendRecordingAction(action: String) {
        try {
            startService(
                Intent(
                    this,
                    ScreenRecordingService::class.java
                ).apply {
                    this.action = action
                }
            )
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Recording command failed.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(
            context: Context?,
            intent: Intent?
        ) {
            if (
                intent?.action !=
                ScreenRecordingService.ACTION_STATE_CHANGED
            ) return

            val wasRecording = recording
            val wasPaused = paused

            recording = intent.getBooleanExtra(
                ScreenRecordingService.EXTRA_IS_RECORDING,
                false
            )

            paused = intent.getBooleanExtra(
                ScreenRecordingService.EXTRA_IS_PAUSED,
                false
            )

            if (intent.hasExtra(
                    ScreenRecordingService.EXTRA_ELAPSED_MILLIS
                )
            ) {
                elapsedBeforePause = intent.getLongExtra(
                    ScreenRecordingService.EXTRA_ELAPSED_MILLIS,
                    0L
                ).coerceAtLeast(0L)

                recordingStartedAt =
                    if (recording && !paused)
                        SystemClock.elapsedRealtime()
                    else 0L

            } else if (!recording) {
                elapsedBeforePause = 0L
                recordingStartedAt = 0L

            } else if (!wasRecording) {
                elapsedBeforePause = 0L
                recordingStartedAt =
                    if (!paused) SystemClock.elapsedRealtime()
                    else 0L

            } else if (!wasPaused && paused) {
                if (recordingStartedAt > 0L) {
                    elapsedBeforePause +=
                        SystemClock.elapsedRealtime() -
                        recordingStartedAt
                }
                recordingStartedAt = 0L

            } else if (wasPaused && !paused) {
                recordingStartedAt =
                    SystemClock.elapsedRealtime()
            }

            updateMainBubble()
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            updateTimer()
            handler.postDelayed(this, 1000L)
        }
    }

    private fun updateTimer() {
        val running = if (
            recording && !paused && recordingStartedAt > 0L
        ) {
            SystemClock.elapsedRealtime() - recordingStartedAt
        } else 0L

        val seconds =
            (elapsedBeforePause + running).coerceAtLeast(0L) / 1000L

        val hours = seconds / 3600L
        val minutes = (seconds % 3600L) / 60L
        val remaining = seconds % 60L

        currentTimer = if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, remaining)
        } else {
            String.format("%02d:%02d", minutes, remaining)
        }

        updateMainBubble()
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

        if (::root.isInitialized) {
            try {
                if (root.parent != null) {
                    wm.removeView(root)
                }
            } catch (_: Exception) {
            }
        }

        actionBubbles.clear()

        super.onDestroy()
    }
}

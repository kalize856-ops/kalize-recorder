
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

    private val statePrefs by lazy {
        getSharedPreferences("kalize_recording_state", MODE_PRIVATE)
    }

    private val purple = Color.rgb(125, 75, 220)
    private val orange = Color.rgb(255, 115, 25)
    private val red = Color.rgb(235, 55, 65)
    private val blue = Color.rgb(45, 125, 235)
    private val green = Color.rgb(35, 165, 105)
    private val white = Color.WHITE

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun bubbleSize(): Int =
        prefs.getInt("bubble_size", 60).coerceIn(48, 80)

    private fun bubbleOpacity(): Float =
        prefs.getInt("bubble_opacity", 100).coerceIn(30, 100) / 100f

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
            textSize = if (size > 50) 25f else 21f
            gravity = Gravity.CENTER
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
            background = circle(color)
            elevation = dp(9).toFloat()
            contentDescription = description
            isClickable = true
            alpha = bubbleOpacity()
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

        recording = statePrefs.getBoolean("is_recording", false)
        paused = statePrefs.getBoolean("is_paused", false)

        elapsedBeforePause = statePrefs.getLong(
            "recording_elapsed_ms", 0L
        ).coerceAtLeast(0L)

        val savedRealtime = statePrefs.getLong(
            "recording_elapsed_realtime_ms", 0L
        )

        if (recording && !paused) {
            recordingStartedAt = if (savedRealtime > 0L) {
                savedRealtime
            } else {
                SystemClock.elapsedRealtime()
            }
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

        val size = bubbleSize()

        mainBubble = makeBubble(
            "K",
            orange,
            "Tap to expand Kalize Recorder controls",
            size
        ).apply {
            textSize = 25f
        }

        root.addView(
            mainBubble,
            FrameLayout.LayoutParams(dp(size), dp(size)).apply {
                leftMargin = dp((196 - size) / 2)
                topMargin = dp((196 - size) / 2)
            }
        )

        addAction("▶", orange, "Open Kalize Recorder", 76, 0) {
            openMainApp()
        }

        addAction("📷", red, "Take screenshot", 136, 27) {
            openScreenshot()
        }

        addAction("▧", blue, "Open screenshot gallery", 8, 78) {
            openGallery()
        }

        addAction("Ⅱ", green, "Pause or resume recording", 136, 125) {
            if (!recording) {
                Toast.makeText(
                    this,
                    "Start recording first.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                sendRecordingAction(
                    if (paused) {
                        ScreenRecordingService.ACTION_RESUME
                    } else {
                        ScreenRecordingService.ACTION_PAUSE
                    }
                )
            }
        }

        addAction("■", red, "Stop recording", 76, 152) {
            if (recording) {
                sendRecordingAction(
                    ScreenRecordingService.ACTION_STOP
                )
            } else {
                Toast.makeText(
                    this,
                    "No active recording.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        mainBubble.setOnTouchListener(mainTouchListener)

        try {
            wm.addView(root, params)
        } catch (_: Exception) {
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
                .alpha(bubbleOpacity())
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

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    mainBubble.setTag(
                        intArrayOf(
                            params.x,
                            params.y,
                            event.rawX.toInt(),
                            event.rawY.toInt(),
                            0
                        )
                    )
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val saved = mainBubble.tag as? IntArray
                        ?: return@OnTouchListener true

                    val dx = event.rawX.toInt() - saved[2]
                    val dy = event.rawY.toInt() - saved[3]

                    if (
                        kotlin.math.abs(dx) > dp(5) ||
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

    // FLAG_SECURE is applied only to the floating overlay.
    // It is enabled during recording and removed when recording stops.
    private fun updateSecureOverlay() {
        if (!::params.isInitialized || !::root.isInitialized) return

        val secureFlag = WindowManager.LayoutParams.FLAG_SECURE

        val updatedFlags = if (recording) {
            params.flags or secureFlag
        } else {
            params.flags and secureFlag.inv()
        }

        if (updatedFlags == params.flags) return

        params.flags = updatedFlags

        if (root.parent != null) {
            try {
                wm.updateViewLayout(root, params)
            } catch (_: Exception) {
            }
        }
    }

    private fun updateMainBubble() {
        if (!::mainBubble.isInitialized) return

        // Keep the overlay's secure state synchronized with recording.
        updateSecureOverlay()

        val color = when {
            recording && !paused -> red
            recording && paused -> Color.rgb(230, 165, 45)
            else -> orange
        }

        mainBubble.background = circle(color)
        mainBubble.alpha = bubbleOpacity()

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

            if (
                intent.hasExtra(
                    ScreenRecordingService.EXTRA_ELAPSED_MILLIS
                )
            ) {
                elapsedBeforePause = intent.getLongExtra(
                    ScreenRecordingService.EXTRA_ELAPSED_MILLIS,
                    0L
                ).coerceAtLeast(0L)

                recordingStartedAt =
                    if (recording && !paused) {
                        SystemClock.elapsedRealtime()
                    } else {
                        0L
                    }

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
        } else {
            0L
        }

        val seconds =
            (elapsedBeforePause + running).coerceAtLeast(0L) / 1000L

        val hours = seconds / 3600L
        val minutes = (seconds % 3600L) / 60L
        val remaining = seconds % 60L

        currentTimer = if (hours > 0) {
            String.format(
                "%02d:%02d:%02d",
                hours,
                minutes,
                remaining
            )
        } else {
            String.format(
                "%02d:%02d",
                minutes,
                remaining
            )
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

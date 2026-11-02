
package com.kalize.recorder

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import java.util.Locale

class VideoPreviewActivity : Activity() {

    companion object {
        const val EXTRA_VIDEO_URI = "kalize_video_uri"
    }

    private var fullScreen = false
    private var playbackSpeed = 1.0f
    private var preparedPlayer: MediaPlayer? = null
    private var userSeeking = false
    private var videoDuration = 0

    private lateinit var videoView: VideoView
    private lateinit var fullScreenButton: Button
    private lateinit var speedLabel: TextView
    private lateinit var playbackStatus: TextView
    private lateinit var timeline: SeekBar
    private lateinit var currentTimeLabel: TextView
    private lateinit var remainingTimeLabel: TextView
    private lateinit var playPauseButton: Button

    private val chromeViews = mutableListOf<View>()
    private val handler = Handler(Looper.getMainLooper())

    private val backgroundColor = Color.rgb(8, 11, 22)
    private val accent = Color.rgb(154, 105, 255)
    private val white = Color.WHITE
    private val muted = Color.rgb(165, 174, 199)

    private val timelineUpdater = object : Runnable {
        override fun run() {
            if (!::videoView.isInitialized) return

            try {
                if (!userSeeking && videoDuration > 0) {
                    val position = videoView.currentPosition
                        .coerceIn(0, videoDuration)

                    timeline.progress = positionToProgress(position)
                    updateTimeLabels(position)
                }
            } catch (_: Exception) {
            }

            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uriText = intent.getStringExtra(EXTRA_VIDEO_URI)

        if (uriText.isNullOrBlank()) {
            finish()
            return
        }

        val videoUri = Uri.parse(uriText)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(backgroundColor)
        }

        val brand = makeLabel("ƘALIZE  /  STUDIO", 13f, accent, true)
        root.addView(brand, matchHeight(44))
        chromeViews.add(brand)

        val heading = makeLabel("VIDEO PREVIEW", 25f, white, true)
        root.addView(heading, matchHeight(55))
        chromeViews.add(heading)

        val subtitle = makeLabel(
            "Your video, your playback controls.",
            13f,
            muted
        )
        root.addView(subtitle, matchHeight(35))
        chromeViews.add(subtitle)

        videoView = VideoView(this).apply {
            setVideoURI(videoUri)
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }

        root.addView(
            videoView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            }
        )

        val controller = MediaController(this)
        controller.setAnchorView(videoView)
        videoView.setMediaController(controller)

        playbackStatus = makeLabel(
            "Loading video...",
            12f,
            muted
        )
        root.addView(playbackStatus, matchHeight(34))
        chromeViews.add(playbackStatus)

        val timelineTitle = makeLabel(
            "PLAYBACK TIMELINE",
            11f,
            accent,
            true
        )
        root.addView(timelineTitle, matchHeight(28))
        chromeViews.add(timelineTitle)

        timeline = SeekBar(this).apply {
            max = 1000
            progress = 0
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(white)
        }

        timeline.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    userSeeking = true
                }

                override fun onProgressChanged(
                    seekBar: SeekBar,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (fromUser && videoDuration > 0) {
                        updateTimeLabels(progressToPosition(progress))
                    }
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    if (videoDuration > 0) {
                        videoView.seekTo(
                            progressToPosition(seekBar.progress)
                        )
                    }

                    userSeeking = false
                }
            }
        )

        root.addView(timeline, matchHeight(42))
        chromeViews.add(timeline)

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        currentTimeLabel = makeLabel("00:00", 12f, white)
        remainingTimeLabel = makeLabel("-00:00", 12f, muted)

        timeRow.addView(
            currentTimeLabel,
            LinearLayout.LayoutParams(0, dp(35), 1f)
        )

        timeRow.addView(
            remainingTimeLabel,
            LinearLayout.LayoutParams(0, dp(35), 1f).apply {
                gravity = Gravity.END
            }
        )

        root.addView(timeRow)
        chromeViews.add(timeRow)

        val playbackRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        playbackRow.addView(
            makeButton("−10 SEC", false) {
                seekVideo(-10000)
            },
            weightedButton()
        )

        playPauseButton = makeButton("PAUSE", true) {
            togglePlayback()
        }

        playbackRow.addView(playPauseButton, weightedButton())

        playbackRow.addView(
            makeButton("+10 SEC", false) {
                seekVideo(10000)
            },
            weightedButton()
        )

        root.addView(
            playbackRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )
        chromeViews.add(playbackRow)

        val speedTitle = makeLabel(
            "PLAYBACK SPEED",
            11f,
            accent,
            true
        )
        root.addView(speedTitle, matchHeight(30))
        chromeViews.add(speedTitle)

        speedLabel = makeLabel(
            "Current speed: 1.0×",
            14f,
            white
        )
        root.addView(speedLabel, matchHeight(32))
        chromeViews.add(speedLabel)

        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        listOf(0.5f, 1.0f, 1.5f, 2.0f).forEach { speed ->
            speedRow.addView(
                makeButton("${speed}×", speed == 1.0f) {
                    setPlaybackSpeed(speed)
                },
                weightedButton()
            )
        }

        root.addView(
            speedRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )
        chromeViews.add(speedRow)

        fullScreenButton = makeButton(
            "ENTER FULL SCREEN",
            true
        ) {
            toggleFullScreen()
        }

        root.addView(fullScreenButton, matchHeight(50))

        // Keep this button visible so full screen can always be exited.

        val backButton = makeButton(
            "BACK TO VIDEO EDITOR",
            false
        ) {
            finish()
        }

        root.addView(
            backButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
            ).apply {
                topMargin = dp(7)
            }
        )
        chromeViews.add(backButton)

        videoView.setOnPreparedListener { player ->
            preparedPlayer = player
            videoDuration = videoView.duration.coerceAtLeast(0)

            timeline.max = 1000
            timeline.progress = 0

            playbackStatus.text = "Video ready • Timeline active"

            updateTimeLabels(0)
            applyPlaybackSpeed()

            player.start()
            playPauseButton.text = "PAUSE"

            handler.removeCallbacks(timelineUpdater)
            handler.post(timelineUpdater)
        }

        videoView.setOnCompletionListener {
            playbackStatus.text =
                "Preview finished • Replay available"

            playPauseButton.text = "REPLAY"
            timeline.progress = timeline.max
            updateTimeLabels(videoDuration)
        }

        videoView.setOnErrorListener { _, _, _ ->
            playbackStatus.text =
                "Could not play this video format."
            true
        }

        setContentView(root)
    }

    private fun makeLabel(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER

            if (bold) {
                setTypeface(
                    null,
                    android.graphics.Typeface.BOLD
                )
            }
        }
    }

    private fun makeButton(
        value: String,
        primary: Boolean,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = value
            textSize = 11f
            isAllCaps = false
            setTextColor(white)

            backgroundTintList = ColorStateList.valueOf(
                if (primary) accent else Color.rgb(49, 55, 76)
            )

            setOnClickListener { action() }
        }
    }

    private fun matchHeight(height: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(height)
        )
    }

    private fun weightedButton(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            0,
            dp(48),
            1f
        ).apply {
            marginEnd = dp(4)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun formatTime(milliseconds: Int): String {
        val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val hours = minutes / 60
        val remainingMinutes = minutes % 60

        return if (hours > 0) {
            String.format(
                Locale.US,
                "%d:%02d:%02d",
                hours,
                remainingMinutes,
                seconds
            )
        } else {
            String.format(
                Locale.US,
                "%02d:%02d",
                minutes,
                seconds
            )
        }
    }

    private fun updateTimeLabels(position: Int) {
        currentTimeLabel.text = formatTime(position)

        val remaining = (videoDuration - position).coerceAtLeast(0)
        remainingTimeLabel.text = "-${formatTime(remaining)}"
    }

    private fun progressToPosition(progress: Int): Int {
        if (videoDuration <= 0 || timeline.max <= 0) return 0

        return (
            progress.toFloat() / timeline.max * videoDuration
        ).toInt().coerceIn(0, videoDuration)
    }

    private fun positionToProgress(position: Int): Int {
        if (videoDuration <= 0) return 0

        return (
            position.toFloat() / videoDuration * timeline.max
        ).toInt().coerceIn(0, timeline.max)
    }

    private fun togglePlayback() {
        try {
            if (videoView.isPlaying) {
                videoView.pause()
                playPauseButton.text = "PLAY"
                playbackStatus.text = "Playback paused"
            } else {
                if (
                    videoDuration > 0 &&
                    videoView.currentPosition >= videoDuration
                ) {
                    videoView.seekTo(0)
                }

                videoView.start()
                playPauseButton.text = "PAUSE"
                playbackStatus.text = "Playing video"
            }
        } catch (_: Exception) {
            playbackStatus.text = "Playback is not ready yet."
        }
    }

    private fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        speedLabel.text = "Current speed: ${speed}×"

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            playbackStatus.text =
                "Speed control requires Android 6.0 or newer."
            return
        }

        if (preparedPlayer == null) {
            playbackStatus.text =
                "Speed selected. It will apply when ready."
            return
        }

        applyPlaybackSpeed()
    }

    private fun applyPlaybackSpeed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        try {
            val player = preparedPlayer ?: return

            player.playbackParams =
                PlaybackParams().setSpeed(playbackSpeed)

            playbackStatus.text =
                "Playing at ${playbackSpeed}×"

        } catch (_: Exception) {
            playbackStatus.text =
                "This device does not support that speed."
        }
    }

    private fun seekVideo(milliseconds: Int) {
        try {
            if (videoDuration <= 0) {
                playbackStatus.text = "Wait for the video to load."
                return
            }

            val target = (
                videoView.currentPosition + milliseconds
            ).coerceIn(0, videoDuration)

            videoView.seekTo(target)
            timeline.progress = positionToProgress(target)
            updateTimeLabels(target)

        } catch (_: Exception) {
            playbackStatus.text =
                "Could not change video position."
        }
    }

    private fun toggleFullScreen() {
        fullScreen = !fullScreen

        if (fullScreen) {
            chromeViews.forEach {
                it.visibility = View.GONE
            }

            videoView.layoutParams =
                (videoView.layoutParams as LinearLayout.LayoutParams).apply {
                    topMargin = 0
                    bottomMargin = 0
                }

            videoView.requestLayout()
            fullScreenButton.text = "EXIT FULL SCREEN"
            applyImmersiveMode()

        } else {
            chromeViews.forEach {
                it.visibility = View.VISIBLE
            }

            videoView.layoutParams =
                (videoView.layoutParams as LinearLayout.LayoutParams).apply {
                    topMargin = dp(10)
                    bottomMargin = dp(10)
                }

            videoView.requestLayout()

            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun applyImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus && fullScreen) {
            applyImmersiveMode()
        }
    }

    @Deprecated("Uses legacy back callback")
    override fun onBackPressed() {
        if (fullScreen) {
            toggleFullScreen()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()

        if (::videoView.isInitialized && videoView.isPlaying) {
            videoView.pause()
            playPauseButton.text = "PLAY"
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(timelineUpdater)
        preparedPlayer = null

        if (::videoView.isInitialized) {
            videoView.stopPlayback()
        }

        super.onDestroy()
    }
}

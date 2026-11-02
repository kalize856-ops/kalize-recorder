package com.kalize.recorder

import android.app.Activity
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView

class VideoPreviewActivity : Activity() {

    companion object {
        const val EXTRA_VIDEO_URI = "kalize_video_uri"
    }

    private var fullScreen = false
    private var playbackSpeed = 1.0f
    private var preparedPlayer: MediaPlayer? = null

    private lateinit var videoView: VideoView
    private lateinit var fullScreenButton: Button
    private lateinit var speedLabel: TextView
    private lateinit var playbackStatus: TextView

    private val chromeViews = mutableListOf<View>()

    private val backgroundColor = android.graphics.Color.rgb(8, 11, 22)
    private val accent = android.graphics.Color.rgb(154, 105, 255)
    private val white = android.graphics.Color.WHITE
    private val muted = android.graphics.Color.rgb(165, 174, 199)

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
            setPadding(18, 18, 18, 18)
            setBackgroundColor(backgroundColor)
        }

        val brand = makeLabel(
            "ƘALIZE  /  STUDIO",
            13f,
            accent,
            true
        )
        root.addView(brand, matchHeight(44))
        chromeViews.add(brand)

        val heading = makeLabel(
            "VIDEO PREVIEW",
            25f,
            white,
            true
        )
        root.addView(heading, matchHeight(60))
        chromeViews.add(heading)

        val subtitle = makeLabel(
            "Preview your clip and control playback.",
            13f,
            muted
        )
        root.addView(subtitle, matchHeight(38))
        chromeViews.add(subtitle)

        videoView = VideoView(this).apply {
            setVideoURI(videoUri)
            setBackgroundColor(android.graphics.Color.BLACK)
            keepScreenOn = true
        }

        root.addView(
            videoView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = 12
                bottomMargin = 12
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
        root.addView(playbackStatus, matchHeight(36))
        chromeViews.add(playbackStatus)

        val seekTitle = makeLabel(
            "SEEK CONTROLS",
            11f,
            accent,
            true
        )
        root.addView(seekTitle, matchHeight(30))
        chromeViews.add(seekTitle)

        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        seekRow.addView(
            makeButton("−10 SEC", false) {
                seekVideo(-10000)
            },
            weightedButton()
        )

        seekRow.addView(
            makeButton("+10 SEC", false) {
                seekVideo(10000)
            },
            weightedButton()
        )

        root.addView(
            seekRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                50
            )
        )
        chromeViews.add(seekRow)

        val speedTitle = makeLabel(
            "PLAYBACK SPEED",
            11f,
            accent,
            true
        )
        root.addView(speedTitle, matchHeight(32))
        chromeViews.add(speedTitle)

        speedLabel = makeLabel(
            "Current speed: 1.0×",
            15f,
            white
        )
        root.addView(speedLabel, matchHeight(36))
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
                52
            ).apply {
                topMargin = 6
                bottomMargin = 8
            }
        )
        chromeViews.add(speedRow)

        fullScreenButton = makeButton(
            "⛶  ENTER FULL SCREEN",
            true
        ) {
            toggleFullScreen()
        }

        root.addView(fullScreenButton, matchHeight(52))
        chromeViews.add(fullScreenButton)

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
                54
            ).apply {
                topMargin = 8
            }
        )
        chromeViews.add(backButton)

        videoView.setOnPreparedListener { player ->
            preparedPlayer = player

            playbackStatus.text =
                "Video ready • Playback controls available"

            applyPlaybackSpeed()
            player.start()
        }

        videoView.setOnCompletionListener {
            playbackStatus.text =
                "Preview finished • Replay using the controls"
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
            textSize = 12f
            isAllCaps = false
            setTextColor(white)

            backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    if (primary) {
                        accent
                    } else {
                        android.graphics.Color.rgb(49, 55, 76)
                    }
                )

            setOnClickListener {
                action()
            }
        }
    }

    private fun matchHeight(height: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            height
        )
    }

    private fun weightedButton(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            0,
            48,
            1f
        ).apply {
            marginEnd = 4
        }
    }

    private fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        speedLabel.text = "Current speed: ${speed}×"

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            playbackStatus.text =
                "Playback speed requires Android 6.0 or newer."
            return
        }

        if (preparedPlayer == null) {
            playbackStatus.text =
                "Speed selected. It will apply when the video loads."
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
                "Video ready • Playback speed ${playbackSpeed}×"

        } catch (_: Exception) {
            playbackStatus.text =
                "This device does not support the selected speed."
        }
    }

    private fun seekVideo(milliseconds: Int) {
        if (!videoView.isPlaying && preparedPlayer == null) {
            playbackStatus.text = "Wait for the video to load."
            return
        }

        try {
            val current = videoView.currentPosition
            val duration = videoView.duration

            val target = (current + milliseconds)
                .coerceIn(0, duration.coerceAtLeast(0))

            videoView.seekTo(target)

            playbackStatus.text =
                "Position: ${target / 1000} seconds"

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
            applyImmersiveMode()

        } else {
            chromeViews.forEach {
                it.visibility = View.VISIBLE
            }

            videoView.layoutParams =
                (videoView.layoutParams as LinearLayout.LayoutParams).apply {
                    topMargin = 12
                    bottomMargin = 12
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

    override fun onDestroy() {
        preparedPlayer = null

        if (::videoView.isInitialized) {
            videoView.stopPlayback()
        }

        super.onDestroy()
    }
}

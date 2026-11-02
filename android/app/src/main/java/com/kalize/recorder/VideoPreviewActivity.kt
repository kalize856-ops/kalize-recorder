package com.kalize.recorder

import android.app.Activity
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

    private lateinit var videoView: VideoView
    private lateinit var fullScreenButton: Button
    private lateinit var speedLabel: TextView
    private lateinit var playbackStatus: TextView

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

        root.addView(
            TextView(this).apply {
                text = "ƘALIZE  /  STUDIO"
                textSize = 13f
                letterSpacing = 0.12f
                setTextColor(accent)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 44
            )
        )

        root.addView(
            TextView(this).apply {
                text = "VIDEO PREVIEW"
                textSize = 25f
                setTextColor(white)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 60
            )
        )

        root.addView(
            TextView(this).apply {
                text = "Preview your clip and adjust playback speed."
                textSize = 13f
                setTextColor(muted)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 38
            )
        )

        videoView = VideoView(this).apply {
            setVideoURI(videoUri)
            setBackgroundColor(android.graphics.Color.BLACK)
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

        playbackStatus = TextView(this).apply {
            text = "Loading video..."
            textSize = 12f
            setTextColor(muted)
            gravity = Gravity.CENTER
        }

        root.addView(
            playbackStatus,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 36
            )
        )

        root.addView(
            TextView(this).apply {
                text = "PLAYBACK SPEED"
                textSize = 11f
                letterSpacing = 0.1f
                setTextColor(accent)
                gravity = Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 32
            )
        )

        speedLabel = TextView(this).apply {
            text = "Current speed: 1.0×"
            textSize = 15f
            setTextColor(white)
            gravity = Gravity.CENTER
        }

        root.addView(
            speedLabel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 36
            )
        )

        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        listOf(0.5f, 1.0f, 1.5f, 2.0f).forEach { speed ->
            val button = Button(this).apply {
                text = "${speed}×"
                textSize = 12f
                isAllCaps = false
                setTextColor(white)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (speed == 1.0f) accent else
                        android.graphics.Color.rgb(49, 55, 76)
                )
                setOnClickListener {
                    setPlaybackSpeed(speed)
                }
            }

            speedRow.addView(
                button,
                LinearLayout.LayoutParams(
                    0,
                    48,
                    1f
                ).apply {
                    marginEnd = 4
                }
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

        videoView.setOnPreparedListener { player ->
            playbackStatus.text = "Video ready • Playback controls available"
            player.start()
            setPlaybackSpeed(playbackSpeed)
        }

        videoView.setOnCompletionListener {
            playbackStatus.text = "Preview finished • Replay using the controls"
        }

        videoView.setOnErrorListener { _, _, _ ->
            playbackStatus.text = "Could not play this video format."
            true
        }

        fullScreenButton = Button(this).apply {
            text = "⛶  ENTER FULL SCREEN"
            isAllCaps = false
            setTextColor(white)
            backgroundTintList =
                android.content.res.ColorStateList.valueOf(accent)
            setOnClickListener {
                toggleFullScreen()
            }
        }

        root.addView(
            fullScreenButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 52
            ).apply {
                topMargin = 10
            }
        )

        val closeButton = Button(this).apply {
            text = "BACK TO VIDEO EDITOR"
            isAllCaps = false
            setTextColor(white)
            backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.rgb(49, 55, 76)
                )
            setOnClickListener {
                if (videoView.isPlaying) {
                    videoView.stopPlayback()
                }
                finish()
            }
        }

        root.addView(
            closeButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 54
            ).apply {
                topMargin = 8
            }
        )

        setContentView(root)
    }

    private fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed

        speedLabel.text = "Current speed: ${speed}×"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                videoView.setOnPreparedListener { player ->
                    player.playbackParams = PlaybackParams().setSpeed(speed)
                    playbackStatus.text =
                        "Video ready • Playback speed ${speed}×"
                    player.start()
                }

                // Apply immediately if the video is already prepared.
                if (videoView.isPlaying) {
                    videoView.setOnPreparedListener { player ->
                        player.playbackParams =
                            PlaybackParams().setSpeed(speed)
                    }
                }
            } catch (_: Exception) {
                playbackStatus.text =
                    "This device does not support the selected speed."
            }
        } else {
            playbackStatus.text =
                "Playback speed controls require Android 6.0 or newer."
        }
    }

    private fun toggleFullScreen() {
        fullScreen = !fullScreen

        if (fullScreen) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

            fullScreenButton.text = "⛶  EXIT FULL SCREEN"
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_VISIBLE

            fullScreenButton.text = "⛶  ENTER FULL SCREEN"
        }
    }

    override fun onBackPressed() {
        if (fullScreen) {
            toggleFullScreen()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::videoView.isInitialized) {
            videoView.stopPlayback()
        }
        super.onDestroy()
    }
}


package com.kalize.recorder

import android.app.Activity
import android.net.Uri
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
    private lateinit var videoView: VideoView
    private lateinit var fullScreenButton: Button

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
                text = "Watch your selected clip before editing."
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

        val playbackStatus = TextView(this).apply {
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

        videoView.setOnPreparedListener { player ->
            playbackStatus.text = "Video ready • Use the playback controls"
            player.start()
        }

        videoView.setOnCompletionListener {
            playbackStatus.text = "Preview finished. You can replay the clip."
        }

        videoView.setOnErrorListener { _, _, _ ->
            playbackStatus.text = "Could not play this video format."
            true
        }

        fullScreenButton = Button(this).apply {
            text = "⛶  ENTER FULL SCREEN"
            isAllCaps = false
            setTextColor(white)
            setBackgroundColor(accent)
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
            setBackgroundColor(
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
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
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
        if (::videoView.isInitialized && videoView.isPlaying) {
            videoView.stopPlayback()
        }
        super.onDestroy()
    }
}

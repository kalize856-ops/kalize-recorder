
package com.kalize.recorder

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class SavedVideosActivity : Activity() {

    private data class SavedVideo(
        val uri: Uri,
        val name: String,
        val durationMs: Long
    )

    private lateinit var listContainer: LinearLayout
    private lateinit var statusText: TextView

    private val backgroundColor = Color.rgb(9, 13, 24)
    private val textColor = Color.WHITE
    private val mutedColor = Color.rgb(155, 169, 199)
    private val accentColor = Color.rgb(152, 116, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(backgroundColor)
        }

        val title = TextView(this).apply {
            text = "Ƙalize Saved Videos"
            textSize = 25f
            setTextColor(textColor)
            setPadding(0, 8, 0, 16)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        root.addView(title)

        val description = TextView(this).apply {
            text = "Videos saved in Movies/Kalize Recorder appear here."
            textSize = 14f
            setTextColor(mutedColor)
            setPadding(0, 0, 0, 14)
        }
        root.addView(description)

        val refreshButton = Button(this).apply {
            text = "Refresh video list"
            setOnClickListener { loadVideos() }
        }
        root.addView(refreshButton)

        statusText = TextView(this).apply {
            textSize = 15f
            setTextColor(mutedColor)
            setPadding(0, 16, 0, 12)
        }
        root.addView(statusText)

        val scrollView = ScrollView(this)
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        scrollView.addView(
            listContainer,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val backButton = Button(this).apply {
            text = "Back to Video Editor"
            setOnClickListener { finish() }
        }
        root.addView(backButton)

        setContentView(root)
        loadVideos()
    }

    override fun onResume() {
        super.onResume()

        if (::listContainer.isInitialized) {
            loadVideos()
        }
    }

    private fun loadVideos() {
        listContainer.removeAllViews()
        statusText.text = "Loading saved videos..."

        try {
            val videos = querySavedVideos()

            if (videos.isEmpty()) {
                statusText.text =
                    "No videos found yet. Export a video from the editor, then tap Refresh."
                return
            }

            statusText.text =
                "${videos.size} video(s) found"

            for (video in videos) {
                addVideoCard(video)
            }
        } catch (e: Exception) {
            statusText.text =
                "Could not load videos. Please try refreshing."
        }
    }

    private fun querySavedVideos(): List<SavedVideo> {
        val results = mutableListOf<SavedVideo>()

        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.RELATIVE_PATH
            )
        } else {
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION
            )
        }

        val selection: String?
        val selectionArgs: Array<String>?

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection =
                "${MediaStore.Video.Media.RELATIVE_PATH} = ?"
            selectionArgs = arrayOf("Movies/Kalize Recorder/")
        } else {
            selection = null
            selectionArgs = null
        }

        val sortOrder =
            "${MediaStore.Video.Media.DATE_ADDED} DESC"

        val cursor: Cursor? = contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )

        cursor?.use {
            val idColumn =
                it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)

            val nameColumn =
                it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)

            val durationColumn =
                it.getColumnIndex(MediaStore.Video.Media.DURATION)

            while (it.moveToNext()) {
                val id = it.getLong(idColumn)

                val name = it.getString(nameColumn)
                    ?: "Untitled video"

                val duration = if (durationColumn >= 0) {
                    it.getLong(durationColumn)
                } else {
                    0L
                }

                val videoUri = ContentUris.withAppendedId(
                    collection,
                    id
                )

                results.add(
                    SavedVideo(
                        uri = videoUri,
                        name = name,
                        durationMs = duration
                    )
                )
            }
        }

        return results
    }

    private fun addVideoCard(video: SavedVideo) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 12)
            setBackgroundColor(Color.rgb(21, 30, 49))
        }

        val nameText = TextView(this).apply {
            text = video.name
            textSize = 16f
            setTextColor(textColor)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        card.addView(nameText)

        val durationText = TextView(this).apply {
            text = "Duration: ${formatDuration(video.durationMs)}"
            textSize = 13f
            setTextColor(mutedColor)
            setPadding(0, 6, 0, 8)
        }
        card.addView(durationText)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val playButton = Button(this).apply {
            text = "Play"
            setOnClickListener { playVideo(video) }
        }

        val shareButton = Button(this).apply {
            text = "Share"
            setOnClickListener { shareVideo(video) }
        }

        buttons.addView(
            playButton,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        buttons.addView(
            shareButton,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        card.addView(buttons)

        val cardParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 14
        }

        listContainer.addView(card, cardParams)
    }

    private fun playVideo(video: SavedVideo) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(video.uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "No compatible video player was found.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun shareVideo(video: SavedVideo) {
        try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, video.uri)
                clipData = android.content.ClipData.newUri(
                    contentResolver,
                    video.name,
                    video.uri
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(
                Intent.createChooser(sendIntent, "Share video")
            )
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Could not share this video.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format(
                Locale.US,
                "%d:%02d:%02d",
                hours,
                minutes,
                seconds
            )
        } else {
            String.format(
                Locale.US,
                "%d:%02d",
                minutes,
                seconds
            )
        }
    }
}

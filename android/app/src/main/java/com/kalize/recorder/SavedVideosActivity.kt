
package com.kalize.recorder

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SavedVideosActivity : Activity() {

    private data class SavedVideo(
        val uri: Uri,
        val name: String,
        val durationMs: Long,
        val dateAdded: Long
    )

    private lateinit var listContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var searchInput: EditText
    private lateinit var clearSearchButton: Button
    private lateinit var emptyText: TextView
    private lateinit var sortSpinner: Spinner

    private var allVideos: List<SavedVideo> = emptyList()

    private val backgroundColor = Color.rgb(9, 13, 27)
    private val cardColor = Color.rgb(20, 28, 48)
    private val textColor = Color.WHITE
    private val mutedColor = Color.rgb(164, 177, 202)
    private val accentColor = Color.rgb(139, 92, 246)
    private val accentBlue = Color.rgb(65, 105, 225)

    private val sortOptions = arrayOf(
        "Newest first",
        "Oldest first",
        "Name A–Z",
        "Longest videos",
        "Shortest videos"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        loadVideos()
    }

    override fun onResume() {
        super.onResume()
        if (::listContainer.isInitialized) {
            loadVideos()
        }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            setBackgroundColor(backgroundColor)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val headingColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        headingColumn.addView(TextView(this).apply {
            text = "ƘALIZE STUDIO  /  LIBRARY"
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(accentColor)
            setTypeface(null, Typeface.BOLD)
        })

        headingColumn.addView(TextView(this).apply {
            text = "Saved Videos"
            textSize = 27f
            setTextColor(textColor)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(4))
        })

        headingColumn.addView(TextView(this).apply {
            text = "Your clips. Ready to replay and share."
            textSize = 13f
            setTextColor(mutedColor)
        })

        header.addView(
            headingColumn,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        val refreshButton = makeButton("↻", accentColor)
        refreshButton.contentDescription = "Refresh saved videos"
        refreshButton.setOnClickListener { loadVideos() }

        header.addView(
            refreshButton,
            LinearLayout.LayoutParams(dp(52), dp(48))
        )
        root.addView(header)

        val summaryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBackground(
                Color.rgb(28, 32, 61),
                dp(18),
                Color.rgb(67, 63, 125)
            )
        }

        summaryCard.addView(TextView(this).apply {
            text = "YOUR VIDEO COLLECTION"
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(mutedColor)
            setTypeface(null, Typeface.BOLD)
        })

        statusText = TextView(this).apply {
            text = "Loading your library..."
            textSize = 18f
            setTextColor(textColor)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(3))
        }
        summaryCard.addView(statusText)

        summaryCard.addView(TextView(this).apply {
            text = "Videos exported to Movies/Kalize Recorder appear here."
            textSize = 12f
            setTextColor(mutedColor)
        })

        root.addView(
            summaryCard,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(20)
                bottomMargin = dp(16)
            }
        )

        searchInput = EditText(this).apply {
            hint = "Search your videos..."
            textSize = 14f
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            isSingleLine = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBackground(
                Color.rgb(24, 32, 51),
                dp(14),
                Color.rgb(45, 56, 81)
            )

            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {}

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    if (::clearSearchButton.isInitialized) {
                        clearSearchButton.visibility =
                            if (s.isNullOrEmpty()) {
                                View.GONE
                            } else {
                                View.VISIBLE
                            }
                    }

                    displayVideos()
                }

                override fun afterTextChanged(
                    s: android.text.Editable?
                ) {}
            })
        }

        clearSearchButton = makeButton(
            "×",
            Color.rgb(39, 48, 70)
        ).apply {
            contentDescription = "Clear video search"
            visibility = View.GONE

            setOnClickListener {
                searchInput.text.clear()
                searchInput.requestFocus()
            }
        }

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        searchRow.addView(
            searchInput,
            LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginEnd = dp(8)
            }
        )

        searchRow.addView(
            clearSearchButton,
            LinearLayout.LayoutParams(dp(52), dp(52))
        )

        root.addView(
            searchRow,
            LinearLayout.LayoutParams(-1, dp(52))
        )

        root.addView(TextView(this).apply {
            text = "SORT YOUR CLIPS"
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(mutedColor)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(2), dp(14), 0, dp(6))
        })

        sortSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SavedVideosActivity,
                android.R.layout.simple_spinner_dropdown_item,
                sortOptions
            ).also {
                it.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item
                )
            }

            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = roundedBackground(
                Color.rgb(24, 32, 51),
                dp(12),
                Color.rgb(45, 56, 81)
            )

            onItemSelectedListener =
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        displayVideos()
                    }

                    override fun onNothingSelected(
                        parent: android.widget.AdapterView<*>?
                    ) {}
                }
        }

        root.addView(
            sortSpinner,
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                bottomMargin = dp(4)
            }
        )

        root.addView(TextView(this).apply {
            text = "YOUR CLIPS"
            textSize = 12f
            letterSpacing = 0.10f
            setTextColor(mutedColor)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(2), dp(12), 0, dp(10))
        })

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        emptyText = TextView(this).apply {
            text = "Your library is waiting for its first clip."
            textSize = 14f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(32), dp(20), dp(32))
            visibility = View.GONE
            background = roundedBackground(cardColor, dp(18))
        }

        scrollView.addView(
            listContainer,
            ViewGroup.LayoutParams(-1, -2)
        )

        root.addView(
            scrollView,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        val backButton = makeButton(
            "←  Back to Video Editor",
            Color.rgb(39, 48, 70)
        )
        backButton.setOnClickListener { finish() }

        root.addView(
            backButton,
            LinearLayout.LayoutParams(-1, dp(50)).apply {
                topMargin = dp(12)
            }
        )

        setContentView(root)
    }

    private fun loadVideos() {
        if (::statusText.isInitialized) {
            statusText.text = "Loading your library..."
        }

        try {
            allVideos = querySavedVideos()
            displayVideos()
        } catch (_: Exception) {
            allVideos = emptyList()
            displayVideos()

            if (::statusText.isInitialized) {
                statusText.text =
                    "Could not load videos. Tap refresh to retry."
            }

            Toast.makeText(
                this,
                "Could not load saved videos.",
                Toast.LENGTH_SHORT
            ).show()
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
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.RELATIVE_PATH
            )
        } else {
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.DATE_ADDED
            )
        }

        val selection: String?
        val selectionArgs: Array<String>?

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Video.Media.RELATIVE_PATH} = ?"
            selectionArgs = arrayOf("Movies/Kalize Recorder/")
        } else {
            selection = null
            selectionArgs = null
        }

        val cursor: Cursor? = contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )

        cursor?.use {
            val idColumn =
                it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn =
                it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationColumn =
                it.getColumnIndex(MediaStore.Video.Media.DURATION)
            val dateColumn =
                it.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)

            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                val name = it.getString(nameColumn) ?: "Untitled video"

                val duration = if (durationColumn >= 0) {
                    it.getLong(durationColumn)
                } else {
                    0L
                }

                val dateAdded = if (dateColumn >= 0) {
                    it.getLong(dateColumn)
                } else {
                    0L
                }

                results.add(
                    SavedVideo(
                        uri = ContentUris.withAppendedId(collection, id),
                        name = name,
                        durationMs = duration,
                        dateAdded = dateAdded
                    )
                )
            }
        }

        return results
    }

    private fun displayVideos() {
        if (!::listContainer.isInitialized) return

        listContainer.removeAllViews()

        val query = searchInput.text.toString().trim()

        val filteredVideos = if (query.isBlank()) {
            allVideos
        } else {
            allVideos.filter {
                it.name.contains(query, ignoreCase = true)
            }
        }

        val sortedVideos = when (
            if (::sortSpinner.isInitialized) sortSpinner.selectedItemPosition
            else 0
        ) {
            1 -> filteredVideos.sortedBy { it.dateAdded }
            2 -> filteredVideos.sortedBy {
                it.name.lowercase(Locale.ROOT)
            }
            3 -> filteredVideos.sortedByDescending { it.durationMs }
            4 -> filteredVideos.sortedBy { it.durationMs }
            else -> filteredVideos.sortedByDescending { it.dateAdded }
        }

        statusText.text = if (allVideos.size == 1) {
            "1 video in your library"
        } else {
            "${allVideos.size} videos in your library"
        }

        if (sortedVideos.isEmpty()) {
            emptyText.text = if (allVideos.isEmpty()) {
                "No saved videos yet.\n\nExport a clip from the Video Editor, then refresh your library."
            } else {
                "No videos match \"$query\".\n\nTry another search."
            }

            emptyText.visibility = View.VISIBLE
            listContainer.addView(
                emptyText,
                LinearLayout.LayoutParams(-1, -2)
            )
            return
        }

        emptyText.visibility = View.GONE
        sortedVideos.forEach { video -> addVideoCard(video) }
    }

    private fun addVideoCard(video: SavedVideo) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(cardColor, dp(18))
        }

        val thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Thumbnail for ${video.name}"
            setBackgroundColor(Color.rgb(31, 41, 65))
        }

        try {
            val bitmap: Bitmap? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.loadThumbnail(
                        video.uri,
                        android.util.Size(640, 360),
                        null
                    )
                } else {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(
                            this@SavedVideosActivity,
                            video.uri
                        )
                        retriever.getFrameAtTime(
                            0,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                    } finally {
                        retriever.release()
                    }
                }

            if (bitmap != null) thumbnail.setImageBitmap(bitmap)
        } catch (_: Exception) {
            // Keep the placeholder if a thumbnail cannot be loaded.
        }

        card.addView(
            thumbnail,
            LinearLayout.LayoutParams(-1, dp(185))
        )

        card.addView(TextView(this).apply {
            text = video.name
            textSize = 16f
            setTextColor(textColor)
            setTypeface(null, Typeface.BOLD)
            maxLines = 2
            setPadding(dp(2), dp(12), dp(2), dp(3))
        })

        card.addView(TextView(this).apply {
            text = "VIDEO  •  ${formatDuration(video.durationMs)}"
            textSize = 12f
            setTextColor(mutedColor)
            setPadding(dp(2), dp(2), 0, dp(12))
        })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val playButton = makeButton("▶ Play", accentColor)
        playButton.setOnClickListener { playVideo(video) }

        val shareButton = makeButton("Share", accentBlue)
        shareButton.setOnClickListener { shareVideo(video) }

        val deleteButton = makeButton(
            "Delete",
            Color.rgb(72, 36, 54)
        )
        deleteButton.setTextColor(Color.rgb(255, 155, 170))
        deleteButton.setOnClickListener { confirmDelete(video) }

        buttons.addView(
            playButton,
            LinearLayout.LayoutParams(0, dp(46), 1.15f).apply {
                marginEnd = dp(5)
            }
        )

        buttons.addView(
            shareButton,
            LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginEnd = dp(5)
            }
        )

        buttons.addView(
            deleteButton,
            LinearLayout.LayoutParams(0, dp(46), 1f)
        )

        card.addView(buttons)

        val detailsButton = makeButton(
            "ⓘ  View video details",
            Color.rgb(39, 48, 70)
        )
        detailsButton.setOnClickListener {
            showVideoDetails(video)
        }

        card.addView(
            detailsButton,
            LinearLayout.LayoutParams(-1, dp(46)).apply {
                topMargin = dp(8)
            }
        )

        val renameButton = makeButton(
            "✎  Rename video",
            Color.rgb(39, 48, 70)
        )
        renameButton.setOnClickListener { showRenameDialog(video) }

        card.addView(
            renameButton,
            LinearLayout.LayoutParams(-1, dp(46)).apply {
                topMargin = dp(8)
            }
        )

        listContainer.addView(
            card,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(14)
            }
        )
    }

    private fun showRenameDialog(video: SavedVideo) {
        val nameInput = EditText(this).apply {
            setText(video.name.substringBeforeLast('.', video.name))
            hint = "Enter a new video name"
            isSingleLine = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)

            addView(
                nameInput,
                LinearLayout.LayoutParams(-1, dp(52))
            )

            addView(TextView(this@SavedVideosActivity).apply {
                text = ".mp4 will be added automatically."
                textSize = 12f
                setTextColor(mutedColor)
                setPadding(0, dp(6), 0, 0)
            })
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Rename video")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val typedName = nameInput.text.toString().trim()

                    val safeName = typedName
                        .substringBeforeLast('.', typedName)
                        .replace(Regex("[^A-Za-z0-9 _-]"), "")
                        .trim()
                        .replace(Regex("\\s+"), "_")
                        .take(60)

                    if (safeName.isBlank()) {
                        nameInput.error = "Enter a valid file name"
                        return@setOnClickListener
                    }

                    val newName = "$safeName.mp4"

                    if (newName.equals(video.name, ignoreCase = true)) {
                        dialog.dismiss()
                        return@setOnClickListener
                    }

                    try {
                        val values = ContentValues().apply {
                            put(
                                MediaStore.Video.Media.DISPLAY_NAME,
                                newName
                            )
                        }

                        val updated = contentResolver.update(
                            video.uri,
                            values,
                            null,
                            null
                        )

                        if (updated > 0) {
                            Toast.makeText(
                                this,
                                "Video renamed.",
                                Toast.LENGTH_SHORT
                            ).show()

                            dialog.dismiss()
                            loadVideos()
                        } else {
                            Toast.makeText(
                                this,
                                "Could not rename this video.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } catch (_: SecurityException) {
                        Toast.makeText(
                            this,
                            "Android did not allow this rename. Try renaming a video saved by Kalize Recorder.",
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (_: Exception) {
                        Toast.makeText(
                            this,
                            "Could not rename this video.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
        }

        dialog.show()
    }

    private fun showVideoDetails(video: SavedVideo) {
        val savedDate = if (video.dateAdded > 0L) {
            SimpleDateFormat(
                "dd MMM yyyy, hh:mm a",
                Locale.getDefault()
            ).format(Date(video.dateAdded * 1000L))
        } else {
            "Date unavailable"
        }

        val details = """
            File name:
            ${video.name}

            Duration:
            ${formatDuration(video.durationMs)}

            Saved date:
            $savedDate

            File location:
            Movies/Kalize Recorder/
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Video details")
            .setMessage(details)
            .setPositiveButton("Done", null)
            .setNeutralButton("Share video") { _, _ ->
                shareVideo(video)
            }
            .show()
    }

    private fun confirmDelete(video: SavedVideo) {
        AlertDialog.Builder(this)
            .setTitle("Delete saved video?")
            .setMessage(
                "Remove \"${video.name}\" from your saved videos? " +
                    "This cannot be undone."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                deleteVideo(video)
            }
            .show()
    }

    private fun deleteVideo(video: SavedVideo) {
        try {
            val deleted = contentResolver.delete(video.uri, null, null)

            if (deleted > 0) {
                Toast.makeText(
                    this,
                    "Video deleted.",
                    Toast.LENGTH_SHORT
                ).show()
                loadVideos()
            } else {
                Toast.makeText(
                    this,
                    "The video could not be deleted.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (_: SecurityException) {
            Toast.makeText(
                this,
                "Android did not allow deletion. Please try again.",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Could not delete this video.",
                Toast.LENGTH_LONG
            ).show()
        }
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

    private fun makeButton(label: String, color: Int): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = roundedBackground(color, dp(13))
            stateListAnimator = null
            minHeight = dp(44)
            minimumHeight = dp(44)
            setPadding(dp(5), dp(4), dp(5), dp(4))
        }
    }

    private fun roundedBackground(
        color: Int,
        radius: Int,
        strokeColor: Int? = null
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeColor != null) {
                setStroke(dp(1), strokeColor)
            }
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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}

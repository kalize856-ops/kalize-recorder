
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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
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
        val dateAdded: Long,
        val sizeBytes: Long
    )

    private lateinit var listContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var storageText: TextView
    private lateinit var searchInput: EditText
    private lateinit var sortSpinner: Spinner
    private lateinit var bulkStatus: TextView
    private lateinit var selectAllButton: Button
    private lateinit var bulkShareButton: Button
    private lateinit var bulkDeleteButton: Button

    private var allVideos = listOf<SavedVideo>()
    private val selectedUris = mutableSetOf<String>()
    private val favoriteUris = mutableSetOf<String>()

    private val favoritePreferences by lazy {
        getSharedPreferences("saved_video_favorites", MODE_PRIVATE)
    }

    private val sortPreferences by lazy {
        getSharedPreferences("saved_videos_preferences", MODE_PRIVATE)
    }

    private val pageColor = Color.rgb(9, 13, 27)
    private val cardColor = Color.rgb(20, 28, 48)
    private val mutedColor = Color.rgb(164, 177, 202)
    private val accentColor = Color.rgb(139, 92, 246)
    private val accentBlue = Color.rgb(65, 105, 225)

    private val sortOptions = arrayOf(
        "Newest first",
        "Oldest first",
        "Name A–Z",
        "Longest videos",
        "Shortest videos",
        "Largest files",
        "Smallest files",
        "Favorites first"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        favoriteUris.addAll(
            favoritePreferences.getStringSet(
                "favorite_uris",
                emptySet()
            ) ?: emptySet()
        )

        buildScreen()
        loadVideos()
    }

    override fun onResume() {
        super.onResume()
        if (::listContainer.isInitialized) loadVideos()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun rounded(
        color: Int,
        radius: Int = dp(14),
        stroke: Int? = null
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun makeButton(
        label: String,
        color: Int = Color.rgb(39, 48, 70)
    ): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTextColor(Color.WHITE)
        setTypeface(null, Typeface.BOLD)
        background = rounded(color)
        stateListAnimator = null
        minHeight = dp(44)
        minimumHeight = dp(44)
        setPadding(dp(5), dp(4), dp(5), dp(4))
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            setBackgroundColor(pageColor)
        }

        root.addView(TextView(this).apply {
            text = "KALIZE STUDIO  /  LIBRARY"
            textSize = 11f
            letterSpacing = 0.1f
            setTextColor(accentColor)
            setTypeface(null, Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "Saved Videos"
            textSize = 27f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(4))
        })

        root.addView(TextView(this).apply {
            text = "Replay, organize and share your clips."
            textSize = 13f
            setTextColor(mutedColor)
        })

        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(12), dp(15), dp(12))
            background = rounded(Color.rgb(28, 32, 61), dp(16))
        }

        statusText = TextView(this).apply {
            text = "Loading your library..."
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }

        storageText = TextView(this).apply {
            text = "Storage used: calculating..."
            textSize = 13f
            setTextColor(Color.rgb(190, 170, 255))
            setPadding(0, dp(5), 0, 0)
        }

        summary.addView(statusText)
        summary.addView(storageText)

        root.addView(
            summary,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(15)
                bottomMargin = dp(12)
            }
        )

        searchInput = EditText(this).apply {
            hint = "Search your videos..."
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(mutedColor)
            isSingleLine = true
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(Color.rgb(24, 32, 51))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?, start: Int, count: Int, after: Int
                ) {}

                override fun onTextChanged(
                    s: CharSequence?, start: Int, before: Int, count: Int
                ) {
                    displayVideos()
                }

                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }

        root.addView(
            searchInput,
            LinearLayout.LayoutParams(-1, dp(50)).apply {
                bottomMargin = dp(8)
            }
        )

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

            setSelection(
                sortPreferences.getInt("sort_position", 0)
                    .coerceIn(0, sortOptions.lastIndex)
            )

            background = rounded(Color.rgb(24, 32, 51))

            onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        sortPreferences.edit()
                            .putInt("sort_position", position)
                            .apply()
                        displayVideos()
                    }

                    override fun onNothingSelected(
                        parent: AdapterView<*>?
                    ) {}
                }
        }

        root.addView(
            sortSpinner,
            LinearLayout.LayoutParams(-1, dp(46)).apply {
                bottomMargin = dp(8)
            }
        )

        bulkStatus = TextView(this).apply {
            text = "Select videos using the boxes on each card."
            textSize = 12f
            setTextColor(mutedColor)
            setPadding(dp(2), dp(3), dp(2), dp(6))
        }

        root.addView(bulkStatus)

        val bulkRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        selectAllButton = makeButton(
            "Select all",
            Color.rgb(49, 43, 91)
        )
        selectAllButton.setOnClickListener { toggleSelectAllVisible() }

        val clearButton = makeButton("Clear selection")
        clearButton.setOnClickListener {
            selectedUris.clear()
            displayVideos()
        }

        bulkRow1.addView(
            selectAllButton,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginEnd = dp(5)
            }
        )
        bulkRow1.addView(
            clearButton,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginStart = dp(5)
            }
        )

        root.addView(
            bulkRow1,
            LinearLayout.LayoutParams(-1, dp(44)).apply {
                bottomMargin = dp(6)
            }
        )

        val bulkRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        bulkShareButton = makeButton("Share selected", accentBlue)
        bulkShareButton.setOnClickListener { shareSelectedVideos() }

        bulkDeleteButton = makeButton(
            "Delete selected",
            Color.rgb(91, 34, 53)
        )
        bulkDeleteButton.setTextColor(Color.rgb(255, 175, 185))
        bulkDeleteButton.setOnClickListener { confirmBulkDelete() }

        bulkRow2.addView(
            bulkShareButton,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginEnd = dp(5)
            }
        )
        bulkRow2.addView(
            bulkDeleteButton,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginStart = dp(5)
            }
        )

        root.addView(
            bulkRow2,
            LinearLayout.LayoutParams(-1, dp(44)).apply {
                bottomMargin = dp(10)
            }
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        scroll.addView(
            listContainer,
            ViewGroup.LayoutParams(-1, -2)
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        val backButton = makeButton("← Back to Video Editor")
        backButton.setOnClickListener { finish() }

        root.addView(
            backButton,
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(10)
            }
        )

        setContentView(root)
    }

    private fun loadVideos() {
        try {
            allVideos = querySavedVideos()

            val validUris = allVideos.map {
                it.uri.toString()
            }.toSet()

            selectedUris.retainAll(validUris)
            favoriteUris.retainAll(validUris)

            favoritePreferences.edit()
                .putStringSet("favorite_uris", favoriteUris.toSet())
                .apply()

            displayVideos()
        } catch (_: Exception) {
            allVideos = emptyList()
            displayVideos()
            statusText.text = "Could not load videos. Reopen or refresh."
            storageText.text = "Storage used: unavailable"
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
                MediaStore.Video.Media.RELATIVE_PATH,
                MediaStore.Video.Media.SIZE
            )
        } else {
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.SIZE
            )
        }

        val selection: String?
        val args: Array<String>?

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Video.Media.RELATIVE_PATH} = ?"
            args = arrayOf("Movies/Kalize Recorder/")
        } else {
            selection = null
            args = null
        }

        val cursor: Cursor? = contentResolver.query(
            collection,
            projection,
            selection,
            args,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )

        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(
                MediaStore.Video.Media._ID
            )
            val nameCol = it.getColumnIndexOrThrow(
                MediaStore.Video.Media.DISPLAY_NAME
            )
            val durationCol = it.getColumnIndex(
                MediaStore.Video.Media.DURATION
            )
            val dateCol = it.getColumnIndex(
                MediaStore.Video.Media.DATE_ADDED
            )
            val sizeCol = it.getColumnIndex(
                MediaStore.Video.Media.SIZE
            )

            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val name = it.getString(nameCol) ?: "Untitled video"
                val duration = if (durationCol >= 0) {
                    it.getLong(durationCol)
                } else 0L
                val date = if (dateCol >= 0) {
                    it.getLong(dateCol)
                } else 0L
                val size = if (sizeCol >= 0) {
                    it.getLong(sizeCol).coerceAtLeast(0L)
                } else 0L

                results.add(
                    SavedVideo(
                        ContentUris.withAppendedId(collection, id),
                        name,
                        duration,
                        date,
                        size
                    )
                )
            }
        }

        return results
    }

    private fun displayVideos() {
        if (!::listContainer.isInitialized) return

        listContainer.removeAllViews()

        val totalBytes = allVideos.sumOf { it.sizeBytes }
        storageText.text = "Storage used: ${formatFileSize(totalBytes)}"

        val query = searchInput.text.toString().trim()

        val filtered = allVideos.filter {
            it.name.contains(query, ignoreCase = true)
        }

        val sorted = when (
            if (::sortSpinner.isInitialized) {
                sortSpinner.selectedItemPosition
            } else 0
        ) {
            1 -> filtered.sortedBy { it.dateAdded }
            2 -> filtered.sortedBy { it.name.lowercase(Locale.ROOT) }
            3 -> filtered.sortedByDescending { it.durationMs }
            4 -> filtered.sortedBy { it.durationMs }
            5 -> filtered.sortedByDescending { it.sizeBytes }
            6 -> filtered.sortedBy { it.sizeBytes }
            7 -> filtered.sortedByDescending {
                favoriteUris.contains(it.uri.toString())
            }
            else -> filtered.sortedByDescending { it.dateAdded }
        }

        statusText.text = if (query.isNotBlank()) {
            "Showing ${sorted.size} of ${allVideos.size} videos"
        } else {
            "${allVideos.size} video(s) in your library"
        }

        bulkStatus.text =
            "${selectedUris.size} video(s) selected"

        selectAllButton.text =
            if (sorted.isNotEmpty() &&
                sorted.all { selectedUris.contains(it.uri.toString()) }
            ) "Deselect visible" else "Select all visible"

        bulkShareButton.isEnabled = selectedUris.isNotEmpty()
        bulkDeleteButton.isEnabled = selectedUris.isNotEmpty()
        bulkShareButton.alpha =
            if (bulkShareButton.isEnabled) 1f else 0.5f
        bulkDeleteButton.alpha =
            if (bulkDeleteButton.isEnabled) 1f else 0.5f

        if (sorted.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = if (allVideos.isEmpty()) {
                    "No saved videos yet.\n\nExport a clip from Video Editor to see it here."
                } else {
                    "No videos match \"$query\"."
                }
                textSize = 14f
                setTextColor(mutedColor)
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(30), dp(20), dp(30))
                background = rounded(cardColor)
            })
            return
        }

        sorted.forEach { addVideoCard(it) }
    }

    private fun toggleSelectAllVisible() {
        val query = searchInput.text.toString().trim()
        val visible = allVideos.filter {
            it.name.contains(query, ignoreCase = true)
        }

        val allSelected = visible.isNotEmpty() && visible.all {
            selectedUris.contains(it.uri.toString())
        }

        if (allSelected) {
            visible.forEach {
                selectedUris.remove(it.uri.toString())
            }
        } else {
            visible.forEach {
                selectedUris.add(it.uri.toString())
            }
        }

        displayVideos()
    }

    private fun addVideoCard(video: SavedVideo) {
        val key = video.uri.toString()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(
                cardColor,
                dp(18),
                if (selectedUris.contains(key)) accentColor else null
            )
        }

        val selectionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val check = CheckBox(this).apply {
            text = "Select this video"
            textSize = 13f
            setTextColor(Color.WHITE)
            isChecked = selectedUris.contains(key)
            buttonTintList = android.content.res.ColorStateList.valueOf(
                accentColor
            )
            setOnCheckedChangeListener { _, checked ->
                if (checked) selectedUris.add(key)
                else selectedUris.remove(key)
                bulkStatus.text =
                    "${selectedUris.size} video(s) selected"
                displayVideos()
            }
        }

        selectionRow.addView(
            check,
            LinearLayout.LayoutParams(-1, dp(42))
        )
        card.addView(selectionRow)

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
                        retriever.setDataSource(this@SavedVideosActivity, video.uri)
                        retriever.getFrameAtTime(
                            0,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                    } finally {
                        retriever.release()
                    }
                }

            if (bitmap != null) thumbnail.setImageBitmap(bitmap)
        } catch (_: Exception) {}

        thumbnail.setOnClickListener { playVideo(video) }

        card.addView(
            thumbnail,
            LinearLayout.LayoutParams(-1, dp(185))
        )

        card.addView(TextView(this).apply {
            text = video.name
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            maxLines = 2
            setPadding(dp(2), dp(12), dp(2), dp(3))
        })

        card.addView(TextView(this).apply {
            text = "VIDEO  •  ${formatDuration(video.durationMs)}  •  ${formatFileSize(video.sizeBytes)}"
            textSize = 12f
            setTextColor(mutedColor)
            setPadding(dp(2), dp(2), 0, dp(12))
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val play = makeButton("▶ Play", accentColor)
        play.setOnClickListener { playVideo(video) }

        val share = makeButton("Share", accentBlue)
        share.setOnClickListener { shareVideo(video) }

        val delete = makeButton("Delete", Color.rgb(72, 36, 54))
        delete.setTextColor(Color.rgb(255, 155, 170))
        delete.setOnClickListener { confirmDelete(video) }

        listOf(play, share, delete).forEachIndexed { index, button ->
            row.addView(
                button,
                LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    if (index < 2) marginEnd = dp(5)
                }
            )
        }

        card.addView(row)

        val favorite = makeButton(
            if (favoriteUris.contains(key)) {
                "★ Remove from favorites"
            } else {
                "☆ Add to favorites"
            },
            Color.rgb(67, 52, 100)
        )

        favorite.setOnClickListener { toggleFavorite(video) }

        card.addView(
            favorite,
            LinearLayout.LayoutParams(-1, dp(44)).apply {
                topMargin = dp(7)
            }
        )

        val details = makeButton("ⓘ View video details")
        details.setOnClickListener { showVideoDetails(video) }

        card.addView(
            details,
            LinearLayout.LayoutParams(-1, dp(44)).apply {
                topMargin = dp(7)
            }
        )

        val rename = makeButton("✎ Rename video")
        rename.setOnClickListener { showRenameDialog(video) }

        card.addView(
            rename,
            LinearLayout.LayoutParams(-1, dp(44)).apply {
                topMargin = dp(7)
            }
        )

        listContainer.addView(
            card,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(14)
            }
        )
    }

    private fun toggleFavorite(video: SavedVideo) {
        val key = video.uri.toString()

        if (favoriteUris.contains(key)) favoriteUris.remove(key)
        else favoriteUris.add(key)

        favoritePreferences.edit()
            .putStringSet("favorite_uris", favoriteUris.toSet())
            .apply()

        displayVideos()
    }

    private fun showRenameDialog(video: SavedVideo) {
        val input = EditText(this).apply {
            setText(video.name.substringBeforeLast('.', video.name))
            isSingleLine = true
            hint = "Enter a new video name"
        }

        AlertDialog.Builder(this)
            .setTitle("Rename video")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val safeName = input.text.toString()
                    .trim()
                    .substringBeforeLast('.', input.text.toString().trim())
                    .replace(Regex("[^A-Za-z0-9 _-]"), "")
                    .replace(Regex("\\s+"), "_")
                    .take(60)

                if (safeName.isBlank()) {
                    Toast.makeText(
                        this,
                        "Enter a valid file name.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }

                try {
                    val values = ContentValues().apply {
                        put(
                            MediaStore.Video.Media.DISPLAY_NAME,
                            "$safeName.mp4"
                        )
                    }

                    val updated = contentResolver.update(
                        video.uri, values, null, null
                    )

                    Toast.makeText(
                        this,
                        if (updated > 0) "Video renamed." else "Rename failed.",
                        Toast.LENGTH_SHORT
                    ).show()

                    loadVideos()
                } catch (_: Exception) {
                    Toast.makeText(
                        this,
                        "Could not rename this video.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .show()
    }

    private fun showVideoDetails(video: SavedVideo) {
        val date = if (video.dateAdded > 0L) {
            SimpleDateFormat(
                "dd MMM yyyy, hh:mm a",
                Locale.getDefault()
            ).format(Date(video.dateAdded * 1000L))
        } else "Date unavailable"

        AlertDialog.Builder(this)
            .setTitle("Video details")
            .setMessage(
                "Name: ${video.name}\n\n" +
                    "Duration: ${formatDuration(video.durationMs)}\n\n" +
                    "Size: ${formatFileSize(video.sizeBytes)}\n\n" +
                    "Saved: $date\n\n" +
                    "Folder: Movies/Kalize Recorder/"
            )
            .setPositiveButton("Done", null)
            .setNeutralButton("Share") { _, _ -> shareVideo(video) }
            .show()
    }

    private fun confirmDelete(video: SavedVideo) {
        AlertDialog.Builder(this)
            .setTitle("Delete video?")
            .setMessage(
                "Permanently delete \"${video.name}\"?"
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                deleteVideo(video)
            }
            .show()
    }

    private fun confirmBulkDelete() {
        val selected = allVideos.filter {
            selectedUris.contains(it.uri.toString())
        }

        if (selected.isEmpty()) {
            Toast.makeText(
                this,
                "Select at least one video first.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Delete selected videos?")
            .setMessage(
                "You selected ${selected.size} video(s). " +
                    "This action cannot be undone."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete all") { _, _ ->
                var deletedCount = 0

                selected.forEach { video ->
                    try {
                        if (contentResolver.delete(
                                video.uri, null, null
                            ) > 0
                        ) {
                            deletedCount++
                            favoriteUris.remove(video.uri.toString())
                            selectedUris.remove(video.uri.toString())
                        }
                    } catch (_: Exception) {}
                }

                favoritePreferences.edit()
                    .putStringSet("favorite_uris", favoriteUris.toSet())
                    .apply()

                Toast.makeText(
                    this,
                    "Deleted $deletedCount of ${selected.size} video(s).",
                    Toast.LENGTH_LONG
                ).show()

                loadVideos()
            }
            .show()
    }

    private fun shareSelectedVideos() {
        val selected = allVideos.filter {
            selectedUris.contains(it.uri.toString())
        }

        if (selected.isEmpty()) {
            Toast.makeText(
                this,
                "Select at least one video first.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        try {
            val uris = ArrayList<Uri>()
            selected.forEach { uris.add(it.uri) }

            val intent = Intent(
                if (uris.size == 1) {
                    Intent.ACTION_SEND
                } else {
                    Intent.ACTION_SEND_MULTIPLE
                }
            ).apply {
                type = "video/*"

                if (uris.size == 1) {
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                    clipData = android.content.ClipData.newUri(
                        contentResolver,
                        selected[0].name,
                        uris[0]
                    )
                } else {
                    putParcelableArrayListExtra(
                        Intent.EXTRA_STREAM,
                        uris
                    )

                    var clips: android.content.ClipData? = null

                    uris.forEachIndexed { index, uri ->
                        if (clips == null) {
                            clips = android.content.ClipData.newUri(
                                contentResolver,
                                selected[index].name,
                                uri
                            )
                        } else {
                            clips!!.addItem(
                                android.content.ClipData.Item(uri)
                            )
                        }
                    }

                    clipData = clips
                }

                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(
                Intent.createChooser(intent, "Share selected videos")
            )
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Could not share the selected videos.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun deleteVideo(video: SavedVideo) {
        try {
            val deleted = contentResolver.delete(video.uri, null, null)

            if (deleted > 0) {
                favoriteUris.remove(video.uri.toString())
                selectedUris.remove(video.uri.toString())

                favoritePreferences.edit()
                    .putStringSet("favorite_uris", favoriteUris.toSet())
                    .apply()

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
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Android did not allow deletion.",
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
            val intent = Intent(Intent.ACTION_SEND).apply {
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
                Intent.createChooser(intent, "Share video")
            )
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Could not share this video.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"

        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = bytes / 1024.0
        var index = 0

        while (size >= 1024.0 && index < units.lastIndex) {
            size /= 1024.0
            index++
        }

        return String.format(
            Locale.US,
            "%.2f %s",
            size,
            units[index]
        )
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = durationMs / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remaining = seconds % 60

        return if (hours > 0) {
            String.format(
                Locale.US,
                "%d:%02d:%02d",
                hours,
                minutes,
                remaining
            )
        } else {
            String.format(
                Locale.US,
                "%d:%02d",
                minutes,
                remaining
            )
        }
    }
}

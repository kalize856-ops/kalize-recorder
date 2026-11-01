
package com.kalize.recorder

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenshotGalleryActivity : Activity() {

    private data class Screenshot(
        val uri: Uri,
        val name: String,
        val dateAdded: Long
    )

    private lateinit var listContainer: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var statusText: TextView
    private lateinit var sortButton: Button
    private lateinit var selectionText: TextView
    private lateinit var selectAllButton: Button
    private lateinit var shareButton: Button
    private lateinit var deleteButton: Button

    private var screenshots = listOf<Screenshot>()
    private val selectedUris = mutableSetOf<String>()
    private var favorites = mutableSetOf<String>()

    private val sortOptions = listOf(
        "Newest first",
        "Oldest first",
        "Name A-Z",
        "Favorites first"
    )

    private var sortMode = "Newest first"

    private val pageColor = Color.rgb(9, 13, 27)
    private val cardColor = Color.rgb(20, 28, 48)
    private val accentColor = Color.rgb(139, 92, 246)
    private val mutedColor = Color.rgb(164, 177, 202)

    private val prefs by lazy {
        getSharedPreferences("kalize_screenshot_gallery", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        favorites = prefs.getStringSet(
            "favorite_screenshot_uris",
            emptySet()
        )?.toMutableSet() ?: mutableSetOf()

        sortMode = prefs.getString(
            "screenshot_sort_mode",
            "Newest first"
        ) ?: "Newest first"

        if (sortMode !in sortOptions) {
            sortMode = "Newest first"
        }

        buildScreen()
        loadScreenshots()
    }

    override fun onResume() {
        super.onResume()
        if (::listContainer.isInitialized) loadScreenshots()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun makeButton(
        label: String,
        color: Int = Color.rgb(49, 43, 91)
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(color)
            minHeight = dp(42)
            minimumHeight = dp(42)
            setPadding(dp(4), dp(3), dp(4), dp(3))
        }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            setBackgroundColor(pageColor)
        }

        root.addView(TextView(this).apply {
            text = "KALIZE STUDIO  /  SCREENSHOTS"
            textSize = 11f
            setTextColor(accentColor)
            setTypeface(null, Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "Screenshot Gallery"
            textSize = 27f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(4))
        })

        root.addView(TextView(this).apply {
            text = "Find, rename, favorite, share and manage captures."
            textSize = 13f
            setTextColor(mutedColor)
            setPadding(0, 0, 0, dp(12))
        })

        searchInput = EditText(this).apply {
            hint = "Search screenshots..."
            setSingleLine(true)
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(mutedColor)
            setPadding(dp(12), 0, dp(12), 0)
            setBackgroundColor(cardColor)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?, start: Int, count: Int, after: Int
                ) {}

                override fun onTextChanged(
                    s: CharSequence?, start: Int, before: Int, count: Int
                ) {
                    displayScreenshots()
                }

                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }

        root.addView(
            searchInput,
            LinearLayout.LayoutParams(-1, dp(50)).apply {
                bottomMargin = dp(7)
            }
        )

        sortButton = makeButton("")
        updateSortButton()
        sortButton.setOnClickListener {
            val index = sortOptions.indexOf(sortMode).coerceAtLeast(0)
            sortMode = sortOptions[(index + 1) % sortOptions.size]
            prefs.edit().putString("screenshot_sort_mode", sortMode).apply()
            updateSortButton()
            displayScreenshots()
        }

        root.addView(
            sortButton,
            LinearLayout.LayoutParams(-1, dp(42)).apply {
                bottomMargin = dp(6)
            }
        )

        statusText = TextView(this).apply {
            text = "Loading screenshots..."
            textSize = 13f
            setTextColor(mutedColor)
            setPadding(dp(2), dp(5), dp(2), dp(5))
        }
        root.addView(statusText)

        selectionText = TextView(this).apply {
            text = "0 selected"
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(2), dp(3), dp(2), dp(6))
        }
        root.addView(selectionText)

        val selectionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        selectAllButton = makeButton("Select all visible")
        selectAllButton.setOnClickListener { toggleSelectAllVisible() }

        val clearButton = makeButton("Clear")
        clearButton.setOnClickListener {
            selectedUris.clear()
            displayScreenshots()
        }

        selectionRow.addView(
            selectAllButton,
            LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginEnd = dp(4)
            }
        )
        selectionRow.addView(
            clearButton,
            LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginStart = dp(4)
            }
        )

        root.addView(
            selectionRow,
            LinearLayout.LayoutParams(-1, dp(42)).apply {
                bottomMargin = dp(5)
            }
        )

        val bulkRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        shareButton = makeButton(
            "Share selected",
            Color.rgb(65, 105, 225)
        )
        shareButton.setOnClickListener { shareSelected() }

        deleteButton = makeButton(
            "Delete selected",
            Color.rgb(105, 36, 53)
        )
        deleteButton.setOnClickListener { confirmBulkDelete() }

        bulkRow.addView(
            shareButton,
            LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginEnd = dp(4)
            }
        )
        bulkRow.addView(
            deleteButton,
            LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginStart = dp(4)
            }
        )

        root.addView(
            bulkRow,
            LinearLayout.LayoutParams(-1, dp(42)).apply {
                bottomMargin = dp(8)
            }
        )

        val scroll = ScrollView(this)
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listContainer)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val refresh = makeButton("↻ Refresh")
        refresh.setOnClickListener { loadScreenshots() }

        val back = makeButton("← Back")
        back.setOnClickListener { finish() }

        bottom.addView(
            refresh,
            LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginEnd = dp(5)
            }
        )
        bottom.addView(
            back,
            LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginStart = dp(5)
            }
        )

        root.addView(
            bottom,
            LinearLayout.LayoutParams(-1, dp(46)).apply {
                topMargin = dp(10)
            }
        )

        setContentView(root)
    }

    private fun updateSortButton() {
        if (::sortButton.isInitialized) {
            sortButton.text = "Sort: $sortMode  ↻"
        }
    }

    private fun saveFavorites() {
        prefs.edit()
            .putStringSet("favorite_screenshot_uris", favorites.toSet())
            .apply()
    }

    private fun loadScreenshots() {
        try {
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED
            )

            val selection: String
            val args: Array<String>

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ?"
                args = arrayOf("Pictures/Kalize Recorder/")
            } else {
                selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
                args = arrayOf("Kalize_Screenshot_%.png")
            }

            val loaded = mutableListOf<Screenshot>()

            contentResolver.query(
                collection,
                projection,
                selection,
                args,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(
                    MediaStore.Images.Media._ID
                )
                val nameIndex = cursor.getColumnIndexOrThrow(
                    MediaStore.Images.Media.DISPLAY_NAME
                )
                val dateIndex = cursor.getColumnIndexOrThrow(
                    MediaStore.Images.Media.DATE_ADDED
                )

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex) ?: continue
                    if (!name.startsWith("Kalize_Screenshot_")) continue

                    val uri = Uri.withAppendedPath(
                        collection,
                        cursor.getLong(idIndex).toString()
                    )

                    loaded.add(
                        Screenshot(uri, name, cursor.getLong(dateIndex))
                    )
                }
            }

            screenshots = loaded
            val validUris = screenshots.map { it.uri.toString() }.toSet()
            selectedUris.retainAll(validUris)

            if (favorites.retainAll(validUris)) saveFavorites()

            displayScreenshots()
        } catch (_: Exception) {
            statusText.text = "Could not load screenshots. Tap Refresh."
        }
    }

    private fun visibleScreenshots(): List<Screenshot> {
        val query = searchInput.text.toString().trim()

        val filtered = screenshots.filter {
            it.name.contains(query, ignoreCase = true)
        }

        return when (sortMode) {
            "Oldest first" -> filtered.sortedBy { it.dateAdded }
            "Name A-Z" -> filtered.sortedBy {
                it.name.lowercase(Locale.getDefault())
            }
            "Favorites first" -> filtered.sortedWith(
                compareByDescending<Screenshot> {
                    favorites.contains(it.uri.toString())
                }.thenByDescending { it.dateAdded }
            )
            else -> filtered.sortedByDescending { it.dateAdded }
        }
    }

    private fun displayScreenshots() {
        if (!::listContainer.isInitialized) return

        listContainer.removeAllViews()
        val visible = visibleScreenshots()
        val favoriteCount = screenshots.count {
            favorites.contains(it.uri.toString())
        }

        statusText.text = when {
            screenshots.isEmpty() -> "No Kalize screenshots found yet."
            visible.isEmpty() -> "No screenshots match your search."
            else -> "${visible.size} shown  •  $favoriteCount favorite(s)"
        }

        selectionText.text = "${selectedUris.size} screenshot(s) selected"

        val allSelected = visible.isNotEmpty() && visible.all {
            selectedUris.contains(it.uri.toString())
        }

        selectAllButton.text =
            if (allSelected) "Deselect visible" else "Select all visible"

        shareButton.isEnabled = selectedUris.isNotEmpty()
        deleteButton.isEnabled = selectedUris.isNotEmpty()
        shareButton.alpha = if (shareButton.isEnabled) 1f else 0.5f
        deleteButton.alpha = if (deleteButton.isEnabled) 1f else 0.5f

        if (visible.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = if (screenshots.isEmpty()) {
                    "Screenshots captured with Kalize Recorder will appear here."
                } else {
                    "Try a different search."
                }
                textSize = 14f
                setTextColor(mutedColor)
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(30), dp(20), dp(30))
            })
            return
        }

        visible.forEach { screenshot ->
            listContainer.addView(createScreenshotCard(screenshot))
        }
    }

    private fun toggleSelectAllVisible() {
        val visible = visibleScreenshots()
        val allSelected = visible.isNotEmpty() && visible.all {
            selectedUris.contains(it.uri.toString())
        }

        visible.forEach {
            if (allSelected) selectedUris.remove(it.uri.toString())
            else selectedUris.add(it.uri.toString())
        }

        displayScreenshots()
    }

    private fun createScreenshotCard(screenshot: Screenshot): View {
        val key = screenshot.uri.toString()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(
                if (selectedUris.contains(key)) {
                    Color.rgb(39, 34, 74)
                } else cardColor
            )
        }

        val checkBox = CheckBox(this).apply {
            text = "Select screenshot"
            textSize = 12f
            setTextColor(Color.WHITE)
            isChecked = selectedUris.contains(key)
            setOnCheckedChangeListener { _, checked ->
                if (checked) selectedUris.add(key)
                else selectedUris.remove(key)
                displayScreenshots()
            }
        }

        card.addView(checkBox)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = screenshot.name
            setBackgroundColor(Color.rgb(30, 36, 55))
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val bitmap: Bitmap = contentResolver.loadThumbnail(
                    screenshot.uri,
                    android.util.Size(dp(100), dp(100)),
                    null
                )
                thumbnail.setImageBitmap(bitmap)
            } else {
                thumbnail.setImageURI(screenshot.uri)
            }
        } catch (_: Exception) {
            thumbnail.setImageResource(
                android.R.drawable.ic_menu_report_image
            )
        }

        thumbnail.setOnClickListener { openScreenshot(screenshot.uri) }

        content.addView(
            thumbnail,
            LinearLayout.LayoutParams(dp(82), dp(92)).apply {
                marginEnd = dp(10)
            }
        )

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        details.addView(TextView(this).apply {
            text = screenshot.name
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            maxLines = 2
        })

        val date = SimpleDateFormat(
            "dd MMM yyyy, HH:mm",
            Locale.getDefault()
        ).format(Date(screenshot.dateAdded * 1000L))

        details.addView(TextView(this).apply {
            text = date
            textSize = 11f
            setTextColor(mutedColor)
            setPadding(0, dp(5), 0, dp(6))
        })

        val favoriteButton = makeButton(
            if (favorites.contains(key)) "★ Favorited"
            else "☆ Add to Favorites"
        )

        favoriteButton.setOnClickListener {
            if (!favorites.add(key)) favorites.remove(key)
            saveFavorites()
            displayScreenshots()
        }

        details.addView(
            favoriteButton,
            LinearLayout.LayoutParams(-1, dp(38)).apply {
                bottomMargin = dp(5)
            }
        )

        content.addView(
            details,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        card.addView(content)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val open = makeButton("Open")
        open.setOnClickListener { openScreenshot(screenshot.uri) }

        val share = makeButton("Share")
        share.setOnClickListener { shareScreenshot(screenshot.uri) }

        val rename = makeButton("Rename")
        rename.setOnClickListener { showRenameDialog(screenshot) }

        val delete = makeButton("Delete", Color.rgb(91, 34, 53))
        delete.setOnClickListener { confirmDelete(screenshot) }

        listOf(open, share, rename, delete).forEachIndexed { index, button ->
            actions.addView(
                button,
                LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    if (index > 0) marginStart = dp(3)
                }
            )
        }

        card.addView(
            actions,
            LinearLayout.LayoutParams(-1, dp(38)).apply {
                topMargin = dp(7)
            }
        )

        return card.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            }
        }
    }

    private fun shareSelected() {
        val selected = screenshots.filter {
            selectedUris.contains(it.uri.toString())
        }

        if (selected.isEmpty()) {
            Toast.makeText(this, "Select screenshots first.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uris = ArrayList<Uri>()
            selected.forEach { uris.add(it.uri) }

            val intent = Intent(
                if (uris.size == 1) Intent.ACTION_SEND
                else Intent.ACTION_SEND_MULTIPLE
            ).apply {
                type = "image/*"

                if (uris.size == 1) {
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                    clipData = android.content.ClipData.newUri(
                        contentResolver, selected[0].name, uris[0]
                    )
                } else {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)

                    var clips: android.content.ClipData? = null
                    uris.forEachIndexed { index, uri ->
                        if (clips == null) {
                            clips = android.content.ClipData.newUri(
                                contentResolver, selected[index].name, uri
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

            startActivity(Intent.createChooser(intent, "Share screenshots"))
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Could not share the selected screenshots.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun confirmBulkDelete() {
        val selected = screenshots.filter {
            selectedUris.contains(it.uri.toString())
        }

        if (selected.isEmpty()) {
            Toast.makeText(this, "Select screenshots first.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Delete selected screenshots?")
            .setMessage(
                "Permanently delete ${selected.size} selected screenshot(s)?"
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete all") { _, _ ->
                var deletedCount = 0

                selected.forEach { screenshot ->
                    try {
                        if (contentResolver.delete(
                                screenshot.uri, null, null
                            ) > 0
                        ) {
                            deletedCount++
                            selectedUris.remove(screenshot.uri.toString())
                            favorites.remove(screenshot.uri.toString())
                        }
                    } catch (_: Exception) {}
                }

                saveFavorites()
                Toast.makeText(
                    this,
                    "Deleted $deletedCount of ${selected.size} screenshot(s).",
                    Toast.LENGTH_LONG
                ).show()
                loadScreenshots()
            }
            .show()
    }

    private fun showRenameDialog(screenshot: Screenshot) {
        val input = EditText(this).apply {
            setText(screenshot.name.removeSuffix(".png"))
            setSelection(text.length)
            hint = "Enter a new screenshot name"
            setSingleLine(true)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(input)
            addView(TextView(this@ScreenshotGalleryActivity).apply {
                text = "The .png extension will be kept automatically."
                textSize = 12f
                setTextColor(mutedColor)
                setPadding(0, dp(8), 0, 0)
            })
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Rename screenshot")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val cleanName = input.text.toString()
                    .trim()
                    .removeSuffix(".png")
                    .trim()

                if (cleanName.isBlank()) {
                    input.error = "Enter a name"
                    return@setOnClickListener
                }

                if (cleanName.contains("/") || cleanName.contains("\\")) {
                    input.error = "Do not use slash characters"
                    return@setOnClickListener
                }

                val newName = "$cleanName.png"

                if (screenshots.any {
                        it.uri != screenshot.uri &&
                            it.name.equals(newName, ignoreCase = true)
                    }
                ) {
                    input.error = "A screenshot already has that name"
                    return@setOnClickListener
                }

                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, newName)
                    }

                    val updated = contentResolver.update(
                        screenshot.uri, values, null, null
                    )

                    Toast.makeText(
                        this,
                        if (updated > 0) "Screenshot renamed."
                        else "Rename was not completed.",
                        Toast.LENGTH_SHORT
                    ).show()

                    dialog.dismiss()
                    loadScreenshots()
                } catch (_: Exception) {
                    Toast.makeText(
                        this,
                        "Could not rename this screenshot.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        dialog.show()
    }

    private fun openScreenshot(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "No suitable image viewer was found.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun shareScreenshot(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newUri(
                    contentResolver, "Screenshot", uri
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share screenshot"))
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Could not share this screenshot.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun confirmDelete(screenshot: Screenshot) {
        AlertDialog.Builder(this)
            .setTitle("Delete screenshot?")
            .setMessage(
                "Permanently delete ${screenshot.name} from your phone?"
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                try {
                    val deleted = contentResolver.delete(
                        screenshot.uri, null, null
                    )

                    if (deleted > 0) {
                        selectedUris.remove(screenshot.uri.toString())
                        favorites.remove(screenshot.uri.toString())
                        saveFavorites()
                        Toast.makeText(
                            this,
                            "Screenshot deleted.",
                            Toast.LENGTH_SHORT
                        ).show()
                        loadScreenshots()
                    } else {
                        Toast.makeText(
                            this,
                            "Screenshot could not be deleted.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (_: Exception) {
                    Toast.makeText(
                        this,
                        "Could not delete this screenshot.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .show()
    }
}

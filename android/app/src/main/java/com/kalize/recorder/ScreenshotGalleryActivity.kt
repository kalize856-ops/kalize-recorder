
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
import android.view.ViewGroup
import android.widget.Button
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

    private var screenshots = listOf<Screenshot>()
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
        getSharedPreferences(
            "kalize_screenshot_gallery",
            MODE_PRIVATE
        )
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
        if (::listContainer.isInitialized) {
            loadScreenshots()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun makeButton(label: String): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(49, 43, 91))
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
            text = "Manage, rename, share and favorite your captures."
            textSize = 13f
            setTextColor(mutedColor)
            setPadding(0, 0, 0, dp(14))
        })

        searchInput = EditText(this).apply {
            hint = "Search screenshots..."
            setSingleLine(true)
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(mutedColor)
            setPadding(dp(12), 0, dp(12), 0)
            setBackgroundColor(cardColor)
        }

        root.addView(
            searchInput,
            LinearLayout.LayoutParams(-1, dp(50)).apply {
                bottomMargin = dp(8)
            }
        )

        sortButton = makeButton("")
        updateSortButton()

        sortButton.setOnClickListener {
            val index = sortOptions.indexOf(sortMode)
                .coerceAtLeast(0)

            sortMode = sortOptions[
                (index + 1) % sortOptions.size
            ]

            prefs.edit()
                .putString("screenshot_sort_mode", sortMode)
                .apply()

            updateSortButton()
            displayScreenshots()
        }

        root.addView(
            sortButton,
            LinearLayout.LayoutParams(-1, dp(44)).apply {
                bottomMargin = dp(8)
            }
        )

        statusText = TextView(this).apply {
            text = "Loading screenshots..."
            textSize = 14f
            setTextColor(mutedColor)
            setPadding(dp(2), dp(6), dp(2), dp(10))
        }

        root.addView(statusText)

        val scroll = ScrollView(this)
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        scroll.addView(listContainer)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        searchInput.addTextChangedListener(
            object : android.text.TextWatcher {
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
                    displayScreenshots()
                }

                override fun afterTextChanged(
                    s: android.text.Editable?
                ) {}
            }
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
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(6)
            }
        )

        bottom.addView(
            back,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(6)
            }
        )

        root.addView(
            bottom,
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(12)
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
            .putStringSet(
                "favorite_screenshot_uris",
                favorites.toSet()
            )
            .apply()
    }

    private fun loadScreenshots() {
        try {
            val collection =
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI

            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED
            )

            val selection: String
            val args: Array<String>

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection =
                    "${MediaStore.Images.Media.RELATIVE_PATH} = ?"
                args = arrayOf("Pictures/Kalize Recorder/")
            } else {
                selection =
                    "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
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
                    val name = cursor.getString(nameIndex)
                        ?: continue

                    if (!name.startsWith("Kalize_Screenshot_")) {
                        continue
                    }

                    val uri = Uri.withAppendedPath(
                        collection,
                        cursor.getLong(idIndex).toString()
                    )

                    loaded.add(
                        Screenshot(
                            uri,
                            name,
                            cursor.getLong(dateIndex)
                        )
                    )
                }
            }

            screenshots = loaded

            val validUris = screenshots.map {
                it.uri.toString()
            }.toSet()

            if (favorites.retainAll(validUris)) {
                saveFavorites()
            }

            displayScreenshots()
        } catch (_: Exception) {
            statusText.text =
                "Could not load screenshots. Tap Refresh."
        }
    }

    private fun displayScreenshots() {
        if (!::listContainer.isInitialized) return

        listContainer.removeAllViews()

        val query = searchInput.text.toString().trim()

        val filtered = screenshots
            .filter { it.name.contains(query, true) }
            .let { items ->
                when (sortMode) {
                    "Oldest first" ->
                        items.sortedBy { it.dateAdded }

                    "Name A-Z" ->
                        items.sortedBy {
                            it.name.lowercase(Locale.getDefault())
                        }

                    "Favorites first" ->
                        items.sortedWith(
                            compareByDescending<Screenshot> {
                                favorites.contains(it.uri.toString())
                            }.thenByDescending { it.dateAdded }
                        )

                    else ->
                        items.sortedByDescending { it.dateAdded }
                }
            }

        val favoriteCount = screenshots.count {
            favorites.contains(it.uri.toString())
        }

        statusText.text = when {
            screenshots.isEmpty() ->
                "No Kalize screenshots found yet."

            filtered.isEmpty() ->
                "No screenshots match your search."

            else ->
                "${filtered.size} shown  •  $favoriteCount favorite(s)"
        }

        if (filtered.isEmpty()) {
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

        filtered.forEach { screenshot ->
            listContainer.addView(createScreenshotCard(screenshot))
        }
    }

    private fun createScreenshotCard(
        screenshot: Screenshot
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(cardColor)
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

        thumbnail.setOnClickListener {
            openScreenshot(screenshot.uri)
        }

        card.addView(
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

        val isFavorite = favorites.contains(
            screenshot.uri.toString()
        )

        val favoriteButton = makeButton(
            if (isFavorite) "★ Favorited" else "☆ Add to Favorites"
        )

        favoriteButton.setOnClickListener {
            val key = screenshot.uri.toString()

            if (!favorites.add(key)) {
                favorites.remove(key)
                Toast.makeText(
                    this,
                    "Removed from Favorites.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Added to Favorites.",
                    Toast.LENGTH_SHORT
                ).show()
            }

            saveFavorites()
            displayScreenshots()
        }

        details.addView(
            favoriteButton,
            LinearLayout.LayoutParams(-1, dp(38)).apply {
                bottomMargin = dp(5)
            }
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val open = makeButton("Open")
        open.setOnClickListener {
            openScreenshot(screenshot.uri)
        }

        val share = makeButton("Share")
        share.setOnClickListener {
            shareScreenshot(screenshot.uri)
        }

        val rename = makeButton("Rename")
        rename.setOnClickListener {
            showRenameDialog(screenshot)
        }

        val delete = makeButton("Delete")
        delete.setOnClickListener {
            confirmDelete(screenshot)
        }

        listOf(open, share, rename, delete).forEachIndexed { index, button ->
            actions.addView(
                button,
                LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    if (index > 0) marginStart = dp(3)
                }
            )
        }

        details.addView(actions)

        card.addView(
            details,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        return card.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            }
        }
    }

    private fun showRenameDialog(screenshot: Screenshot) {
        val input = EditText(this).apply {
            setText(
                screenshot.name.removeSuffix(".png")
            )
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

        AlertDialog.Builder(this)
            .setTitle("Rename screenshot")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(
                        AlertDialog.BUTTON_POSITIVE
                    ).setOnClickListener {
                        val entered = input.text.toString().trim()
                        val cleanName = entered
                            .removeSuffix(".png")
                            .trim()

                        if (cleanName.isBlank()) {
                            input.error = "Enter a name"
                            return@setOnClickListener
                        }

                        if (cleanName.contains("/") ||
                            cleanName.contains("\\")
                        ) {
                            input.error =
                                "Do not use slash characters"
                            return@setOnClickListener
                        }

                        val newName = "$cleanName.png"

                        val duplicate = screenshots.any {
                            it.uri != screenshot.uri &&
                            it.name.equals(
                                newName,
                                ignoreCase = true
                            )
                        }

                        if (duplicate) {
                            input.error =
                                "A screenshot already has that name"
                            return@setOnClickListener
                        }

                        renameScreenshot(screenshot, newName)
                        dialog.dismiss()
                    }
                }

                dialog.show()
            }
    }

    private fun renameScreenshot(
        screenshot: Screenshot,
        newName: String
    ) {
        try {
            val values = ContentValues().apply {
                put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    newName
                )
            }

            val changed = contentResolver.update(
                screenshot.uri,
                values,
                null,
                null
            )

            if (changed > 0) {
                Toast.makeText(
                    this,
                    "Screenshot renamed.",
                    Toast.LENGTH_SHORT
                ).show()
                loadScreenshots()
            } else {
                Toast.makeText(
                    this,
                    "Rename was not completed.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Could not rename this screenshot.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openScreenshot(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/*")
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
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
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            startActivity(
                Intent.createChooser(intent, "Share screenshot")
            )
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
                        screenshot.uri,
                        null,
                        null
                    )

                    if (deleted > 0) {
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

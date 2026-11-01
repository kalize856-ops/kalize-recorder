
package com.kalize.recorder

import android.app.Activity
import android.app.AlertDialog
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

    private var screenshots: List<Screenshot> = emptyList()

    private val backgroundColor = Color.rgb(9, 13, 27)
    private val cardColor = Color.rgb(20, 28, 48)
    private val accentColor = Color.rgb(139, 92, 246)
    private val mutedColor = Color.rgb(164, 177, 202)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            setBackgroundColor(backgroundColor)
        }

        root.addView(TextView(this).apply {
            text = "ƘALIZE STUDIO  /  SCREENSHOTS"
            textSize = 11f
            letterSpacing = 0.1f
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
            text = "View, find, share and manage your captures."
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
                bottomMargin = dp(10)
            }
        )

        statusText = TextView(this).apply {
            text = "Loading screenshots..."
            textSize = 14f
            setTextColor(mutedColor)
            setPadding(dp(2), dp(8), dp(2), dp(10))
        }
        root.addView(statusText)

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
        }

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        scrollView.addView(
            listContainer,
            ViewGroup.LayoutParams(-1, -2)
        )

        root.addView(
            scrollView,
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

        val bottomButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val refreshButton = makeButton("↻ Refresh")
        refreshButton.setOnClickListener { loadScreenshots() }

        val backButton = makeButton("← Back")
        backButton.setOnClickListener { finish() }

        bottomButtons.addView(
            refreshButton,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(6)
            }
        )

        bottomButtons.addView(
            backButton,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(6)
            }
        )

        root.addView(
            bottomButtons,
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(12)
            }
        )

        setContentView(root)
    }

    private fun makeButton(label: String): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(49, 43, 91))
        }

    private fun loadScreenshots() {
        try {
            val result = mutableListOf<Screenshot>()
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

            val projection = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.RELATIVE_PATH
                )
            } else {
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED
                )
            }

            val selection: String?
            val selectionArgs: Array<String>?

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection =
                    "${MediaStore.Images.Media.RELATIVE_PATH} = ?"
                selectionArgs = arrayOf("Pictures/Kalize Recorder/")
            } else {
                selection =
                    "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
                selectionArgs = arrayOf("Kalize_Screenshot_%.png")
            }

            contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.Images.Media._ID
                )
                val nameColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.Images.Media.DISPLAY_NAME
                )
                val dateColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.Images.Media.DATE_ADDED
                )

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn) ?: continue

                    if (!name.startsWith("Kalize_Screenshot_")) {
                        continue
                    }

                    val id = cursor.getLong(idColumn)
                    val uri = Uri.withAppendedPath(
                        collection,
                        id.toString()
                    )

                    result.add(
                        Screenshot(
                            uri = uri,
                            name = name,
                            dateAdded = cursor.getLong(dateColumn)
                        )
                    )
                }
            }

            screenshots = result
            displayScreenshots()
        } catch (e: Exception) {
            statusText.text = "Could not load screenshots. Tap Refresh."
            Toast.makeText(
                this,
                "Could not load screenshots.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun displayScreenshots() {
        if (!::listContainer.isInitialized) return

        listContainer.removeAllViews()

        val query = if (::searchInput.isInitialized) {
            searchInput.text.toString().trim()
        } else {
            ""
        }

        val filtered = screenshots.filter {
            it.name.contains(query, ignoreCase = true)
        }

        statusText.text = when {
            screenshots.isEmpty() ->
                "No Kalize screenshots found yet."
            filtered.isEmpty() ->
                "No screenshots match your search."
            else ->
                "${filtered.size} screenshot(s)"
        }

        if (filtered.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = if (screenshots.isEmpty()) {
                    "Screenshots you take with Kalize Recorder will appear here."
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
            LinearLayout.LayoutParams(dp(92), dp(92)).apply {
                marginEnd = dp(12)
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
            setPadding(0, dp(5), 0, dp(8))
        })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val openButton = makeButton("Open")
        openButton.setOnClickListener {
            openScreenshot(screenshot.uri)
        }

        val shareButton = makeButton("Share")
        shareButton.setOnClickListener {
            shareScreenshot(screenshot.uri)
        }

        val deleteButton = makeButton("Delete")
        deleteButton.setOnClickListener {
            confirmDelete(screenshot)
        }

        buttons.addView(
            openButton,
            LinearLayout.LayoutParams(0, dp(40), 1f)
        )
        buttons.addView(
            shareButton,
            LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                marginStart = dp(4)
            }
        )
        buttons.addView(
            deleteButton,
            LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                marginStart = dp(4)
            }
        )

        details.addView(buttons)

        card.addView(
            details,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        return card.apply {
            val params = LinearLayout.LayoutParams(-1, -2)
            params.bottomMargin = dp(10)
            layoutParams = params
        }
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
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
                "This will permanently delete ${screenshot.name} from your phone."
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

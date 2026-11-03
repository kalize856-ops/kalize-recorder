package com.kalize.recorder

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import java.util.Locale

class MediaLibraryActivity : Activity() {

    private val background = Color.rgb(10, 12, 25)
    private val card = Color.rgb(24, 27, 46)
    private val purple = Color.rgb(151, 112, 255)
    private val muted = Color.rgb(165, 174, 199)
    private val white = Color.WHITE

    private data class MediaItem(
        val uri: Uri,
        val name: String,
        val date: Long,
        val size: Long,
        val isVideo: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 26) {
            buildScreen()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 18): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun label(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(3), 0, dp(3))
        }
    }

    private fun addSpace(parent: LinearLayout, size: Int) {
        parent.addView(
            android.view.View(this),
            LinearLayout.LayoutParams(1, dp(size))
        )
    }

    private fun actionButton(
        title: String,
        description: String,
        symbol: String,
        onClick: () -> Unit
    ): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(card)
            setPadding(dp(15), dp(14), dp(15), dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val icon = TextView(this).apply {
            text = symbol
            textSize = 25f
            setTextColor(purple)
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(48, 39, 79), 14)
        }

        container.addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)))

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, dp(5), 0)
        }

        textColumn.addView(label(title, 15f, white, true))
        textColumn.addView(label(description, 11f, muted))

        container.addView(
            textColumn,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        container.addView(label("›", 27f, purple, true))
        return container
    }

    private fun buildScreen() {
        val videos = queryVideos()
        val screenshots = queryScreenshots()
        val recent = (videos + screenshots).sortedByDescending { it.date }.take(5)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(19), dp(22), dp(19), dp(24))
        }

        content.addView(label("KALIZE STUDIO  /  MEDIA", 11f, purple, true))
        content.addView(label("Your Library", 29f, white, true))
        content.addView(
            label("Your recordings and screenshots, organised.", 13f, muted)
        )

        addSpace(content, 20)

        val storageCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(39, 31, 68), 22)
            setPadding(dp(18), dp(17), dp(18), dp(17))
        }

        storageCard.addView(label("DEVICE STORAGE", 11f, purple, true))
        storageCard.addView(label("Your phone storage", 18f, white, true))
        storageCard.addView(label(storageSummary(), 13f, Color.rgb(220, 213, 245)))
        storageCard.addView(
            label(
                "Device-wide storage information.",
                10f,
                muted
            )
        )

        content.addView(storageCard)

        addSpace(content, 22)

        content.addView(label("YOUR COLLECTIONS", 11f, purple, true))
        content.addView(label("Your media at a glance", 19f, white, true))

        addSpace(content, 12)

        val counts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        counts.addView(
            countCard("VIDEOS", videos.size.toString(), "▶"),
            LinearLayout.LayoutParams(0, dp(105), 1f)
        )

        val spacer = android.view.View(this)
        counts.addView(spacer, LinearLayout.LayoutParams(dp(10), 1))

        counts.addView(
            countCard("SCREENSHOTS", screenshots.size.toString(), "▧"),
            LinearLayout.LayoutParams(0, dp(105), 1f)
        )

        content.addView(counts)

        addSpace(content, 22)

        content.addView(label("RECENT CAPTURES", 11f, purple, true))
        content.addView(label("Your latest media", 19f, white, true))
        content.addView(
            label("The five newest items saved in Kalize folders.", 11f, muted)
        )

        addSpace(content, 10)

        if (recent.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(card)
                setPadding(dp(16), dp(18), dp(16), dp(18))
            }
            empty.addView(label("No captures found yet", 15f, white, true))
            empty.addView(
                label(
                    "Your new Kalize recordings and screenshots will appear here.",
                    12f,
                    muted
                )
            )
            content.addView(empty)
        } else {
            recent.forEach { item ->
                content.addView(recentItem(item))
                addSpace(content, 8)
            }
        }

        addSpace(content, 18)

        content.addView(label("OPEN YOUR COLLECTIONS", 11f, purple, true))
        content.addView(label("Browse and manage", 19f, white, true))

        addSpace(content, 12)

        content.addView(
            actionButton(
                "Saved videos",
                "${videos.size} Kalize recording(s) found",
                "▶"
            ) {
                startActivity(Intent(this, SavedVideosActivity::class.java))
            }
        )

        addSpace(content, 10)

        content.addView(
            actionButton(
                "Screenshot gallery",
                "${screenshots.size} Kalize screenshot(s) found",
                "▧"
            ) {
                startActivity(Intent(this, ScreenshotGalleryActivity::class.java))
            }
        )

        addSpace(content, 10)

        content.addView(
            actionButton(
                "Video editor",
                "Trim, rotate, crop and export clips",
                "✂"
            ) {
                startActivity(Intent(this, VideoEditorActivity::class.java))
            }
        )

        addSpace(content, 22)

        val back = Button(this).apply {
            text = "←  BACK TO KALIZE"
            isAllCaps = false
            setTextColor(white)
            textSize = 13f
            setBackgroundColor(Color.rgb(91, 66, 157))
            setOnClickListener { finish() }
        }

        content.addView(back, LinearLayout.LayoutParams(-1, dp(48)))

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun countCard(
        title: String,
        count: String,
        symbol: String
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(label(symbol, 17f, purple, true))
            addView(label(count, 25f, white, true))
            addView(label(title, 10f, muted, true))
        }
    }

    private fun recentItem(item: MediaItem): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(card)
            setPadding(dp(11), dp(10), dp(11), dp(10))
        }

        val thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Color.rgb(48, 39, 79), 12)
        }

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val bitmap: Bitmap = contentResolver.loadThumbnail(
                    item.uri,
                    Size(dp(64), dp(64)),
                    null
                )
                thumbnail.setImageBitmap(bitmap)
            }
        } catch (_: Exception) {
            // Keep the media-type icon if thumbnail loading is unavailable.
        }

        if (thumbnail.drawable == null) {
            thumbnail.setImageDrawable(
                if (item.isVideo) {
                    getDrawable(android.R.drawable.ic_media_play)
                } else {
                    getDrawable(android.R.drawable.ic_menu_gallery)
                }
            )
        }

        row.addView(thumbnail, LinearLayout.LayoutParams(dp(64), dp(64)))

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }

        details.addView(
            label(
                item.name,
                12f,
                white,
                true
            ).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        )

        details.addView(
            label(
                (if (item.isVideo) "Video" else "Screenshot") +
                    " • " + formatSize(item.size),
                10f,
                muted
            )
        )

        details.addView(
            label(
                if (item.date > 0) {
                    java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.MEDIUM,
                        java.text.DateFormat.SHORT
                    ).format(java.util.Date(item.date * 1000))
                } else {
                    "Date unavailable"
                },
                10f,
                muted
            )
        )

        row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun queryVideos(): List<MediaItem> {
        val result = mutableListOf<MediaItem>()

        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.RELATIVE_PATH
        )

        try {
            contentResolver.query(
                collection,
                projection,
                "${MediaStore.Video.Media.RELATIVE_PATH}=?",
                arrayOf("Movies/Kalize Recorder/"),
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    result.add(
                        MediaItem(
                            Uri.withAppendedPath(collection, id.toString()),
                            cursor.getString(nameColumn) ?: "Video",
                            cursor.getLong(dateColumn),
                            cursor.getLong(sizeColumn),
                            true
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // A restricted or unavailable media collection returns an empty list.
        }

        return result
    }

    private fun queryScreenshots(): List<MediaItem> {
        val result = mutableListOf<MediaItem>()

        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.RELATIVE_PATH
        )

        try {
            contentResolver.query(
                collection,
                projection,
                "${MediaStore.Images.Media.RELATIVE_PATH}=?",
                arrayOf("Pictures/Kalize Recorder/"),
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    result.add(
                        MediaItem(
                            Uri.withAppendedPath(collection, id.toString()),
                            cursor.getString(nameColumn) ?: "Screenshot",
                            cursor.getLong(dateColumn),
                            cursor.getLong(sizeColumn),
                            false
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // A restricted or unavailable media collection returns an empty list.
        }

        return result
    }

    private fun storageSummary(): String {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = stat.totalBytes
            val free = stat.availableBytes
            val used = total - free

            "${formatSize(used)} used  •  ${formatSize(free)} available\n" +
                "${formatSize(total)} total"
        } catch (_: Exception) {
            "Storage information is unavailable."
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"

        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var index = 0

        while (size >= 1024 && index < units.lastIndex) {
            size /= 1024
            index++
        }

        return String.format(Locale.US, "%.1f %s", size, units[index])
    }
}

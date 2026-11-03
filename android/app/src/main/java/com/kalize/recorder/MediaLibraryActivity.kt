package com.kalize.recorder

import android.app.Activity
import android.content.Intent
import android.content.ContentUris
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MediaLibraryActivity : Activity() {

    private val background = Color.rgb(10, 12, 25)
    private val card = Color.rgb(24, 27, 46)
    private val purple = Color.rgb(151, 112, 255)
    private val muted = Color.rgb(165, 174, 199)
    private val white = Color.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        buildScreen()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun rounded(
        color: Int,
        radius: Int = 18
    ): GradientDrawable {
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

        container.addView(
            icon,
            LinearLayout.LayoutParams(dp(52), dp(52))
        )

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

    private data class MediaStats(
        val count: Int,
        val totalBytes: Long,
        val latestName: String
    )

    private data class StorageInfo(
        val total: Long,
        val free: Long,
        val used: Long,
        val percent: Int
    )

    private fun readMediaStats(
        collection: android.net.Uri,
        relativePath: String
    ): MediaStats {
        return try {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DISPLAY_NAME
            )

            var count = 0
            var totalBytes = 0L
            var latestName = ""

            contentResolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(relativePath),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->

                val sizeColumn =
                    cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)

                val nameColumn =
                    cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    count++

                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                        totalBytes += cursor.getLong(sizeColumn)
                            .coerceAtLeast(0L)
                    }

                    if (
                        latestName.isEmpty() &&
                        nameColumn >= 0 &&
                        !cursor.isNull(nameColumn)
                    ) {
                        latestName = cursor.getString(nameColumn)
                    }
                }
            }

            MediaStats(count, totalBytes, latestName)

        } catch (_: Exception) {
            MediaStats(0, 0L, "")
        }
    }

    private fun getVideoStats(): MediaStats {
        return readMediaStats(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            "${Environment.DIRECTORY_MOVIES}/Kalize Recorder/"
        )
    }

    private fun getScreenshotStats(): MediaStats {
        return readMediaStats(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${Environment.DIRECTORY_PICTURES}/Kalize Recorder/"
        )
    }

    private fun getStorageInfo(): StorageInfo {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)

            val total = stat.totalBytes
            val free = stat.availableBytes.coerceAtMost(total)
            val used = (total - free).coerceAtLeast(0L)

            val percent = if (total > 0L) {
                ((used.toDouble() / total.toDouble()) * 100)
                    .toInt()
                    .coerceIn(0, 100)
            } else {
                0
            }

            StorageInfo(total, free, used, percent)

        } catch (_: Exception) {
            StorageInfo(0L, 0L, 0L, 0)
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

        return String.format(
            Locale.US,
            "%.1f %s",
            size,
            units[index]
        )
    }

    private fun addSpace(
        parent: LinearLayout,
        size: Int
    ) {
        parent.addView(
            android.view.View(this),
            LinearLayout.LayoutParams(1, dp(size))
        )
    }

    private fun buildScreen() {
        val videos = getVideoStats()
        val screenshots = getScreenshotStats()
        val storage = getStorageInfo()

        val totalMediaCount =
            videos.count + screenshots.count

        val totalMediaSize =
            videos.totalBytes + screenshots.totalBytes

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@MediaLibraryActivity.background)
        }

        val scroll = ScrollView(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(19), dp(22), dp(19), dp(20))
        }

        content.addView(
            label("KALIZE STUDIO  /  MEDIA", 11f, purple, true)
        )

        content.addView(
            label("Your Library", 29f, white, true)
        )

        content.addView(
            label(
                "Browse and manage everything you capture.",
                13f,
                muted
            )
        )

        addSpace(content, 20)

        // Actual Kalize media overview.
        val mediaCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(39, 31, 68), 22)
            setPadding(dp(18), dp(17), dp(18), dp(17))
        }

        mediaCard.addView(
            label("YOUR KALIZE MEDIA", 11f, purple, true)
        )

        mediaCard.addView(
            label(
                totalMediaCount.toString(),
                34f,
                white,
                true
            )
        )

        mediaCard.addView(
            label(
                "Total captures found in Kalize folders",
                12f,
                muted
            )
        )

        addSpace(mediaCard, 12)

        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val videoColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(51, 42, 82), 14)
            setPadding(dp(12), dp(11), dp(8), dp(11))
        }

        videoColumn.addView(
            label("VIDEOS", 10f, purple, true)
        )

        videoColumn.addView(
            label(videos.count.toString(), 23f, white, true)
        )

        videoColumn.addView(
            label(formatSize(videos.totalBytes), 11f, muted)
        )

        val screenshotColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(51, 42, 82), 14)
            setPadding(dp(12), dp(11), dp(8), dp(11))
        }

        screenshotColumn.addView(
            label("SCREENSHOTS", 10f, purple, true)
        )

        screenshotColumn.addView(
            label(screenshots.count.toString(), 23f, white, true)
        )

        screenshotColumn.addView(
            label(formatSize(screenshots.totalBytes), 11f, muted)
        )

        statsRow.addView(
            videoColumn,
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginEnd = dp(6)
            }
        )

        statsRow.addView(
            screenshotColumn,
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = dp(6)
            }
        )

        mediaCard.addView(statsRow)

        addSpace(mediaCard, 12)

        mediaCard.addView(
            label(
                "Combined media size: ${formatSize(totalMediaSize)}",
                12f,
                Color.rgb(220, 213, 245),
                true
            )
        )

        if (videos.latestName.isNotEmpty()) {
            mediaCard.addView(
                label(
                    "Latest video: ${videos.latestName}",
                    10f,
                    muted
                )
            )
        }

        if (screenshots.latestName.isNotEmpty()) {
            mediaCard.addView(
                label(
                    "Latest screenshot: ${screenshots.latestName}",
                    10f,
                    muted
                )
            )
        }

        content.addView(
            mediaCard,
            LinearLayout.LayoutParams(-1, -2)
        )

        addSpace(content, 18)

        // Overall phone storage.
        val storageCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, 20)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        storageCard.addView(
            label("DEVICE STORAGE", 11f, purple, true)
        )

        storageCard.addView(
            label("Your phone storage", 18f, white, true)
        )

        storageCard.addView(
            label(
                "${formatSize(storage.used)} used  •  ${storage.percent}% full",
                13f,
                Color.rgb(220, 213, 245),
                true
            )
        )

        val storageProgress = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = storage.percent
            progressTintList =
                android.content.res.ColorStateList.valueOf(purple)
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    Color.rgb(69, 57, 99)
                )
        }

        val progressParams = LinearLayout.LayoutParams(
            -1,
            dp(9)
        ).apply {
            topMargin = dp(12)
            bottomMargin = dp(10)
        }

        storageCard.addView(
            storageProgress,
            progressParams
        )

        storageCard.addView(
            label(
                "${formatSize(storage.free)} available",
                12f,
                muted
            )
        )

        storageCard.addView(
            label(
                "${formatSize(storage.total)} total device storage",
                11f,
                muted
            )
        )

        storageCard.addView(
            label(
                "This meter shows overall phone storage.",
                10f,
                muted
            )
        )

        content.addView(
            storageCard,
            LinearLayout.LayoutParams(-1, -2)
        )

        addSpace(content, 22)

        content.addView(
            label("YOUR MEDIA", 11f, purple, true)
        )

        content.addView(
            label("Browse your captures", 19f, white, true)
        )

        content.addView(
            label(
                "Open a collection to search, sort, favourite, share or manage files.",
                12f,
                muted
            )
        )

        addSpace(content, 12)

        content.addView(
            actionButton(
                "Saved videos",
                "Watch, rename, favourite and share recordings",
                "▶"
            ) {
                startActivity(
                    Intent(this, SavedVideosActivity::class.java)
                )
            }
        )

        addSpace(content, 10)

        content.addView(
            actionButton(
                "Screenshot gallery",
                "Browse, organise and share screenshots",
                "▧"
            ) {
                startActivity(
                    Intent(this, ScreenshotGalleryActivity::class.java)
                )
            }
        )

        addSpace(content, 10)

        content.addView(
            actionButton(
                "Video editor",
                "Trim, rotate, crop and export clips",
                "✂"
            ) {
                startActivity(
                    Intent(this, VideoEditorActivity::class.java)
                )
            }
        )

        addSpace(content, 22)

        content.addView(
            label("LIBRARY TIPS", 11f, purple, true)
        )

        val tips = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card)
            setPadding(dp(16), dp(13), dp(16), dp(13))
        }

        tips.addView(
            label(
                "Keep your recordings organised",
                14f,
                white,
                true
            )
        )

        tips.addView(
            label(
                "• Use favourites to mark important captures.\n" +
                    "• Search by file name in the galleries.\n" +
                    "• Review a video before sharing it.\n" +
                    "• Delete only files you no longer need.",
                12f,
                muted
            )
        )

        content.addView(tips)

        addSpace(content, 20)

        val back = Button(this).apply {
            text = "←  BACK TO KALIZE"
            isAllCaps = false
            setTextColor(white)
            textSize = 13f
            setBackgroundColor(Color.rgb(91, 66, 157))
            setOnClickListener { finish() }
        }

        content.addView(
            back,
            LinearLayout.LayoutParams(-1, dp(48))
        )

        scroll.addView(content)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(-1, -1)
        )

        setContentView(root)
    }
}

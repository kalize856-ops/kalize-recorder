package com.kalize.recorder

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.StatFs
import android.os.Environment
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Button
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
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

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@MediaLibraryActivity.background)
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(19), dp(22), dp(19), dp(20))
        }

        content.addView(label("KALIZE STUDIO  /  MEDIA", 11f, purple, true))
        content.addView(label("Your Library", 29f, white, true))
        content.addView(
            label(
                "Browse and manage everything you capture.",
                13f,
                muted
            )
        )

        addSpace(content, 20)

        val storageCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(39, 31, 68), 22)
            setPadding(dp(18), dp(17), dp(18), dp(17))
        }

        storageCard.addView(label("DEVICE STORAGE", 11f, purple, true))
        storageCard.addView(label("Your phone storage", 18f, white, true))
        storageCard.addView(
            label(
                storageSummary(),
                13f,
                Color.rgb(220, 213, 245)
            )
        )
        storageCard.addView(
            label(
                "This is your device's storage overview, not a count of Kalize files.",
                10f,
                muted
            )
        )

        content.addView(
            storageCard,
            LinearLayout.LayoutParams(-1, -2)
        )

        addSpace(content, 22)

        content.addView(label("YOUR MEDIA", 11f, purple, true))
        content.addView(label("Browse your captures", 19f, white, true))
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
                startActivity(Intent(this, SavedVideosActivity::class.java))
            }
        )

        addSpace(content, 10)

        content.addView(
            actionButton(
                "Screenshot gallery",
                "Browse, organise and share screenshots",
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

        content.addView(label("LIBRARY TIPS", 11f, purple, true))

        val tips = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card)
            setPadding(dp(16), dp(13), dp(16), dp(13))
        }

        tips.addView(label("Keep your recordings organised", 14f, white, true))
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

    private fun addSpace(parent: LinearLayout, size: Int) {
        parent.addView(
            android.view.View(this),
            LinearLayout.LayoutParams(1, dp(size))
        )
    }

    private fun storageSummary(): String {
        return try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val total = stat.totalBytes
            val free = stat.availableBytes
            val used = total - free

            "${formatSize(used)} used  •  ${formatSize(free)} available\n" +
                "${formatSize(total)} total"
        } catch (_: Exception) {
            "Storage information is unavailable on this device."
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

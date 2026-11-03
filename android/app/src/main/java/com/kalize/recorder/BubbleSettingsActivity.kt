
package com.kalize.recorder

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class BubbleSettingsActivity : Activity() {

    private val prefs by lazy {
        getSharedPreferences("kalize_bubble_position", MODE_PRIVATE)
    }

    private var bubbleSize = 60
    private var bubbleOpacity = 100
    private var bubbleColor = Color.rgb(255, 115, 25)

    private lateinit var sizeValue: TextView
    private lateinit var opacityValue: TextView
    private lateinit var preview: TextView
    private lateinit var selectedColorText: TextView

    private val purple = Color.rgb(125, 75, 220)

    private val colors = listOf(
        "Orange" to Color.rgb(255, 115, 25),
        "Purple" to Color.rgb(125, 75, 220),
        "Blue" to Color.rgb(45, 125, 235),
        "Green" to Color.rgb(35, 165, 105),
        "Red" to Color.rgb(235, 55, 65)
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bubbleSize = prefs.getInt("bubble_size", 60).coerceIn(48, 80)
        bubbleOpacity = prefs.getInt("bubble_opacity", 100).coerceIn(30, 100)
        bubbleColor = prefs.getInt(
            "bubble_color",
            Color.rgb(255, 115, 25)
        )

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
            setBackgroundColor(Color.rgb(10, 11, 20))
        }

        val title = TextView(this).apply {
            text = "KALIZE  /  BUBBLE SETTINGS"
            textSize = 13f
            setTextColor(purple)
            setTypeface(null, Typeface.BOLD)
        }
        page.addView(title)

        val heading = TextView(this).apply {
            text = "Customize Bubble"
            textSize = 27f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(6))
        }
        page.addView(heading)

        val subtitle = TextView(this).apply {
            text = "Personalize your floating recording control."
            textSize = 14f
            setTextColor(Color.LTGRAY)
        }
        page.addView(subtitle)

        preview = TextView(this).apply {
            text = "K"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = bubbleBackground()
        }

        val previewHolder = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
        }

        previewHolder.addView(
            preview,
            LinearLayout.LayoutParams(dp(bubbleSize), dp(bubbleSize))
        )
        page.addView(previewHolder)

        val colorHeading = TextView(this).apply {
            text = "Bubble colour"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(8))
        }
        page.addView(colorHeading)

        selectedColorText = TextView(this).apply {
            text = "Selected: ${colorName(bubbleColor)}"
            textSize = 13f
            setTextColor(Color.LTGRAY)
        }
        page.addView(selectedColorText)

        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }

        colors.forEach { (name, color) ->
            val colorButton = TextView(this).apply {
                text = if (bubbleColor == color) "✓" else ""
                textSize = 19f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(
                        dp(if (bubbleColor == color) 4 else 1),
                        Color.WHITE
                    )
                }
                setOnClickListener {
                    bubbleColor = color
                    selectedColorText.text = "Selected: $name"
                    updatePreview()
                    refreshColorButtons(colorRow)
                }
            }

            colorButton.tag = color

            colorRow.addView(
                colorButton,
                LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                    setMargins(dp(5), 0, dp(5), 0)
                }
            )
        }

        page.addView(colorRow)

        sizeValue = addSlider(
            page,
            "Bubble size",
            bubbleSize,
            48,
            32,
            " dp"
        ) { value ->
            bubbleSize = value
            sizeValue.text = "$value dp"
            updatePreview()
        }

        opacityValue = addSlider(
            page,
            "Bubble transparency",
            bubbleOpacity,
            30,
            70,
            "%"
        ) { value ->
            bubbleOpacity = value
            opacityValue.text = "$value%"
            updatePreview()
        }

        val save = Button(this).apply {
            text = "SAVE AND APPLY"
            setTextColor(Color.WHITE)
            setBackgroundColor(purple)

            setOnClickListener {
                prefs.edit()
                    .putInt("bubble_size", bubbleSize)
                    .putInt("bubble_opacity", bubbleOpacity)
                    .putInt("bubble_color", bubbleColor)
                    .apply()

                try {
                    stopService(
                        Intent(
                            this@BubbleSettingsActivity,
                            FloatingBubbleService::class.java
                        )
                    )

                    startService(
                        Intent(
                            this@BubbleSettingsActivity,
                            FloatingBubbleService::class.java
                        )
                    )
                } catch (_: Exception) {
                    Toast.makeText(
                        this@BubbleSettingsActivity,
                        "Settings saved. Re-enable the bubble if needed.",
                        Toast.LENGTH_LONG
                    ).show()
                }

                Toast.makeText(
                    this@BubbleSettingsActivity,
                    "Bubble settings saved.",
                    Toast.LENGTH_SHORT
                ).show()

                finish()
            }
        }

        page.addView(
            save,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                topMargin = dp(20)
            }
        )

        val back = Button(this).apply {
            text = "BACK"
            setOnClickListener { finish() }
        }

        page.addView(
            back,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                topMargin = dp(10)
            }
        )

        setContentView(page)
    }

    private fun addSlider(
        parent: LinearLayout,
        heading: String,
        initial: Int,
        minimum: Int,
        range: Int,
        suffix: String,
        onValueChanged: (Int) -> Unit
    ): TextView {

        val title = TextView(this).apply {
            text = heading
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        }
        parent.addView(title)

        val value = TextView(this).apply {
            text = "$initial$suffix"
            textSize = 13f
            setTextColor(Color.LTGRAY)
        }
        parent.addView(value)

        val slider = SeekBar(this).apply {
            max = range
            progress = initial - minimum

            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        onValueChanged(minimum + progress)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                }
            )
        }

        parent.addView(slider)
        return value
    }

    private fun refreshColorButtons(row: LinearLayout) {
        for (index in 0 until row.childCount) {
            val button = row.getChildAt(index) as? TextView ?: continue
            val color = button.tag as? Int ?: continue

            button.text = if (color == bubbleColor) "✓" else ""
            button.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(
                    dp(if (color == bubbleColor) 4 else 1),
                    Color.WHITE
                )
            }
        }
    }

    private fun colorName(color: Int): String {
        return colors.firstOrNull { it.second == color }?.first ?: "Orange"
    }

    private fun bubbleBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bubbleColor)
            setStroke(dp(2), Color.argb(100, 255, 255, 255))
        }
    }

    private fun updatePreview() {
        preview.background = bubbleBackground()
        preview.alpha = bubbleOpacity / 100f
        preview.layoutParams = preview.layoutParams.apply {
            width = dp(bubbleSize)
            height = dp(bubbleSize)
        }
        preview.requestLayout()
    }
}

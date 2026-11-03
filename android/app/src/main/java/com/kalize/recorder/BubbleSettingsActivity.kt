
package com.kalize.recorder

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class BubbleSettingsActivity : Activity() {

    private val prefs by lazy {
        getSharedPreferences("kalize_bubble_position", MODE_PRIVATE)
    }

    private val colors = listOf(
        "Orange" to Color.rgb(255, 115, 25),
        "Purple" to Color.rgb(125, 75, 220),
        "Blue" to Color.rgb(45, 125, 235),
        "Green" to Color.rgb(35, 165, 105),
        "Red" to Color.rgb(235, 55, 65)
    )

    private var selectedColor = Color.rgb(255, 115, 25)
    private var selectedSize = 60
    private var selectedOpacity = 100

    private lateinit var preview: TextView
    private lateinit var sizeValue: TextView
    private lateinit var opacityValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        selectedColor = prefs.getInt("bubble_color", colors[0].second)
        selectedSize = prefs.getInt("bubble_size", 60).coerceIn(48, 80)
        selectedOpacity = prefs.getInt("bubble_opacity", 100).coerceIn(30, 100)

        buildScreen()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
            setBackgroundColor(Color.rgb(15, 17, 25))
        }

        val scroll = android.widget.ScrollView(this)
        scroll.addView(root)

        val heading = TextView(this).apply {
            text = "Bubble Settings"
            textSize = 27f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(heading)

        val subtitle = TextView(this).apply {
            text = "Customise your floating Kalize bubble"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(7), 0, dp(24))
        }
        root.addView(subtitle)

        addSection(root, "LIVE PREVIEW")

        preview = TextView(this).apply {
            text = "K"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            elevation = dp(8).toFloat()
        }

        val previewHolder = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(25))
        }

        previewHolder.addView(
            preview,
            LinearLayout.LayoutParams(dp(selectedSize), dp(selectedSize))
        )
        root.addView(previewHolder)

        addSection(root, "BUBBLE COLOUR")

        val colorContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        colors.forEach { item ->
            val button = Button(this).apply {
                text = item.first
                textSize = 14f
                setTextColor(Color.WHITE)
                background = rounded(item.second, 14)
                setOnClickListener {
                    selectedColor = item.second
                    updatePreview()
                    Toast.makeText(
                        this@BubbleSettingsActivity,
                        "${item.first} selected",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            colorContainer.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(48)
                ).apply {
                    bottomMargin = dp(8)
                }
            )
        }

        root.addView(colorContainer)

        addSection(root, "BUBBLE SIZE")

        sizeValue = addValue(root, "${selectedSize}dp")

        val sizeSeek = SeekBar(this).apply {
            max = 32
            progress = selectedSize - 48
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        selectedSize = progress + 48
                        sizeValue.text = "${selectedSize}dp"
                        updatePreview()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                }
            )
        }
        root.addView(sizeSeek)

        addSection(root, "TRANSPARENCY")

        opacityValue = addValue(root, "$selectedOpacity%")

        val opacitySeek = SeekBar(this).apply {
            max = 70
            progress = selectedOpacity - 30
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        selectedOpacity = progress + 30
                        opacityValue.text = "$selectedOpacity%"
                        updatePreview()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                }
            )
        }
        root.addView(opacitySeek)

        val saveButton = Button(this).apply {
            text = "SAVE SETTINGS"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(125, 75, 220), 16)

            setOnClickListener {
                prefs.edit()
                    .putInt("bubble_color", selectedColor)
                    .putInt("bubble_size", selectedSize)
                    .putInt("bubble_opacity", selectedOpacity)
                    .apply()

                Toast.makeText(
                    this@BubbleSettingsActivity,
                    "Bubble settings saved",
                    Toast.LENGTH_LONG
                ).show()

                finish()
            }
        }

        root.addView(
            saveButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)
            ).apply {
                topMargin = dp(28)
            }
        )

        val note = TextView(this).apply {
            text = "Note: Saved settings will be used when the floating bubble is next created."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(18), 0, 0)
            gravity = Gravity.CENTER
        }
        root.addView(note)

        updatePreview()
        setContentView(scroll)
    }

    private fun addSection(parent: LinearLayout, text: String) {
        val label = TextView(this).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(180, 160, 255))
            setPadding(0, dp(18), 0, dp(10))
        }
        parent.addView(label)
    }

    private fun addValue(
        parent: LinearLayout,
        value: String
    ): TextView {
        val label = TextView(this).apply {
            text = value
            textSize = 15f
            setTextColor(Color.WHITE)
        }
        parent.addView(label)
        return label
    }

    private fun updatePreview() {
        if (!::preview.isInitialized) return

        preview.background = rounded(
            selectedColor,
            selectedSize
        )

        preview.alpha = selectedOpacity / 100f

        val params = preview.layoutParams
        params.width = dp(selectedSize)
        params.height = dp(selectedSize)
        preview.layoutParams = params
    }

    private fun rounded(
        color: Int,
        radius: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}

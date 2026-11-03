
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

    private lateinit var sizeValue: TextView
    private lateinit var opacityValue: TextView
    private lateinit var preview: TextView

    private val purple = Color.rgb(125, 75, 220)
    private val orange = Color.rgb(255, 115, 25)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bubbleSize = prefs.getInt("bubble_size", 60).coerceIn(48, 80)
        bubbleOpacity = prefs.getInt("bubble_opacity", 100).coerceIn(30, 100)

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
            text = "Adjust how your floating recording control looks."
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

        sizeValue = addSlider(
            page,
            "Bubble size",
            "48–80 dp",
            bubbleSize,
            48,
            32
        ) { value ->
            bubbleSize = value
            sizeValue.text = "${value} dp"
            updatePreview()
        }

        opacityValue = addSlider(
            page,
            "Bubble transparency",
            "30–100%",
            bubbleOpacity,
            30,
            70
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
                topMargin = dp(24)
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
        description: String,
        initial: Int,
        minimum: Int,
        range: Int,
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
            text = "$initial"
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
                        val selected = minimum + progress
                        onValueChanged(selected)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                }
            )
        }

        parent.addView(slider)

        return value
    }

    private fun bubbleBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(orange)
            setStroke(dp(2), Color.argb(100, 255, 255, 255))
        }
    }

    private fun updatePreview() {
        preview.alpha = bubbleOpacity / 100f
        preview.layoutParams = preview.layoutParams.apply {
            width = dp(bubbleSize)
            height = dp(bubbleSize)
        }
        preview.requestLayout()
    }
}

package com.kalize.recorder

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class QuickScreenshotActivity : ComponentActivity() {

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->

        val data = result.data

        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val screenshotIntent = Intent(
                this,
                ScreenshotService::class.java
            ).apply {
                putExtra(
                    ScreenshotService.EXTRA_RESULT_CODE,
                    result.resultCode
                )
                putExtra(
                    ScreenshotService.EXTRA_RESULT_DATA,
                    data
                )
            }

            try {
                ContextCompat.startForegroundService(
                    this,
                    screenshotIntent
                )

                Toast.makeText(
                    this,
                    "Screenshot capture started.",
                    Toast.LENGTH_SHORT
                ).show()

            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "Could not start screenshot capture.",
                    Toast.LENGTH_LONG
                ).show()
            }

        } else {
            Toast.makeText(
                this,
                "Screenshot permission was not granted.",
                Toast.LENGTH_SHORT
            ).show()
        }

        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            finish()
            return
        }

        val manager = getSystemService(
            MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        captureLauncher.launch(
            manager.createScreenCaptureIntent()
        )
    }
}

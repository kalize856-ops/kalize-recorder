package com.kalize.recorder

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File

@UnstableApi
class KalizeVideoEngine(context: Context) {

    interface ExportListener {
        fun onProgress(progress: Int)
        fun onCompleted(outputFile: File)
        fun onError(message: String)
    }

    data class EditSettings(
        val startMs: Long = 0L,
        val endMs: Long,
        val muteAudio: Boolean = false,
        val rotationDegrees: Float = 0f,
        val cropEnabled: Boolean = false,
        val cropLeft: Float = -1f,
        val cropRight: Float = 1f,
        val cropBottom: Float = -1f,
        val cropTop: Float = 1f
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var transformer: Transformer? = null
    private var currentOutput: File? = null

    private var progressRunnable: Runnable? = null

    fun export(
        inputUri: Uri,
        settings: EditSettings,
        listener: ExportListener
    ) {
        if (settings.startMs < 0L ||
            settings.endMs <= settings.startMs
        ) {
            listener.onError("Invalid video trim range.")
            return
        }

        if (settings.rotationDegrees % 90f != 0f) {
            listener.onError("Rotation must be 90, 180 or 270 degrees.")
            return
        }

        if (settings.cropEnabled) {
            if (
                settings.cropLeft < -1f ||
                settings.cropRight > 1f ||
                settings.cropBottom < -1f ||
                settings.cropTop > 1f ||
                settings.cropLeft >= settings.cropRight ||
                settings.cropBottom >= settings.cropTop
            ) {
                listener.onError("The crop area is invalid.")
                return
            }
        }

        try {
            cancelProgressUpdates()

            val outputDirectory = File(
                appContext.cacheDir,
                "kalize_exports"
            )

            if (!outputDirectory.exists() &&
                !outputDirectory.mkdirs()
            ) {
                listener.onError("Could not create export folder.")
                return
            }

            val outputFile = File(
                outputDirectory,
                "kalize_edit_${System.currentTimeMillis()}.mp4"
            )

            currentOutput = outputFile

            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(settings.startMs)
                .setEndPositionMs(settings.endMs)
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(inputUri)
                .setClippingConfiguration(clipping)
                .build()

            val videoEffects =
                mutableListOf<androidx.media3.common.Effect>()

            if (settings.rotationDegrees != 0f) {
                videoEffects.add(
                    ScaleAndRotateTransformation.Builder()
                        .setRotationDegrees(settings.rotationDegrees)
                        .build()
                )
            }

            if (settings.cropEnabled) {
                videoEffects.add(
                    Crop(
                        settings.cropLeft,
                        settings.cropRight,
                        settings.cropBottom,
                        settings.cropTop
                    )
                )
            }

            val effects = Effects(
                emptyList(),
                videoEffects
            )

            val editedItem = EditedMediaItem.Builder(mediaItem)
                .setEffects(effects)
                .setRemoveAudio(settings.muteAudio)
                .build()

            val exportTransformer = Transformer.Builder(appContext)
                .addListener(
                    object : Transformer.Listener {

                        override fun onCompleted(
                            composition: androidx.media3.transformer.Composition,
                            exportResult: ExportResult
                        ) {
                            cancelProgressUpdates()

                            mainHandler.post {
                                listener.onProgress(100)
                                listener.onCompleted(outputFile)
                            }
                        }

                        override fun onError(
                            composition: androidx.media3.transformer.Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            cancelProgressUpdates()
                            outputFile.delete()

                            mainHandler.post {
                                listener.onError(
                                    exportException.message
                                        ?: "Video export failed."
                                )
                            }
                        }
                    }
                )
                .build()

            transformer = exportTransformer

            exportTransformer.start(
                editedItem,
                outputFile.absolutePath
            )

            startProgressUpdates(exportTransformer, listener)

        } catch (error: Exception) {
            cancelProgressUpdates()

            mainHandler.post {
                listener.onError(
                    error.message ?: "Could not start video export."
                )
            }
        }
    }

    private fun startProgressUpdates(
        activeTransformer: Transformer,
        listener: ExportListener
    ) {
        val holder = ProgressHolder()

        val runnable = object : Runnable {
            override fun run() {
                if (transformer !== activeTransformer) return

                try {
                    val state =
                        activeTransformer.getProgress(holder)

                    if (
                        state ==
                        Transformer.PROGRESS_STATE_AVAILABLE
                    ) {
                        listener.onProgress(
                            holder.progress.coerceIn(0, 99)
                        )
                    }

                    if (
                        state !=
                        Transformer.PROGRESS_STATE_NOT_STARTED
                    ) {
                        mainHandler.postDelayed(this, 500L)
                    }

                } catch (_: Exception) {
                    cancelProgressUpdates()
                }
            }
        }

        progressRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun cancelProgressUpdates() {
        progressRunnable?.let {
            mainHandler.removeCallbacks(it)
        }

        progressRunnable = null
    }

    fun cancelExport() {
        cancelProgressUpdates()

        try {
            transformer?.cancel()
        } catch (_: Exception) {
        }

        transformer = null
        currentOutput?.delete()
        currentOutput = null
    }

    fun release() {
        cancelProgressUpdates()
        transformer = null
        currentOutput = null
    }
}

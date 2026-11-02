package com.kalize.recorder

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
class KalizeVideoEngine(context: Context) {

    data class EditSettings(
        val startMs: Long,
        val endMs: Long,
        val muteAudio: Boolean = false,
        val rotationDegrees: Float = 0f,
        val cropEnabled: Boolean = false,
        val cropLeft: Float = -1f,
        val cropRight: Float = 1f,
        val cropBottom: Float = -1f,
        val cropTop: Float = 1f
    )

    interface ExportListener {
        fun onProgress(progress: Int)
        fun onCompleted(outputFile: File)
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var transformer: Transformer? = null
    private var currentOutput: File? = null
    private var currentListener: ExportListener? = null

    private val exporting = AtomicBoolean(false)

    private val progressHolder = ProgressHolder()

    private val progressRunnable = object : Runnable {
        override fun run() {
            val activeTransformer = transformer ?: return

            if (!exporting.get()) return

            try {
                val state = activeTransformer.getProgress(progressHolder)

                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val progress = progressHolder.progress.coerceIn(0, 99)
                    currentListener?.onProgress(progress)
                }
            } catch (_: Exception) {
                // Progress reporting must not interrupt an export.
            }

            if (exporting.get()) {
                mainHandler.postDelayed(this, 500)
            }
        }
    }

    fun export(
        inputUri: Uri,
        settings: EditSettings,
        listener: ExportListener
    ) {
        if (!exporting.compareAndSet(false, true)) {
            listener.onError("Another video export is already running.")
            return
        }

        currentListener = listener

        try {
            require(settings.startMs >= 0L) {
                "The start time cannot be negative."
            }

            require(settings.endMs > settings.startMs) {
                "The end time must be after the start time."
            }

            require(settings.rotationDegrees in 0f..360f) {
                "The selected rotation is invalid."
            }

            if (settings.cropEnabled) {
                require(
                    settings.cropLeft >= -1f &&
                    settings.cropRight <= 1f &&
                    settings.cropBottom >= -1f &&
                    settings.cropTop <= 1f &&
                    settings.cropLeft < settings.cropRight &&
                    settings.cropBottom < settings.cropTop
                ) {
                    "The selected crop area is invalid."
                }
            }

            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(settings.startMs)
                .setEndPositionMs(settings.endMs)
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(inputUri)
                .setClippingConfiguration(clipping)
                .build()

            val videoEffects = mutableListOf<androidx.media3.common.Effect>()

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

            val output = File.createTempFile(
                "kalize_edit_",
                ".mp4",
                appContext.cacheDir
            )

            currentOutput = output

            val newTransformer = Transformer.Builder(appContext)
                .addListener(
                    object : Transformer.Listener {

                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult
                        ) {
                            exporting.set(false)
                            mainHandler.removeCallbacks(progressRunnable)

                            transformer = null
                            currentOutput = null

                            val completedListener = currentListener
                            currentListener = null

                            completedListener?.onProgress(100)
                            completedListener?.onCompleted(output)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            exporting.set(false)
                            mainHandler.removeCallbacks(progressRunnable)

                            transformer = null

                            output.delete()
                            currentOutput = null

                            val errorListener = currentListener
                            currentListener = null

                            errorListener?.onError(
                                exportException.message
                                    ?: "The video could not be processed."
                            )
                        }
                    }
                )
                .build()

            transformer = newTransformer

            newTransformer.start(
                editedItem,
                output.absolutePath
            )

            mainHandler.post(progressRunnable)

        } catch (error: Exception) {
            exporting.set(false)
            mainHandler.removeCallbacks(progressRunnable)

            transformer = null

            currentOutput?.delete()
            currentOutput = null

            currentListener?.onError(
                error.message ?: "Could not start video export."
            )

            currentListener = null
        }
    }

    fun cancelExport() {
        mainHandler.removeCallbacks(progressRunnable)

        try {
            transformer?.cancel()
        } catch (_: Exception) {
        }

        transformer = null

        currentOutput?.delete()
        currentOutput = null

        exporting.set(false)

        val listener = currentListener
        currentListener = null

        listener?.onError("Video export was cancelled.")
    }

    fun release() {
        if (exporting.get()) {
            cancelExport()
        } else {
            mainHandler.removeCallbacks(progressRunnable)
            transformer = null
            currentOutput?.delete()
            currentOutput = null
            currentListener = null
        }
    }
}

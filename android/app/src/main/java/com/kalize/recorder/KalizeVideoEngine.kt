
package com.kalize.recorder

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
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
    private val lock = Any()

    private var exportGeneration = 0

    private val progressHolder = ProgressHolder()

    private val progressRunnable = object : Runnable {
        override fun run() {
            val activeTransformer: Transformer
            val activeGeneration: Int

            synchronized(lock) {
                if (!exporting.get()) return

                activeTransformer = transformer ?: return
                activeGeneration = exportGeneration
            }

            try {
                val state = activeTransformer.getProgress(progressHolder)

                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val progress = progressHolder.progress.coerceIn(0, 99)

                    val listener = synchronized(lock) {
                        if (
                            exporting.get() &&
                            exportGeneration == activeGeneration
                        ) {
                            currentListener
                        } else {
                            null
                        }
                    }

                    listener?.onProgress(progress)
                }
            } catch (_: Exception) {
                // Progress reporting must never interrupt video processing.
            }

            synchronized(lock) {
                if (
                    exporting.get() &&
                    exportGeneration == activeGeneration
                ) {
                    mainHandler.postDelayed(this, 500)
                }
            }
        }
    }

    fun export(
        inputUri: Uri,
        settings: EditSettings,
        listener: ExportListener
    ) {
        synchronized(lock) {
            if (!exporting.compareAndSet(false, true)) {
                mainHandler.post {
                    listener.onError(
                        "Another video export is already running."
                    )
                }
                return
            }

            exportGeneration++
            currentListener = listener
        }

        try {
            validateSettings(settings)

            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(settings.startMs)
                .setEndPositionMs(settings.endMs)
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(inputUri)
                .setClippingConfiguration(clipping)
                .build()

            val videoEffects = mutableListOf<Effect>()

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

            val generation: Int

            synchronized(lock) {
                if (!exporting.get()) {
                    output.delete()
                    return
                }

                generation = exportGeneration
                currentOutput = output
            }

            val newTransformer = Transformer.Builder(appContext)
                .addListener(
                    object : Transformer.Listener {

                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult
                        ) {
                            val completedListener: ExportListener?

                            synchronized(lock) {
                                if (
                                    !exporting.get() ||
                                    exportGeneration != generation
                                ) {
                                    output.delete()
                                    return
                                }

                                exporting.set(false)
                                mainHandler.removeCallbacks(progressRunnable)

                                transformer = null
                                currentOutput = null

                                completedListener = currentListener
                                currentListener = null
                            }

                            mainHandler.post {
                                completedListener?.onProgress(100)
                                completedListener?.onCompleted(output)
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            val errorListener: ExportListener?

                            synchronized(lock) {
                                if (exportGeneration != generation) {
                                    output.delete()
                                    return
                                }

                                exporting.set(false)
                                mainHandler.removeCallbacks(progressRunnable)

                                transformer = null
                                currentOutput = null

                                errorListener = currentListener
                                currentListener = null
                            }

                            output.delete()

                            val message = exportException.message
                                ?: "The video could not be processed."

                            mainHandler.post {
                                errorListener?.onError(message)
                            }
                        }
                    }
                )
                .build()

            synchronized(lock) {
                if (
                    !exporting.get() ||
                    exportGeneration != generation
                ) {
                    output.delete()
                    return
                }

                transformer = newTransformer
            }

            newTransformer.start(
                editedItem,
                output.absolutePath
            )

            mainHandler.post(progressRunnable)

        } catch (error: Exception) {
            val errorListener: ExportListener?

            synchronized(lock) {
                exporting.set(false)
                mainHandler.removeCallbacks(progressRunnable)

                transformer = null
                currentOutput?.delete()
                currentOutput = null

                errorListener = currentListener
                currentListener = null
            }

            val message = error.message
                ?: "Could not start video export."

            mainHandler.post {
                errorListener?.onError(message)
            }
        }
    }

    private fun validateSettings(settings: EditSettings) {
        require(settings.startMs >= 0L) {
            "The start time cannot be negative."
        }

        require(settings.endMs > settings.startMs) {
            "The end time must be after the start time."
        }

        require(
            settings.rotationDegrees.isFinite() &&
                settings.rotationDegrees in 0f..360f
        ) {
            "The selected rotation is invalid."
        }

        if (settings.cropEnabled) {
            require(
                settings.cropLeft.isFinite() &&
                    settings.cropRight.isFinite() &&
                    settings.cropBottom.isFinite() &&
                    settings.cropTop.isFinite() &&
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
    }

    fun cancelExport() {
        val activeTransformer: Transformer?
        val output: File?
        val listener: ExportListener?

        synchronized(lock) {
            if (!exporting.get()) return

            exportGeneration++

            exporting.set(false)
            mainHandler.removeCallbacks(progressRunnable)

            activeTransformer = transformer
            transformer = null

            output = currentOutput
            currentOutput = null

            listener = currentListener
            currentListener = null
        }

        try {
            activeTransformer?.cancel()
        } catch (_: Exception) {
        }

        output?.delete()

        mainHandler.post {
            listener?.onError("Video export was cancelled.")
        }
    }

    fun release() {
        cancelExport()

        synchronized(lock) {
            mainHandler.removeCallbacks(progressRunnable)

            transformer = null
            currentOutput?.delete()
            currentOutput = null
            currentListener = null
        }
    }
}

package com.kalize.recorder

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class CompressionPreset(
    val title: String,
    val detail: String,
    val targetHeight: Int,
    val bitrate: Int
) {
    SMALL("Small file", "480p · 1.2 Mbps", 480, 1_200_000),
    BALANCED("Balanced", "720p · 2.5 Mbps", 720, 2_500_000),
    HIGH("High quality", "1080p · 5 Mbps", 1080, 5_000_000)
}

@OptIn(UnstableApi::class)
object VideoCompressor {
    suspend fun compress(context: Context, source: Uri, preset: CompressionPreset): Uri {
        val sourceHeight = readVideoHeight(context, source)
        require(sourceHeight > 0) { "Could not read the selected video's dimensions." }
        val outputHeight = minOf(sourceHeight, preset.targetHeight).coerceAtLeast(144)
        val tempFile = File(context.cacheDir, "kalize_compressed_" + System.currentTimeMillis() + ".mp4")
        try {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val encoderFactory = DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder().setBitrate(preset.bitrate).build()
                        )
                        .build()
                    val listener = object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            if (continuation.isActive) continuation.resumeWithException(exportException)
                        }
                    }
                    val transformer = Transformer.Builder(context)
                        .setEncoderFactory(encoderFactory)
                        .addListener(listener)
                        .build()
                    continuation.invokeOnCancellation {
                        try { transformer.cancel() } catch (_: Exception) { }
                        tempFile.delete()
                    }
                    try {
                        val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(source))
                            .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(outputHeight))))
                            .build()
                        transformer.start(editedItem, tempFile.absolutePath)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
            return withContext(Dispatchers.IO) { saveCompressedCopy(context, tempFile) }
        } finally {
            tempFile.delete()
        }
    }

    private fun readVideoHeight(context: Context, uri: Uri): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        } finally {
            retriever.release()
        }
    }

    private fun saveCompressedCopy(context: Context, file: File): Uri {
        require(file.exists() && file.length() > 0L) { "The compression export produced no video data." }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Kalize_Compressed_" + System.currentTimeMillis() + ".mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Kalize Recorder/Compressed")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val outputUri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create the compressed video file.")
        try {
            context.contentResolver.openOutputStream(outputUri, "w")!!.use { output ->
                FileInputStream(file).use { input -> input.copyTo(output) }
            }
            context.contentResolver.update(outputUri, ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }, null, null)
            return outputUri
        } catch (error: Exception) {
            context.contentResolver.delete(outputUri, null, null)
            throw error
        }
    }
}

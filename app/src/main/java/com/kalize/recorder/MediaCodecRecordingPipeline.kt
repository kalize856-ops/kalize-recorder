package com.kalize.recorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.Surface
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Surface-input H.264 recorder. MediaProjection renders directly into the encoder
 * surface; encoded access units are drained on a dedicated thread into MP4.
 *
 * The selected FPS is a codec configuration target, not a guarantee that the
 * device can sustain that rate under load.
 */
class MediaCodecRecordingPipeline(
    private val width: Int,
    private val height: Int,
    frameRate: Int,
    private val bitRate: Int,
    private val outputDescriptor: ParcelFileDescriptor?,
    private val outputFile: File?
) {
    private val requestedFrameRate = frameRate.coerceIn(30, 240)
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var drainThread: Thread? = null
    private var muxerStarted = false
    private var videoTrack = -1
    private var encodedSamples = 0L
    private val stopping = AtomicBoolean(false)
    @Volatile private var drainFailure: Throwable? = null

    lateinit var inputSurface: Surface
        private set

    var actualFrameRate: Int = requestedFrameRate
        private set

    var encoderName: String = "unknown"
        private set

    fun start() {
        require(width >= 2 && height >= 2) { "Invalid recording dimensions" }
        require(outputDescriptor != null || outputFile != null) { "Missing MP4 output destination" }

        val candidates = listOf(requestedFrameRate, 120, 90, 60, 30)
            .distinct().filter { it <= requestedFrameRate && it >= 30 }
        var lastError: Throwable? = null

        for (fps in candidates) {
            val codecInfo = findEncoder(width, height, fps) ?: continue
            var candidate: MediaCodec? = null
            try {
                candidate = MediaCodec.createByCodecName(codecInfo.name)
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate.coerceAtLeast(500_000))
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    if (Build.VERSION.SDK_INT >= 29) {
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    }
                }
                candidate.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = candidate.createInputSurface()
                candidate.start()
                encoder = candidate
                encoderName = codecInfo.name
                actualFrameRate = fps
                muxer = if (outputDescriptor != null && Build.VERSION.SDK_INT >= 26) {
                    MediaMuxer(outputDescriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                } else {
                    MediaMuxer(outputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                }
                drainThread = thread(name = "KalizeMediaCodecDrain", isDaemon = true) { drainEncoder() }
                return
            } catch (error: Throwable) {
                lastError = error
                runCatching { candidate?.stop() }
                runCatching { candidate?.release() }
                runCatching { if (::inputSurface.isInitialized) inputSurface.release() }
                runCatching { muxer?.release() }
                muxer = null
                muxerStarted = false
                encoder = null
            }
        }
        throw IllegalStateException("No compatible H.264 surface encoder could start at 30–${requestedFrameRate} FPS.", lastError)
    }

    private fun findEncoder(width: Int, height: Int, fps: Int): MediaCodecInfo? {
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .mapNotNull { info ->
                val supported = runCatching {
                    val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) &&
                        caps.videoCapabilities.areSizeAndRateSupported(width, height, fps.toDouble())
                }.getOrDefault(false)
                if (supported) info else null
            }
            .sortedBy { if (Build.VERSION.SDK_INT >= 29 && it.isHardwareAccelerated) 0 else 1 }
            .firstOrNull()
    }

    private fun drainEncoder() {
        val info = MediaCodec.BufferInfo()
        var endOfStream = false
        try {
            while (!endOfStream) {
                val codec = encoder ?: break
                val index = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (stopping.get() && drainThread?.isInterrupted == true) break
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "Encoder output format changed more than once" }
                        videoTrack = muxer!!.addTrack(codec.outputFormat)
                        muxer!!.start()
                        muxerStarted = true
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0 && muxerStarted &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer!!.writeSampleData(videoTrack, buffer, info)
                            encodedSamples++
                        }
                        endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        } catch (error: Throwable) {
            drainFailure = error
        }
    }

    /** Signals EOS, drains the final samples and closes a playable MP4. */
    fun stop(): Boolean {
        if (!stopping.compareAndSet(false, true)) return false
        runCatching { encoder?.signalEndOfInputStream() }
        try {
            drainThread?.join(7_000)
            if (drainThread?.isAlive == true) {
                drainThread?.interrupt()
                drainThread?.join(500)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { if (::inputSurface.isInitialized) inputSurface.release() }
        runCatching { if (muxerStarted) muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
        return muxerStarted && encodedSamples > 0 && drainFailure == null
    }
}

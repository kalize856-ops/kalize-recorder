package com.kalize.recorder

import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.Surface
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * H.264 surface video recorder with optional Android 10+ playback-audio capture.
 * Playback audio is limited by Android's capture policy: apps can opt out, and
 * only eligible media/game usages are included. It never bypasses that policy.
 */
class MediaCodecRecordingPipeline(
    private val width: Int,
    private val height: Int,
    frameRate: Int,
    private val bitRate: Int,
    private val outputDescriptor: ParcelFileDescriptor?,
    private val outputFile: File?,
    private val projection: MediaProjection? = null,
    private val capturePlaybackAudio: Boolean = false
) {
    private val requestedFrameRate = frameRate.coerceIn(30, 240)
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var videoDrainThread: Thread? = null
    private var audioDrainThread: Thread? = null
    private var audioInputThread: Thread? = null
    private var muxerStarted = false
    private var videoFormatReady = false
    private var audioFormatReady = false
    private var videoTrack = -1
    private var audioTrack = -1
    private var encodedVideoSamples = 0L
    private var encodedAudioSamples = 0L
    private val stopping = AtomicBoolean(false)
    private val muxerLock = Any()
    @Volatile private var playbackAudioPaused = false
    @Volatile private var drainFailure: Throwable? = null

    lateinit var inputSurface: Surface
        private set

    var actualFrameRate: Int = requestedFrameRate
        private set

    var encoderName: String = "unknown"
        private set

    val audioCaptureActive: Boolean
        get() = capturePlaybackAudio && audioRecord != null && audioEncoder != null

    fun start() {
        require(width >= 2 && height >= 2) { "Invalid recording dimensions" }
        require(outputDescriptor != null || outputFile != null) { "Missing MP4 output destination" }
        require(!capturePlaybackAudio || (Build.VERSION.SDK_INT >= 29 && projection != null)) {
            "Internal app audio requires Android 10 or newer and an active screen-capture session."
        }

        val candidates = listOf(requestedFrameRate, 120, 90, 60, 30)
            .distinct().filter { it <= requestedFrameRate && it >= 30 }
        var lastError: Throwable? = null

        for (fps in candidates) {
            val codecInfo = findVideoEncoder(width, height, fps) ?: continue
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
                videoEncoder = candidate
                encoderName = codecInfo.name
                actualFrameRate = fps

                muxer = if (outputDescriptor != null && Build.VERSION.SDK_INT >= 26) {
                    MediaMuxer(outputDescriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                } else {
                    MediaMuxer(outputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                }

                if (capturePlaybackAudio) startPlaybackAudioCapture()
                videoDrainThread = thread(name = "KalizeVideoDrain", isDaemon = true) { drainVideoEncoder() }
                if (audioEncoder != null) {
                    audioDrainThread = thread(name = "KalizeAudioDrain", isDaemon = true) { drainAudioEncoder() }
                    audioInputThread = thread(name = "KalizeAudioInput", isDaemon = true) { feedAudioEncoder() }
                }
                return
            } catch (error: Throwable) {
                lastError = error
                releaseAudioResources()
                runCatching { candidate?.stop() }
                runCatching { candidate?.release() }
                runCatching { if (::inputSurface.isInitialized) inputSurface.release() }
                runCatching { muxer?.release() }
                muxer = null
                videoEncoder = null
            }
        }
        throw IllegalStateException("No compatible H.264 surface encoder could start at 30–${requestedFrameRate} FPS.", lastError)
    }

    private fun findVideoEncoder(width: Int, height: Int, fps: Int): MediaCodecInfo? {
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

    private fun startPlaybackAudioCapture() {
        val activeProjection = projection ?: throw IllegalStateException("Screen capture permission is missing.")
        val config = AudioPlaybackCaptureConfiguration.Builder(activeProjection)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
            .build()
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AUDIO_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minimumBuffer = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        require(minimumBuffer > 0) { "This device cannot create a playback-audio capture buffer." }
        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(maxOf(minimumBuffer * 2, AUDIO_BUFFER_BYTES))
            .build()
        require(record.state == AudioRecord.STATE_INITIALIZED) { "Playback-audio capture is unavailable." }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_BUFFER_BYTES)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            record.startRecording()
            audioEncoder = codec
            audioRecord = record
        } catch (error: Throwable) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { record.release() }
            throw error
        }
    }

    private fun startMuxerIfReadyLocked() {
        if (!muxerStarted && videoFormatReady && (!capturePlaybackAudio || audioFormatReady)) {
            muxer?.start()
            muxerStarted = true
        }
    }

    private fun drainVideoEncoder() {
        val info = MediaCodec.BufferInfo()
        var endOfStream = false
        try {
            while (!endOfStream) {
                val codec = videoEncoder ?: break
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(muxerLock) {
                        check(!videoFormatReady) { "Video encoder format changed more than once" }
                        videoTrack = muxer!!.addTrack(codec.outputFormat)
                        videoFormatReady = true
                        startMuxerIfReadyLocked()
                    }
                    else -> if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            synchronized(muxerLock) {
                                if (muxerStarted) {
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    muxer!!.writeSampleData(videoTrack, buffer, info)
                                    encodedVideoSamples++
                                }
                            }
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

    fun pausePlaybackAudio() {
        if (audioRecord == null || stopping.get()) return
        playbackAudioPaused = true
        runCatching { audioRecord?.stop() }
    }

    fun resumePlaybackAudio() {
        if (audioRecord == null || stopping.get()) return
        runCatching {
            audioRecord?.startRecording()
            playbackAudioPaused = false
        }.onFailure { drainFailure = it }
    }

    private fun feedAudioEncoder() {
        val record = audioRecord ?: return
        val codec = audioEncoder ?: return
        val pcm = ByteArray(AUDIO_BUFFER_BYTES)
        var totalBytes = 0L
        try {
            while (!stopping.get()) {
                val read = record.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) {
                    if (playbackAudioPaused && !stopping.get()) Thread.sleep(20)
                    continue
                }
                var offset = 0
                while (offset < read && !stopping.get()) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex < 0) continue
                    val input = codec.getInputBuffer(inputIndex) ?: continue
                    input.clear()
                    val count = minOf(input.remaining(), read - offset)
                    input.put(pcm, offset, count)
                    val ptsUs = totalBytes * 1_000_000L / (AUDIO_SAMPLE_RATE * AUDIO_CHANNELS * 2L)
                    codec.queueInputBuffer(inputIndex, 0, count, ptsUs, 0)
                    totalBytes += count
                    offset += count
                }
            }
        } catch (error: Throwable) {
            if (!stopping.get()) drainFailure = error
        } finally {
            runCatching {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) codec.queueInputBuffer(index, 0, 0, totalBytes * 1_000_000L / (AUDIO_SAMPLE_RATE * AUDIO_CHANNELS * 2L), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }
    }

    private fun drainAudioEncoder() {
        val info = MediaCodec.BufferInfo()
        var endOfStream = false
        try {
            while (!endOfStream) {
                val codec = audioEncoder ?: break
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(muxerLock) {
                        check(!audioFormatReady) { "Audio encoder format changed more than once" }
                        audioTrack = muxer!!.addTrack(codec.outputFormat)
                        audioFormatReady = true
                        startMuxerIfReadyLocked()
                    }
                    else -> if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            synchronized(muxerLock) {
                                if (muxerStarted) {
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    muxer!!.writeSampleData(audioTrack, buffer, info)
                                    encodedAudioSamples++
                                }
                            }
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

    private fun releaseAudioResources() {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { audioEncoder?.stop() }
        runCatching { audioEncoder?.release() }
        audioEncoder = null
    }

    /** Signals EOS, drains both tracks and closes a playable MP4. */
    fun stop(): Boolean {
        if (!stopping.compareAndSet(false, true)) return false
        runCatching { audioRecord?.stop() }
        try { audioInputThread?.join(2_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        runCatching { videoEncoder?.signalEndOfInputStream() }
        try {
            videoDrainThread?.join(7_000)
            audioDrainThread?.join(7_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        runCatching { videoEncoder?.stop() }
        runCatching { videoEncoder?.release() }
        videoEncoder = null
        runCatching { audioEncoder?.stop() }
        runCatching { audioEncoder?.release() }
        audioEncoder = null
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { if (::inputSurface.isInitialized) inputSurface.release() }
        synchronized(muxerLock) {
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            muxer = null
        }
        val audioOkay = !capturePlaybackAudio || encodedAudioSamples > 0
        return muxerStarted && encodedVideoSamples > 0 && audioOkay && drainFailure == null
    }

    companion object {
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_CHANNELS = 2
        private const val AUDIO_BIT_RATE = 128_000
        private const val AUDIO_BUFFER_BYTES = 32 * 1024
    }
}

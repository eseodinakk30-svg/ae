package com.autoedit.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface

/** Кодировщик H.264, принимающий кадры через Surface, и муксер в mp4. */
class VideoEncoder(
    width: Int,
    height: Int,
    fps: Int,
    bitRate: Int,
    outputPath: String,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME)
    private val muxer: MediaMuxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxerStarted = false
    private var released = false

    val inputSurface: Surface

    init {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_CAPTURE_RATE, fps)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
    }

    /** Забирает готовые пакеты у кодировщика и пишет их в файл. */
    fun drain(endOfStream: Boolean) {
        if (released) return
        if (endOfStream) {
            runCatching { codec.signalEndOfInputStream() }
        }
        var idleGuard = 0
        while (idleGuard < 2000) {
            val outIdx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return
                idleGuard++
                continue
            }
            idleGuard = 0
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                check(!muxerStarted) { "Формат кодировщика сменился дважды" }
                trackIndex = muxer.addTrack(codec.outputFormat)
                muxer.start()
                muxerStarted = true
                continue
            }
            if (outIdx < 0) continue

            val buf = codec.getOutputBuffer(outIdx)
            if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                if (muxerStarted) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    muxer.writeSampleData(trackIndex, buf, info)
                }
            }
            codec.releaseOutputBuffer(outIdx, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
        }
    }

    fun release() {
        if (released) return
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { if (muxerStarted) muxer.stop() }
        runCatching { muxer.release() }
        runCatching { inputSurface.release() }
    }

    companion object {
        const val MIME = "video/avc"

        /** Битрейт под разрешение — чтобы картинка не сыпалась на резких резах. */
        fun bitrateFor(width: Int, height: Int, fps: Int): Int =
            (width.toLong() * height * fps * 0.22).toInt().coerceIn(3_500_000, 24_000_000)
    }
}

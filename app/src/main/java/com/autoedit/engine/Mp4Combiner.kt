package com.autoedit.engine

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer

/** Склейка отрендеренного видео и готовой аудиодорожки в один mp4 без перекодирования. */
object Mp4Combiner {

    fun combine(videoPath: String, audioPath: String?, outPath: String): Boolean {
        val videoEx = MediaExtractor()
        videoEx.setDataSource(videoPath)
        val videoTrack = firstTrack(videoEx, "video/") ?: run {
            videoEx.release()
            return false
        }
        videoEx.selectTrack(videoTrack)
        val videoFormat = videoEx.getTrackFormat(videoTrack)

        var audioEx: MediaExtractor? = null
        var audioFormat: MediaFormat? = null
        if (audioPath != null) {
            val ex = MediaExtractor()
            runCatching { ex.setDataSource(audioPath) }.onSuccess {
                val t = firstTrack(ex, "audio/")
                if (t != null) {
                    ex.selectTrack(t)
                    audioEx = ex
                    audioFormat = ex.getTrackFormat(t)
                } else {
                    ex.release()
                }
            }.onFailure { ex.release() }
        }

        val muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outVideoTrack = muxer.addTrack(videoFormat)
        val outAudioTrack = audioFormat?.let { muxer.addTrack(it) } ?: -1
        muxer.start()

        val bufferSize = maxOf(
            maxInputSize(videoFormat),
            audioFormat?.let { maxInputSize(it) } ?: 0,
            256 * 1024,
        )
        val buffer = ByteBuffer.allocate(bufferSize)
        val info = MediaCodec.BufferInfo()

        try {
            // Пишем чересстрочно по времени, чтобы плеер не буферизовал лишнего.
            var videoDone = false
            var audioDone = audioEx == null
            var videoTime = nextTime(videoEx)
            var audioTime = audioEx?.let { nextTime(it) } ?: Long.MAX_VALUE

            while (!videoDone || !audioDone) {
                val takeVideo = !videoDone && (audioDone || videoTime <= audioTime)
                if (takeVideo) {
                    if (!writeSample(videoEx, muxer, outVideoTrack, buffer, info)) {
                        videoDone = true
                    } else {
                        videoTime = nextTime(videoEx)
                        if (videoTime == Long.MAX_VALUE) videoDone = true
                    }
                } else {
                    val ex = audioEx
                    if (ex == null || outAudioTrack < 0 ||
                        !writeSample(ex, muxer, outAudioTrack, buffer, info)
                    ) {
                        audioDone = true
                    } else {
                        audioTime = nextTime(ex)
                        if (audioTime == Long.MAX_VALUE) audioDone = true
                    }
                }
            }
        } finally {
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
            runCatching { videoEx.release() }
            runCatching { audioEx?.release() }
        }
        return true
    }

    private fun nextTime(ex: MediaExtractor): Long {
        val t = ex.sampleTime
        return if (t < 0) Long.MAX_VALUE else t
    }

    private fun writeSample(
        ex: MediaExtractor,
        muxer: MediaMuxer,
        track: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
    ): Boolean {
        buffer.clear()
        val size = ex.readSampleData(buffer, 0)
        if (size < 0) return false
        info.offset = 0
        info.size = size
        info.presentationTimeUs = ex.sampleTime
        info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            MediaCodec.BUFFER_FLAG_KEY_FRAME
        } else 0
        muxer.writeSampleData(track, buffer, info)
        ex.advance()
        return true
    }

    private fun firstTrack(ex: MediaExtractor, prefix: String): Int? {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return null
    }

    private fun maxInputSize(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else 0
}

package com.autoedit.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlin.math.abs

/**
 * Декодер одного исходного видео: выдаёт кадр в [ExternalTexture]
 * на запрошенный момент времени.
 */
class SourceDecoder(
    private val context: Context,
    private val uri: Uri,
    private val target: ExternalTexture,
) {
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()

    var durationUs: Long = 0
        private set
    var width: Int = 0
        private set
    var height: Int = 0
        private set
    var rotationDegrees: Int = 0
        private set
    /** PTS кадра, который сейчас лежит в текстуре. */
    var lastPtsUs: Long = -1L
        private set

    private var inputDone = false
    private var outputDone = false
    private var frameIntervalUs: Long = 33_333

    fun start() {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                trackIndex = i
                format = f
                break
            }
        }
        require(trackIndex >= 0 && format != null) { "В файле нет видеодорожки" }
        ex.selectTrack(trackIndex)

        width = format.getInteger(MediaFormat.KEY_WIDTH)
        height = format.getInteger(MediaFormat.KEY_HEIGHT)
        durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else 0L
        rotationDegrees = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
            format.getInteger(MediaFormat.KEY_ROTATION)
        } else 0
        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            val fps = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(30)
            if (fps > 0) frameIntervalUs = 1_000_000L / fps
        }

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val dec = MediaCodec.createDecoderByType(mime)
        dec.configure(format, target.surface, null, 0)
        dec.start()

        extractor = ex
        codec = dec
        inputDone = false
        outputDone = false
        lastPtsUs = -1L
    }

    fun seekTo(us: Long) {
        val ex = extractor ?: return
        val dec = codec ?: return
        ex.seekTo(us.coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        dec.flush()
        inputDone = false
        outputDone = false
        lastPtsUs = -1L
    }

    /** Нужно ли перематывать, чтобы попасть в [targetUs]. */
    fun needsSeek(targetUs: Long): Boolean {
        if (lastPtsUs < 0) return true
        if (targetUs < lastPtsUs - frameIntervalUs) return true
        return targetUs - lastPtsUs > 2_500_000L
    }

    /**
     * Проматывает декодер до [targetUs] и заливает нужный кадр в текстуру.
     * @return true, если в текстуре появился новый кадр.
     */
    fun advanceTo(targetUs: Long): Boolean {
        val ex = extractor ?: return false
        val dec = codec ?: return false
        if (lastPtsUs >= 0 && lastPtsUs + frameIntervalUs / 2 >= targetUs) return false

        var rendered = false
        var idleLoops = 0
        while (!outputDone && idleLoops < 600) {
            if (!inputDone) {
                val inIdx = dec.dequeueInputBuffer(2000)
                if (inIdx >= 0) {
                    val buf = dec.getInputBuffer(inIdx)
                    val size = if (buf != null) ex.readSampleData(buf, 0) else -1
                    if (size < 0) {
                        dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        dec.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }

            when (val outIdx = dec.dequeueOutputBuffer(info, 5000)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> idleLoops++
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> idleLoops++
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> idleLoops++
                else -> {
                    if (outIdx < 0) {
                        idleLoops++
                        continue
                    }
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val pts = info.presentationTimeUs
                    if (info.size <= 0) {
                        dec.releaseOutputBuffer(outIdx, false)
                    } else if (!eos && pts + frameIntervalUs / 2 < targetUs) {
                        // Кадр ещё до нужного момента — пропускаем без отрисовки.
                        dec.releaseOutputBuffer(outIdx, false)
                        idleLoops = 0
                    } else {
                        dec.releaseOutputBuffer(outIdx, true)
                        if (target.awaitAndUpdate()) {
                            lastPtsUs = pts
                            rendered = true
                        }
                        if (eos) outputDone = true
                        break
                    }
                    if (eos) {
                        outputDone = true
                        break
                    }
                }
            }
        }
        return rendered
    }

    /** Есть ли ещё материал после [us]. */
    fun hasMaterialAfter(us: Long): Boolean = durationUs <= 0 || us < durationUs - 100_000L

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor?.release() }
        codec = null
        extractor = null
    }

    companion object {
        fun rotationAwareSize(width: Int, height: Int, rotation: Int): Pair<Int, Int> =
            if (abs(rotation) % 180 == 90) height to width else width to height
    }
}

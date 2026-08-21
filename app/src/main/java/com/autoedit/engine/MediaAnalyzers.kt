package com.autoedit.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.autoedit.core.BeatDetector
import com.autoedit.core.ClipInfo
import com.autoedit.core.SegmentBuilder
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Загрузка музыки в моно-PCM для анализа битов. */
object AudioLoader {

    private const val TIMEOUT = 5_000L

    /**
     * @return моно-сэмплы, приведённые к [BeatDetector.ANALYSIS_RATE], либо null.
     */
    fun loadForAnalysis(context: Context, uri: Uri, maxSeconds: Int = 420): FloatArray? {
        val extractor = MediaExtractor()
        runCatching { extractor.setDataSource(context, uri, null) }.onFailure {
            extractor.release()
            return null
        }
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            return null
        }
        extractor.selectTrack(trackIndex)

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        // Копим сразу в целевой частоте, чтобы не держать в памяти весь трек.
        val target = BeatDetector.ANALYSIS_RATE
        val out = FloatArrayBuilder(target * min(maxSeconds, 600))
        var srcPos = 0.0
        var step = sampleRate.toDouble() / target

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, max(0L, extractor.sampleTime), 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = max(1, f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    step = sampleRate.toDouble() / target
                    continue
                }
                if (outIdx < 0) continue

                val buf = codec.getOutputBuffer(outIdx)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val shorts = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = shorts.remaining() / channels
                    if (step <= 0.0) step = sampleRate.toDouble() / target
                    var i = 0
                    while (i < frames) {
                        if (srcPos <= i) {
                            var sum = 0f
                            for (c in 0 until channels) sum += shorts.get(i * channels + c) / 32768f
                            out.add(sum / channels)
                            srcPos += step
                        }
                        i++
                    }
                    srcPos -= frames
                    if (out.size >= target * maxSeconds) outputDone = true
                }
                codec.releaseOutputBuffer(outIdx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
            }
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }
        return if (out.size > 1000) out.toArray() else null
    }

    fun durationUs(context: Context, uri: Uri): Long {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { mmr.release() }
        }
    }
}

/** Растущий буфер float без боксинга. */
class FloatArrayBuilder(initial: Int = 1024) {
    private var data = FloatArray(max(16, initial))
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

/**
 * Оценивает исходное видео: выборка кадров, движение, детализация, яркость.
 * По этим замерам строятся сегменты, из которых планировщик собирает эдит.
 */
object VideoAnalyzer {

    private const val THUMB = 48

    fun analyze(
        context: Context,
        uri: Uri,
        clipIndex: Int,
        sampleStepUs: Long = 500_000L,
        maxSamples: Int = 240,
        onProgress: ((Float) -> Unit)? = null,
    ): ClipInfo {
        val mmr = MediaMetadataRetriever()
        var durationUs = 0L
        var width = 0
        var height = 0
        var rotation = 0
        try {
            mmr.setDataSource(context, uri)
            durationUs = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L) * 1000L
            width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            // Файл может не читаться метаданными — тогда работаем с тем, что есть.
        }
        if (durationUs <= 0) durationUs = 10_000_000L

        var step = sampleStepUs
        if (durationUs / step > maxSamples) step = durationUs / maxSamples

        val times = ArrayList<Long>()
        val motion = ArrayList<Float>()
        val detail = ArrayList<Float>()
        val bright = ArrayList<Float>()

        var prev: IntArray? = null
        var t = 0L
        var index = 0
        val total = max(1, (durationUs / step).toInt())
        while (t < durationUs) {
            val frame: Bitmap? = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                    mmr.getScaledFrameAtTime(
                        t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB, THUMB,
                    )
                } else {
                    mmr.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { big ->
                        val small = Bitmap.createScaledBitmap(big, THUMB, THUMB, true)
                        if (small !== big) big.recycle()
                        small
                    }
                }
            } catch (e: Exception) {
                null
            }
            if (frame != null) {
                val pixels = IntArray(frame.width * frame.height)
                frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
                val stats = frameStats(pixels, frame.width, frame.height, prev)
                times.add(t)
                motion.add(stats[0])
                detail.add(stats[1])
                bright.add(stats[2])
                prev = pixels
                frame.recycle()
            }
            t += step
            index++
            onProgress?.invoke((index.toFloat() / total).coerceIn(0f, 1f))
        }
        runCatching { mmr.release() }

        val segments = SegmentBuilder.build(
            clipIndex = clipIndex,
            durationUs = durationUs,
            times = times.toLongArray(),
            motion = motion.toFloatArray(),
            detail = detail.toFloatArray(),
            brightness = bright.toFloatArray(),
        )
        return ClipInfo(clipIndex, durationUs, width, height, rotation, segments)
    }

    /** @return [движение, детализация, яркость] */
    private fun frameStats(pixels: IntArray, w: Int, h: Int, prev: IntArray?): FloatArray {
        var lumaSum = 0f
        val luma = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val l = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            luma[i] = l
            lumaSum += l
        }
        val brightness = lumaSum / pixels.size

        // Детализация — средний градиент (аналог резкости/наполненности кадра).
        var grad = 0f
        for (y in 1 until h) {
            for (x in 1 until w) {
                val i = y * w + x
                grad += abs(luma[i] - luma[i - 1]) + abs(luma[i] - luma[i - w])
            }
        }
        val detail = min(1f, grad / max(1, (w - 1) * (h - 1)) * 6f)

        var motion = 0f
        if (prev != null && prev.size == pixels.size) {
            var diff = 0f
            for (i in pixels.indices) {
                val p = prev[i]
                val pl = (0.299f * ((p shr 16) and 0xFF) +
                    0.587f * ((p shr 8) and 0xFF) +
                    0.114f * (p and 0xFF)) / 255f
                diff += abs(luma[i] - pl)
            }
            motion = min(1f, diff / pixels.size * 5f)
        }
        return floatArrayOf(motion, detail, brightness)
    }
}

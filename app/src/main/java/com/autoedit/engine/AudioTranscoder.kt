package com.autoedit.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** Итог декодирования музыки в сырой PCM. */
class RawAudio(
    val file: File,
    val sampleRate: Int,
    val channels: Int,
    val totalFrames: Long,
)

/**
 * Музыка: декодирование в PCM (с обрезкой и фейдами) и обратное кодирование в AAC.
 * Готовый m4a потом склеивается с видео.
 */
object AudioTranscoder {

    private const val TIMEOUT = 5_000L

    /**
     * Декодирует музыку в сырой PCM-файл, обрезая до [durationUs] и добавляя фейды.
     */
    fun decodeToRaw(
        context: Context,
        uri: Uri,
        startUs: Long,
        durationUs: Long,
        outFile: File,
        fadeInUs: Long = 40_000,
        fadeOutUs: Long = 700_000,
    ): RawAudio? {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
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
        if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var framesWritten = 0L

        val out = outFile.outputStream().buffered(1 shl 16)
        val endUs = startUs + durationUs

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        val time = extractor.sampleTime
                        if (size < 0 || (time >= 0 && time > endUs + 200_000)) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, max(0L, time), 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    continue
                }
                if (outIdx < 0) continue

                val buf = codec.getOutputBuffer(outIdx)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val shorts = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val count = shorts.remaining()
                    val chunk = ShortArray(count)
                    shorts.get(chunk)

                    val chunkStartUs = info.presentationTimeUs
                    // Многоканальный звук сводим к стерео — кодировщики телефонов ждут <= 2.
                    val mixed = if (channels > 2) downmixToStereo(chunk, channels) else chunk
                    val outChannels = min(2, max(1, channels))
                    val frameCount = mixed.size / outChannels
                    val keep = applyFades(
                        mixed, outChannels, sampleRate, chunkStartUs, startUs, endUs, fadeInUs, fadeOutUs,
                    )
                    if (keep > 0) {
                        val bytes = ByteBuffer.allocate(keep * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until keep) bytes.putShort(mixed[i])
                        out.write(bytes.array())
                        framesWritten += keep / outChannels
                    }
                    if (chunkStartUs >= endUs) {
                        outputDone = true
                    }
                    if (frameCount == 0 && (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
                codec.releaseOutputBuffer(outIdx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
            }
        } finally {
            out.flush()
            out.close()
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }
        if (framesWritten <= 0) return null
        return RawAudio(outFile, sampleRate, min(2, max(1, channels)), framesWritten)
    }

    /** Читает ровно [buffer].size байт (или сколько осталось до конца). */
    private fun fill(input: java.io.InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    /** Сводит многоканальный поток к стерео. */
    private fun downmixToStereo(chunk: ShortArray, channels: Int): ShortArray {
        val frames = chunk.size / channels
        val out = ShortArray(frames * 2)
        for (f in 0 until frames) {
            out[f * 2] = chunk[f * channels]
            out[f * 2 + 1] = chunk[f * channels + 1]
        }
        return out
    }

    /** Обрезает хвост чанка по [endUs] и накладывает фейды. Возвращает число сэмплов к записи. */
    private fun applyFades(
        chunk: ShortArray,
        channels: Int,
        sampleRate: Int,
        chunkStartUs: Long,
        startUs: Long,
        endUs: Long,
        fadeInUs: Long,
        fadeOutUs: Long,
    ): Int {
        val ch = max(1, channels)
        val frames = chunk.size / ch
        var keepFrames = frames
        for (f in 0 until frames) {
            val tUs = chunkStartUs + f.toLong() * 1_000_000L / sampleRate
            if (tUs >= endUs) {
                keepFrames = f
                break
            }
            var gain = 1f
            val sinceStart = tUs - startUs
            if (sinceStart < fadeInUs) gain *= (sinceStart.toFloat() / fadeInUs).coerceIn(0f, 1f)
            val untilEnd = endUs - tUs
            if (untilEnd < fadeOutUs) gain *= (untilEnd.toFloat() / fadeOutUs).coerceIn(0f, 1f)
            if (gain < 0.999f) {
                for (c in 0 until ch) {
                    val idx = f * ch + c
                    chunk[idx] = (chunk[idx] * gain).toInt().coerceIn(-32768, 32767).toShort()
                }
            }
        }
        return keepFrames * ch
    }

    /** Кодирует сырой PCM в AAC-дорожку внутри m4a. */
    fun encodeRawToM4a(raw: RawAudio, outPath: String, bitRate: Int = 192_000): Boolean {
        val channels = min(2, max(1, raw.channels))
        val format = MediaFormat.createAudioFormat(MIME_AAC, raw.sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MIME_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()
        val input = raw.file.inputStream().buffered(1 shl 16)
        val bytesPerFrame = channels * 2
        var framesRead = 0L
        var inputDone = false

        var idleGuard = 0
        try {
            while (idleGuard < 4000) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        buf.clear()
                        val capacityFrames = buf.capacity() / bytesPerFrame
                        val bytes = ByteArray(capacityFrames * bytesPerFrame)
                        val read = fill(input, bytes)
                        if (read <= 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, framesRead * 1_000_000L / raw.sampleRate,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val ptsUs = framesRead * 1_000_000L / raw.sampleRate
                            buf.put(bytes, 0, read)
                            codec.queueInputBuffer(inIdx, 0, read, ptsUs, 0)
                            framesRead += read / bytesPerFrame
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                    continue
                }
                if (outIdx < 0) {
                    idleGuard++
                    continue
                }
                idleGuard = 0
                val buf = codec.getOutputBuffer(outIdx)
                if (buf != null && info.size > 0 &&
                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxerStarted
                ) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    muxer.writeSampleData(trackIndex, buf, info)
                }
                codec.releaseOutputBuffer(outIdx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        } finally {
            runCatching { input.close() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
        }
        return muxerStarted
    }

    const val MIME_AAC = "audio/mp4a-latm"
}

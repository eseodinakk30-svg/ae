package com.autoedit.engine

import android.content.Context
import android.net.Uri
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import com.autoedit.core.ClipInfo
import com.autoedit.core.SegmentBuilder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Разбор исходного видео последовательным декодированием.
 *
 * Раньше кадры брались через MediaMetadataRetriever по ближайшему ключевому
 * кадру: несколько замеров подряд возвращали один и тот же кадр, движение
 * выходило нулевым, и «моменты» искались фактически вслепую. Здесь видео
 * проигрывается один раз, а замеры снимаются с нужной частотой.
 */
class VideoScanner(
    private val context: Context,
    private val uri: Uri,
    private val clipIndex: Int,
) {
    private companion object {
        const val SIZE = 96
        const val DEFAULT_FPS = 5f
        const val MAX_SAMPLES = 420
    }

    fun scan(
        sampleFps: Float = DEFAULT_FPS,
        onProgress: ((Float) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ): ClipInfo? {
        val callbackThread = HandlerThread("autoedit-scan").apply { start() }
        var eglCore: EglCore? = null
        var surface: EGLSurface? = null
        var texture: ExternalTexture? = null
        var decoder: SourceDecoder? = null
        var fbo: Fbo? = null
        var program: GlProgram? = null

        try {
            eglCore = EglCore()
            surface = eglCore.createOffscreenSurface(SIZE, SIZE)
            eglCore.makeCurrent(surface)
            fbo = Fbo(SIZE, SIZE)
            program = GlProgram(Shaders.VERTEX, Shaders.OES_COPY)
            val quad = FullQuad()

            texture = ExternalTexture(Handler(callbackThread.looper))
            decoder = SourceDecoder(context, uri, texture)
            decoder.start()

            val durationUs = if (decoder.durationUs > 0) decoder.durationUs else 10_000_000L
            var stepUs = (1_000_000f / sampleFps).toLong()
            if (durationUs / stepUs > MAX_SAMPLES) stepUs = durationUs / MAX_SAMPLES
            val count = max(1, (durationUs / stepUs).toInt())

            val times = ArrayList<Long>(count)
            val motion = ArrayList<Float>(count)
            val detail = ArrayList<Float>(count)
            val center = ArrayList<Float>(count)
            val bright = ArrayList<Float>(count)

            val pixels = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())
            val luma = FloatArray(SIZE * SIZE)
            var prevLuma: FloatArray? = null
            val weights = centerWeights()
            val stMatrix = FloatArray(16)

            var t = 0L
            var index = 0
            while (t < durationUs && index < MAX_SAMPLES) {
                if (isCancelled()) return null
                decoder.advanceTo(t)
                if (decoder.lastPtsUs < 0) {
                    // Кадра ещё нет — двигаемся дальше, чтобы не встать намертво.
                    t += stepUs
                    index++
                    continue
                }

                texture.transformMatrix(stMatrix)
                fbo.bind()
                program.use()
                program.bindTexture(
                    "uTex", 0, texture.textureId, android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                )
                program.setMatrix("uStMatrix", stMatrix)
                quad.draw(program)

                pixels.rewind()
                GLES20.glReadPixels(
                    0, 0, SIZE, SIZE, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels,
                )
                pixels.rewind()
                var sum = 0f
                for (i in 0 until SIZE * SIZE) {
                    val r = (pixels.get(i * 4).toInt() and 0xFF)
                    val g = (pixels.get(i * 4 + 1).toInt() and 0xFF)
                    val b = (pixels.get(i * 4 + 2).toInt() and 0xFF)
                    val l = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                    luma[i] = l
                    sum += l
                }

                var grad = 0f
                var gradCenter = 0f
                var weightSum = 0f
                for (y in 0 until SIZE) {
                    for (x in 0 until SIZE) {
                        val i = y * SIZE + x
                        var g = 0f
                        if (x + 1 < SIZE) g += abs(luma[i] - luma[i + 1])
                        if (y + 1 < SIZE) g += abs(luma[i] - luma[i + SIZE])
                        grad += g
                        gradCenter += g * weights[i]
                        weightSum += weights[i]
                    }
                }
                val n = (SIZE * SIZE).toFloat()
                var diff = 0f
                val prev = prevLuma
                if (prev != null) {
                    for (i in 0 until SIZE * SIZE) diff += abs(luma[i] - prev[i])
                }

                times.add(t)
                bright.add(sum / n)
                detail.add(min(1f, grad / n * 3f))
                center.add(min(1f, gradCenter / max(1f, weightSum) * 3f))
                motion.add(if (prev == null) 0f else min(1f, diff / n * 5f))
                prevLuma = luma.copyOf()

                onProgress?.invoke((index + 1).toFloat() / count)
                t += stepUs
                index++
            }

            if (times.size < 2) return null
            val segments = SegmentBuilder.build(
                clipIndex = clipIndex,
                durationUs = durationUs,
                times = times.toLongArray(),
                motion = motion.toFloatArray(),
                detail = detail.toFloatArray(),
                brightness = bright.toFloatArray(),
                centerDetail = center.toFloatArray(),
            )
            return ClipInfo(
                index = clipIndex,
                durationUs = durationUs,
                width = decoder.width,
                height = decoder.height,
                rotationDegrees = decoder.rotationDegrees,
                segments = segments,
            )
        } catch (e: Throwable) {
            return null
        } finally {
            runCatching { decoder?.release() }
            runCatching { texture?.release() }
            runCatching { fbo?.release() }
            runCatching { program?.release() }
            runCatching { surface?.let { eglCore?.releaseSurface(it) } }
            runCatching { eglCore?.release() }
            callbackThread.quitSafely()
        }
    }

    /** Вес к центру кадра: там обычно и находится герой. */
    private fun centerWeights(): FloatArray {
        val w = FloatArray(SIZE * SIZE)
        val sigma = SIZE * 0.42f
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val dx = (x - SIZE / 2f) / sigma
                val dy = (y - SIZE / 2f) / sigma
                w[y * SIZE + x] = exp(-(dx * dx + dy * dy))
            }
        }
        return w
    }
}

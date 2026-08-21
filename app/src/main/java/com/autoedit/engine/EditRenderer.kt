package com.autoedit.engine

import android.content.Context
import android.net.Uri
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import com.autoedit.core.EditPlan
import com.autoedit.core.Shot
import com.autoedit.core.Transition
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class RenderCancelledException : Exception("Рендер отменён")

/**
 * Сборка готового ролика: кадр за кадром гоняем исходники через GL-цепочку
 * в кодировщик, отдельно перекодируем музыку и склеиваем.
 */
class EditRenderer(
    private val context: Context,
    private val plan: EditPlan,
    private val clipUris: List<Uri>,
    private val musicUri: Uri?,
    private val workDir: File,
) {
    private companion object {
        const val BURST_HOLD_US = 190_000L
        const val MAX_OPEN_DECODERS = 6
    }

    private class DecoderSlot(
        val decoder: SourceDecoder,
        val texture: ExternalTexture,
        var lastUsedFrame: Int,
    )

    fun render(
        outputFile: File,
        onProgress: (Float, String) -> Unit,
        isCancelled: () -> Boolean,
    ): File {
        workDir.mkdirs()
        val videoTmp = File(workDir, "video_tmp.mp4")
        val audioTmp = File(workDir, "audio_tmp.m4a")
        val rawTmp = File(workDir, "audio_tmp.pcm")
        listOf(videoTmp, audioTmp, rawTmp).forEach { runCatching { it.delete() } }

        var audioReady = false
        val music = musicUri
        if (music != null) {
            onProgress(0.02f, "Готовлю музыку")
            val raw = runCatching {
                AudioTranscoder.decodeToRaw(
                    context = context,
                    uri = music,
                    startUs = 0,
                    durationUs = plan.durationUs,
                    outFile = rawTmp,
                )
            }.getOrNull()
            if (raw != null) {
                if (isCancelled()) throw RenderCancelledException()
                onProgress(0.08f, "Кодирую звук")
                audioReady = runCatching {
                    AudioTranscoder.encodeRawToM4a(raw, audioTmp.absolutePath)
                }.getOrDefault(false)
            }
            runCatching { rawTmp.delete() }
        }

        renderVideo(videoTmp, onProgress, isCancelled)

        if (isCancelled()) throw RenderCancelledException()
        onProgress(0.95f, "Склеиваю дорожки")
        runCatching { outputFile.delete() }
        val combined = runCatching {
            Mp4Combiner.combine(
                videoPath = videoTmp.absolutePath,
                audioPath = if (audioReady) audioTmp.absolutePath else null,
                outPath = outputFile.absolutePath,
            )
        }.getOrDefault(false)
        if (!combined || !outputFile.exists() || outputFile.length() < 1024) {
            videoTmp.copyTo(outputFile, overwrite = true)
        }
        runCatching { videoTmp.delete() }
        runCatching { audioTmp.delete() }
        onProgress(1f, "Готово")
        return outputFile
    }

    // ------------------------------------------------------------- Видео

    private fun renderVideo(
        outFile: File,
        onProgress: (Float, String) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val callbackThread = HandlerThread("autoedit-frames").apply { start() }
        val handler = Handler(callbackThread.looper)
        var eglCore: EglCore? = null
        var windowSurface: EGLSurface? = null
        var encoder: VideoEncoder? = null
        var compositor: FrameCompositor? = null
        val slots = HashMap<Int, DecoderSlot>()

        try {
            val bitrate = VideoEncoder.bitrateFor(plan.width, plan.height, plan.fps)
            encoder = VideoEncoder(plan.width, plan.height, plan.fps, bitrate, outFile.absolutePath)
            eglCore = EglCore()
            windowSurface = eglCore.createWindowSurface(encoder.inputSurface)
            eglCore.makeCurrent(windowSurface)
            compositor = FrameCompositor(plan.width, plan.height)

            val frameDurUs = 1_000_000L / plan.fps
            val totalFrames = max(1, (plan.durationUs / frameDurUs).toInt())
            val shots = plan.shots
            var shotIdx = 0
            var shotStartedFrame = 0
            val params = FrameParams()
            val style = plan.style.params

            for (frame in 0 until totalFrames) {
                if (isCancelled()) throw RenderCancelledException()
                val outUs = frame * frameDurUs

                var shotChanged = false
                while (shotIdx < shots.size - 1 && outUs >= shots[shotIdx].outEndUs) {
                    shotIdx++
                    shotChanged = true
                }
                if (frame == 0) shotChanged = true
                if (shotChanged) shotStartedFrame = frame

                val shot = shots[shotIdx]
                val slot = obtainSlot(shot.clipIndex, slots, handler, frame)
                val srcUs = shot.srcStartUs + sourceOffset(shot, outUs - shot.outStartUs)

                if (slot != null) {
                    slot.lastUsedFrame = frame
                    if (shotChanged && slot.decoder.needsSeek(srcUs)) {
                        slot.decoder.seekTo(srcUs)
                    }
                    slot.decoder.advanceTo(srcUs)
                }

                fillParams(params, shot, shotIdx, outUs, frame, shotChanged, style)
                if (slot != null) {
                    compositor.render(
                        source = slot.texture,
                        srcWidth = slot.decoder.width,
                        srcHeight = slot.decoder.height,
                        srcRotation = slot.decoder.rotationDegrees,
                        p = params,
                    )
                } else {
                    // Клип не открылся — не тащим в кадр мусор из прошлого буфера.
                    android.opengl.GLES20.glClearColor(0f, 0f, 0f, 1f)
                    android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT)
                }

                eglCore.setPresentationTime(windowSurface, outUs * 1000L)
                eglCore.swapBuffers(windowSurface)
                encoder.drain(false)

                if (frame % 5 == 0) {
                    val done = frame.toFloat() / totalFrames
                    onProgress(
                        0.12f + 0.80f * done,
                        "Рендер ${(done * 100).toInt()}% · шот ${shotIdx + 1}/${shots.size}",
                    )
                }
            }
            encoder.drain(true)
        } finally {
            slots.values.forEach {
                runCatching { it.decoder.release() }
                runCatching { it.texture.release() }
            }
            runCatching { compositor?.release() }
            runCatching { windowSurface?.let { eglCore?.releaseSurface(it) } }
            runCatching { encoder?.release() }
            runCatching { eglCore?.release() }
            callbackThread.quitSafely()
        }
    }

    /** Открывает (или переиспользует) декодер под клип, вытесняя самый старый. */
    private fun obtainSlot(
        clipIndex: Int,
        slots: HashMap<Int, DecoderSlot>,
        handler: Handler,
        frame: Int,
    ): DecoderSlot? {
        slots[clipIndex]?.let { return it }
        val uri = clipUris.getOrNull(clipIndex) ?: return null

        if (slots.size >= MAX_OPEN_DECODERS) evictOldest(slots)
        return openSlot(uri, clipIndex, slots, handler, frame)
            ?: run {
                // Устройство не тянет столько кодеков — освобождаем и пробуем ещё раз.
                evictOldest(slots)
                openSlot(uri, clipIndex, slots, handler, frame)
            }
    }

    private fun openSlot(
        uri: Uri,
        clipIndex: Int,
        slots: HashMap<Int, DecoderSlot>,
        handler: Handler,
        frame: Int,
    ): DecoderSlot? = runCatching {
        val texture = ExternalTexture(handler)
        val decoder = SourceDecoder(context, uri, texture)
        decoder.start()
        DecoderSlot(decoder, texture, frame).also { slots[clipIndex] = it }
    }.getOrNull()

    private fun evictOldest(slots: HashMap<Int, DecoderSlot>) {
        val oldest = slots.entries.minByOrNull { it.value.lastUsedFrame } ?: return
        runCatching { oldest.value.decoder.release() }
        runCatching { oldest.value.texture.release() }
        slots.remove(oldest.key)
    }

    /** Смещение внутри исходника с учётом стоп-кадра, разгона и «вылета». */
    private fun sourceOffset(shot: Shot, localUs: Long): Long {
        if (shot.freeze) return 0L
        var t = localUs
        if (shot.fx.burst > 0f) {
            t = if (localUs < BURST_HOLD_US) 0L else localUs - BURST_HOLD_US
        }
        if (shot.rampIn <= 0f) return (t * shot.speed).toLong()

        val total = max(1L, shot.durationUs).toFloat()
        val s0 = shot.speed * (1f - 0.55f * shot.rampIn)
        val s1 = shot.speed * (1f + 0.90f * shot.rampIn)
        val x = t.toFloat()
        // Интеграл скорости v(x) = s0 + (s1 - s0) * (x / T)^2
        val offset = s0 * x + (s1 - s0) * x * x * x / (3f * total * total)
        return offset.toLong()
    }

    /** Покадровая модуляция эффектов: пульс по битам, вспышки, тряска, переход. */
    private fun fillParams(
        p: FrameParams,
        shot: Shot,
        shotIdx: Int,
        outUs: Long,
        frame: Int,
        shotChanged: Boolean,
        style: com.autoedit.core.StyleParams,
    ) {
        val fx = shot.fx
        val localUs = outUs - shot.outStartUs
        val localSec = localUs / 1_000_000f
        val progress = (localUs.toFloat() / max(1L, shot.durationUs)).coerceIn(0f, 1f)

        val beatIdx = beatIndexAt(outUs)
        val beatUs = if (beatIdx >= 0) plan.beatsUs[beatIdx] else 0L
        val sinceBeat = ((outUs - beatUs) / 1_000_000f).coerceAtLeast(0f)
        val pulse = exp(-sinceBeat / 0.085f).coerceIn(0f, 1f)

        var zoom = fx.baseZoom * (1f + fx.kenBurns * progress) * (1f + 0.16f * fx.punch * pulse)
        var trailAlpha = 1f - (0.45f + 0.42f * fx.echo).coerceIn(0f, 0.92f)
        if (fx.burst > 0f) {
            // «Вылет перса»: стоп-кадр вылетает на зрителя и резко отъезжает.
            val env = exp(-localSec / 0.16f)
            zoom *= 1f + 1.25f * fx.burst * env
            trailAlpha = 0.10f
        }
        p.zoom = zoom.coerceIn(0.6f, 6f)

        val shakeAmp = fx.shake * pulse * 0.028f
        p.shakeX = shakeAmp * noise(frame * 1.7f)
        p.shakeY = shakeAmp * noise(frame * 2.3f + 11.7f)
        p.tilt = (fx.rotate * (PI / 180.0).toFloat()) * exp(-localSec / 0.28f) +
            shakeAmp * 0.5f * noise(frame * 3.1f + 5f)
        p.mirror = fx.mirror
        p.shock = if (fx.shockwave > 0f && sinceBeat < 0.3f) {
            fx.shockwave * (1f - sinceBeat / 0.3f)
        } else 0f
        p.shockPhase = (sinceBeat / 0.3f).coerceIn(0f, 1f) * 0.8f

        p.rgbSplit = fx.rgbSplit * (0.28f + 0.72f * pulse)
        p.echo = fx.echo * (0.55f + 0.45f * pulse)
        p.trailAlpha = trailAlpha.coerceIn(0.06f, 1f)
        p.resetTrail = shotChanged && fx.echo < 0.25f
        p.glow = fx.glow
        p.saturation = style.saturation
        p.contrast = style.contrast
        p.shadowTint = style.shadowTint
        p.highTint = style.highlightTint
        p.gradeAmount = fx.grade
        p.vignette = fx.vignette
        p.grain = fx.grain
        p.flash = (fx.flashIn * exp(-localSec / 0.055f)).coerceIn(0f, 0.95f)
        p.invert = if (fx.strobe > 0f && beatIdx % 2 == 0) {
            (fx.strobe * pulse * pulse).coerceIn(0f, 1f)
        } else 0f
        p.time = frame * 0.013f

        val trDur = shot.transitionDurUs
        if (shotIdx > 0 && trDur > 0 && localUs < trDur && shot.transition != Transition.CUT) {
            p.transitionType = shot.transition.ordinal
            p.transitionProgress = (localUs.toFloat() / trDur).coerceIn(0f, 1f)
            p.transitionSeed = shotIdx.toFloat() * 0.37f
        } else {
            p.transitionType = 0
            p.transitionProgress = 1f
        }
    }

    private fun beatIndexAt(us: Long): Int {
        val beats = plan.beatsUs
        if (beats.isEmpty()) return -1
        var lo = 0
        var hi = beats.size - 1
        if (us < beats[0]) return 0
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (beats[mid] <= us) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Детерминированный «шум» для тряски. */
    private fun noise(x: Float): Float {
        val s = sin(x * 12.9898f) * 43758.5453f
        return ((s - Math.floor(s.toDouble()).toFloat()) * 2f - 1f)
    }
}

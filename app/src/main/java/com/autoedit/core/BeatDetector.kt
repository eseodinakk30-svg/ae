package com.autoedit.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Поиск битов, темпа и дропа в треке — без сторонних библиотек.
 *
 * Конвейер: STFT -> спектральный поток (онсеты) -> автокорреляция для BPM ->
 * динамическое программирование для сетки битов (метод Эллиса) -> разметка интенсивности.
 */
object BeatDetector {

    const val ANALYSIS_RATE = 22050
    private const val FFT_SIZE = 1024
    private const val HOP = 256

    /**
     * @param mono моно-сэмплы в диапазоне -1..1
     * @param sampleRate частота дискретизации входа
     */
    fun analyze(mono: FloatArray, sampleRate: Int): AudioAnalysis {
        val durationSec = if (sampleRate > 0) mono.size.toFloat() / sampleRate else 0f
        if (mono.size < FFT_SIZE * 4 || sampleRate <= 0) {
            return AudioAnalysis.fallback(max(durationSec, 1f))
        }
        val x = if (sampleRate == ANALYSIS_RATE) mono else resample(mono, sampleRate, ANALYSIS_RATE)
        val frameRate = ANALYSIS_RATE.toFloat() / HOP

        val spec = stft(x)
        val frames = spec.size
        if (frames < 8) return AudioAnalysis.fallback(max(durationSec, 1f))

        val flux = spectralFlux(spec)
        val onset = normalizeEnvelope(flux, frameRate)
        val energy = bandEnergy(spec, 0, spec[0].size)
        val bass = bandEnergy(spec, 0, binForHz(180f))
        smoothInPlace(energy, (frameRate * 0.20f).toInt())
        smoothInPlace(bass, (frameRate * 0.12f).toInt())
        normalizeMaxInPlace(energy)
        normalizeMaxInPlace(bass)

        val bpm = estimateTempo(onset, frameRate)
        val periodFrames = 60f / bpm * frameRate
        val beatFrames = trackBeats(onset, periodFrames)
        if (beatFrames.size < 4) return AudioAnalysis.fallback(max(durationSec, 1f), bpm)

        val beats = FloatArray(beatFrames.size) { beatFrames[it] / frameRate }
        val strength = FloatArray(beats.size) { i ->
            val f = beatFrames[i]
            var s = 0f
            for (k in max(0, f - 1)..min(onset.size - 1, f + 2)) s = max(s, onset[k])
            s
        }
        normalizeMaxInPlace(strength)

        val beatEnergy = FloatArray(beats.size) { i ->
            val f = beatFrames[i].coerceIn(0, energy.size - 1)
            energy[f]
        }
        val beatBass = FloatArray(beats.size) { i ->
            val f = beatFrames[i].coerceIn(0, bass.size - 1)
            bass[f]
        }

        val level = classifyLevels(beatEnergy, strength)
        val drop = findDrop(beatEnergy, level)
        val barPhase = findBarPhase(beatBass, strength)

        return AudioAnalysis(
            durationSec = durationSec,
            bpm = bpm,
            beats = beats,
            beatStrength = strength,
            beatLevel = level,
            dropBeat = drop,
            barPhase = barPhase,
            onsetEnv = onset,
            energy = energy,
            bass = bass,
            frameRate = frameRate,
        )
    }

    private fun binForHz(hz: Float): Int =
        max(1, (hz / (ANALYSIS_RATE.toFloat() / FFT_SIZE)).roundToInt())

    // ---------------------------------------------------------------- STFT

    private fun stft(x: FloatArray): Array<FloatArray> {
        val fft = Fft(FFT_SIZE)
        val window = FloatArray(FFT_SIZE) {
            (0.5 - 0.5 * Math.cos(2.0 * Math.PI * it / (FFT_SIZE - 1))).toFloat()
        }
        val frames = max(0, (x.size - FFT_SIZE) / HOP + 1)
        val bins = FFT_SIZE / 2 + 1
        val out = Array(frames) { FloatArray(bins) }
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        for (t in 0 until frames) {
            val off = t * HOP
            for (i in 0 until FFT_SIZE) {
                re[i] = x[off + i] * window[i]
                im[i] = 0f
            }
            fft.transform(re, im)
            val row = out[t]
            for (k in 0 until bins) {
                row[k] = sqrt(re[k] * re[k] + im[k] * im[k])
            }
        }
        return out
    }

    /** Спектральный поток с логарифмическим сжатием — устойчивее к громкости. */
    private fun spectralFlux(spec: Array<FloatArray>): FloatArray {
        val frames = spec.size
        val bins = spec[0].size
        val comp = Array(frames) { t ->
            FloatArray(bins) { k -> ln(1f + 40f * spec[t][k]) }
        }
        val flux = FloatArray(frames)
        for (t in 1 until frames) {
            var sum = 0f
            val cur = comp[t]
            val prev = comp[t - 1]
            for (k in 0 until bins) {
                val d = cur[k] - prev[k]
                if (d > 0f) sum += d
            }
            flux[t] = sum
        }
        flux[0] = flux.getOrElse(1) { 0f }
        return flux
    }

    /** Вычитание скользящего среднего + нормировка. */
    private fun normalizeEnvelope(flux: FloatArray, frameRate: Float): FloatArray {
        val n = flux.size
        val win = max(3, (frameRate * 0.35f).toInt())
        val out = FloatArray(n)
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + flux[i]
        for (i in 0 until n) {
            val lo = max(0, i - win)
            val hi = min(n, i + win + 1)
            val mean = ((prefix[hi] - prefix[lo]) / (hi - lo)).toFloat()
            out[i] = max(0f, flux[i] - mean)
        }
        var mean = 0f
        for (v in out) mean += v
        mean /= max(1, n)
        var varSum = 0f
        for (v in out) varSum += (v - mean) * (v - mean)
        val sd = sqrt(varSum / max(1, n))
        if (sd > 1e-6f) for (i in out.indices) out[i] = out[i] / sd
        return out
    }

    private fun bandEnergy(spec: Array<FloatArray>, from: Int, to: Int): FloatArray {
        val hi = min(to, spec[0].size)
        return FloatArray(spec.size) { t ->
            var s = 0f
            val row = spec[t]
            for (k in from until hi) s += row[k] * row[k]
            sqrt(s / max(1, hi - from))
        }
    }

    private fun smoothInPlace(v: FloatArray, radius: Int) {
        if (radius < 1 || v.isEmpty()) return
        val n = v.size
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + v[i]
        for (i in 0 until n) {
            val lo = max(0, i - radius)
            val hi = min(n, i + radius + 1)
            v[i] = ((prefix[hi] - prefix[lo]) / (hi - lo)).toFloat()
        }
    }

    private fun normalizeMaxInPlace(v: FloatArray) {
        var m = 0f
        for (x in v) m = max(m, x)
        if (m > 1e-9f) for (i in v.indices) v[i] = v[i] / m
    }

    // --------------------------------------------------------------- Темп

    private fun estimateTempo(onset: FloatArray, frameRate: Float): Float {
        val minLag = max(2, (frameRate * 60f / 200f).toInt())   // 200 BPM
        val maxLag = min(onset.size - 1, (frameRate * 60f / 55f).toInt()) // 55 BPM
        if (maxLag <= minLag) return 120f
        var bestLag = minLag
        var bestScore = Float.NEGATIVE_INFINITY
        val scores = FloatArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var s = 0f
            var i = lag
            while (i < onset.size) {
                s += onset[i] * onset[i - lag]
                i++
            }
            s /= (onset.size - lag)
            val bpm = 60f * frameRate / lag
            // Приоритет привычному диапазону темпа (лог-гаусс вокруг 125 BPM).
            val prior = exp(-0.5 * ((ln(bpm / 125f) / ln(2f)) / 0.85).pow(2)).toFloat()
            val score = s * prior
            scores[lag] = score
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        var bpm = 60f * frameRate / bestLag
        // Проверка на ошибку в октаву: сравниваем с половинным/двойным темпом.
        val halfLag = bestLag * 2
        val doubleLag = bestLag / 2
        if (halfLag <= maxLag && scores[halfLag] > bestScore * 0.92f && bpm / 2f >= 60f) {
            bpm /= 2f
        } else if (doubleLag >= minLag && scores[doubleLag] > bestScore * 1.05f && bpm * 2f <= 200f) {
            bpm *= 2f
        }
        while (bpm < 70f) bpm *= 2f
        while (bpm > 190f) bpm /= 2f
        return bpm
    }

    /**
     * Динамическое программирование по методу Эллиса: находит сетку битов,
     * максимизируя сумму онсетов при штрафе за отклонение от периода.
     */
    private fun trackBeats(onset: FloatArray, periodFrames: Float): IntArray {
        val n = onset.size
        if (n < 4 || periodFrames < 2f) return IntArray(0)
        val tightness = 100f
        val cumscore = FloatArray(n)
        val backlink = IntArray(n) { -1 }
        val searchLo = max(1, (periodFrames / 2f).roundToInt())
        val searchHi = max(searchLo + 1, (periodFrames * 2f).roundToInt())

        for (i in 0 until n) {
            var best = Float.NEGATIVE_INFINITY
            var bestJ = -1
            val lo = i - searchHi
            val hi = i - searchLo
            var j = max(0, lo)
            while (j <= hi) {
                val d = (i - j).toFloat()
                val penalty = -tightness * (ln(d / periodFrames)).pow(2)
                val score = cumscore[j] + penalty
                if (score > best) {
                    best = score
                    bestJ = j
                }
                j++
            }
            if (bestJ < 0) {
                cumscore[i] = onset[i]
                backlink[i] = -1
            } else {
                cumscore[i] = onset[i] + best
                backlink[i] = bestJ
            }
        }

        // Старт обратного хода — лучший счёт в конце трека.
        var tail = -1
        var bestTail = Float.NEGATIVE_INFINITY
        val from = max(0, n - searchHi)
        for (i in from until n) {
            if (cumscore[i] > bestTail) {
                bestTail = cumscore[i]
                tail = i
            }
        }
        if (tail < 0) return IntArray(0)
        val rev = ArrayList<Int>()
        var cur = tail
        while (cur >= 0) {
            rev.add(cur)
            cur = backlink[cur]
        }
        rev.reverse()
        return rev.toIntArray()
    }

    // -------------------------------------------------------- Интенсивность

    private fun classifyLevels(beatEnergy: FloatArray, strength: FloatArray): IntArray {
        val n = beatEnergy.size
        val sorted = beatEnergy.clone()
        sorted.sort()
        fun pct(p: Float): Float = sorted[((n - 1) * p).toInt().coerceIn(0, n - 1)]
        val q35 = pct(0.35f)
        val q60 = pct(0.60f)
        val q82 = pct(0.82f)
        return IntArray(n) { i ->
            val e = beatEnergy[i]
            val boost = if (strength[i] > 0.55f) 1 else 0
            val base = when {
                e >= q82 -> 3
                e >= q60 -> 2
                e >= q35 -> 1
                else -> 0
            }
            min(3, if (base >= 2) base else base + boost)
        }
    }

    /** Дроп — момент максимального скачка энергии после спокойного участка. */
    private fun findDrop(beatEnergy: FloatArray, level: IntArray): Int {
        val n = beatEnergy.size
        if (n < 16) return -1
        val win = 8
        var best = -1
        var bestJump = 0.14f
        for (i in win until n - win) {
            var before = 0f
            for (j in i - win until i) before += beatEnergy[j]
            before /= win
            var after = 0f
            for (j in i until i + win) after += beatEnergy[j]
            after /= win
            val jump = after - before
            if (jump > bestJump && level[i] >= 2) {
                bestJump = jump
                best = i
            }
        }
        return best
    }

    /** Фаза сильной доли в 4/4 — там, где чаще всего бас. */
    private fun findBarPhase(beatBass: FloatArray, strength: FloatArray): Int {
        var bestPhase = 0
        var bestScore = -1f
        for (phase in 0 until 4) {
            var s = 0f
            var i = phase
            while (i < beatBass.size) {
                s += beatBass[i] + 0.5f * strength[i]
                i += 4
            }
            if (s > bestScore) {
                bestScore = s
                bestPhase = phase
            }
        }
        return bestPhase
    }

    // ------------------------------------------------------------ Утилиты

    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val ratio = toRate.toDouble() / fromRate
        val outLen = (input.size * ratio).toInt()
        val out = FloatArray(max(1, outLen))
        for (i in out.indices) {
            val srcPos = i / ratio
            val i0 = srcPos.toInt()
            val i1 = min(i0 + 1, input.size - 1)
            val frac = (srcPos - i0).toFloat()
            out[i] = input[i0] * (1f - frac) + input[i1] * frac
        }
        return out
    }

    /** Простая проверка: есть ли вообще звук. */
    fun isSilent(mono: FloatArray): Boolean {
        var peak = 0f
        for (v in mono) peak = max(peak, abs(v))
        return peak < 0.002f
    }
}

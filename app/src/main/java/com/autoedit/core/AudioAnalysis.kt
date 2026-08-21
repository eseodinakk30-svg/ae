package com.autoedit.core

/** Результат анализа музыки: сетка битов, энергия, дроп. */
class AudioAnalysis(
    val durationSec: Float,
    val bpm: Float,
    /** Времена битов в секундах. */
    val beats: FloatArray,
    /** Сила каждого бита 0..1. */
    val beatStrength: FloatArray,
    /** Уровень интенсивности каждого бита: 0 тихо ... 3 дроп. */
    val beatLevel: IntArray,
    /** Индекс бита, с которого начинается главный дроп, или -1. */
    val dropBeat: Int,
    /** Смещение сильной доли такта (0..3) в 4/4. */
    val barPhase: Int,
    /** Огибающая онсетов, кадров в секунду = [frameRate]. */
    val onsetEnv: FloatArray,
    val energy: FloatArray,
    val bass: FloatArray,
    val frameRate: Float,
) {
    val beatCount: Int get() = beats.size

    fun levelAt(timeSec: Float): Int {
        if (beats.isEmpty()) return 1
        val i = beatIndexAt(timeSec)
        return beatLevel[i.coerceIn(0, beatLevel.size - 1)]
    }

    fun beatIndexAt(timeSec: Float): Int {
        if (beats.isEmpty()) return 0
        var lo = 0
        var hi = beats.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (beats[mid] <= timeSec) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Средний интервал между битами в секундах. */
    val beatPeriod: Float
        get() = if (bpm > 1f) 60f / bpm else 0.5f

    /**
     * Бит, с которого трек уже «едет»: дроп, если он есть, иначе первый участок,
     * где громкость держится высокой. Выравнивается на начало такта.
     */
    fun strongStartBeat(): Int {
        if (beats.size < 12 || beatLevel.isEmpty()) return 0
        var start = -1
        if (dropBeat in 0 until beats.size - 8) {
            start = dropBeat
        } else {
            val win = 8
            var i = 0
            while (i < beatLevel.size - win) {
                var sum = 0
                for (j in i until i + win) sum += beatLevel[j]
                if (sum.toFloat() / win >= 2.1f) {
                    start = i
                    break
                }
                i++
            }
        }
        if (start <= 0) return 0
        // На начало такта, чтобы эдит стартовал с сильной доли.
        val aligned = start - ((start - barPhase) % 4 + 4) % 4
        return aligned.coerceIn(0, beats.size - 4)
    }

    /**
     * Кусок анализа с [startSec] длиной [durationSec] — времена сдвигаются к нулю.
     */
    fun slice(startSec: Float, durationSec: Float): AudioAnalysis {
        if (startSec <= 0.01f) return this
        val endSec = startSec + durationSec
        val keep = beats.indices.filter { beats[it] >= startSec - 0.001f && beats[it] <= endSec }
        if (keep.size < 4) return this
        val first = keep.first()
        return AudioAnalysis(
            durationSec = durationSec,
            bpm = bpm,
            beats = FloatArray(keep.size) { beats[keep[it]] - startSec },
            beatStrength = FloatArray(keep.size) { beatStrength[keep[it]] },
            beatLevel = IntArray(keep.size) { beatLevel[keep[it]] },
            dropBeat = if (dropBeat >= first && dropBeat <= keep.last()) dropBeat - first else -1,
            barPhase = ((barPhase - first) % 4 + 4) % 4,
            onsetEnv = onsetEnv,
            energy = energy,
            bass = bass,
            frameRate = frameRate,
        )
    }

    companion object {
        /** Запасной вариант, если музыка не распозналась (ровная сетка). */
        fun fallback(durationSec: Float, bpm: Float = 120f): AudioAnalysis {
            val period = 60f / bpm
            val n = Math.max(1, (durationSec / period).toInt())
            val beats = FloatArray(n) { it * period }
            return AudioAnalysis(
                durationSec = durationSec,
                bpm = bpm,
                beats = beats,
                beatStrength = FloatArray(n) { 0.6f },
                beatLevel = IntArray(n) { 1 },
                dropBeat = -1,
                barPhase = 0,
                onsetEnv = FloatArray(0),
                energy = FloatArray(0),
                bass = FloatArray(0),
                frameRate = 1f,
            )
        }
    }
}

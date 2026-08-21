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

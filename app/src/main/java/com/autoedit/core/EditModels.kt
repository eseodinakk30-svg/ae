package com.autoedit.core

/** Переход, которым шот входит в кадр. */
enum class Transition {
    CUT,
    FLASH,
    WHIP_LEFT,
    WHIP_RIGHT,
    WHIP_UP,
    WHIP_DOWN,
    ZOOM_BLUR,
    GLITCH,
    DISSOLVE,
    SPIN,
    SHUTTER,
}

/**
 * Набор эффектов шота. Значения 0..1, если не указано иное —
 * рендер модулирует их по времени и по битам.
 */
data class ShotFx(
    /** Базовый зум кадра (1.0 = вписан по ширине/высоте с кропом). */
    val baseZoom: Float = 1f,
    /** Медленный наезд/отъезд за время шота: >0 наезд, <0 отъезд. */
    val kenBurns: Float = 0f,
    /** Резкий "удар" зумом по биту. */
    val punch: Float = 0f,
    /** Тряска камеры. */
    val shake: Float = 0f,
    /** Хроматическая аберрация / RGB-сплит. */
    val rgbSplit: Float = 0f,
    /** Свечение ярких участков (bloom). */
    val glow: Float = 0f,
    /** Сила цветокоррекции пресета. */
    val grade: Float = 0f,
    /** Шлейфы предыдущих кадров. */
    val echo: Float = 0f,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    /** Вспышка в начале шота. */
    val flashIn: Float = 0f,
    /** Строб/инверсия по битам. */
    val strobe: Float = 0f,
    /** "Вылет перса": стоп-кадр + резкий отъезд из увеличения со шлейфами. */
    val burst: Float = 0f,
    /** Наклон кадра в градусах в начале шота (уходит в 0). */
    val rotate: Float = 0f,
    /** Отражение по горизонтали. */
    val mirror: Boolean = false,
    /** Ударная волна по биту. */
    val shockwave: Float = 0f,
)

/** Один кусок таймлайна. */
class Shot(
    val clipIndex: Int,
    /** Стартовая позиция в исходном видео. */
    val srcStartUs: Long,
    val outStartUs: Long,
    val outEndUs: Long,
    /** Скорость воспроизведения источника. */
    val speed: Float,
    /** Сила speed-ramp: 0 — ровно, 1 — из слоу-мо в резкий разгон. */
    val rampIn: Float,
    /** Стоп-кадр (источник не двигается). */
    val freeze: Boolean,
    val transition: Transition,
    val transitionDurUs: Long,
    val fx: ShotFx,
) {
    val durationUs: Long get() = outEndUs - outStartUs
}

/** Готовый план монтажа. */
class EditPlan(
    val shots: List<Shot>,
    /** Времена битов в микросекундах (для пульсаций в рендере). */
    val beatsUs: LongArray,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bpm: Float,
    val style: Style,
) {
    val shotCount: Int get() = shots.size

    fun summary(): String =
        "%d шотов · %.0f BPM · %.1f с · %dx%d".format(shots.size, bpm, durationUs / 1_000_000f, width, height)
}

/** Кусок исходного видео с оценкой «интересности». */
class ClipSegment(
    val clipIndex: Int,
    val startUs: Long,
    val endUs: Long,
    /** Пик активности внутри сегмента. */
    val peakUs: Long,
    val motion: Float,
    val detail: Float,
    val brightness: Float,
    val score: Float,
) {
    val durationUs: Long get() = endUs - startUs
}

/** Метаданные исходного видео. */
class ClipInfo(
    val index: Int,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val segments: List<ClipSegment>,
)

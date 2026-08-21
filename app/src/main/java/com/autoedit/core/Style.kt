package com.autoedit.core

/** Пресеты визуального стиля эдита. */
enum class Style(val title: String, val subtitle: String) {
    ANIME("Аниме-эдит", "Пурпурный грейд, свечение, шлейфы, стробы на дропе"),
    PHONK("Фонк", "Жёсткий контраст, тряска, глитчи, инверсия"),
    VELOCITY("Velocity", "Speed-ramp, вип-переходы, плавные разгоны"),
    CLEAN("Чистый", "Аккуратные резы по битам, лёгкий грейд"),
    ;

    val params: StyleParams get() = StyleParams.of(this)
}

/**
 * Числовые параметры пресета. Цветовые тонировки уходят прямо в шейдер:
 * [shadowTint] подмешивается в тени, [highlightTint] — в света.
 */
class StyleParams(
    val shadowTint: FloatArray,
    val highlightTint: FloatArray,
    val saturation: Float,
    val contrast: Float,
    val glow: Float,
    val grain: Float,
    val vignette: Float,
    val rgbSplit: Float,
    val shake: Float,
    val punch: Float,
    val echo: Float,
    val strobe: Float,
    /** Насколько строб уходит в инверсию цвета, а не в белую вспышку. */
    val strobeInvert: Float,
    val burstChance: Float,
    val rampChance: Float,
    val mirrorChance: Float,
    val baseZoom: Float,
    val calmTransitions: List<Transition>,
    val midTransitions: List<Transition>,
    val hotTransitions: List<Transition>,
) {
    companion object {
        fun of(style: Style): StyleParams = when (style) {
            Style.ANIME -> StyleParams(
                shadowTint = floatArrayOf(0.07f, -0.03f, 0.16f),
                highlightTint = floatArrayOf(0.11f, 0.00f, 0.07f),
                saturation = 1.28f,
                contrast = 1.20f,
                glow = 0.42f,
                grain = 0.10f,
                vignette = 0.34f,
                rgbSplit = 0.55f,
                shake = 0.45f,
                punch = 0.75f,
                echo = 0.45f,
                strobe = 0.55f,
                strobeInvert = 0.18f,
                burstChance = 0.22f,
                rampChance = 0.18f,
                mirrorChance = 0.10f,
                baseZoom = 1.06f,
                calmTransitions = listOf(Transition.DISSOLVE, Transition.FLASH, Transition.CUT),
                midTransitions = listOf(Transition.FLASH, Transition.ZOOM_BLUR, Transition.WHIP_LEFT, Transition.WHIP_RIGHT, Transition.CUT),
                hotTransitions = listOf(Transition.GLITCH, Transition.FLASH, Transition.ZOOM_BLUR, Transition.SHUTTER, Transition.WHIP_UP, Transition.CUT),
            )

            Style.PHONK -> StyleParams(
                shadowTint = floatArrayOf(0.05f, -0.04f, 0.03f),
                highlightTint = floatArrayOf(0.18f, -0.02f, -0.04f),
                saturation = 0.80f,
                contrast = 1.38f,
                glow = 0.36f,
                grain = 0.24f,
                vignette = 0.46f,
                rgbSplit = 0.42f,
                shake = 0.80f,
                punch = 0.85f,
                echo = 0.30f,
                strobe = 0.85f,
                strobeInvert = 0.85f,
                burstChance = 0.16f,
                rampChance = 0.12f,
                mirrorChance = 0.14f,
                baseZoom = 1.10f,
                calmTransitions = listOf(Transition.CUT, Transition.FLASH, Transition.GLITCH),
                midTransitions = listOf(Transition.GLITCH, Transition.FLASH, Transition.SHUTTER, Transition.CUT, Transition.ZOOM_BLUR),
                hotTransitions = listOf(Transition.GLITCH, Transition.SHUTTER, Transition.FLASH, Transition.SPIN, Transition.CUT),
            )

            Style.VELOCITY -> StyleParams(
                shadowTint = floatArrayOf(-0.02f, 0.02f, 0.12f),
                highlightTint = floatArrayOf(0.10f, 0.06f, 0.02f),
                saturation = 1.18f,
                contrast = 1.14f,
                glow = 0.34f,
                grain = 0.08f,
                vignette = 0.26f,
                rgbSplit = 0.30f,
                shake = 0.30f,
                punch = 0.55f,
                echo = 0.55f,
                strobe = 0.22f,
                strobeInvert = 0.05f,
                burstChance = 0.14f,
                rampChance = 0.62f,
                mirrorChance = 0.06f,
                baseZoom = 1.04f,
                calmTransitions = listOf(Transition.DISSOLVE, Transition.WHIP_LEFT, Transition.CUT),
                midTransitions = listOf(Transition.WHIP_LEFT, Transition.WHIP_RIGHT, Transition.ZOOM_BLUR, Transition.WHIP_UP, Transition.CUT),
                hotTransitions = listOf(Transition.WHIP_LEFT, Transition.WHIP_RIGHT, Transition.ZOOM_BLUR, Transition.SPIN, Transition.FLASH),
            )

            Style.CLEAN -> StyleParams(
                shadowTint = floatArrayOf(0f, 0.01f, 0.05f),
                highlightTint = floatArrayOf(0.05f, 0.03f, 0f),
                saturation = 1.10f,
                contrast = 1.08f,
                glow = 0.18f,
                grain = 0.04f,
                vignette = 0.18f,
                rgbSplit = 0.10f,
                shake = 0.12f,
                punch = 0.40f,
                echo = 0.10f,
                strobe = 0.05f,
                strobeInvert = 0f,
                burstChance = 0.05f,
                rampChance = 0.10f,
                mirrorChance = 0f,
                baseZoom = 1.02f,
                calmTransitions = listOf(Transition.DISSOLVE, Transition.CUT),
                midTransitions = listOf(Transition.CUT, Transition.DISSOLVE, Transition.FLASH),
                hotTransitions = listOf(Transition.CUT, Transition.FLASH, Transition.ZOOM_BLUR),
            )
        }
    }
}

/** Формат кадра на выходе. */
enum class AspectPreset(val title: String, val w: Int, val h: Int) {
    AUTO("Как исходник", 0, 0),
    VERTICAL("9:16", 1080, 1920),
    SQUARE("1:1", 1080, 1080),
    WIDE("16:9", 1920, 1080),
    ;

    /**
     * Размер кадра под выбранный формат.
     * @param maxSide бюджет по длинной стороне (720 / 1280 / 1920)
     * @param sourceAspect ширина/высота исходника — используется для [AUTO]
     */
    fun resolve(maxSide: Int, sourceAspect: Float): Pair<Int, Int> {
        val aspect = if (this == AUTO) {
            sourceAspect.coerceIn(0.4f, 2.6f)
        } else {
            w.toFloat() / h
        }
        val width: Float
        val height: Float
        if (aspect >= 1f) {
            width = maxSide.toFloat()
            height = maxSide / aspect
        } else {
            height = maxSide.toFloat()
            width = maxSide * aspect
        }
        return even(width) to even(height)
    }

    private fun even(v: Float): Int {
        val i = Math.round(v).coerceAtLeast(2)
        return if (i % 2 == 0) i else i + 1
    }
}

/** Как исходник ложится в кадр. */
enum class FitMode(val title: String) {
    SMART("Авто"),
    COVER("Заполнить"),
    FIT("Вписать"),
    ;

    /**
     * @return true, если кадр нужно вписать целиком (с размытым фоном по краям).
     */
    fun contain(sourceAspect: Float, outputAspect: Float): Boolean = when (this) {
        COVER -> false
        FIT -> true
        // Кропаем, пока теряется немного; сильную разницу форматов вписываем.
        SMART -> Math.max(sourceAspect / outputAspect, outputAspect / sourceAspect) > 1.22f
    }
}

package com.autoedit.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Мозг монтажа: раскладывает шоты по битам, подбирает под каждый бит
 * кусок исходника и навешивает эффекты по пресету.
 */
class EditPlanner(
    private val audio: AudioAnalysis,
    private val clips: List<ClipInfo>,
    private val style: Style,
    private val intensity: Float,
    private val seed: Long,
) {
    private val p = style.params
    private val rnd = Random(seed)
    private val allSegments: List<ClipSegment> = clips.flatMap { it.segments }
    private val usage = IntArray(max(1, allSegments.size))

    /** Множитель силы эффектов от ползунка интенсивности. */
    private val punchScale = 0.55f + 0.90f * intensity.coerceIn(0f, 1f)

    /** Точка реза: начало будущего шота. */
    private class Mark(
        val startUs: Long,
        val level: Int,
        val strobe: Boolean,
        val beatIndex: Int,
    )

    fun build(targetDurationUs: Long, width: Int, height: Int, fps: Int): EditPlan {
        val durationUs = max(1_000_000L, targetDurationUs)
        val grid = buildBeatGrid(durationUs)
        val marks = layoutCuts(grid, durationUs)
        val shots = buildShots(marks, durationUs)

        return EditPlan(
            shots = shots,
            beatsUs = grid,
            durationUs = durationUs,
            width = width,
            height = height,
            fps = fps,
            bpm = audio.bpm,
            style = style,
        )
    }

    /**
     * Первый проход: расставляем точки реза по сетке битов.
     * Индекс бита всегда двигается вперёд минимум на один — цикл конечен,
     * а сами резы строго возрастают, поэтому таймлайн получается без дыр.
     */
    private fun layoutCuts(grid: LongArray, durationUs: Long): List<Mark> {
        val marks = ArrayList<Mark>()
        val minShotUs = 80_000L
        val dropBeat = audio.dropBeat
        var bi = 0
        var strobeCooldown = 0

        while (bi < grid.size - 1 && grid[bi] < durationUs - minShotUs) {
            val level = levelAt(bi)
            val periodUs = localPeriodUs(grid, bi)

            if (strobeCooldown <= 0 && shouldStrobe(bi, dropBeat, level)) {
                val count = 6 + rnd.nextInt(7)
                val microUs = max(60_000L, periodUs / 8)
                for (k in 0 until count) {
                    val t = grid[bi] + k * microUs
                    if (t >= durationUs - minShotUs) break
                    marks.add(Mark(t, 3, strobe = true, beatIndex = bi))
                }
                // Округляем вверх: иначе следующий рез мог бы попасть внутрь очереди.
                val beatsUsed = max(1, Math.ceil(count * microUs.toDouble() / periodUs).toInt())
                bi += beatsUsed
                strobeCooldown = 16
                continue
            }
            strobeCooldown--

            val lenBeats = baseShotBeats(level)
            marks.add(Mark(grid[bi], level, strobe = false, beatIndex = bi))
            if (lenBeats < 1f) {
                // Полубитовый рез: добавляем ещё одну точку посередине.
                val mid = grid[bi] + periodUs / 2
                if (mid < durationUs - minShotUs) {
                    marks.add(Mark(mid, level, strobe = false, beatIndex = bi))
                }
                bi += 1
            } else {
                bi += lenBeats.toInt().coerceAtLeast(1)
            }
        }
        if (marks.isEmpty()) marks.add(Mark(0, 1, strobe = false, beatIndex = 0))
        // Страховка: точки реза должны строго возрастать.
        val sorted = marks.sortedBy { it.startUs }
        val clean = ArrayList<Mark>(sorted.size)
        for (m in sorted) {
            if (clean.isEmpty() || m.startUs - clean.last().startUs >= 40_000L) clean.add(m)
        }
        return clean
    }

    /** Второй проход: длительности, источники, скорости, переходы и эффекты. */
    private fun buildShots(marks: List<Mark>, durationUs: Long): List<Shot> {
        val shots = ArrayList<Shot>(marks.size)
        val dropBeat = audio.dropBeat
        var prevTransition: Transition? = null
        var prevClip = -1
        var prevSrcEndUs = -1L
        var shotsSinceBurst = 99

        for ((i, mark) in marks.withIndex()) {
            val startUs = mark.startUs
            val endUs = if (i + 1 < marks.size) min(marks[i + 1].startUs, durationUs) else durationUs
            if (endUs - startUs < 40_000L) continue
            val outDur = endUs - startUs
            val level = mark.level

            val burst = !mark.strobe && level >= 2 && shotsSinceBurst >= 5 &&
                (mark.beatIndex == dropBeat ||
                    rnd.nextFloat() < p.burstChance * (0.6f + 0.6f * intensity))

            val beforeDrop = dropBeat > 0 && mark.beatIndex in (dropBeat - 2) until dropBeat
            val ramp = when {
                mark.strobe -> 0f
                beforeDrop -> 1f
                level >= 1 && rnd.nextFloat() < p.rampChance -> 0.5f + rnd.nextFloat() * 0.5f
                else -> 0f
            }
            val speed = when {
                mark.strobe -> 1f
                beforeDrop -> 0.55f
                ramp > 0f -> 0.75f + rnd.nextFloat() * 0.2f
                level >= 3 && rnd.nextFloat() < 0.25f -> 1.15f + rnd.nextFloat() * 0.25f
                level == 0 && rnd.nextFloat() < 0.35f -> 0.7f + rnd.nextFloat() * 0.2f
                else -> 1f
            }

            val transition = if (mark.strobe) {
                if (i > 0 && marks[i - 1].strobe) Transition.CUT else Transition.FLASH
            } else {
                pickTransition(level, prevTransition, burst)
            }
            val trDur = if (mark.strobe) min(40_000L, outDur / 3)
            else transitionDurationUs(level, transition, outDur)

            val fx = fxFor(level, burst = burst, strobeShot = mark.strobe)
            val freeze = mark.strobe && (i % 2 == 1)

            val shot = makeShot(
                level = level,
                outStartUs = startUs,
                outEndUs = endUs,
                transition = transition,
                transitionDurUs = trDur,
                fx = fx,
                freeze = freeze,
                rampIn = ramp,
                speed = speed,
                prevClip = prevClip,
                prevSrcEndUs = prevSrcEndUs,
            )
            shots.add(shot)
            prevClip = shot.clipIndex
            prevSrcEndUs = shot.srcStartUs + (shot.durationUs * shot.speed).toLong()
            prevTransition = transition
            shotsSinceBurst = if (burst) 0 else shotsSinceBurst + 1
        }

        if (shots.isEmpty()) shots.add(fallbackShot(durationUs))
        return shots
    }

    // ------------------------------------------------------------- Сетка

    private fun buildBeatGrid(durationUs: Long): LongArray {
        val periodUs = (audio.beatPeriod * 1_000_000f).toLong().coerceIn(180_000L, 2_000_000L)
        val src = audio.beats
        val out = ArrayList<Long>()
        if (src.isEmpty()) {
            var t = 0L
            while (t <= durationUs + periodUs) {
                out.add(t)
                t += periodUs
            }
            return out.toLongArray()
        }
        // Достраиваем сетку назад к нулю и вперёд до конца трека.
        var first = (src[0] * 1_000_000f).toLong()
        var prepended = 0
        while (first - periodUs > 60_000L) {
            first -= periodUs
            out.add(first)
            prepended++
        }
        gridOffset = prepended
        for (b in src) {
            val us = (b * 1_000_000f).toLong()
            if (us <= durationUs + periodUs) out.add(us)
        }
        var t = out.max()
        while (t < durationUs + periodUs) {
            t += periodUs
            out.add(t)
        }
        out.sort()
        return out.toLongArray()
    }

    private fun levelAt(beatIdx: Int): Int {
        if (audio.beatLevel.isEmpty()) return 2
        // Сетка достроена в обе стороны, поэтому смещаем индекс к разметке трека.
        val offset = beatIdx - gridOffset
        val i = offset.coerceIn(0, audio.beatLevel.size - 1)
        return audio.beatLevel[i]
    }

    /** На сколько битов сетка достроена влево относительно разметки трека. */
    private var gridOffset: Int = 0

    private fun localPeriodUs(grid: LongArray, i: Int): Long {
        if (grid.size < 2) return 500_000L
        val a = grid[i.coerceIn(0, grid.size - 2)]
        val b = grid[(i + 1).coerceIn(1, grid.size - 1)]
        return max(120_000L, b - a)
    }

    /**
     * Длина плана в битах. Эдит держится на длинных планах с наездом,
     * а быстрая нарезка живёт в отдельных строб-очередях на акцентах.
     * Ползунок интенсивности сжимает или растягивает всю сетку.
     */
    private fun baseShotBeats(level: Int): Float {
        val base = when (level) {
            0 -> 8f
            1 -> if (rnd.nextFloat() < 0.5f) 8f else 4f
            2 -> 4f
            else -> if (rnd.nextFloat() < 0.35f) 2f else 4f
        }
        val scaled = base * (1.6f - 0.9f * intensity.coerceIn(0f, 1f))
        // Прижимаем к музыкальным длинам, чтобы резы ложились на доли такта.
        val steps = floatArrayOf(1f, 2f, 3f, 4f, 6f, 8f, 12f)
        var best = steps[0]
        for (step in steps) {
            if (Math.abs(step - scaled) < Math.abs(best - scaled)) best = step
        }
        return best
    }

    private var dropStrobeUsed = false

    private fun shouldStrobe(beatIdx: Int, dropBeat: Int, level: Int): Boolean {
        if (level < 3) return false
        // Индекс бита шагает через 1-4, поэтому ловим окно после дропа, а не точное совпадение.
        val rel = beatIdx - gridOffset - dropBeat
        if (!dropStrobeUsed && dropBeat >= 0 && rel in 6..12) {
            dropStrobeUsed = true
            return true
        }
        return rnd.nextFloat() < 0.12f * intensity
    }

    // ---------------------------------------------------------- Источники

    private fun makeShot(
        level: Int,
        outStartUs: Long,
        outEndUs: Long,
        transition: Transition,
        transitionDurUs: Long,
        fx: ShotFx,
        freeze: Boolean,
        rampIn: Float,
        speed: Float,
        prevClip: Int,
        prevSrcEndUs: Long = -1L,
    ): Shot {
        val outDur = outEndUs - outStartUs
        var spd = speed
        val neededSrc = if (freeze) 40_000L else (outDur * spd).toLong()

        // Часть резов — продолжение той же сцены: действие едет дальше,
        // просто по биту меняется ракурс/эффект. Так эдит читается цельно.
        if (!freeze && prevClip >= 0 && prevSrcEndUs > 0) {
            val continueChance = if (level >= 2) 0.45f else 0.30f
            val clipDur = clips.getOrNull(prevClip)?.durationUs ?: 0L
            if (rnd.nextFloat() < continueChance && prevSrcEndUs + neededSrc < clipDur) {
                return Shot(
                    clipIndex = prevClip,
                    srcStartUs = prevSrcEndUs,
                    outStartUs = outStartUs,
                    outEndUs = outEndUs,
                    speed = spd,
                    rampIn = rampIn,
                    freeze = false,
                    transition = transition,
                    transitionDurUs = transitionDurUs,
                    fx = fx,
                )
            }
        }
        val seg = pickSegment(level, neededSrc, prevClip)
        val clip = clips.getOrNull(seg?.clipIndex ?: 0)
        val clipDur = clip?.durationUs ?: outDur

        var srcStart = if (seg != null) {
            val centered = seg.peakUs - neededSrc / 3
            centered.coerceIn(seg.startUs, max(seg.startUs, seg.endUs - neededSrc))
        } else 0L
        srcStart = srcStart.coerceIn(0L, max(0L, clipDur - 1))

        // План не должен переезжать через склейку внутри исходника: если сцены
        // не хватает на всю длину, замедляем — получается ровный длинный план.
        val available = clipDur - srcStart
        val sceneAvailable = if (seg != null) min(seg.endUs - srcStart, available) else available
        if (!freeze && sceneAvailable < neededSrc && outDur > 0) {
            spd = max(0.35f, sceneAvailable.toFloat() / outDur)
        }

        return Shot(
            clipIndex = seg?.clipIndex ?: 0,
            srcStartUs = srcStart,
            outStartUs = outStartUs,
            outEndUs = outEndUs,
            speed = spd,
            rampIn = rampIn,
            freeze = freeze,
            transition = transition,
            transitionDurUs = transitionDurUs,
            fx = fx,
        )
    }

    private fun pickSegment(level: Int, neededUs: Long, prevClip: Int): ClipSegment? {
        if (allSegments.isEmpty()) return null
        var best: ClipSegment? = null
        var bestIdx = -1
        var bestScore = Float.NEGATIVE_INFINITY
        for ((idx, seg) in allSegments.withIndex()) {
            var s = seg.score
            s -= 0.42f * usage[idx]
            if (seg.clipIndex == prevClip && clips.size > 1) s -= 0.35f
            s += if (level >= 2) 0.45f * seg.motion else 0.35f * seg.detail
            if (level <= 1) s -= 0.25f * seg.motion
            if (seg.durationUs < neededUs) {
                s -= 0.6f * (1f - seg.durationUs.toFloat() / max(1L, neededUs))
            }
            s += rnd.nextFloat() * 0.12f
            if (s > bestScore) {
                bestScore = s
                best = seg
                bestIdx = idx
            }
        }
        if (bestIdx >= 0) usage[bestIdx]++
        return best
    }

    private fun fallbackShot(durationUs: Long): Shot = Shot(
        clipIndex = 0,
        srcStartUs = 0,
        outStartUs = 0,
        outEndUs = durationUs,
        speed = 1f,
        rampIn = 0f,
        freeze = false,
        transition = Transition.CUT,
        transitionDurUs = 0,
        fx = fxFor(1, burst = false, strobeShot = false),
    )

    // ----------------------------------------------------------- Эффекты

    private fun pickTransition(level: Int, prev: Transition?, burst: Boolean): Transition {
        if (burst) return Transition.FLASH
        val pool = when (level) {
            0 -> p.calmTransitions
            1 -> p.midTransitions
            2 -> p.midTransitions
            else -> p.hotTransitions
        }
        if (pool.isEmpty()) return Transition.CUT
        var pick = pool[rnd.nextInt(pool.size)]
        var guard = 0
        while (pick == prev && pool.size > 1 && guard++ < 4) {
            pick = pool[rnd.nextInt(pool.size)]
        }
        return pick
    }

    private fun transitionDurationUs(level: Int, transition: Transition, shotUs: Long): Long {
        val base = when (level) {
            0 -> 260_000L
            1 -> 180_000L
            2 -> 110_000L
            else -> 70_000L
        }
        val scaled = when (transition) {
            Transition.DISSOLVE -> (base * 1.6f).toLong()
            Transition.CUT -> 0L
            Transition.FLASH -> (base * 0.8f).toLong()
            Transition.GLITCH -> (base * 0.7f).toLong()
            else -> base
        }
        return min(scaled, (shotUs * 0.45f).toLong())
    }

    private fun fxFor(level: Int, burst: Boolean, strobeShot: Boolean): ShotFx {
        val lvl = level.toFloat() / 3f
        val hot = level >= 2
        return ShotFx(
            baseZoom = p.baseZoom + rnd.nextFloat() * 0.05f + 0.04f * lvl,
            // Непрерывный наезд — основа плана, отъезд оставляем как редкий приём.
            kenBurns = (if (rnd.nextFloat() < 0.75f) 1f else -1f) *
                (0.06f + 0.10f * rnd.nextFloat()),
            punch = p.punch * (0.45f + 0.55f * lvl) * punchScale,
            shake = p.shake * (if (hot) 0.45f + 0.55f * lvl else 0.12f) * punchScale,
            rgbSplit = p.rgbSplit * (0.30f + 0.70f * lvl) * punchScale,
            glow = p.glow * (0.7f + 0.5f * lvl),
            // Обычные шоты держат цвет исходника, тонировка бьёт по акцентам.
            grade = if (burst || strobeShot) 1.25f else 0.40f + 0.25f * lvl,
            echo = p.echo * (if (hot) 0.7f + 0.4f * lvl else 0.25f) * (if (burst) 1.4f else 1f),
            vignette = p.vignette,
            grain = p.grain,
            flashIn = when {
                strobeShot -> 0.75f
                burst -> 0.45f
                else -> 0.05f + 0.10f * lvl
            },
            strobe = if (strobeShot) p.strobe * punchScale else 0f,
            burst = if (burst) 1f else 0f,
            rotate = if (hot && rnd.nextFloat() < 0.35f) (rnd.nextFloat() * 5f - 2.5f) * punchScale else 0f,
            mirror = rnd.nextFloat() < p.mirrorChance,
            shockwave = if (hot) 0.35f + 0.4f * lvl else 0f,
        )
    }
}

/** Разбивка исходника на сегменты по замерам кадров. */
object SegmentBuilder {

    /**
     * Оценка отдельного кадра как «момента»: хороший момент — это резкий,
     * устойчивый и нормально проэкспонированный кадр с наполненным центром.
     * Смазы, стыки сцен и вспышки уходят вниз.
     */
    fun frameScores(
        motion: FloatArray,
        detail: FloatArray,
        centerDetail: FloatArray,
        brightness: FloatArray,
    ): FloatArray {
        val n = motion.size
        if (n == 0) return FloatArray(0)
        val nm = normalized(motion)
        val nd = normalized(detail)
        val nc = normalized(centerDetail)
        val cutLevel = percentile(nm, 0.92f)

        val out = FloatArray(n)
        for (i in 0 until n) {
            val exposure = 1f - (Math.abs(brightness[i] - 0.45f) / 0.45f).coerceIn(0f, 1f)
            var s = 0.42f * nc[i] + 0.22f * nd[i] + 0.22f * (1f - nm[i]) + 0.14f * exposure
            // Тёмные кадры и заставки — плавным штрафом, пересветы — жёстко.
            if (brightness[i] < 0.18f) s -= (0.18f - brightness[i]) * 4f
            if (brightness[i] < 0.07f || brightness[i] > 0.93f) s -= 0.60f
            // Рядом со стыком или вспышкой кадр брать нельзя — это мусор.
            for (k in Math.max(0, i - 1)..Math.min(n - 1, i + 1)) {
                if (nm[k] >= cutLevel) {
                    s -= 0.35f
                    break
                }
            }
            out[i] = s
        }
        return out
    }

    /**
     * @param times времена сэмплов в мкс
     * @param motion отличие кадра от предыдущего 0..1
     * @param detail насыщенность деталями 0..1
     * @param centerDetail то же, но с весом к центру кадра
     * @param brightness средняя яркость 0..1
     */
    fun build(
        clipIndex: Int,
        durationUs: Long,
        times: LongArray,
        motion: FloatArray,
        detail: FloatArray,
        brightness: FloatArray,
        centerDetail: FloatArray = detail,
        maxSegmentUs: Long = 4_000_000L,
    ): List<ClipSegment> {
        if (times.isEmpty()) return listOf(wholeClip(clipIndex, durationUs))

        val scores = frameScores(motion, detail, centerDetail, brightness)
        val nm = normalized(motion)
        val cutLevel = Math.max(0.55f, percentile(nm, 0.94f))

        // Границы — по стыкам сцен, длинные сцены дополнительно режем.
        val bounds = ArrayList<Int>()
        bounds.add(0)
        var lastBoundUs = times[0]
        for (i in 1 until times.size) {
            val sceneCut = nm[i] >= cutLevel
            val tooLong = times[i] - lastBoundUs >= maxSegmentUs
            if ((sceneCut || tooLong) && times[i] - lastBoundUs >= 600_000L) {
                bounds.add(i)
                lastBoundUs = times[i]
            }
        }
        bounds.add(times.size)

        val out = ArrayList<ClipSegment>()
        for (b in 0 until bounds.size - 1) {
            val from = bounds[b]
            val to = bounds[b + 1]
            if (to <= from) continue
            val startUs = times[from]
            val endUs = if (to < times.size) times[to] else durationUs
            if (endUs - startUs < 400_000L) continue

            var mSum = 0f
            var nmSum = 0f
            var dSum = 0f
            var bSum = 0f
            var peakIdx = from
            var peakScore = -Float.MAX_VALUE
            val inner = ArrayList<Float>(to - from)
            for (i in from until to) {
                mSum += motion[i]
                nmSum += nm[i]
                dSum += detail[i]
                bSum += brightness[i]
                inner.add(scores[i])
                // Края сегмента пропускаем: там стык.
                val edge = i == from || i == to - 1
                if (!edge && scores[i] > peakScore) {
                    peakScore = scores[i]
                    peakIdx = i
                }
            }
            if (peakScore == -Float.MAX_VALUE) {
                peakIdx = (from + to) / 2
            }
            val n = (to - from).toFloat()
            // Сегмент оценивается по лучшей своей трети, а не по среднему:
            // одна сильная секунда важнее ровного фона.
            inner.sortDescending()
            val topCount = Math.max(1, inner.size / 3)
            var topSum = 0f
            for (i in 0 until topCount) topSum += inner[i]
            // Кусок, где всё трясётся (проводка, смаз, стык) — плохой источник плана.
            val chaos = 0.30f * (nmSum / n)
            val score = (topSum / topCount - chaos).coerceIn(-1f, 1f)

            out.add(
                ClipSegment(
                    clipIndex = clipIndex,
                    startUs = startUs,
                    endUs = endUs,
                    peakUs = times[peakIdx],
                    motion = (mSum / n).coerceIn(0f, 1f),
                    detail = (dSum / n).coerceIn(0f, 1f),
                    brightness = (bSum / n).coerceIn(0f, 1f),
                    score = ((score + 1f) / 2f).coerceIn(0f, 1f),
                )
            )
        }
        if (out.isEmpty()) out.add(wholeClip(clipIndex, durationUs))
        return out
    }

    private fun wholeClip(clipIndex: Int, durationUs: Long) = ClipSegment(
        clipIndex, 0, durationUs, durationUs / 2,
        motion = 0.5f, detail = 0.5f, brightness = 0.5f, score = 0.5f,
    )

    private fun normalized(v: FloatArray): FloatArray {
        if (v.isEmpty()) return v
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (x in v) {
            if (x < lo) lo = x
            if (x > hi) hi = x
        }
        val range = hi - lo
        if (range < 1e-6f) return FloatArray(v.size)
        return FloatArray(v.size) { (v[it] - lo) / range }
    }

    private fun percentile(sortedInput: FloatArray, p: Float): Float {
        if (sortedInput.isEmpty()) return 0f
        val copy = sortedInput.clone()
        copy.sort()
        return copy[((copy.size - 1) * p).toInt().coerceIn(0, copy.size - 1)]
    }
}

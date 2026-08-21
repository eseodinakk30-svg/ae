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
                val count = 4 + rnd.nextInt(5)
                val microUs = max(minShotUs, periodUs / 4)
                for (k in 0 until count) {
                    val t = grid[bi] + k * microUs
                    if (t >= durationUs - minShotUs) break
                    marks.add(Mark(t, 3, strobe = true, beatIndex = bi))
                }
                val beatsUsed = max(1, Math.round(count * microUs.toFloat() / periodUs))
                bi += beatsUsed
                strobeCooldown = 8
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
        return marks
    }

    /** Второй проход: длительности, источники, скорости, переходы и эффекты. */
    private fun buildShots(marks: List<Mark>, durationUs: Long): List<Shot> {
        val shots = ArrayList<Shot>(marks.size)
        val dropBeat = audio.dropBeat
        var prevTransition: Transition? = null
        var prevClip = -1
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
            )
            shots.add(shot)
            prevClip = shot.clipIndex
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

    private fun baseShotBeats(level: Int): Float {
        var len = when (level) {
            0 -> 4f
            1 -> if (rnd.nextFloat() < 0.30f) 4f else 2f
            2 -> if (rnd.nextFloat() < 0.35f) 1f else 2f
            else -> if (rnd.nextFloat() < 0.30f) 2f else 1f
        }
        if (intensity > 0.7f && len > 1f && rnd.nextFloat() < (intensity - 0.7f) / 0.3f) len /= 2f
        if (intensity < 0.35f && rnd.nextFloat() < (0.35f - intensity) / 0.35f) len *= 2f
        if (level >= 3 && intensity > 0.75f && rnd.nextFloat() < 0.22f) len = 0.5f
        return len.coerceIn(0.5f, 8f)
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
        return rnd.nextFloat() < 0.06f * intensity
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
    ): Shot {
        val outDur = outEndUs - outStartUs
        var spd = speed
        val neededSrc = if (freeze) 40_000L else (outDur * spd).toLong()
        val seg = pickSegment(level, neededSrc, prevClip)
        val clip = clips.getOrNull(seg?.clipIndex ?: 0)
        val clipDur = clip?.durationUs ?: outDur

        var srcStart = if (seg != null) {
            val centered = seg.peakUs - neededSrc / 3
            centered.coerceIn(seg.startUs, max(seg.startUs, seg.endUs - neededSrc))
        } else 0L
        srcStart = srcStart.coerceIn(0L, max(0L, clipDur - 1))

        // Если материала не хватает — притормаживаем, чтобы не упереться в конец.
        val available = clipDur - srcStart
        if (!freeze && available < neededSrc && outDur > 0) {
            spd = max(0.35f, available.toFloat() / outDur)
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
            kenBurns = (if (rnd.nextBoolean()) 1f else -1f) *
                (0.03f + 0.09f * rnd.nextFloat()) * (0.6f + 0.8f * lvl),
            punch = p.punch * (0.35f + 0.65f * lvl) * punchScale,
            shake = p.shake * (if (hot) 0.45f + 0.55f * lvl else 0.12f) * punchScale,
            rgbSplit = p.rgbSplit * (0.30f + 0.70f * lvl) * punchScale,
            glow = p.glow * (0.7f + 0.5f * lvl),
            grade = 0.85f + 0.15f * lvl,
            echo = p.echo * (if (hot) 0.7f + 0.4f * lvl else 0.25f) * (if (burst) 1.4f else 1f),
            vignette = p.vignette,
            grain = p.grain,
            flashIn = if (strobeShot) 0.9f else 0.25f + 0.45f * lvl,
            strobe = if (level >= 3 || strobeShot) p.strobe * punchScale else 0f,
            burst = if (burst) 1f else 0f,
            rotate = if (hot && rnd.nextFloat() < 0.35f) (rnd.nextFloat() * 5f - 2.5f) * punchScale else 0f,
            mirror = rnd.nextFloat() < p.mirrorChance,
            shockwave = if (hot) 0.35f + 0.4f * lvl else 0f,
        )
    }
}

/** Разбивка исходника на сегменты по замерам активности кадров. */
object SegmentBuilder {

    /**
     * @param times времена сэмплов в мкс
     * @param motion отличие кадра от предыдущего 0..1
     * @param detail насыщенность деталями 0..1
     * @param brightness средняя яркость 0..1
     */
    fun build(
        clipIndex: Int,
        durationUs: Long,
        times: LongArray,
        motion: FloatArray,
        detail: FloatArray,
        brightness: FloatArray,
        targetSegmentUs: Long = 1_400_000L,
    ): List<ClipSegment> {
        if (times.isEmpty()) {
            return listOf(
                ClipSegment(
                    clipIndex, 0, durationUs, durationUs / 2,
                    motion = 0.5f, detail = 0.5f, brightness = 0.5f, score = 0.5f
                )
            )
        }
        // Границы сегментов — по резкой смене кадра (сцене) либо по таймеру.
        val bounds = ArrayList<Int>()
        bounds.add(0)
        var lastBound = 0L
        for (i in 1 until times.size) {
            val sceneCut = motion[i] > 0.55f
            val timeUp = times[i] - lastBound >= targetSegmentUs
            if (sceneCut || timeUp) {
                if (times[i] - lastBound >= 500_000L) {
                    bounds.add(i)
                    lastBound = times[i]
                }
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
            if (endUs - startUs < 320_000L) continue

            var mSum = 0f
            var dSum = 0f
            var bSum = 0f
            var peakIdx = from
            var peakVal = -1f
            for (i in from until to) {
                mSum += motion[i]
                dSum += detail[i]
                bSum += brightness[i]
                val v = motion[i] * 0.6f + detail[i] * 0.4f
                if (v > peakVal) {
                    peakVal = v
                    peakIdx = i
                }
            }
            val n = (to - from).toFloat()
            val m = (mSum / n).coerceIn(0f, 1f)
            val d = (dSum / n).coerceIn(0f, 1f)
            val br = (bSum / n).coerceIn(0f, 1f)
            // Слишком тёмное или засвеченное — вниз; детализация и движение — вверх.
            val exposurePenalty = if (br < 0.10f) 0.55f else if (br > 0.94f) 0.30f else 0f
            val score = (0.44f * d + 0.36f * m + 0.20f * (1f - abs(br - 0.52f) * 1.6f))
                .coerceIn(0f, 1f) - exposurePenalty
            out.add(
                ClipSegment(
                    clipIndex = clipIndex,
                    startUs = startUs,
                    endUs = endUs,
                    peakUs = times[peakIdx],
                    motion = m,
                    detail = d,
                    brightness = br,
                    score = score.coerceIn(0f, 1f),
                )
            )
        }
        if (out.isEmpty()) {
            out.add(
                ClipSegment(
                    clipIndex, 0, durationUs, durationUs / 2,
                    motion = 0.5f, detail = 0.5f, brightness = 0.5f, score = 0.5f
                )
            )
        }
        return out
    }
}

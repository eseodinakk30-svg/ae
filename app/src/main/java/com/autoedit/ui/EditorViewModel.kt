package com.autoedit.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autoedit.core.AspectPreset
import com.autoedit.core.AudioAnalysis
import com.autoedit.core.BeatDetector
import com.autoedit.core.ClipInfo
import com.autoedit.core.EditPlanner
import com.autoedit.core.FitMode
import com.autoedit.core.Style
import com.autoedit.engine.AudioLoader
import com.autoedit.engine.EditRenderer
import com.autoedit.engine.RenderCancelledException
import com.autoedit.engine.VideoAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

class PickedItem(val uri: Uri, val name: String)

enum class Stage { IDLE, WORKING, DONE, ERROR }

class EditorState(
    val videos: List<PickedItem> = emptyList(),
    val music: PickedItem? = null,
    val style: Style = Style.ANIME,
    val aspect: AspectPreset = AspectPreset.AUTO,
    val fitMode: FitMode = FitMode.SMART,
    val startFromDrop: Boolean = true,
    val intensity: Float = 0.75f,
    val maxHeight: Int = 1280,
    val limitSec: Int = 30,
    val stage: Stage = Stage.IDLE,
    val progress: Float = 0f,
    val status: String = "",
    val error: String? = null,
    val result: File? = null,
    val info: String? = null,
    val savedToGallery: Boolean = false,
    val outWidth: Int = 0,
    val outHeight: Int = 0,
) {
    val canStart: Boolean get() = videos.isNotEmpty() && stage != Stage.WORKING

    fun copy(
        videos: List<PickedItem> = this.videos,
        music: PickedItem? = this.music,
        style: Style = this.style,
        aspect: AspectPreset = this.aspect,
        fitMode: FitMode = this.fitMode,
        startFromDrop: Boolean = this.startFromDrop,
        intensity: Float = this.intensity,
        maxHeight: Int = this.maxHeight,
        limitSec: Int = this.limitSec,
        stage: Stage = this.stage,
        progress: Float = this.progress,
        status: String = this.status,
        error: String? = this.error,
        result: File? = this.result,
        info: String? = this.info,
        savedToGallery: Boolean = this.savedToGallery,
        outWidth: Int = this.outWidth,
        outHeight: Int = this.outHeight,
    ) = EditorState(
        videos, music, style, aspect, fitMode, startFromDrop, intensity, maxHeight, limitSec,
        stage, progress, status, error, result, info, savedToGallery, outWidth, outHeight,
    )
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private var job: Job? = null
    private var seed: Long = System.currentTimeMillis()

    fun addVideos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val ctx = getApplication<Application>()
        val existing = _state.value.videos.map { it.uri }.toSet()
        val added = uris.filter { it !in existing }
            .map { PickedItem(it, MediaUtils.displayName(ctx, it)) }
        _state.value = _state.value.copy(videos = _state.value.videos + added, error = null)
    }

    fun removeVideo(item: PickedItem) {
        _state.value = _state.value.copy(videos = _state.value.videos.filter { it !== item })
    }

    fun setMusic(uri: Uri?) {
        val ctx = getApplication<Application>()
        _state.value = _state.value.copy(
            music = uri?.let { PickedItem(it, MediaUtils.displayName(ctx, it)) },
            error = null,
        )
    }

    fun setStyle(style: Style) {
        _state.value = _state.value.copy(style = style)
    }

    fun setAspect(aspect: AspectPreset) {
        _state.value = _state.value.copy(aspect = aspect)
    }

    fun setFitMode(mode: FitMode) {
        _state.value = _state.value.copy(fitMode = mode)
    }

    fun setStartFromDrop(value: Boolean) {
        _state.value = _state.value.copy(startFromDrop = value)
    }

    fun setIntensity(value: Float) {
        _state.value = _state.value.copy(intensity = value)
    }

    fun setQuality(maxHeight: Int) {
        _state.value = _state.value.copy(maxHeight = maxHeight)
    }

    fun setLimit(seconds: Int) {
        _state.value = _state.value.copy(limitSec = seconds)
    }

    fun reroll() {
        seed = System.currentTimeMillis()
        start()
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(stage = Stage.IDLE, status = "Отменено", progress = 0f)
    }

    fun start() {
        if (_state.value.videos.isEmpty()) {
            _state.value = _state.value.copy(error = "Сначала добавь хотя бы одно видео")
            return
        }
        job?.cancel()
        _state.value = _state.value.copy(
            stage = Stage.WORKING, progress = 0f, status = "Готовлюсь", error = null,
            result = null, savedToGallery = false,
        )
        job = viewModelScope.launch(Dispatchers.Default) {
            try {
                runPipeline()
            } catch (e: RenderCancelledException) {
                _state.value = _state.value.copy(stage = Stage.IDLE, status = "Отменено")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    stage = Stage.ERROR,
                    error = e.message ?: e.javaClass.simpleName,
                    status = "Ошибка",
                )
            }
        }
    }

    private suspend fun runPipeline() {
        val ctx = getApplication<Application>()
        val s = _state.value

        // 1. Разбор исходников.
        val clips = ArrayList<ClipInfo>(s.videos.size)
        for ((i, item) in s.videos.withIndex()) {
            coroutineContext.ensureActive()
            update(
                progress = 0.02f + 0.06f * (i.toFloat() / max(1, s.videos.size)),
                status = "Смотрю видео ${i + 1}/${s.videos.size}",
            )
            clips.add(VideoAnalyzer.analyze(ctx, item.uri, i))
        }
        if (clips.all { it.segments.isEmpty() }) error("Не удалось прочитать видео")

        // 2. Разбор музыки.
        coroutineContext.ensureActive()
        update(progress = 0.09f, status = "Слушаю музыку, ищу биты")
        val musicUri = s.music?.uri
        val pcm = musicUri?.let { AudioLoader.loadForAnalysis(ctx, it) }
        val musicDurationUs = musicUri?.let { AudioLoader.durationUs(ctx, it) } ?: 0L
        val audio = if (pcm != null && !BeatDetector.isSilent(pcm)) {
            BeatDetector.analyze(pcm, BeatDetector.ANALYSIS_RATE)
        } else {
            AudioAnalysis.fallback(
                durationSec = if (musicDurationUs > 0) musicDurationUs / 1_000_000f else 20f,
            )
        }

        // 3. Длительность ролика.
        val materialUs = clips.sumOf { it.durationUs }
        var durationUs = when {
            musicDurationUs > 0 -> musicDurationUs
            else -> min(materialUs, 30_000_000L)
        }
        if (s.limitSec > 0) durationUs = min(durationUs, s.limitSec * 1_000_000L)
        durationUs = durationUs.coerceIn(3_000_000L, 10 * 60_000_000L)

        // Короткий ролик начинаем с мощного места трека, а не с вступления.
        var musicStartUs = 0L
        var timeline = audio
        if (s.startFromDrop && s.limitSec > 0 && musicDurationUs > durationUs) {
            val startBeat = audio.strongStartBeat()
            if (startBeat > 0) {
                var startSec = audio.beats[startBeat]
                val maxStartSec = (musicDurationUs - durationUs) / 1_000_000f
                startSec = startSec.coerceIn(0f, max(0f, maxStartSec))
                if (startSec > 0.5f) {
                    musicStartUs = (startSec * 1_000_000f).toLong()
                    timeline = audio.slice(startSec, durationUs / 1_000_000f)
                }
            }
        }

        // 4. План монтажа.
        coroutineContext.ensureActive()
        update(progress = 0.10f, status = "Собираю монтаж по битам")
        val sourceAspect = dominantAspect(clips)
        val (w, h) = s.aspect.resolve(s.maxHeight, sourceAspect)
        val plan = EditPlanner(timeline, clips, s.style, s.intensity, seed)
            .build(durationUs, w, h, FPS)
        _state.value = _state.value.copy(outWidth = w, outHeight = h)
        val info = "%.0f BPM · %d шотов · %.0f с · %dx%d%s".format(
            audio.bpm, plan.shots.size, durationUs / 1_000_000f, w, h,
            if (musicStartUs > 0) " · с %.0f с трека".format(musicStartUs / 1_000_000f) else "",
        )
        update(progress = 0.11f, status = "Рендер", info = info)

        // 5. Рендер.
        val outDir = File(ctx.cacheDir, "renders").apply { mkdirs() }
        outDir.listFiles()?.forEach { if (it.name.endsWith(".mp4")) it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(outDir, "autoedit_$stamp.mp4")

        val renderer = EditRenderer(
            context = ctx,
            plan = plan,
            clipUris = s.videos.map { it.uri },
            musicUri = musicUri,
            workDir = File(ctx.cacheDir, "work"),
            fitMode = s.fitMode,
            musicStartUs = musicStartUs,
        )
        val scope = coroutineContext
        renderer.render(
            outputFile = outFile,
            onProgress = { p, text ->
                update(progress = 0.10f + 0.90f * p, status = text, info = info)
            },
            isCancelled = { !scope.isActive },
        )

        update(progress = 1f, status = "Готово", info = info)
        _state.value = _state.value.copy(stage = Stage.DONE, result = outFile)
    }

    /** Формат, который чаще всего встречается в исходниках. */
    private fun dominantAspect(clips: List<ClipInfo>): Float {
        val aspects = clips.mapNotNull { c ->
            if (c.width <= 0 || c.height <= 0) return@mapNotNull null
            val swapped = c.rotationDegrees == 90 || c.rotationDegrees == 270
            if (swapped) c.height.toFloat() / c.width else c.width.toFloat() / c.height
        }
        if (aspects.isEmpty()) return 9f / 16f
        return aspects.sorted()[aspects.size / 2]
    }

    private fun update(progress: Float, status: String, info: String? = _state.value.info) {
        _state.value = _state.value.copy(
            progress = progress.coerceIn(0f, 1f), status = status, info = info,
        )
    }

    fun saveToGallery() {
        val file = _state.value.result ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                MediaUtils.saveToGallery(getApplication(), file, file.name)
            }.getOrNull() != null
            _state.value = _state.value.copy(
                savedToGallery = ok,
                error = if (ok) null else "Не удалось сохранить в галерею",
            )
        }
    }

    companion object {
        const val FPS = 30
    }
}

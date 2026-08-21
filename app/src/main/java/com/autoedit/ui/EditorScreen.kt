package com.autoedit.ui

import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.autoedit.core.AspectPreset
import com.autoedit.core.FitMode
import com.autoedit.core.Style

@Composable
fun EditorScreen(state: EditorState, vm: EditorViewModel) {
    val pickVideos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(12),
    ) { uris -> vm.addVideos(uris) }

    val pickMusic = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.setMusic(uri) }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.background, Surface1),
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header()

            SectionCard(title = "Видео", subtitle = "Из чего резать") {
                Button(
                    onClick = {
                        pickVideos.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Добавить видео")
                }
                if (state.videos.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    state.videos.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                item.name,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            OutlinedButton(
                                onClick = { vm.removeVideo(item) },
                                contentPadding = PaddingValues(6.dp),
                            ) {
                                Icon(Icons.Default.Close, null, Modifier.height(16.dp))
                            }
                        }
                    }
                }
            }

            SectionCard(title = "Музыка", subtitle = "По ней ищутся биты и дроп") {
                Button(
                    onClick = { pickMusic.launch(arrayOf("audio/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                    ),
                ) {
                    Icon(Icons.Default.MusicNote, null)
                    Spacer(Modifier.width(8.dp))
                    Text(state.music?.name ?: "Выбрать трек")
                }
            }

            SectionCard(title = "Стиль", subtitle = state.style.subtitle) {
                ChipRow {
                    Style.entries.forEach { style ->
                        FilterChip(
                            selected = state.style == style,
                            onClick = { vm.setStyle(style) },
                            label = { Text(style.title) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
            }

            SectionCard(title = "Формат и длина") {
                ChipRow {
                    AspectPreset.entries.forEach { a ->
                        FilterChip(
                            selected = state.aspect == a,
                            onClick = { vm.setAspect(a) },
                            label = { Text(a.title) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                ChipRow {
                    FitMode.entries.forEach { m ->
                        FilterChip(
                            selected = state.fitMode == m,
                            onClick = { vm.setFitMode(m) },
                            label = { Text(m.title) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                ChipRow {
                    listOf(0 to "Вся музыка", 15 to "15 с", 30 to "30 с", 60 to "60 с").forEach { (v, t) ->
                        FilterChip(
                            selected = state.limitSec == v,
                            onClick = { vm.setLimit(v) },
                            label = { Text(t) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                ChipRow {
                    listOf(720 to "720p", 1280 to "HD", 1920 to "Full HD").forEach { (v, t) ->
                        FilterChip(
                            selected = state.maxHeight == v,
                            onClick = { vm.setQuality(v) },
                            label = { Text(t) },
                        )
                    }
                }
                if (state.limitSec > 0) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = state.startFromDrop,
                            onCheckedChange = { vm.setStartFromDrop(it) },
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Начинать с мощного места трека",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            SectionCard(
                title = "Интенсивность",
                subtitle = "Чем выше — тем короче резы и жёстче эффекты",
            ) {
                Slider(
                    value = state.intensity,
                    onValueChange = { vm.setIntensity(it) },
                    valueRange = 0f..1f,
                )
            }

            when (state.stage) {
                Stage.WORKING -> WorkingBlock(state, vm)
                Stage.DONE -> ResultBlock(state, vm)
                else -> Unit
            }

            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (state.stage != Stage.WORKING) {
                Button(
                    onClick = { vm.start() },
                    enabled = state.canStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Icon(Icons.Default.AutoAwesome, null)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (state.stage == Stage.DONE) "Сделать заново" else "Сделать эдит",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun Header() {
    Column {
        Text(
            "AutoEdit",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
        )
        Text(
            "Кидаешь видео и трек — приложение само режет по битам",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface2),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Composable
private fun WorkingBlock(state: EditorState, vm: EditorViewModel) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface2),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(state.status, fontWeight = FontWeight.Bold)
            state.info?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { vm.cancel() }) { Text("Отменить") }
        }
    }
}

@Composable
private fun ResultBlock(state: EditorState, vm: EditorViewModel) {
    val context = LocalContext.current
    val file = state.result ?: return
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface2),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Готово", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            state.info?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoPath(file.absolutePath)
                        setOnPreparedListener { mp ->
                            mp.isLooping = true
                            start()
                        }
                    }
                },
                update = { view ->
                    if (view.tag != file.absolutePath) {
                        view.tag = file.absolutePath
                        view.setVideoPath(file.absolutePath)
                        view.start()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(
                        if (state.outWidth > 0 && state.outHeight > 0) {
                            state.outWidth.toFloat() / state.outHeight
                        } else 9f / 16f,
                    )
                    .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.saveToGallery() },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Download, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.savedToGallery) "Сохранено" else "В галерею")
                }
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            android.content.Intent.createChooser(
                                MediaUtils.shareIntent(context, file), "Поделиться",
                            ),
                        )
                    },
                ) {
                    Icon(Icons.Default.Share, null)
                }
                OutlinedButton(onClick = { vm.reroll() }) {
                    Icon(Icons.Default.Refresh, null)
                }
            }
        }
    }
}

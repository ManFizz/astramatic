package com.manfis.astramatic.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.manfis.astramatic.headset.HeadsetScreen
import com.manfis.astramatic.model.Projection
import com.manfis.astramatic.model.StereoLayout
import com.manfis.astramatic.model.VideoFormat
import kotlinx.coroutines.delay

/** Состояние интерфейса плеера; живёт в активности, поэтому переживает переключение VR ⇄ 2D. */
class VrMenuState {
    /** Компактная полоска управления воспроизведением или большое меню. */
    enum class Mode { Controls, Menu }

    enum class Tab { Video, Files, Headset }

    var mode by mutableStateOf(Mode.Menu)
    var tab by mutableStateOf(Tab.Files)

    /** Имя открытого видео, null — видео ещё не выбрано. */
    var title by mutableStateOf<String?>(null)
    var format by mutableStateOf(VideoFormat())
    var stereo by mutableStateOf(true)

    val library = LibraryState()
}

/** Что интерфейс умеет делать с плеером. */
interface VrMenuActions {
    fun isPlaying(): Boolean
    fun positionMs(): Long
    fun durationMs(): Long
    fun togglePlayback()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)
    fun setFormat(format: VideoFormat)
    fun toggleStereo()
    fun recenter()
    fun play(uri: Uri, name: String, format: VideoFormat)
    fun headsetSaved()
    fun openMenu(tab: VrMenuState.Tab)
    fun hide()
    fun exit()
}

/** Высота полоски управления в шлеме: столько пикселей сверху виртуального дисплея показывает рендерер. */
val ControlsHeightDp = 200.dp

@Composable
fun VrMenu(state: VrMenuState, actions: VrMenuActions) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        when (state.mode) {
            VrMenuState.Mode.Controls -> ControlsLayer(state, actions)
            VrMenuState.Mode.Menu -> Panel(Modifier.fillMaxSize(), shape(state)) { MenuContent(state, actions) }
        }
    }
}

private fun shape(state: VrMenuState): Shape = RoundedCornerShape(if (state.stereo) 28.dp else 0.dp)

/**
 * Фон панели. Цвет текста задан явно: у полупрозрачного фона Material не находит пару
 * в теме и оставил бы текст чёрным.
 */
@Composable
private fun Panel(modifier: Modifier, shape: Shape, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        content = content,
    )
}

// --- Полоска управления ---------------------------------------------------------------------

@Composable
private fun ControlsLayer(state: VrMenuState, actions: VrMenuActions) {
    if (state.stereo) {
        // В шлеме рендерер показывает только верхнюю часть дисплея высотой ControlsHeightDp.
        Panel(Modifier.fillMaxWidth().height(ControlsHeightDp), shape(state)) { ControlsBar(state, actions) }
    } else {
        // На экране — полоска снизу, нажатие по видео над ней прячет её.
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        actions.hide()
                    },
            )
            Panel(Modifier.fillMaxWidth(), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
                Box(Modifier.navigationBarsPadding()) { ControlsBar(state, actions) }
            }
        }
    }
}

@Composable
private fun ControlsBar(state: VrMenuState, actions: VrMenuActions) {
    var position by remember { mutableLongStateOf(actions.positionMs()) }
    var duration by remember { mutableLongStateOf(actions.durationMs()) }
    var playing by remember { mutableStateOf(actions.isPlaying()) }
    var dragging by remember { mutableStateOf<Float?>(null) }

    // Плеер не сообщает о каждом сдвиге позиции, проще опрашивать, пока полоска на экране.
    LaunchedEffect(Unit) {
        while (true) {
            position = actions.positionMs()
            duration = actions.durationMs()
            playing = actions.isPlaying()
            delay(250)
        }
    }

    val compact = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.title ?: "",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "${formatTime(dragging?.toLong() ?: position)} / ${formatTime(duration)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Slider(
            value = dragging ?: position.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { actions.seekTo(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { actions.seekBy(-30_000) }, contentPadding = compact) { Text("−30") }
            TextButton(onClick = { actions.seekBy(-10_000) }, contentPadding = compact) { Text("−10") }
            Button(onClick = actions::togglePlayback, contentPadding = compact) { Text(if (playing) "⏸" else "▶") }
            TextButton(onClick = { actions.seekBy(10_000) }, contentPadding = compact) { Text("+10") }
            TextButton(onClick = { actions.seekBy(30_000) }, contentPadding = compact) { Text("+30") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = actions::recenter, contentPadding = compact) { Text("Центр") }
            TextButton(onClick = actions::toggleStereo, contentPadding = compact) { Text(if (state.stereo) "2D" else "VR") }
            FilledTonalButton(onClick = { actions.openMenu(VrMenuState.Tab.Video) }, contentPadding = compact) {
                Text("☰ Меню")
            }
        }
    }
}

// --- Меню -----------------------------------------------------------------------------------

@Composable
private fun MenuContent(state: VrMenuState, actions: VrMenuActions) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.title != null) TabChip(state, VrMenuState.Tab.Video, "🎬 Видео")
            TabChip(state, VrMenuState.Tab.Files, "📁 Файлы")
            TabChip(state, VrMenuState.Tab.Headset, "👓 Шлем")
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = actions::toggleStereo) { Text(if (state.stereo) "2D" else "VR") }
            if (state.title != null) Button(onClick = actions::hide) { Text("К видео") }
            TextButton(onClick = actions::exit) { Text("Выход") }
        }
        HorizontalDivider()
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (state.tab) {
                VrMenuState.Tab.Video -> VideoTab(state, actions)
                VrMenuState.Tab.Files -> LibraryApp(
                    onPlay = actions::play,
                    state = state.library,
                    embedded = true,
                    allowEditing = !state.stereo,
                )
                VrMenuState.Tab.Headset -> HeadsetScreen(
                    onBack = {
                        state.tab = if (state.title != null) VrMenuState.Tab.Video else VrMenuState.Tab.Files
                    },
                    onSaved = actions::headsetSaved,
                    closeOnSave = false,
                )
            }
        }
    }
}

@Composable
private fun TabChip(state: VrMenuState, tab: VrMenuState.Tab, label: String) {
    FilterChip(selected = state.tab == tab, onClick = { state.tab = tab }, label = { Text(label) })
}

/** Настройки показа текущего видео. */
@Composable
private fun VideoTab(state: VrMenuState, actions: VrMenuActions) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(state.title ?: "", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)

        Text("Проекция", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Projection.entries.forEach {
                FilterChip(
                    selected = state.format.projection == it,
                    onClick = { actions.setFormat(state.format.copy(projection = it)) },
                    label = { Text(projectionLabel(it)) },
                )
            }
        }
        Text("Стерео", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StereoLayout.entries.forEach {
                FilterChip(
                    selected = state.format.stereo == it,
                    onClick = { actions.setFormat(state.format.copy(stereo = it)) },
                    label = { Text(stereoLabel(it)) },
                )
            }
        }
        Text(
            "Формат угадывается по имени файла. Если картинка двоится или искажена — выберите здесь вручную.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedButton(onClick = actions::recenter) { Text("Рецентр — центр видео туда, куда смотрю") }
    }
}

private fun formatTime(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

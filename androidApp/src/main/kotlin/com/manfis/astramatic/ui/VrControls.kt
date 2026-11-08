package com.manfis.astramatic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.manfis.astramatic.settings.AppSettings
import com.manfis.astramatic.settings.RepeatMode
import kotlinx.coroutines.delay

@Composable
internal fun ControlsLayer(state: VrMenuState, actions: VrMenuActions) {
    val sheet = state.sheet
    if (state.stereo) {
        // В шлеме рендерер показывает только верхнюю часть дисплея: полоску, а с раскрытой панелью —
        // панель сверху и полоску под ней (нижний край там же, где у одной полоски).
        val height = if (sheet == null) ControlsHeightDp else SheetHeightDp + ControlsHeightDp
        Panel(Modifier.fillMaxWidth().height(height), shape(state)) {
            Column {
                if (sheet != null) {
                    Box(Modifier.fillMaxWidth().height(SheetHeightDp)) { SheetContent(sheet, state, actions) }
                    Divider(Modifier.padding(horizontal = 20.dp))
                }
                ControlsBar(state, actions)
            }
        }
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
            Panel(Modifier.fillMaxWidth(), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                Column(Modifier.navigationBarsPadding()) {
                    if (sheet != null) {
                        Box(Modifier.fillMaxWidth().height(SheetHeightDp)) { SheetContent(sheet, state, actions) }
                        Divider(Modifier.padding(horizontal = 20.dp))
                    }
                    ControlsBar(state, actions)
                }
            }
        }
    }
}

/**
 * Полоска плеера. Сверху название и время, под ними перемотка. Ниже по центру — управление
 * воспроизведением крупно, а ряд под ним: вид слева, режимы по центру, панели и хаб справа.
 */
@Composable
private fun ControlsBar(state: VrMenuState, actions: VrMenuActions) {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
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

    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.title ?: "",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!state.isImage) {
                Gap()
                Text(
                    "${formatTime(dragging?.toLong() ?: position)} / ${formatTime(duration)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!state.isImage) {
            AppSlider(
                value = dragging ?: position.toFloat(),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { actions.seekTo(it.toLong()) }
                    dragging = null
                },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            )
        } else {
            Spacer(Modifier.height(12.dp))
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        ) {
            AppIconButton(Icons.Rounded.SkipPrevious, "Предыдущий файл", actions::previousFile, size = 52.dp, enabled = state.hasPrevious)
            if (!state.isImage) {
                AppIconButton(Icons.Rounded.Replay30, "Назад на 30 секунд", { actions.seekBy(-30_000) }, size = 52.dp)
                AppIconButton(Icons.Rounded.Replay10, "Назад на 10 секунд", { actions.seekBy(-10_000) }, size = 52.dp)
            }
            // У фото — пауза слайд-шоу.
            AppIconButton(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (playing) "Пауза" else "Воспроизвести",
                actions::togglePlayback,
                size = 64.dp,
                style = ButtonStyle.Filled,
            )
            if (!state.isImage) {
                AppIconButton(Icons.Rounded.Forward10, "Вперёд на 10 секунд", { actions.seekBy(10_000) }, size = 52.dp)
                AppIconButton(Icons.Rounded.Forward30, "Вперёд на 30 секунд", { actions.seekBy(30_000) }, size = 52.dp)
            }
            AppIconButton(Icons.Rounded.SkipNext, "Следующий файл", actions::nextFile, size = 52.dp, enabled = state.hasNext)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                AppButton("Центр", actions::recenterAfterCountdown, icon = Icons.Rounded.CenterFocusStrong, style = ButtonStyle.Ghost)
                AppIconButton(Icons.Rounded.ZoomOut, "Отдалить", { actions.setZoom(state.zoom / ZOOM_STEP) })
                AppIconButton(Icons.Rounded.ZoomIn, "Приблизить", { actions.setZoom(state.zoom * ZOOM_STEP) })
                // Из шлема 2D не включается: только пальцем, кнопкой на экране (как крестик).
                if (!state.stereo) AppButton("VR", actions::toggleStereo, style = ButtonStyle.Ghost)
            }
            // Режимы — значками: включённый подсвечен.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val repeat = RepeatMode.entries.find { it.name == settings.repeatMode } ?: RepeatMode.Off
                AppIconButton(
                    if (repeat == RepeatMode.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    when (repeat) {
                        RepeatMode.Off -> "Повтор выключен"
                        RepeatMode.All -> "Повтор папки"
                        RepeatMode.One -> "Повтор файла"
                    },
                    actions::cycleRepeat,
                    selected = repeat != RepeatMode.Off,
                )
                AppIconButton(Icons.Rounded.Shuffle, "Случайный порядок", actions::toggleShuffle, selected = settings.shuffle)
                if (state.isImage) {
                    AppButton(
                        "${settings.slideshowSeconds} с",
                        actions::toggleSlideshow,
                        icon = Icons.Rounded.Slideshow,
                        style = ButtonStyle.Ghost,
                        selected = settings.slideshow,
                    )
                }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                SheetButton(state, VrMenuState.Sheet.Queue, "Очередь", Icons.Rounded.ViewCarousel)
                SheetButton(state, VrMenuState.Sheet.View, "Вид", Icons.Rounded.Tune)
                AppIconButton(Icons.Rounded.Home, "Хаб", actions::toHub, style = ButtonStyle.Tonal)
            }
        }
    }
}

@Composable
private fun SheetButton(state: VrMenuState, sheet: VrMenuState.Sheet, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    AppButton(
        label,
        { state.sheet = if (state.sheet == sheet) null else sheet },
        icon = icon,
        style = ButtonStyle.Ghost,
        selected = state.sheet == sheet,
    )
}

private const val ZOOM_STEP = 1.15f

private fun formatTime(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

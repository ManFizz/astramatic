package com.manfis.astramatic.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
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
            Panel(Modifier.fillMaxWidth(), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
                Column(Modifier.navigationBarsPadding()) {
                    if (sheet != null) {
                        Box(Modifier.fillMaxWidth().height(SheetHeightDp)) { SheetContent(sheet, state, actions) }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                    ControlsBar(state, actions)
                }
            }
        }
    }
}

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

    val big = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
    val small = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    val bigText = MaterialTheme.typography.titleLarge
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.title ?: "",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            if (!state.isImage) {
                Text(
                    "${formatTime(dragging?.toLong() ?: position)} / ${formatTime(duration)}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        if (!state.isImage) {
            Slider(
                value = dragging ?: position.toFloat(),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { actions.seekTo(it.toLong()) }
                    dragging = null
                },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            )
        } else {
            Spacer(Modifier.height(8.dp))
        }
        // Главный ряд — крупно и точно по центру: файлы, перемотка, пауза.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            TextButton(onClick = actions::previousFile, enabled = state.hasPrevious, contentPadding = big) {
                Text("⏮", style = bigText)
            }
            if (!state.isImage) {
                TextButton(onClick = { actions.seekBy(-30_000) }, contentPadding = big) { Text("−30", style = bigText) }
                TextButton(onClick = { actions.seekBy(-10_000) }, contentPadding = big) { Text("−10", style = bigText) }
            }
            // У фото ⏯ — пауза слайд-шоу.
            Button(onClick = actions::togglePlayback, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp)) {
                Text(if (playing) "⏸" else "▶", style = bigText)
            }
            if (!state.isImage) {
                TextButton(onClick = { actions.seekBy(10_000) }, contentPadding = big) { Text("+10", style = bigText) }
                TextButton(onClick = { actions.seekBy(30_000) }, contentPadding = big) { Text("+30", style = bigText) }
            }
            TextButton(onClick = actions::nextFile, enabled = state.hasNext, contentPadding = big) {
                Text("⏭", style = bigText)
            }
        }
        // Служебный ряд: вид слева, режимы по центру, переходы справа.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = actions::recenter, contentPadding = small) { Text("Центр") }
                TextButton(onClick = { actions.setZoom(state.zoom / ZOOM_STEP) }, contentPadding = small) { Text("−") }
                TextButton(onClick = { actions.setZoom(state.zoom * ZOOM_STEP) }, contentPadding = small) { Text("+") }
                // Из шлема 2D не включается: только пальцем, кнопкой на экране (как крестик).
                if (!state.stereo) TextButton(onClick = actions::toggleStereo, contentPadding = small) { Text("VR") }
            }
            // Режимы — короткими значками: выбранный подсвечен, подпись только у включённого.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val repeat = RepeatMode.entries.find { it.name == settings.repeatMode } ?: RepeatMode.Off
                FilterChip(
                    selected = repeat != RepeatMode.Off,
                    onClick = actions::cycleRepeat,
                    label = {
                        Text(
                            when (repeat) {
                                RepeatMode.Off -> "🔁"
                                RepeatMode.All -> "🔁 Папка"
                                RepeatMode.One -> "🔂 Файл"
                            },
                        )
                    },
                )
                FilterChip(selected = settings.shuffle, onClick = actions::toggleShuffle, label = { Text("🔀") })
                if (state.isImage) {
                    FilterChip(
                        selected = settings.slideshow,
                        onClick = actions::toggleSlideshow,
                        label = { Text("⏱ ${settings.slideshowSeconds} с") },
                    )
                }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                SheetChip(state, VrMenuState.Sheet.Queue, "▤ Очередь")
                SheetChip(state, VrMenuState.Sheet.View, "◐ Вид")
                FilledTonalButton(onClick = { actions.openMenu(VrMenuState.Tab.Home) }, contentPadding = small) {
                    Text("🏠")
                }
            }
        }
    }
}

@Composable
private fun SheetChip(state: VrMenuState, sheet: VrMenuState.Sheet, label: String) {
    FilterChip(
        selected = state.sheet == sheet,
        onClick = { state.sheet = if (state.sheet == sheet) null else sheet },
        label = { Text(label) },
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

package com.manfis.astramatic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.manfis.astramatic.model.Projection
import com.manfis.astramatic.model.StereoLayout
import com.manfis.astramatic.settings.AppSettings
import kotlin.math.roundToInt

@Composable
internal fun SheetContent(sheet: VrMenuState.Sheet, state: VrMenuState, actions: VrMenuActions) {
    when (sheet) {
        VrMenuState.Sheet.View -> ViewSheet(state, actions)
        VrMenuState.Sheet.Queue -> QueueSheet(state, actions)
    }
}

/** Быстрые настройки вида, как у Play'a: самое частое — в один взгляд, остальное — в «Формат». */
@Composable
internal fun ViewSheet(state: VrMenuState, actions: VrMenuActions) {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val format = state.format
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Проекция", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(88.dp))
            Projection.entries.forEach {
                FilterChip(
                    selected = format.projection == it,
                    onClick = { actions.setFormat(format.copy(projection = it)) },
                    label = { Text(projectionLabel(it)) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Стерео", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(88.dp))
            StereoLayout.entries.forEach {
                FilterChip(
                    selected = format.stereo == it,
                    onClick = { actions.setFormat(format.copy(stereo = it)) },
                    label = { Text(stereoLabel(it)) },
                )
            }
            if (!state.isImage) {
                Spacer(Modifier.width(16.dp))
                Text("Скорость", style = MaterialTheme.typography.labelLarge)
                SPEEDS.forEach { speed ->
                    FilterChip(
                        selected = state.speed == speed,
                        onClick = { actions.setSpeed(speed) },
                        label = { Text("${speed}×".replace(".0×", "×")) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.weight(1f)) {
                LabeledSlider(
                    title = "Яркость ${(settings.brightness * 100).roundToInt()}%",
                    hint = null,
                    value = settings.brightness,
                    range = 0.5f..1.5f,
                    step = 0.05f,
                ) { settings.brightness = it }
            }
            Box(Modifier.weight(1f)) {
                if (format.projection == Projection.Flat) {
                    LabeledSlider(
                        title = "Экран ${"%.1f".format(settings.screenHeight)} м",
                        hint = null,
                        value = settings.screenHeight,
                        range = 0.5f..8f,
                        step = 0.1f,
                    ) { settings.screenHeight = it }
                } else {
                    LabeledSlider(
                        title = "Приближение ${"%.1f".format(state.zoom)}×",
                        hint = null,
                        value = state.zoom,
                        range = MIN_ZOOM..MAX_ZOOM,
                        step = 0.1f,
                    ) { actions.setZoom(it) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = actions::resetFormat) { Text("Формат по имени файла") }
            TextButton(onClick = { actions.openMenu(VrMenuState.Tab.Video) }) { Text("Все настройки вида →") }
        }
    }
}

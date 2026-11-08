package com.manfis.astramatic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
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

/** Быстрые настройки вида: самое частое — в один взгляд, остальное — в «Формат». */
@Composable
internal fun ViewSheet(state: VrMenuState, actions: VrMenuActions) {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val format = state.format
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowLabel("Проекция")
            Segmented(Projection.entries, format.projection, ::projectionLabel, { actions.setFormat(format.copy(projection = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowLabel("Стерео")
            Segmented(StereoLayout.entries, format.stereo, ::stereoLabel, { actions.setFormat(format.copy(stereo = it)) })
            if (!state.isImage) {
                Gap(28.dp)
                SpeedStepper(state.speed, actions::setSpeed)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            LabeledSlider(
                title = "Яркость: ${(settings.brightness * 100).roundToInt()}%",
                hint = null,
                value = settings.brightness,
                range = 0.5f..1.5f,
                step = 0.05f,
                modifier = Modifier.weight(1f),
            ) { settings.brightness = it }
            if (format.projection == Projection.Flat) {
                LabeledSlider(
                    title = "Экран: ${"%.1f".format(settings.screenHeight)} м",
                    hint = null,
                    value = settings.screenHeight,
                    range = 0.5f..8f,
                    step = 0.1f,
                    modifier = Modifier.weight(1f),
                ) { settings.screenHeight = it }
            } else {
                LabeledSlider(
                    title = "Приближение: ${"%.1f".format(state.zoom)}×",
                    hint = null,
                    value = state.zoom,
                    range = MIN_ZOOM..MAX_ZOOM,
                    step = 0.1f,
                    modifier = Modifier.weight(1f),
                ) { actions.setZoom(it) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton("Формат по имени файла", actions::resetFormat, icon = Icons.Rounded.RestartAlt, style = ButtonStyle.Ghost)
            AppButton("Все настройки вида", { actions.openMenu(VrMenuState.Tab.Video) }, icon = Icons.Rounded.ChevronRight, style = ButtonStyle.Ghost)
        }
    }
}

@Composable
private fun RowLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(112.dp),
    )
}

/** Скорость: −, значение, +. Шесть значений сегментами не помещаются рядом со стерео. */
@Composable
internal fun SpeedStepper(speed: Float, onChange: (Float) -> Unit) {
    val index = SPEEDS.indexOf(speed).takeIf { it >= 0 } ?: SPEEDS.indexOf(1f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Скорость", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Gap(8.dp)
        AppIconButton(Icons.Rounded.Remove, "Медленнее", { onChange(SPEEDS[index - 1]) }, size = 44.dp, style = ButtonStyle.Tonal, enabled = index > 0)
        Text(speedLabel(speed), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, modifier = Modifier.width(64.dp))
        AppIconButton(Icons.Rounded.Add, "Быстрее", { onChange(SPEEDS[index + 1]) }, size = 44.dp, style = ButtonStyle.Tonal, enabled = index < SPEEDS.lastIndex)
    }
}

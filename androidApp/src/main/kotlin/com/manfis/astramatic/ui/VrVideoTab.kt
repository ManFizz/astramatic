package com.manfis.astramatic.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Panorama
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.manfis.astramatic.model.Projection
import com.manfis.astramatic.model.StereoLayout
import com.manfis.astramatic.settings.AppSettings
import kotlin.math.roundToInt

/** Настройки показа текущего видео. Выбранное вручную запоминается для этого файла. */
@Composable
internal fun VideoTab(state: VrMenuState, actions: VrMenuActions) {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val format = state.format

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(state.title ?: "", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)

        SectionTitle("Проекция")
        Segmented(Projection.entries, format.projection, ::projectionLabel, { actions.setFormat(format.copy(projection = it)) })
        SectionTitle("Стерео")
        Segmented(StereoLayout.entries, format.stereo, ::stereoLabel, { actions.setFormat(format.copy(stereo = it)) })

        if (format.projection == Projection.Dome180 || format.projection == Projection.Fisheye) {
            LabeledSlider(
                title = "Угол обзора видео: ${format.fovDeg}°",
                hint = "Если картинка растянута или сжата по краям. Объективы fisheye бывают на 190–220°.",
                value = format.fovDeg.toFloat(),
                range = 120f..240f,
                step = 5f,
            ) { actions.setFormat(format.copy(fovDeg = it.roundToInt())) }
        }
        if (format.projection != Projection.Flat) {
            LabeledSlider(
                title = "Наклон: ${format.tiltDeg}°",
                hint = "Если горизонт выше или ниже взгляда.",
                value = format.tiltDeg.toFloat(),
                range = -45f..45f,
                step = 1f,
            ) { actions.setFormat(format.copy(tiltDeg = it.roundToInt())) }
        }
        if (format.projection == Projection.Flat) {
            SectionTitle("Экран кинотеатра")
            LabeledSlider(
                title = "Высота экрана: ${"%.1f".format(settings.screenHeight)} м",
                hint = null,
                value = settings.screenHeight,
                range = 0.5f..8f,
                step = 0.1f,
            ) { settings.screenHeight = it }
            LabeledSlider(
                title = "Расстояние: ${"%.1f".format(settings.screenDistance)} м",
                hint = "Размер и расстояние — общие для всех плоских видео.",
                value = settings.screenDistance,
                range = 1f..12f,
                step = 0.1f,
            ) { settings.screenDistance = it }
            LabeledSlider(
                title = "Изгиб экрана: ${(settings.screenCurve * 100).roundToInt()}%",
                hint = "100% — экран дугой вокруг вас, края на том же расстоянии, что и центр.",
                value = settings.screenCurve,
                range = 0f..1f,
                step = 0.05f,
            ) { settings.screenCurve = it }
        }

        Divider(Modifier.padding(vertical = 4.dp))
        LabeledSlider(
            title = "Приближение: ${"%.1f".format(state.zoom)}×",
            hint = "Только для этого файла. Меню не приближается.",
            value = state.zoom,
            range = MIN_ZOOM..MAX_ZOOM,
            step = 0.1f,
        ) { actions.setZoom(it) }
        LabeledSlider(
            title = "Яркость: ${(settings.brightness * 100).roundToInt()}%",
            hint = null,
            value = settings.brightness,
            range = 0.5f..1.5f,
            step = 0.05f,
        ) { settings.brightness = it }
        if (!state.isImage) {
            SectionTitle("Скорость")
            Segmented(SPEEDS, state.speed, ::speedLabel, actions::setSpeed)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton("Рецентр", actions::recenterAfterCountdown, icon = Icons.Rounded.CenterFocusStrong)
            AppButton("Формат по имени файла", actions::resetFormat, icon = Icons.Rounded.RestartAlt)
        }
        if (state.isImage) {
            var note by remember { mutableStateOf<String?>(null) }
            AppButton("Сделать окружением", { note = actions.setAsEnvironment() }, icon = Icons.Rounded.Panorama)
            Hint(note ?: "Фото-панорама 360° станет окружением вокруг вас (Настройки → Окружение).")
        }
        Hint("Формат угадывается по имени файла. Выбранный здесь запоминается для этого файла.")
    }
}

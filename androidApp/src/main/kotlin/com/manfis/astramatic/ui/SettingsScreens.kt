package com.manfis.astramatic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.InputChip
import androidx.compose.foundation.horizontalScroll
import com.manfis.astramatic.settings.ActionGroup
import androidx.compose.material3.FilterChip
import com.manfis.astramatic.model.Projection
import com.manfis.astramatic.model.StereoLayout
import com.manfis.astramatic.model.VideoFormat
import com.manfis.astramatic.player.Environment
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.manfis.astramatic.settings.AppSettings
import com.manfis.astramatic.settings.KeyBindings
import com.manfis.astramatic.settings.RemoteAction
import kotlin.math.roundToInt

/** Общие настройки плеера. */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Воспроизведение", style = MaterialTheme.typography.titleMedium)
        SwitchRow(
            title = "Продолжать с места остановки",
            hint = "Видео откроется там, где вы его закрыли.",
            checked = settings.resumePlayback,
        ) { settings.resumePlayback = it }
        SwitchRow(
            title = "Следующее видео автоматически",
            hint = "После конца видео открывается следующее из той же папки.",
            checked = settings.autoplayNext,
        ) { settings.autoplayNext = it }

        if (settings.slideshowSeconds > 0) {
            Text("Слайд-шоу фото: ${settings.slideshowSeconds} с на кадр")
        } else {
            Text("Слайд-шоу фото: выключено")
        }
        Slider(
            value = settings.slideshowSeconds.toFloat(),
            onValueChange = { settings.slideshowSeconds = it.roundToInt() },
            valueRange = 0f..30f,
        )

        HorizontalDivider()

        Text("Окружение", style = MaterialTheme.typography.titleMedium)
        Text(
            "Что вокруг в меню, у плоского экрана и за видео 180°.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Environment.entries.forEach { environment ->
                FilterChip(
                    selected = settings.environment == environment.name,
                    onClick = { settings.environment = environment.name },
                    label = { Text(environment.title) },
                )
            }
        }
        if (settings.environment == Environment.Custom.name) {
            Text(
                "Откройте фото-панораму 360° и во вкладке «Видео» нажмите «Сделать окружением».",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SwitchRow(
            title = "Подсветка в кинозале",
            hint = "Стены мягко светятся цветом кадра плоского видео.",
            checked = settings.ambientLight,
        ) { settings.ambientLight = it }
        SwitchRow(
            title = "Режим «лёжа»",
            hint = "Рецентр поворачивает картинку и вверх-вниз: можно смотреть в потолок. Меню встаёт туда же.",
            checked = settings.recenterPitch,
        ) { settings.recenterPitch = it }

        HorizontalDivider()

        Text("Формат, если не распознан по имени", style = MaterialTheme.typography.titleMedium)
        Text(
            "Теги в имени вроде _180_LR, _360, _TB, _3dh определяются автоматически.",
            style = MaterialTheme.typography.bodySmall,
        )
        DefaultFormatPicker("Видео", settings.defaultVideoFormat) { settings.defaultVideoFormat = it }
        DefaultFormatPicker("Фото", settings.defaultImageFormat) { settings.defaultImageFormat = it }

        HorizontalDivider()

        Text("Шлем без кнопки", style = MaterialTheme.typography.titleMedium)
        SwitchRow(
            title = "Нажатие взглядом",
            hint = "Задержите точку на кнопке меню — она нажмётся. Вокруг точки виден отсчёт.",
            checked = settings.dwellClick,
        ) { settings.dwellClick = it }
        if (settings.dwellClick) {
            Text("Время задержки: ${"%.1f".format(settings.dwellMs / 1000f)} с")
            Slider(
                value = settings.dwellMs.toFloat(),
                onValueChange = { settings.dwellMs = (it / 100).roundToInt() * 100 },
                valueRange = 600f..3000f,
            )
        }
    }
}

@Composable
private fun DefaultFormatPicker(title: String, encoded: String, onChange: (String) -> Unit) {
    val format = VideoFormat.decode(encoded) ?: VideoFormat()
    Text(title, style = MaterialTheme.typography.bodyLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Projection.entries.forEach {
            FilterChip(
                selected = format.projection == it,
                onClick = { onChange(format.copy(projection = it).encode()) },
                label = { Text(projectionLabel(it)) },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StereoLayout.entries.forEach {
            FilterChip(
                selected = format.stereo == it,
                onClick = { onChange(format.copy(stereo = it).encode()) },
                label = { Text(stereoLabel(it)) },
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Пульт: что нажато последним и назначения действий, как в играх.
 * Пока [VrMenuState.capturingKey] не null, следующая кнопка (или отклонённая ось) уходит этому действию —
 * её ловит активность плеера.
 */
@Composable
fun RemoteScreen(state: VrMenuState) {
    val context = LocalContext.current
    val bindings = remember { AppSettings.get(context).keys }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Последнее, что прислал пульт", style = MaterialTheme.typography.titleMedium)
        Text(
            state.lastKey ?: "Нажмите кнопку или подвигайте стик",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        val capturing = state.capturingKey
        if (capturing != null) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "«${capturing.title}»: ${captureHint(capturing)}",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    TextButton(onClick = { state.capturingKey = null }) { Text("Отмена") }
                }
            }
        }

        ActionGroup.entries.forEach { group ->
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text(group.title, style = MaterialTheme.typography.titleMedium)
            RemoteAction.entries.filter { it.group == group }.forEach { action ->
                BindingRow(action, bindings, onAdd = { state.capturingKey = action })
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { bindings.resetAll() }) { Text("Как было по умолчанию") }
            OutlinedButton(onClick = { bindings.clearAll() }) { Text("Очистить всё") }
        }
        Text(
            "Если ось работает в обратную сторону — нажмите на неё (⇅), ✕ рядом убирает назначение. " +
                "Не назначенные стрелки и OK по-прежнему ходят по меню, «Назад» системы закрывает его. " +
                "Колесо мыши (пульты в режиме мыши) прокручивает меню.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun BindingRow(action: RemoteAction, bindings: KeyBindings, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(action.title, style = MaterialTheme.typography.bodyLarge)
            val keys = bindings.keysOf(action)
            val axes = bindings.axesOf(action)
            if (keys.isEmpty() && axes.isEmpty()) {
                Text("не назначено", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    keys.forEach { key ->
                        InputChip(
                            selected = false,
                            onClick = { bindings.removeKey(action, key) },
                            label = { Text(KeyBindings.keyName(key)) },
                            trailingIcon = { Text("✕") },
                        )
                    }
                    // У оси два действия: развернуть направление и убрать.
                    axes.forEach { axis ->
                        InputChip(
                            selected = axis.inverted,
                            onClick = { bindings.invertAxis(action, axis) },
                            label = { Text(axis.title) },
                            leadingIcon = { Text("⇅") },
                        )
                        TextButton(onClick = { bindings.removeAxis(action, axis) }) { Text("✕") }
                    }
                }
            }
        }
        TextButton(onClick = onAdd) { Text(if (action.isAxis) "+ Ось" else "+ Назначить") }
    }
}

/** Что сделать при назначении. Направление важно: так определяется, куда ось работает «вперёд». */
private fun captureHint(action: RemoteAction): String = when (action) {
    RemoteAction.AxisScroll, RemoteAction.AxisNavVertical -> "отклоните стик ВНИЗ…"
    RemoteAction.AxisSeek, RemoteAction.AxisNavHorizontal -> "отклоните стик ВПРАВО (вперёд)…"
    RemoteAction.AxisZoom -> "отклоните стик ВВЕРХ (приблизить)…"
    else -> "нажмите кнопку или курок…"
}

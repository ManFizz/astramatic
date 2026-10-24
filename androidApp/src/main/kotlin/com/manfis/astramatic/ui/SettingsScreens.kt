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
 * Кнопки пульта: что нажато последним и назначения действий, как в играх.
 * Пока [capturing] не null, следующая нажатая кнопка уходит этому действию (ловит активность).
 */
@Composable
fun RemoteScreen(state: VrMenuState) {
    val context = LocalContext.current
    val keys = remember { AppSettings.get(context).keys }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Последняя нажатая кнопка", style = MaterialTheme.typography.titleMedium)
        Text(
            state.lastKey ?: "Нажмите любую кнопку на пульте",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        val capturing = state.capturingKey
        if (capturing != null) {
            Text(
                "Нажмите на пульте кнопку для «${capturing.title}»…",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            TextButton(onClick = { state.capturingKey = null }) { Text("Отмена") }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        RemoteAction.entries.forEach { action ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(action.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        keys.keysOf(action).joinToString(", ") { KeyBindings.keyName(it) }.ifEmpty { "не назначено" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { state.capturingKey = action }) { Text("+ Кнопка") }
                TextButton(onClick = { keys.clear(action) }) { Text("Очистить") }
            }
        }

        OutlinedButton(onClick = { keys.resetAll() }) { Text("Вернуть кнопки по умолчанию") }
        Text(
            "Пока открыто меню, стрелки и OK ходят по нему. «Назад» на пульте закрывает меню и полоску. " +
                "Стик и крестовина геймпада работают как стрелки, клик мыши — как кнопка Cardboard.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

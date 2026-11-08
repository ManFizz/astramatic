package com.manfis.astramatic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SwapVert
import com.manfis.astramatic.player.MAX_MENU_DISTANCE
import com.manfis.astramatic.player.MAX_MENU_SIZE
import com.manfis.astramatic.player.MIN_MENU_DISTANCE
import com.manfis.astramatic.player.MIN_MENU_SIZE

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
import androidx.compose.material3.Surface
import androidx.compose.foundation.horizontalScroll
import com.manfis.astramatic.settings.ActionGroup
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
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionTitle("Воспроизведение")
        SwitchRow(
            title = "Продолжать с места остановки",
            hint = "Видео откроется там, где вы его закрыли.",
            checked = settings.resumePlayback,
        ) { settings.resumePlayback = it }
        SwitchRow(
            title = "История просмотра",
            hint = "Недавние файлы — на главной, и в телефоне, и в шлеме.",
            checked = settings.keepHistory,
        ) { settings.keepHistory = it }
        SwitchRow(
            title = "Следующее видео автоматически",
            hint = "После конца видео открывается следующее из той же папки.",
            checked = settings.autoplayNext,
        ) { settings.autoplayNext = it }

        // Включается кнопкой в плеере, здесь — только скорость.
        LabeledSlider(
            title = "Слайд-шоу фото: ${settings.slideshowSeconds} с на кадр",
            hint = null,
            value = settings.slideshowSeconds.toFloat(),
            range = 2f..30f,
            step = 1f,
        ) { settings.slideshowSeconds = it.roundToInt().coerceAtLeast(2) }

        SectionTitle("Окружение")
        Hint("Что вокруг в меню, у плоского экрана и за видео 180°.")
        val environment = Environment.entries.find { it.name == settings.environment } ?: Environment.Cinema
        Segmented(Environment.entries, environment, { it.title }, { settings.environment = it.name })
        if (settings.environment == Environment.Custom.name) {
            Hint("Откройте фото-панораму 360° и в «Формате» нажмите «Сделать окружением».")
        }
        SwitchRow(
            title = "Подсветка в кинозале",
            hint = "Стены мягко светятся цветом кадра плоского видео.",
            checked = settings.ambientLight,
        ) { settings.ambientLight = it }
        SwitchRow(
            title = "Глубина меню как у видео",
            hint = "Глазам не нужно перестраиваться при переводе взгляда с видео на меню. У стерео глубина измеряется по кадру рядом с панелью.",
            checked = settings.menuDepthAuto,
        ) { settings.menuDepthAuto = it }
        if (!settings.menuDepthAuto) {
            LabeledSlider(
                title = "Расстояние до меню: ${"%.1f".format(settings.menuDistance)} м",
                hint = "Дальше — меньше сводить глаза при переходе от видео к меню. Размер меню на глаз тот же.",
                value = settings.menuDistance,
                range = MIN_MENU_DISTANCE..MAX_MENU_DISTANCE,
                step = 0.5f,
            ) { settings.menuDistance = it }
        }
        LabeledSlider(
            title = "Размер меню: ${(settings.menuSize * 100).roundToInt()}%",
            hint = "В шлеме меню растягивается за уголки, а перетаскивается за полоску под ним: наведите точку и держите нажатие.",
            value = settings.menuSize,
            range = MIN_MENU_SIZE..MAX_MENU_SIZE,
            step = 0.05f,
        ) { settings.menuSize = it }
        SwitchRow(
            title = "Поправка по компасу",
            hint = "Центр не уплывает со временем. Если рядом магниты или железо и картинка медленно плывёт — выключите.",
            checked = settings.compassCorrection,
        ) { settings.compassCorrection = it }
        SwitchRow(
            title = "Режим «лёжа»",
            hint = "Рецентр поворачивает картинку и вверх-вниз: можно смотреть в потолок. Меню встаёт туда же.",
            checked = settings.recenterPitch,
        ) { settings.recenterPitch = it }

        SectionTitle("Формат, если не распознан по имени")
        Hint("Теги в имени вроде _180_LR, _360, _TB, _3dh определяются автоматически.")
        DefaultFormatPicker("Видео", settings.defaultVideoFormat) { settings.defaultVideoFormat = it }
        DefaultFormatPicker("Фото", settings.defaultImageFormat) { settings.defaultImageFormat = it }

        SectionTitle("Формат частично распознанных медиа")
        Hint(
            "Известно, что это VR, но не какой: тег vr, virtual reality, virtual-reality и подобные у файла в Darkframe " +
                "или «vr» в имени — без 180, 360, SBS. Если раскладка стерео указана, она остаётся своей.",
        )
        DefaultFormatPicker("VR без подробностей", settings.partialFormat) { settings.partialFormat = it }

        SectionTitle("Шлем без кнопки")
        SwitchRow(
            title = "Нажатие взглядом",
            hint = "Задержите точку на кнопке меню — она нажмётся. Вокруг точки виден отсчёт.",
            checked = settings.dwellClick,
        ) { settings.dwellClick = it }
        if (settings.dwellClick) {
            LabeledSlider(
                title = "Время задержки: ${"%.1f".format(settings.dwellMs / 1000f)} с",
                hint = null,
                value = settings.dwellMs.toFloat(),
                range = 600f..3000f,
                step = 100f,
            ) { settings.dwellMs = it.roundToInt() }
        }
    }
}

@Composable
private fun DefaultFormatPicker(title: String, encoded: String, onChange: (String) -> Unit) {
    val format = VideoFormat.decode(encoded) ?: VideoFormat()
    Text(title, style = MaterialTheme.typography.bodyLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Segmented(Projection.entries, format.projection, ::projectionLabel, { onChange(format.copy(projection = it).encode()) })
        Segmented(StereoLayout.entries, format.stereo, ::stereoLabel, { onChange(format.copy(stereo = it).encode()) })
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
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("Последнее, что прислал пульт")
        Text(
            state.lastKey ?: "Нажмите кнопку или подвигайте стик",
            style = MaterialTheme.typography.titleLarge,
            color = if (state.lastKey != null) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ActionGroup.entries.forEach { group ->
            SectionTitle(group.title, Modifier.padding(top = 8.dp))
            RemoteAction.entries.filter { it.group == group }.forEach { action ->
                BindingRow(
                    action,
                    bindings,
                    capturing = state.capturingKey == action,
                    onAdd = { state.capturingKey = action },
                    onCancel = { state.capturingKey = null },
                )
            }
        }

        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton("Как было по умолчанию", { bindings.resetAll() }, icon = Icons.Rounded.RestartAlt)
            AppButton("Очистить всё", { bindings.clearAll() }, icon = Icons.Rounded.DeleteSweep)
        }
        Hint(
            "Если ось работает в обратную сторону — нажмите на неё, крестик рядом убирает назначение. " +
                "Одну ось можно назначить и на меню, и на просмотр: стрелки и прокрутка работают, пока открыто меню, " +
                "перемотка и приближение — когда закрыто. " +
                "Не назначенные стрелки и OK по-прежнему ходят по меню, «Назад» системы закрывает его. " +
                "Колесо мыши (пульты в режиме мыши) прокручивает меню.",
        )
    }
}

@Composable
private fun BindingRow(
    action: RemoteAction,
    bindings: KeyBindings,
    capturing: Boolean,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
) {
    // Подсказка — прямо в строке действия: её видно там, куда только что нажали, как бы ни был прокручен список.
    if (capturing) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .border(1.dp, Accent, MaterialTheme.shapes.medium)
                .padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(action.title, style = MaterialTheme.typography.bodyLarge)
                Text(captureHint(action).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium, color = Accent)
            }
            AppButton("Отмена", onCancel, style = ButtonStyle.Ghost)
        }
        return
    }
    SettingRow(action.title, hint = null) {
        val keys = bindings.keysOf(action)
        val axes = bindings.axesOf(action)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (keys.isEmpty() && axes.isEmpty()) {
                Hint("не назначено")
            }
            keys.forEach { key ->
                AppButton(KeyBindings.keyName(key), { bindings.removeKey(action, key) }, icon = Icons.Rounded.Close, height = 40.dp)
            }
            // У оси два действия: развернуть направление и убрать.
            axes.forEach { axis ->
                AppButton(axis.title, { bindings.invertAxis(action, axis) }, icon = Icons.Rounded.SwapVert, selected = axis.inverted, height = 40.dp)
                AppIconButton(Icons.Rounded.Close, "Убрать ось", { bindings.removeAxis(action, axis) }, size = 40.dp)
            }
            AppButton(if (action.isAxis) "Ось" else "Назначить", onAdd, icon = Icons.Rounded.Add, style = ButtonStyle.Ghost, height = 40.dp)
        }
    }
}

/** Что сделать при назначении. Направление важно: так определяется, куда ось работает «вперёд». */
private fun captureHint(action: RemoteAction): String = when (action) {
    RemoteAction.AxisScroll, RemoteAction.AxisNavVertical -> "отклоните стик ВНИЗ…"
    RemoteAction.AxisSeek, RemoteAction.AxisNavHorizontal -> "отклоните стик ВПРАВО (вперёд)…"
    RemoteAction.AxisZoom -> "отклоните стик ВВЕРХ (приблизить)…"
    else -> "нажмите кнопку или курок…"
}

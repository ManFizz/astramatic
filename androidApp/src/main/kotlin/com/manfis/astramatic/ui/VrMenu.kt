package com.manfis.astramatic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.manfis.astramatic.settings.AppSettings
import kotlin.math.roundToInt
import com.manfis.astramatic.headset.HeadsetScreen
import com.manfis.astramatic.player.Playlist
import com.manfis.astramatic.settings.RemoteAction
import com.manfis.astramatic.model.Projection
import com.manfis.astramatic.model.StereoLayout
import com.manfis.astramatic.model.VideoFormat
import kotlinx.coroutines.delay

/** Состояние интерфейса плеера; живёт в активности, поэтому переживает переключение VR ⇄ 2D. */
class VrMenuState {
    /** Компактная полоска управления воспроизведением или большое меню. */
    enum class Mode { Controls, Menu }

    enum class Tab { Video, Files, Headset, Remote, Settings }

    var mode by mutableStateOf(Mode.Menu)
    var tab by mutableStateOf(Tab.Files)

    /** Имя открытого видео, null — видео ещё не выбрано. */
    var title by mutableStateOf<String?>(null)
    var format by mutableStateOf(VideoFormat())
    var stereo by mutableStateOf(true)

    var hasPrevious by mutableStateOf(false)
    var hasNext by mutableStateOf(false)

    /** Открыто фото, а не видео: без перемотки и скорости. */
    var isImage by mutableStateOf(false)
    var zoom by mutableStateOf(1f)
    var speed by mutableStateOf(1f)

    /** Сообщение приложения (ошибка и т. п.) — показывается вверху меню, а не системной всплывашкой. */
    var notice by mutableStateOf<String?>(null)

    /** Последняя кнопка пульта: что именно он присылает. */
    var lastKey by mutableStateOf<String?>(null)

    /** Ждём кнопку пульта для этого действия. */
    var capturingKey by mutableStateOf<RemoteAction?>(null)

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

    /** Забыть ручной выбор и вернуть формат, угаданный по имени файла. */
    fun resetFormat()
    fun toggleStereo()
    fun recenter()
    fun setZoom(zoom: Float)
    fun setSpeed(speed: Float)

    /** Текущее фото — в окружение (своя панорама). Возвращает, что получилось, для подписи. */
    fun setAsEnvironment(): String
    fun play(playlist: Playlist)
    fun previousFile()
    fun nextFile()
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
            if (!state.isImage) {
                Text(
                    "${formatTime(dragging?.toLong() ?: position)} / ${formatTime(duration)}",
                    style = MaterialTheme.typography.bodyMedium,
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
            Spacer(Modifier.height(12.dp))
        }
        // Три зоны: служебные кнопки слева и справа равной ширины, перемотка и пауза — точно по центру.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = actions::recenter, contentPadding = compact) { Text("Центр") }
                TextButton(onClick = { actions.setZoom(state.zoom / ZOOM_STEP) }, contentPadding = compact) { Text("−") }
                TextButton(onClick = { actions.setZoom(state.zoom * ZOOM_STEP) }, contentPadding = compact) { Text("+") }
                // Из шлема 2D не включается: только пальцем, кнопкой на экране (как крестик).
                if (!state.stereo) TextButton(onClick = actions::toggleStereo, contentPadding = compact) { Text("VR") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = actions::previousFile, enabled = state.hasPrevious, contentPadding = compact) { Text("⏮") }
                if (!state.isImage) {
                    TextButton(onClick = { actions.seekBy(-30_000) }, contentPadding = compact) { Text("−30") }
                    TextButton(onClick = { actions.seekBy(-10_000) }, contentPadding = compact) { Text("−10") }
                }
                // У фото ⏯ — слайд-шоу.
                Button(onClick = actions::togglePlayback, contentPadding = compact) { Text(if (playing) "⏸" else "▶") }
                if (!state.isImage) {
                    TextButton(onClick = { actions.seekBy(10_000) }, contentPadding = compact) { Text("+10") }
                    TextButton(onClick = { actions.seekBy(30_000) }, contentPadding = compact) { Text("+30") }
                }
                TextButton(onClick = actions::nextFile, enabled = state.hasNext, contentPadding = compact) { Text("⏭") }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = { actions.openMenu(VrMenuState.Tab.Video) }, contentPadding = compact) {
                    Text("☰ Меню")
                }
            }
        }
    }
}

// --- Меню -----------------------------------------------------------------------------------

@Composable
private fun MenuContent(state: VrMenuState, actions: VrMenuActions) {
    Column(Modifier.fillMaxSize()) {
        state.notice?.let { notice ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
            ) {
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(notice, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { state.notice = null }) { Text("✕") }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Вкладки прокручиваются вбок, если не влезают; кнопки справа всегда видны.
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.title != null) TabChip(state, VrMenuState.Tab.Video, "🎬 Видео")
                TabChip(state, VrMenuState.Tab.Files, "📁 Файлы")
                TabChip(state, VrMenuState.Tab.Headset, "👓 Шлем")
                TabChip(state, VrMenuState.Tab.Remote, "🎮 Пульт")
                TabChip(state, VrMenuState.Tab.Settings, "⚙ Настройки")
            }
            if (state.title != null) Button(onClick = actions::hide) { Text("К видео") }
            // Выйти из VR и включить 2D можно только пальцем — крестиком и кнопкой на экране.
            if (!state.stereo) {
                OutlinedButton(onClick = actions::toggleStereo) { Text("VR") }
                TextButton(onClick = actions::exit) { Text("Выход") }
            }
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
                VrMenuState.Tab.Remote -> RemoteScreen(state)
                VrMenuState.Tab.Settings -> SettingsScreen()
            }
        }
    }
}

@Composable
private fun TabChip(state: VrMenuState, tab: VrMenuState.Tab, label: String) {
    FilterChip(selected = state.tab == tab, onClick = { state.tab = tab }, label = { Text(label) })
}

/** Настройки показа текущего видео. Выбранное вручную запоминается для этого файла. */
@Composable
private fun VideoTab(state: VrMenuState, actions: VrMenuActions) {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val format = state.format

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(state.title ?: "", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)

        Text("Проекция", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Projection.entries.forEach {
                FilterChip(
                    selected = format.projection == it,
                    onClick = { actions.setFormat(format.copy(projection = it)) },
                    label = { Text(projectionLabel(it)) },
                )
            }
        }
        Text("Стерео", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StereoLayout.entries.forEach {
                FilterChip(
                    selected = format.stereo == it,
                    onClick = { actions.setFormat(format.copy(stereo = it)) },
                    label = { Text(stereoLabel(it)) },
                )
            }
        }

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
            Text("Экран кинотеатра", style = MaterialTheme.typography.titleMedium)
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

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
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
            Text("Скорость", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SPEEDS.forEach { speed ->
                    FilterChip(
                        selected = state.speed == speed,
                        onClick = { actions.setSpeed(speed) },
                        label = { Text("${speed}×".replace(".0×", "×")) },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions::recenter) { Text("Рецентр") }
            OutlinedButton(onClick = actions::resetFormat) { Text("Формат по имени файла") }
        }
        if (state.isImage) {
            var note by remember { mutableStateOf<String?>(null) }
            OutlinedButton(onClick = { note = actions.setAsEnvironment() }) { Text("Сделать окружением") }
            Text(
                note ?: "Фото-панорама 360° станет окружением вокруг вас (Настройки → Окружение).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "Формат угадывается по имени файла. Выбранный здесь запоминается для этого файла.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun LabeledSlider(
    title: String,
    hint: String?,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = value.coerceIn(range),
            onValueChange = { onChange((it / step).roundToInt() * step) },
            valueRange = range,
        )
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall)
    }
}

private const val ZOOM_STEP = 1.15f
const val MIN_ZOOM = 0.5f
const val MAX_ZOOM = 4f
private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

private fun formatTime(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

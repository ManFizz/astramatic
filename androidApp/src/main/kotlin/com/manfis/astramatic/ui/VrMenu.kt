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
import com.manfis.astramatic.player.PlaylistItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.manfis.astramatic.settings.RemoteAction
import com.manfis.astramatic.settings.RepeatMode
import com.manfis.astramatic.model.Projection
import com.manfis.astramatic.model.StereoLayout
import com.manfis.astramatic.model.VideoFormat
import kotlinx.coroutines.delay

/** Состояние интерфейса плеера; живёт в активности, поэтому переживает переключение VR ⇄ 2D. */
class VrMenuState {
    /** Компактная полоска управления воспроизведением или большое меню. */
    enum class Mode { Controls, Menu }

    /** Home — главная с файлами; Video — формат текущего видео; остальные — настройки. */
    enum class Tab { Home, Video, Headset, Remote, Settings }

    var mode by mutableStateOf(Mode.Menu)
    var tab by mutableStateOf(Tab.Home)

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

    /** Что раскрыто над полоской плеера: быстрые настройки вида или очередь; null — ничего. */
    enum class Sheet { View, Queue }

    var sheet by mutableStateOf<Sheet?>(null)

    /** Очередь — файлы папки, из которой открыт текущий, и номер текущего. */
    var queue by mutableStateOf<List<PlaylistItem>>(emptyList())
    var queueIndex by mutableStateOf(0)

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

    /** Повтор: выкл → папка → файл. */
    fun cycleRepeat()
    fun toggleShuffle()
    fun toggleSlideshow()

    /** Включить файл очереди с этим номером. */
    fun playQueueItem(index: Int)
    fun headsetSaved()
    fun openMenu(tab: VrMenuState.Tab)
    fun hide()
    fun exit()
}

/** Высота полоски управления в шлеме: столько пикселей сверху виртуального дисплея показывает рендерер. */
val ControlsHeightDp = 240.dp

/** Высота панели над полоской (вид, очередь); вместе с полоской рендерер показывает 490 dp сверху. */
val SheetHeightDp = 250.dp

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

@Composable
private fun SheetContent(sheet: VrMenuState.Sheet, state: VrMenuState, actions: VrMenuActions) {
    when (sheet) {
        VrMenuState.Sheet.View -> ViewSheet(state, actions)
        VrMenuState.Sheet.Queue -> QueueSheet(state, actions)
    }
}

/** Быстрые настройки вида, как у Play'a: самое частое — в один взгляд, остальное — в «Формат». */
@Composable
private fun ViewSheet(state: VrMenuState, actions: VrMenuActions) {
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

/** Очередь — лентой превью: где мы и что дальше; нажатие включает файл. */
@Composable
private fun QueueSheet(state: VrMenuState, actions: VrMenuActions) {
    val queue = state.queue
    val listState = rememberLazyListState()
    // Текущий — всегда в кадре, чуть правее левого края: видно и предыдущий.
    LaunchedEffect(state.queueIndex, queue.size) {
        if (queue.isNotEmpty()) listState.animateScrollToItem((state.queueIndex - 1).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Очередь", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(12.dp))
            Text(
                if (queue.isEmpty()) "" else "${state.queueIndex + 1} из ${queue.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(queue) { index, item ->
                QueueCard(item, current = index == state.queueIndex) { actions.playQueueItem(index) }
            }
        }
    }
}

@Composable
private fun QueueCard(item: PlaylistItem, current: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .width(176.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(99.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(if (current) Modifier.border(3.dp, accent, RoundedCornerShape(10.dp)) else Modifier),
        ) {
            if (item.thumbnail != null) {
                AsyncImage(
                    model = item.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    if (item.isImage) "🖼" else "🎬",
                    Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            if (current) {
                Surface(
                    color = accent,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(bottomEnd = 10.dp),
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    Text("▶ сейчас", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
        Text(
            item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (current) accent else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}

// --- Меню -----------------------------------------------------------------------------------

/**
 * Главная — файлы, как в Play'a: с неё всё начинается и на неё возвращает «Назад».
 * Настройки (общие, шлем, пульт) — отдельный раздел, формат текущего видео — тоже.
 */
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
        val home = state.tab == VrMenuState.Tab.Home
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (home) {
                Text("AstraMatic", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
                Spacer(Modifier.weight(1f))
            } else {
                TextButton(onClick = { state.tab = VrMenuState.Tab.Home }) { Text("← Главная") }
                // Разделы настроек — вкладками; прокручиваются вбок, если не влезают.
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.tab == VrMenuState.Tab.Video) {
                        Text("Формат видео", style = MaterialTheme.typography.titleMedium)
                    } else {
                        TabChip(state, VrMenuState.Tab.Settings, "⚙ Общие")
                        TabChip(state, VrMenuState.Tab.Headset, "👓 Шлем")
                        TabChip(state, VrMenuState.Tab.Remote, "🎮 Пульт")
                    }
                }
            }
            if (state.title != null) {
                if (home) OutlinedButton(onClick = { state.tab = VrMenuState.Tab.Video }) { Text("Формат") }
                Button(onClick = actions::hide) { Text("▶ К видео") }
            }
            if (home) FilledTonalButton(onClick = { state.tab = VrMenuState.Tab.Settings }) { Text("⚙ Настройки") }
            // Выйти из VR и включить 2D можно только пальцем — крестиком и кнопкой на экране.
            if (!state.stereo) {
                OutlinedButton(onClick = actions::toggleStereo) { Text("VR") }
                TextButton(onClick = actions::exit) { Text("Выход") }
            }
        }
        HorizontalDivider()
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (state.tab) {
                VrMenuState.Tab.Home -> LibraryApp(
                    onPlay = actions::play,
                    state = state.library,
                    embedded = true,
                    allowEditing = !state.stereo,
                )
                VrMenuState.Tab.Video -> VideoTab(state, actions)
                VrMenuState.Tab.Headset -> HeadsetScreen(
                    onBack = { state.tab = VrMenuState.Tab.Home },
                    onSaved = actions::headsetSaved,
                    closeOnSave = false,
                    // В шлеме — на лету, вокруг калибровочная комната.
                    live = state.stereo,
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

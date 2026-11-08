package com.manfis.astramatic.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.manfis.astramatic.library.DarkframeServer
import com.manfis.astramatic.library.DesktopServer
import com.manfis.astramatic.library.MediaLibrary
import com.manfis.astramatic.library.MediaSourceRef
import com.manfis.astramatic.library.SmbServer
import com.manfis.astramatic.player.HistoryEntry

/** Заголовок и действия хаба: что отложено и куда из него можно уйти. */
class HubChrome(
    val parkedTitle: String?,
    val videoOpen: Boolean,
    val onResume: () -> Unit,
    val onToVideo: () -> Unit,
    val onFormat: () -> Unit,
    val onSettings: () -> Unit,
)

/** Откуда смотреть: телефон, браузер, компьютер, Darkframe. */
internal class HubSource(
    val icon: ImageVector,
    val title: String,
    val subtitle: String?,
    /** Изменить или удалить; у телефона нет. */
    val onEdit: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/** Добавить источник: в шлеме есть клавиатура. */
internal class HubAdd(val onDesktop: () -> Unit, val onFolder: () -> Unit, val onDarkframe: () -> Unit)

@Composable
internal fun rememberHubSources(
    library: MediaLibrary,
    onOpen: (MediaSourceRef, String) -> Unit,
    onEditServer: (SmbServer) -> Unit,
    onEditDesktop: (DesktopServer) -> Unit,
    onEditDarkframe: (DarkframeServer) -> Unit,
    onBrowser: () -> Unit,
): List<HubSource> {
    val context = LocalContext.current
    return remember(context) {
        buildList {
            add(HubSource(Icons.Rounded.PhoneAndroid, "Этот телефон", "Видео и фото") { onOpen(MediaSourceRef.Phone, "Телефон") })
            add(HubSource(Icons.Rounded.Language, "Браузер", "Сайты и поиск", onClick = onBrowser))
            library.servers.all().forEach {
                add(HubSource(Icons.Rounded.Folder, it.title, "Папка Windows", onEdit = { onEditServer(it) }) { onOpen(MediaSourceRef.Smb(it.id), it.title) })
            }
            library.desktops.all().forEach {
                add(HubSource(Icons.Rounded.Computer, it.name, "AstraMatic на ПК", onEdit = { onEditDesktop(it) }) { onOpen(MediaSourceRef.Desktop(it.id), it.name) })
            }
            library.darkframes.all().forEach {
                add(HubSource(Icons.Rounded.PhotoLibrary, it.name, "Darkframe", onEdit = { onEditDarkframe(it) }) { onOpen(MediaSourceRef.Darkframe(it.id), it.name) })
            }
        }
    }
}

private val TileShape = RoundedCornerShape(20.dp)
private const val TILE_COLUMNS = 3
private const val TILE_ROWS = 2

/**
 * Хаб в шлеме: всё видно сразу, крутить нечего. Отдельные стеклянные плитки парят над окружением:
 * слева откуда смотреть, справа то, что смотрели последним, и недавние. Под взглядом плитка
 * чуть приподнимается и получает светлую кромку.
 */
@Composable
internal fun HubHome(
    chrome: HubChrome,
    sources: List<HubSource>,
    recent: List<HistoryEntry>,
    onPlayRecent: (HistoryEntry) -> Unit,
    onOpenHistory: () -> Unit,
    add: HubAdd? = null,
) {
    val parked = chrome.parkedTitle
    // Большая плитка — то, что отложено, или последнее просмотренное.
    val hero = if (parked != null) recent.firstOrNull { it.title == parked } else recent.firstOrNull()
    val rest = recent.filter { it !== hero }.take(TILE_COLUMNS * TILE_ROWS)

    Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(272.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HubTitle(chrome.onSettings, onOpenHistory)
            HubLabel("Источники")
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                items(sources) { SourceTile(it) }
                if (add != null) item { AddSource(add) }
            }
            if (chrome.videoOpen) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassButton("К видео", Icons.Rounded.PlayArrow, chrome.onToVideo, Modifier.weight(1f), filled = true)
                    Box(Modifier.clip(CircleShape).background(GlassBrush)) {
                        AppIconButton(Icons.Rounded.Tune, "Формат", chrome.onFormat, size = 52.dp)
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HeroTile(
                chrome = chrome,
                entry = hero,
                modifier = Modifier.fillMaxWidth().weight(1.1f),
                onPlay = { if (parked != null) chrome.onResume() else hero?.let(onPlayRecent) },
            )
            if (rest.isNotEmpty()) {
                HubLabel("Недавние")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rest.chunked(TILE_COLUMNS).forEach { row ->
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { entry ->
                                MediaTile(entry, Modifier.weight(1f).fillMaxHeight()) { onPlayRecent(entry) }
                            }
                            repeat(TILE_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (rest.size <= TILE_COLUMNS) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HubTitle(onSettings: () -> Unit, onHistory: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(TileShape).background(GlassBrush).padding(start = 20.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("AstraMatic", style = MaterialTheme.typography.titleLarge)
            Text(
                LocalVersionText.current,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        AppIconButton(Icons.Rounded.History, "История", onHistory, style = ButtonStyle.Tonal)
        AppIconButton(Icons.Rounded.Settings, "Настройки", onSettings, style = ButtonStyle.Tonal)
    }
}

/** Подпись над группой плиток. Вокруг окружение, поэтому у подписи тень-подложка не нужна: она крупная и светлая. */
@Composable
private fun HubLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x99141519))
            .padding(horizontal = 12.dp, vertical = 2.dp),
    )
}

/** Плитка, которая откликается на взгляд: чуть крупнее и со светлой кромкой. */
@Composable
private fun GlassTile(
    modifier: Modifier,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by hoverState(interaction)
    val lift by animateFloatAsState(if (hovered) HOVER_SCALE else 1f, label = "lift")
    Box(
        modifier
            .graphicsLayer {
                scaleX = lift
                scaleY = lift
            }
            .clip(TileShape)
            .background(GlassBrush)
            .border(if (hovered) 2.dp else 1.dp, if (hovered) Brush.linearGradient(listOf(HOVER_EDGE, HOVER_EDGE)) else GlassEdge, TileShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        content = content,
    )
}

private const val HOVER_SCALE = 1.025f
private val HOVER_EDGE = Color(0xCCFFFFFF)

/** Кнопка хаба: стекло, чтобы читалась на любом окружении. */
@Composable
private fun GlassButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = false) {
    Box(modifier.clip(CircleShape).background(if (filled) Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)) else GlassBrush)) {
        AppButton(
            text,
            onClick,
            icon = icon,
            style = if (filled) ButtonStyle.Filled else ButtonStyle.Ghost,
            modifier = Modifier.fillMaxWidth(),
            height = 52.dp,
        )
    }
}

@Composable
private fun SourceTile(source: HubSource) {
    GlassTile(Modifier.fillMaxWidth().height(68.dp), onClick = source.onClick) {
        Row(
            Modifier.fillMaxSize().padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconTile(source.icon)
            Column(Modifier.weight(1f)) {
                Text(source.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                source.subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            source.onEdit?.let { AppIconButton(Icons.Rounded.Edit, "Изменить", it, size = 44.dp) }
        }
    }
}

/** «Добавить источник» раскрывается в три варианта. */
@Composable
private fun AddSource(add: HubAdd) {
    var open by remember { mutableStateOf(false) }
    if (!open) {
        GlassButton("Добавить источник", Icons.Rounded.Add, { open = true }, Modifier.fillMaxWidth())
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GlassButton("Компьютер с AstraMatic", Icons.Rounded.Computer, add.onDesktop, Modifier.fillMaxWidth())
        GlassButton("Папка Windows", Icons.Rounded.Folder, add.onFolder, Modifier.fillMaxWidth())
        GlassButton("Darkframe", Icons.Rounded.PhotoLibrary, add.onDarkframe, Modifier.fillMaxWidth())
    }
}

@Composable
private fun HeroTile(chrome: HubChrome, entry: HistoryEntry?, modifier: Modifier, onPlay: () -> Unit) {
    val parked = chrome.parkedTitle
    val title = parked ?: entry?.title
    if (title == null) {
        // Смотреть пока нечего: плитка-приветствие.
        Box(modifier.clip(TileShape).background(GlassBrush).border(1.dp, GlassEdge, TileShape).padding(28.dp)) {
            Column(Modifier.align(Alignment.CenterStart), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Что посмотрим?", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Выберите источник слева. Здесь появится то, что вы смотрели, и с этого места можно будет продолжить.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    GlassTile(modifier, onClick = onPlay) {
        val thumbnail = entry?.thumbnail
        if (thumbnail != null) {
            AsyncImage(model = thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(ShadeBrush))
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (parked != null) "Отложено" else "Последнее",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AppButton("Продолжить", onPlay, icon = Icons.Rounded.PlayArrow, style = ButtonStyle.Filled, height = 52.dp)
                entry?.let { WatchedText(it) }
            }
        }
        entry?.let { WatchedLine(it, Modifier.align(Alignment.BottomStart)) }
    }
}

@Composable
private fun MediaTile(entry: HistoryEntry, modifier: Modifier, onClick: () -> Unit) {
    GlassTile(modifier, onClick = onClick) {
        if (entry.thumbnail != null) {
            AsyncImage(model = entry.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(ShadeBrush))
        } else {
            Icon(
                if (entry.isImage) Icons.Rounded.Image else Icons.Rounded.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(bottom = 18.dp).size(32.dp),
            )
        }
        Text(
            entry.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        )
        WatchedLine(entry, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun WatchedText(entry: HistoryEntry) {
    if (entry.isImage || entry.durationMs <= 0) return
    val left = (entry.durationMs - entry.positionMs).coerceAtLeast(0) / 60_000
    Text(
        if (left > 0) "Осталось $left мин" else "Досмотрено",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Докуда досмотрено: тонкая линия по нижнему краю плитки. */
@Composable
private fun WatchedLine(entry: HistoryEntry, modifier: Modifier) {
    if (entry.isImage || entry.durationMs <= 0) return
    val progress = (entry.positionMs.toFloat() / entry.durationMs).coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(3.dp).background(Color(0x1FFFFFFF))) {
        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
    }
}

/** Снизу темнее: подпись читается на любой картинке. */
private val ShadeBrush = Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color(0xE60E0F12))

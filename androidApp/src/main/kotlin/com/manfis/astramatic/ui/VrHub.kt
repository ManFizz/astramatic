package com.manfis.astramatic.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.manfis.astramatic.library.DarkframeServer
import com.manfis.astramatic.library.DesktopServer
import com.manfis.astramatic.library.MediaLibrary
import com.manfis.astramatic.library.SmbServer
import com.manfis.astramatic.library.MediaSourceRef
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

/** Откуда смотреть: телефон, компьютер, Darkframe. */
internal class HubSource(
    val icon: String,
    val title: String,
    val subtitle: String?,
    /** Изменить или удалить; у телефона нет. */
    val onEdit: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/** Добавить источник: в шлеме теперь есть клавиатура. */
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
            add(HubSource("📱", "Этот телефон", "Видео и фото") { onOpen(MediaSourceRef.Phone, "Телефон") })
            add(HubSource("🌐", "Браузер", "Сайты и поиск", onClick = onBrowser))
            library.servers.all().forEach {
                add(HubSource("🖥", it.title, "Папка Windows", onEdit = { onEditServer(it) }) { onOpen(MediaSourceRef.Smb(it.id), it.title) })
            }
            library.desktops.all().forEach {
                add(HubSource("💻", it.name, "AstraMatic на ПК", onEdit = { onEditDesktop(it) }) { onOpen(MediaSourceRef.Desktop(it.id), it.name) })
            }
            library.darkframes.all().forEach {
                add(HubSource("🖼", it.name, "Darkframe", onEdit = { onEditDarkframe(it) }) { onOpen(MediaSourceRef.Darkframe(it.id), it.name) })
            }
        }
    }
}

private val TileShape = RoundedCornerShape(20.dp)
private val PillShape = RoundedCornerShape(50)
private const val TILE_COLUMNS = 3
private const val TILE_ROWS = 2

/**
 * Хаб в шлеме: всё видно сразу, крутить нечего. Отдельные стеклянные плитки парят над окружением:
 * слева откуда смотреть, справа большая плитка того, что смотрели последним, и плитки недавних.
 * Плитка под взглядом приподнимается и подсвечивается.
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

    Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.width(262.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HubTitle()
            SectionLabel("Источники")
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                items(sources) { SourceTile(it) }
                if (add != null) item { AddSource(add) }
            }
            if (chrome.videoOpen) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HubPill("▶ К видео", Modifier.weight(1f), accent = true, onClick = chrome.onToVideo)
                    HubPill("Формат", Modifier.weight(1f), onClick = chrome.onFormat)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HubPill("⚙ Настройки", Modifier.weight(1f), onClick = chrome.onSettings)
                HubPill("🕘 История", Modifier.weight(1f), onClick = onOpenHistory)
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HeroTile(
                chrome = chrome,
                entry = hero,
                modifier = Modifier.fillMaxWidth().weight(1.15f),
                onPlay = { if (parked != null) chrome.onResume() else hero?.let(onPlayRecent) },
            )
            if (rest.isNotEmpty()) {
                SectionLabel("Недавние")
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
private fun HubTitle() {
    Row(
        Modifier.fillMaxWidth().background(GlassBrush, TileShape).border(1.dp, GlassEdge, TileShape).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(38.dp).background(
                Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)),
                CircleShape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text("A", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        Column {
            Text("AstraMatic", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                LocalVersionText.current,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        modifier = Modifier.padding(start = 6.dp),
    )
}

/** Плитка, которая откликается на взгляд: приподнимается и получает светлую кромку. */
@Composable
private fun GlassTile(
    modifier: Modifier,
    onClick: () -> Unit,
    background: Brush = GlassBrush,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lift by animateFloatAsState(if (hovered) HOVER_SCALE else 1f, label = "lift")
    val edge: Dp = if (hovered) 2.dp else 1.dp
    val edgeBrush = if (hovered) SolidBrush(MaterialTheme.colorScheme.primary) else GlassEdge
    Box(
        modifier
            .graphicsLayer {
                scaleX = lift
                scaleY = lift
            }
            .clip(TileShape)
            .background(background)
            .border(edge, edgeBrush, TileShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        content = content,
    )
}

private fun SolidBrush(color: Color) = Brush.linearGradient(listOf(color, color))

private const val HOVER_SCALE = 1.035f

@Composable
private fun SourceTile(source: HubSource) {
    GlassTile(Modifier.fillMaxWidth().height(66.dp), onClick = source.onClick) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(42.dp).background(placeholder(source.title), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(source.icon, style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f)) {
                Text(source.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                source.subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            source.onEdit?.let { edit ->
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(Color(0x22FFFFFF)).clickable(onClick = edit),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✎", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** «Добавить источник» раскрывается в три варианта. */
@Composable
private fun AddSource(add: HubAdd) {
    var open by remember { mutableStateOf(false) }
    if (!open) {
        HubPill("+ Добавить источник", Modifier.fillMaxWidth()) { open = true }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HubPill("💻 Компьютер с AstraMatic", Modifier.fillMaxWidth(), onClick = add.onDesktop)
        HubPill("🖥 Папка Windows", Modifier.fillMaxWidth(), onClick = add.onFolder)
        HubPill("🖼 Darkframe", Modifier.fillMaxWidth(), onClick = add.onDarkframe)
    }
}

@Composable
private fun HubPill(text: String, modifier: Modifier = Modifier, accent: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = MaterialTheme.colorScheme
    val background = when {
        accent -> SolidBrush(colors.primary)
        hovered -> SolidBrush(colors.primaryContainer)
        else -> GlassBrush
    }
    Box(
        modifier
            .height(44.dp)
            .clip(PillShape)
            .background(background)
            .border(1.dp, if (hovered) SolidBrush(colors.primary) else GlassEdge, PillShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (accent) colors.onPrimary else colors.onSurface,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HeroTile(chrome: HubChrome, entry: HistoryEntry?, modifier: Modifier, onPlay: () -> Unit) {
    val parked = chrome.parkedTitle
    val title = parked ?: entry?.title
    if (title == null) {
        // Смотреть пока нечего: плитка-приветствие.
        Box(modifier.background(GlassBrush, TileShape).border(1.dp, GlassEdge, TileShape).padding(24.dp)) {
            Column(Modifier.align(Alignment.CenterStart), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Что посмотрим?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Выберите источник слева. Здесь появится то, что вы смотрели, и с него можно будет продолжить.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
        return
    }
    GlassTile(modifier, onClick = onPlay, background = placeholder(title)) {
        entry?.thumbnail?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(ShadeBrush))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HubPill("▶ Продолжить", accent = true, onClick = onPlay)
                entry?.let { Spacer(Modifier.weight(1f)); WatchedText(it) }
            }
        }
        entry?.let { WatchedLine(it, Modifier.align(Alignment.BottomStart)) }
    }
}

@Composable
private fun MediaTile(entry: HistoryEntry, modifier: Modifier, onClick: () -> Unit) {
    GlassTile(modifier, onClick = onClick, background = placeholder(entry.title)) {
        if (entry.thumbnail != null) {
            AsyncImage(model = entry.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                if (entry.isImage) "🖼" else "🎬",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        Box(Modifier.fillMaxSize().background(ShadeBrush))
        Text(
            entry.title,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
        )
        WatchedLine(entry, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun WatchedText(entry: HistoryEntry) {
    if (entry.isImage || entry.durationMs <= 0) return
    val left = (entry.durationMs - entry.positionMs).coerceAtLeast(0) / 60_000
    Text(
        if (left > 0) "ещё $left мин" else "досмотрено",
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.8f),
    )
}

/** Докуда досмотрено: тонкая линия по нижнему краю плитки. */
@Composable
private fun WatchedLine(entry: HistoryEntry, modifier: Modifier) {
    if (entry.isImage || entry.durationMs <= 0) return
    val progress = (entry.positionMs.toFloat() / entry.durationMs).coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth(progress).height(4.dp).background(MaterialTheme.colorScheme.primary))
}

/** Снизу темнее: подпись читается на любой картинке. */
private val ShadeBrush = Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xD0080812))

/** Плитка без превью не серая: мягкий градиент, свой для каждого имени. */
private fun placeholder(title: String): Brush {
    val hue = (title.hashCode() * 47).mod(360).toFloat()
    return Brush.linearGradient(
        listOf(Color.hsv(hue, 0.5f, 0.42f), Color.hsv((hue + 50f) % 360f, 0.6f, 0.2f)),
    )
}

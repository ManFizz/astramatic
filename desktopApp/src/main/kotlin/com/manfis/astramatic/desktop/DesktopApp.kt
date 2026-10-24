package com.manfis.astramatic.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.manfis.astramatic.model.Projection
import com.manfis.astramatic.model.StereoLayout
import com.manfis.astramatic.model.VideoFormat
import com.manfis.astramatic.remote.PlayerStatus
import com.manfis.astramatic.remote.RemoteCommand
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File

private val Accent = Color(0xFF9B6BFF)

@Composable
fun DesktopApp(holder: ConfigHolder, server: MediaServer, hub: RemoteHub) {
    val config by holder.flow.collectAsState()
    val statuses by hub.statuses.collectAsState()

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, secondaryContainer = Color(0xFF3A2D5C))) {
        Surface(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    Modifier.width(380.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PhonesCard(config, statuses.values.toList(), server, holder)
                    FoldersCard(config, holder)
                    DarkframeCard(config, holder)
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    RemoteCard(statuses.values.firstOrNull(), hub)
                    BrowserCard(config, online = statuses.isNotEmpty(), hub = hub, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Иконка-кнопка с подсказкой при наведении. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionIcon(icon: ImageVector, hint: String, enabled: Boolean = true, onClick: () -> Unit) {
    TooltipArea(
        tooltip = {
            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.inverseSurface) {
                Text(hint, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            }
        },
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 20.dp)),
    ) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = hint) }
    }
}

// --- Телефоны -------------------------------------------------------------------------------

@Composable
private fun PhonesCard(config: DesktopConfig, online: List<PlayerStatus>, server: MediaServer, holder: ConfigHolder) {
    val error by server.error.collectAsState()
    var showQr by remember { mutableStateOf(false) }
    val onlineNames = online.map { it.device }.toSet()

    Section("Телефоны") {
        error?.let { Text("Сервер не запустился: $it", color = MaterialTheme.colorScheme.error) }

        config.devices.sortedByDescending { it.lastSeen }.forEach { device ->
            val isOnline = device.name in onlineNames
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.PhoneAndroid, null)
                Column(Modifier.weight(1f)) {
                    Text(device.name)
                    Text(
                        if (isOnline) "в плеере сейчас" else "был ${ago(device.lastSeen)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (isOnline) Color(0xFF4CD964) else Color(0xFF55505F)))
            }
        }

        // QR-код нужен, только пока телефонов нет или подключают ещё один.
        if (config.devices.isEmpty() || showQr) {
            PairingQr(config)
            if (config.devices.isNotEmpty()) TextButton(onClick = { showQr = false }) { Text("Скрыть QR-код") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showQr = true }) { Text("Подключить ещё") }
                TextButton(onClick = { holder.update { it.copy(key = newKey(), devices = emptyList()) } }) {
                    Text("Отключить все")
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.PairingQr(config: DesktopConfig) {
    val addresses = remember { lanAddresses() }
    var addressIndex by remember { mutableStateOf(0) }
    val address = addresses.getOrNull(addressIndex)
    if (address == null) {
        Text("Не найден адрес в домашней сети — подключите ПК к роутеру.")
        return
    }
    val link = "http://$address:${config.port}/pair?key=${config.key}"
    val qr = remember(link) { qrCode(link) }
    Image(
        qr, contentDescription = "QR-код подключения",
        modifier = Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)).align(Alignment.CenterHorizontally),
    )
    Text(
        "На телефоне в AstraMatic: «+ Компьютер (AstraMatic)» → «Сканировать QR-код». Телефон и ПК — в одной сети. " +
            "Windows при первом запуске спросит про брандмауэр: разрешите частные сети.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SelectionContainer { Text(link, style = MaterialTheme.typography.labelSmall) }
    if (addresses.size > 1) {
        TextButton(onClick = { addressIndex = (addressIndex + 1) % addresses.size }) {
            Text("Другой адрес ПК (${addressIndex + 1} из ${addresses.size})")
        }
    }
}

private fun qrCode(text: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 440, 440)
    val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) image.setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xFFFFFF)
    }
    return image.toComposeImageBitmap()
}

private fun ago(epochSeconds: Long): String {
    val minutes = (System.currentTimeMillis() / 1000 - epochSeconds) / 60
    return when {
        minutes < 1 -> "только что"
        minutes < 60 -> "$minutes мин назад"
        minutes < 60 * 24 -> "${minutes / 60} ч назад"
        else -> "${minutes / 60 / 24} дн назад"
    }
}

// --- Папки ----------------------------------------------------------------------------------

@Composable
private fun FoldersCard(config: DesktopConfig, holder: ConfigHolder) {
    val scope = rememberCoroutineScope()
    Section("Папки для телефона") {
        if (config.folders.isEmpty()) {
            Text("Пока ни одной.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        config.folders.forEach { folder ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Folder, null, tint = Accent)
                Column(Modifier.weight(1f)) {
                    Text(folder.name)
                    Text(
                        folder.path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ActionIcon(Icons.Rounded.Close, "Убрать папку") { holder.update { it.copy(folders = it.folders - folder) } }
            }
        }
        Button(onClick = {
            scope.launch {
                // Обычное окно выбора папки Windows.
                val dir = FileKit.openDirectoryPicker()?.path?.let(::File) ?: return@launch
                holder.update { c ->
                    if (c.folders.any { File(it.path) == dir }) c
                    else c.copy(folders = c.folders + FolderConfig(name = dir.name.ifEmpty { dir.path }, path = dir.path))
                }
            }
        }) {
            Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Добавить папку")
        }
    }
}

// --- Darkframe ------------------------------------------------------------------------------

@Composable
private fun DarkframeCard(config: DesktopConfig, holder: ConfigHolder) {
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Section("Darkframe") {
        val connected = config.darkframe
        if (connected != null) {
            Text("Подключено: ${connected.baseUrl}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Библиотека Darkframe — во вкладке справа. Видео открывается на телефоне, если он тоже подключён к Darkframe.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { holder.update { it.copy(darkframe = null) } }) { Text("Отключить") }
            return@Section
        }
        Text(
            "В Darkframe: «Панель управления → Устройства» → «Подключить устройство», скопируйте ссылку под QR-кодом сюда.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = link, onValueChange = { link = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Ссылка подключения") }, placeholder = { Text("http://…:7860/pair?c=…") },
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(enabled = link.isNotBlank() && !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { DarkframeDesktop.pair(link) } }
                    busy = false
                    result.onSuccess { linked -> holder.update { it.copy(darkframe = linked) } }
                        .onFailure { error = it.message }
                }
            }) { Text("Подключить") }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

// --- Пульт ----------------------------------------------------------------------------------

@Composable
private fun RemoteCard(status: PlayerStatus?, hub: RemoteHub) {
    Section("Пульт") {
        if (status == null) {
            Text(
                "Телефон не на связи. Откройте на нём плеер AstraMatic — он подключится сам.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Section
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(status.device, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            status.title ?: "Видео не выбрано",
            style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (status.title == null) return@Section

        var dragging by remember { mutableStateOf<Float?>(null) }
        Slider(
            value = dragging ?: status.positionMs.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { hub.send(RemoteCommand.SeekTo(it.toLong())) }
                dragging = null
            },
            valueRange = 0f..status.durationMs.coerceAtLeast(1).toFloat(),
        )
        Row {
            Text(formatTime(dragging?.toLong() ?: status.positionMs), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(formatTime(status.durationMs), style = MaterialTheme.typography.bodySmall)
        }

        // Перемотка и пауза — по центру, служебное — по краям.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f)) {
                ActionIcon(Icons.Rounded.CenterFocusStrong, "Рецентр (R)") { hub.send(RemoteCommand.Recenter) }
            }
            ActionIcon(Icons.Rounded.SkipPrevious, "Предыдущее видео (PgUp)") { hub.send(RemoteCommand.PreviousFile) }
            ActionIcon(Icons.Rounded.Replay30, "Назад 30 с (Shift+←)") { hub.send(RemoteCommand.SeekBy(-30_000)) }
            ActionIcon(Icons.Rounded.Replay10, "Назад 10 с (←)") { hub.send(RemoteCommand.SeekBy(-10_000)) }
            FilledIconButton(
                onClick = { hub.send(RemoteCommand.TogglePlayback) },
                modifier = Modifier.padding(horizontal = 8.dp).size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent),
            ) {
                Icon(if (status.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Пауза (Пробел)", Modifier.size(30.dp))
            }
            ActionIcon(Icons.Rounded.Forward10, "Вперёд 10 с (→)") { hub.send(RemoteCommand.SeekBy(10_000)) }
            ActionIcon(Icons.Rounded.Forward30, "Вперёд 30 с (Shift+→)") { hub.send(RemoteCommand.SeekBy(30_000)) }
            ActionIcon(Icons.Rounded.SkipNext, "Следующее видео (PgDn)") { hub.send(RemoteCommand.NextFile) }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { hub.send(RemoteCommand.ToggleStereo) }) {
                    Icon(Icons.Rounded.ViewInAr, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (status.stereo) "В 2D" else "В VR")
                }
            }
        }

        FormatSelector(status, hub)
    }
}

@Composable
private fun FormatSelector(status: PlayerStatus, hub: RemoteHub) {
    val format = status.format?.let(VideoFormat::decode) ?: return
    fun set(new: VideoFormat) = hub.send(RemoteCommand.SetFormat(new.encode()))

    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            Projection.entries.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = format.projection == p,
                    onClick = { set(format.copy(projection = p)) },
                    shape = SegmentedButtonDefaults.itemShape(i, Projection.entries.size),
                    icon = {},
                ) { Text(p.label, maxLines = 1) }
            }
        }
        SingleChoiceSegmentedButtonRow {
            StereoLayout.entries.forEachIndexed { i, s ->
                SegmentedButton(
                    selected = format.stereo == s,
                    onClick = { set(format.copy(stereo = s)) },
                    shape = SegmentedButtonDefaults.itemShape(i, StereoLayout.entries.size),
                    icon = {},
                ) { Text(s.label, maxLines = 1) }
            }
        }
    }
    Text(
        "Клавиши: Пробел — пауза, ←/→ — 10 с (с Shift — 30 с), PgUp/PgDn — видео, R — рецентр, V — 2D/VR.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val Projection.label
    get() = when (this) {
        Projection.Flat -> "Плоское"
        Projection.Dome180 -> "180°"
        Projection.Sphere360 -> "360°"
        Projection.Fisheye -> "Fisheye"
        Projection.Eac360 -> "EAC"
    }

private val StereoLayout.label
    get() = when (this) {
        StereoLayout.Mono -> "Моно"
        StereoLayout.SideBySide -> "SBS"
        StereoLayout.TopBottom -> "TB"
    }

private fun formatTime(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// --- Обзор: папки ПК и Darkframe ------------------------------------------------------------

private data class BrowserRow(val name: String, val path: String, val isFolder: Boolean)

/** Клик по видео открывает его на телефоне вместе с соседними. */
@Composable
private fun BrowserCard(config: DesktopConfig, online: Boolean, hub: RemoteHub, modifier: Modifier) {
    var tab by remember { mutableStateOf(0) }
    val darkframe = config.darkframe
    if (darkframe == null && tab == 1) tab = 0

    Section("Открыть на телефоне", modifier) {
        if (darkframe != null) {
            PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Папки ПК") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Darkframe") })
            }
        }
        if (!online) {
            Text(
                "Телефон не на связи — откройте на нём плеер, чтобы запускать видео отсюда.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (tab == 1 && darkframe != null) DarkframeBrowser(darkframe, hub) else FolderBrowserView(config, hub)
    }
}

@Composable
private fun FolderBrowserView(config: DesktopConfig, hub: RemoteHub) {
    var folder by remember { mutableStateOf<FolderConfig?>(null) }
    val path = remember { mutableStateListOf<String>() }
    val current = folder?.takeIf { it in config.folders }

    if (current == null) {
        if (config.folders.isEmpty()) Text("Добавьте папку слева.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        BrowserList(config.folders.map { BrowserRow(it.name, it.id, true) }) { row ->
            folder = config.folders.find { it.id == row.path }
            path.clear()
        }
        return
    }
    Breadcrumb(listOf(current.name) + path) { if (path.isEmpty()) folder = null else path.removeAt(path.lastIndex) }
    val rows = remember(current, path.toList()) {
        FolderBrowser.list(current, path.joinToString("/")).orEmpty().map { BrowserRow(it.name, it.path, it.isDirectory) }
    }
    BrowserList(rows) { row ->
        if (row.isFolder) path.add(row.name) else hub.send(RemoteCommand.Open(current.id, row.path))
    }
}

@Composable
private fun DarkframeBrowser(link: DarkframeLink, hub: RemoteHub) {
    // Путь — цепочка коллекций (имя, путь коллекции), корень — пустой список.
    val trail = remember { mutableStateListOf<Pair<String, String>>() }
    var rows by remember { mutableStateOf<List<BrowserRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val collection = trail.lastOrNull()?.second.orEmpty()

    LaunchedEffect(link, collection) {
        rows = null
        error = null
        withContext(Dispatchers.IO) { runCatching { DarkframeDesktop.list(link, collection) } }
            .onSuccess { list -> rows = list.map { BrowserRow(it.name, it.path, it.isFolder) } }
            .onFailure { error = it.message }
    }

    Breadcrumb(listOf("Darkframe") + trail.map { it.first }) { if (trail.isNotEmpty()) trail.removeAt(trail.lastIndex) }
    when {
        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
        rows == null -> CircularProgressIndicator(Modifier.size(24.dp))
        else -> BrowserList(rows!!) { row ->
            if (row.isFolder) trail.add(row.name to row.path) else hub.send(RemoteCommand.OpenDarkframe(collection, row.path))
        }
    }
}

@Composable
private fun Breadcrumb(parts: List<String>, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (parts.size > 1) TextButton(onClick = onBack) { Text("← Назад") }
        Text(parts.joinToString(" / "), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
    HorizontalDivider()
}

@Composable
private fun BrowserList(rows: List<BrowserRow>, onClick: (BrowserRow) -> Unit) {
    LazyColumn {
        items(rows, key = { it.path }) { row ->
            ListItem(
                headlineContent = { Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = {
                    Icon(if (row.isFolder) Icons.Rounded.Folder else Icons.Rounded.Movie, null, tint = if (row.isFolder) Accent else Color.Unspecified)
                },
                trailingContent = { if (row.isFolder) Icon(Icons.Rounded.ChevronRight, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick(row) },
            )
        }
    }
}

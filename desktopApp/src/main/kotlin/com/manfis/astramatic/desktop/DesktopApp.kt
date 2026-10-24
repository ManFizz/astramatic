package com.manfis.astramatic.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.manfis.astramatic.model.Projection
import com.manfis.astramatic.model.StereoLayout
import com.manfis.astramatic.model.VideoFormat
import com.manfis.astramatic.remote.FolderEntry
import com.manfis.astramatic.remote.PlayerStatus
import com.manfis.astramatic.remote.RemoteCommand
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.JFileChooser

@Composable
fun DesktopApp(
    initialConfig: DesktopConfig,
    onConfigChange: (DesktopConfig) -> Unit,
    server: MediaServer,
    hub: RemoteHub,
) {
    var config by remember { mutableStateOf(initialConfig) }
    fun update(new: DesktopConfig) {
        config = new
        onConfigChange(new)
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PairingCard(config, server, onNewKey = { update(config.copy(key = newKey())) })
                    FoldersCard(config, ::update)
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    RemoteCard(hub)
                    BrowserCard(config, hub, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

// --- Подключение телефона -------------------------------------------------------------------

@Composable
private fun PairingCard(config: DesktopConfig, server: MediaServer, onNewKey: () -> Unit) {
    val error by server.error.collectAsState()
    val addresses = remember { lanAddresses() }
    var addressIndex by remember { mutableStateOf(0) }
    val address = addresses.getOrNull(addressIndex)
    val link = address?.let { "http://$it:${config.port}/pair?key=${config.key}" }

    Section("Подключить телефон") {
        if (error != null) {
            Text("Сервер не запустился: $error", color = MaterialTheme.colorScheme.error)
        }
        if (link == null) {
            Text("Не найден адрес в домашней сети — подключите ПК к роутеру.")
            return@Section
        }
        val qr = remember(link) { qrCode(link) }
        Image(qr, contentDescription = "QR-код подключения", modifier = Modifier.size(260.dp).align(Alignment.CenterHorizontally))
        Text(
            "В AstraMatic на телефоне: «+ Компьютер (AstraMatic)» → «Сканировать QR-код». " +
                "Телефон и ПК должны быть в одной сети.",
            style = MaterialTheme.typography.bodySmall,
        )
        SelectionContainer { Text(link, style = MaterialTheme.typography.bodySmall) }
        if (addresses.size > 1) {
            TextButton(onClick = { addressIndex = (addressIndex + 1) % addresses.size }) {
                Text("Другой адрес ПК (${addressIndex + 1} из ${addresses.size})")
            }
        }
        OutlinedButton(onClick = onNewKey) { Text("Новый ключ — отключить все телефоны") }
        Text(
            "При первом запуске Windows спросит про брандмауэр — разрешите доступ в частных сетях.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun qrCode(text: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
    val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) image.setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xFFFFFF)
    }
    return image.toComposeImageBitmap()
}

// --- Общие папки ----------------------------------------------------------------------------

@Composable
private fun FoldersCard(config: DesktopConfig, onChange: (DesktopConfig) -> Unit) {
    Section("Папки для телефона") {
        if (config.folders.isEmpty()) Text("Пока ни одной. Добавьте папки с видео.", style = MaterialTheme.typography.bodySmall)
        config.folders.forEach { folder ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(folder.name)
                    Text(folder.path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { onChange(config.copy(folders = config.folders - folder)) }) { Text("Убрать") }
            }
        }
        Button(onClick = {
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = "Папка с видео"
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                val dir = chooser.selectedFile
                if (config.folders.none { File(it.path) == dir }) {
                    onChange(config.copy(folders = config.folders + FolderConfig(name = dir.name.ifEmpty { dir.path }, path = dir.path)))
                }
            }
        }) { Text("+ Добавить папку") }
    }
}

// --- Пульт ----------------------------------------------------------------------------------

@Composable
private fun RemoteCard(hub: RemoteHub) {
    val statuses by hub.statuses.collectAsState()
    val status = statuses.values.firstOrNull()

    Section("Пульт") {
        if (status == null) {
            Text("Телефон не на связи. Откройте на нём плеер AstraMatic — он подключится сам.")
            return@Section
        }
        Text("📱 ${status.device}", style = MaterialTheme.typography.bodySmall)
        Text(status.title ?: "Видео не выбрано", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            Text(formatTime(dragging?.toLong() ?: status.positionMs))
            Spacer(Modifier.weight(1f))
            Text(formatTime(status.durationMs))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { hub.send(RemoteCommand.PreviousFile) }) { Text("⏮") }
            OutlinedButton(onClick = { hub.send(RemoteCommand.SeekBy(-30_000)) }) { Text("−30") }
            OutlinedButton(onClick = { hub.send(RemoteCommand.SeekBy(-10_000)) }) { Text("−10") }
            Button(onClick = { hub.send(RemoteCommand.TogglePlayback) }) { Text(if (status.playing) "⏸ Пауза" else "▶ Смотреть") }
            OutlinedButton(onClick = { hub.send(RemoteCommand.SeekBy(10_000)) }) { Text("+10") }
            OutlinedButton(onClick = { hub.send(RemoteCommand.SeekBy(30_000)) }) { Text("+30") }
            OutlinedButton(onClick = { hub.send(RemoteCommand.NextFile) }) { Text("⏭") }
            Spacer(Modifier.width(16.dp))
            FilledTonalButton(onClick = { hub.send(RemoteCommand.Recenter) }) { Text("Рецентр") }
            FilledTonalButton(onClick = { hub.send(RemoteCommand.ToggleStereo) }) { Text(if (status.stereo) "2D" else "VR") }
        }
        FormatChips(status, hub)
        Text(
            "Клавиши: Пробел — пауза, ←/→ — 10 с (с Shift — 30 с), PgUp/PgDn — файл, R — рецентр, V — 2D/VR.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun FormatChips(status: PlayerStatus, hub: RemoteHub) {
    val format = status.format?.let(VideoFormat::decode) ?: return
    fun set(new: VideoFormat) = hub.send(RemoteCommand.SetFormat(new.encode()))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Projection.entries.forEach {
            FilterChip(selected = format.projection == it, onClick = { set(format.copy(projection = it)) }, label = { Text(it.label) })
        }
        Spacer(Modifier.width(12.dp))
        StereoLayout.entries.forEach {
            FilterChip(selected = format.stereo == it, onClick = { set(format.copy(stereo = it)) }, label = { Text(it.label) })
        }
    }
}

private val Projection.label
    get() = when (this) {
        Projection.Flat -> "Плоское"
        Projection.Dome180 -> "180°"
        Projection.Sphere360 -> "360°"
        Projection.Fisheye -> "Fisheye"
        Projection.Eac360 -> "360° EAC"
    }

private val StereoLayout.label
    get() = when (this) {
        StereoLayout.Mono -> "Моно"
        StereoLayout.SideBySide -> "SBS"
        StereoLayout.TopBottom -> "Над/под"
    }

private fun formatTime(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// --- Файлы на ПК ----------------------------------------------------------------------------

/** Обзор общих папок прямо на ПК: клик по видео открывает его на телефоне. */
@Composable
private fun BrowserCard(config: DesktopConfig, hub: RemoteHub, modifier: Modifier) {
    val statuses by hub.statuses.collectAsState()
    var folder by remember { mutableStateOf<FolderConfig?>(null) }
    val path = remember { mutableStateListOf<String>() }

    Section("Открыть на телефоне", modifier) {
        val current = folder?.takeIf { it in config.folders }
        if (current == null) {
            config.folders.forEach { f ->
                Row(Modifier.fillMaxWidth().clickable { folder = f; path.clear() }.padding(vertical = 6.dp)) { Text("📁  ${f.name}") }
            }
            if (config.folders.isEmpty()) Text("Добавьте папку слева.", style = MaterialTheme.typography.bodySmall)
            return@Section
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (path.isEmpty()) folder = null else path.removeAt(path.lastIndex) }) { Text("← Назад") }
            Text((listOf(current.name) + path).joinToString(" / "), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider()
        val entries = remember(current, path.toList()) { FolderBrowser.list(current, path.joinToString("/")).orEmpty() }
        if (statuses.isEmpty()) {
            Text("Телефон не на связи — открыть видео на нём сейчас не получится.", style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn {
            items(entries, key = { it.path }) { entry: FolderEntry ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (entry.isDirectory) path.add(entry.name) else hub.send(RemoteCommand.Open(current.id, entry.path))
                    }.padding(vertical = 6.dp),
                ) {
                    Text(if (entry.isDirectory) "📁  ${entry.name}" else "🎬  ${entry.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

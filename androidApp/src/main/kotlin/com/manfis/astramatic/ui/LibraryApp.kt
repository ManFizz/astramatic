package com.manfis.astramatic.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.manfis.astramatic.headset.HeadsetActivity
import coil3.compose.AsyncImage
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import com.manfis.astramatic.library.DarkframeRef
import com.manfis.astramatic.library.DarkframeServer
import com.manfis.astramatic.library.DesktopServer
import com.manfis.astramatic.library.MediaEntry
import com.manfis.astramatic.library.MediaLibrary
import com.manfis.astramatic.library.MediaSourceRef
import com.manfis.astramatic.library.SmbConnections
import com.manfis.astramatic.library.SmbServer
import com.manfis.astramatic.model.Projection
import com.manfis.astramatic.model.StereoLayout
import com.manfis.astramatic.model.VideoFormat
import com.manfis.astramatic.model.VideoFormatDetector
import com.manfis.astramatic.player.PlayerActivity
import com.manfis.astramatic.settings.SettingsActivity
import com.manfis.astramatic.player.Playlist
import com.manfis.astramatic.player.PlaylistItem
import com.manfis.astramatic.player.History
import com.manfis.astramatic.BuildConfig
import androidx.compose.foundation.layout.Spacer
import com.manfis.astramatic.player.HistoryEntry
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import com.manfis.astramatic.remote.VideoFiles
import com.manfis.astramatic.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Строка версии на главной. В скриншот-тестах подменяется: хеш коммита менял бы эталон при каждом коммите. */
internal val LocalVersionText = staticCompositionLocalOf { "v${BuildConfig.VERSION_NAME} · ${BuildConfig.COMMIT}" }

internal sealed interface Screen {
    data object Home : Screen
    data class Browse(val source: MediaSourceRef, val path: String, val title: String) : Screen
    data class EditServer(val server: SmbServer?) : Screen
    data class EditDarkframe(val server: DarkframeServer?) : Screen
    data class EditDesktop(val server: DesktopServer?) : Screen
    data object History : Screen
}

/** Где пользователь находится в библиотеке. Вынесено наружу, чтобы переживать переключение вкладок меню. */
class LibraryState {
    internal val backStack = mutableStateListOf<Screen>(Screen.Home)

    /** Открыт корень библиотеки, а не папка или редактор. */
    val atRoot get() = backStack.last() == Screen.Home
}

/**
 * Источники видео, просмотр папок, формат по умолчанию.
 * [embedded] — внутри меню плеера (без кнопок «Войти в VR», шлема и справки),
 * [allowEditing] = false — компьютеры только просматриваются, без изменения и добавления.
 */
@Composable
fun LibraryApp(
    onPlay: (Playlist) -> Unit,
    state: LibraryState = remember { LibraryState() },
    embedded: Boolean = false,
    allowEditing: Boolean = true,
    /** В шлеме: вместо простого списка — хаб, с этим заголовком и действиями. */
    hub: HubChrome? = null,
) {
    val context = LocalContext.current
    val library = remember { MediaLibrary(context) }
    val backStack = state.backStack

    fun push(screen: Screen) = backStack.add(screen)
    fun pop() = backStack.removeAt(backStack.lastIndex)

    // Внутри плеера «Назад» разбирает сам плеер: у него своя цепочка до главной.
    BackHandler(enabled = !embedded && backStack.size > 1) { pop() }

    fun item(
        uri: Uri,
        name: String,
        image: Boolean,
        hint: String = name,
        darkframe: DarkframeRef? = null,
        thumbnail: String? = null,
    ) = PlaylistItem(
        uri, name, VideoFormatDetector.detect(hint) ?: DefaultFormat.forFile(context, image), darkframe,
        isImage = image, thumbnail = thumbnail,
    )

    /** Список — все видео и фото той же папки, чтобы работали «следующий / предыдущий». */
    fun play(entry: MediaEntry, folder: List<MediaEntry>) {
        val files = folder.filter { !it.isDirectory && it.playUri != null }
        val index = files.indexOf(entry).takeIf { it >= 0 } ?: return
        onPlay(Playlist(files.map { item(it.playUri!!, it.name, it.isImage, it.formatHint, it.darkframe, it.thumbnail) }, index))
    }

    when (val screen = backStack.last()) {
        Screen.Home -> if (hub != null) {
            val settings = remember { AppSettings.get(context) }
            val history = remember { History.get(context) }
            HubHome(
                chrome = hub,
                sources = rememberHubSources(
                    library,
                    onOpen = { source, title -> push(Screen.Browse(source, "", title)) },
                    onEditServer = { push(Screen.EditServer(it)) },
                    onEditDesktop = { push(Screen.EditDesktop(it)) },
                    onEditDarkframe = { push(Screen.EditDarkframe(it)) },
                ),
                recent = if (settings.keepHistory) history.entries else emptyList(),
                onPlayRecent = { onPlay(Playlist.single(it.toPlaylistItem())) },
                onOpenHistory = { push(Screen.History) },
                add = HubAdd(
                    onDesktop = { push(Screen.EditDesktop(null)) },
                    onFolder = { push(Screen.EditServer(null)) },
                    onDarkframe = { push(Screen.EditDarkframe(null)) },
                ).takeIf { allowEditing },
            )
        } else HomeScreen(
            library = library,
            embedded = embedded,
            allowEditing = allowEditing,
            onOpen = { source, title -> push(Screen.Browse(source, "", title)) },
            onEditServer = { push(Screen.EditServer(it)) },
            onEditDarkframe = { push(Screen.EditDarkframe(it)) },
            onEditDesktop = { push(Screen.EditDesktop(it)) },
            onPickFile = { uri, name -> onPlay(Playlist.single(item(uri, name, VideoFiles.isImage(name), thumbnail = uri.toString()))) },
            onPlayHistory = { onPlay(Playlist.single(it.toPlaylistItem())) },
            onOpenHistory = { push(Screen.History) },
        )

        is Screen.Browse -> BrowseScreen(
            library = library,
            screen = screen,
            onBack = ::pop,
            onOpenFolder = { push(Screen.Browse(screen.source, it.path, it.name)) },
            onPlay = ::play,
        )

        is Screen.EditServer -> EditServerScreen(
            initial = screen.server,
            onSave = {
                library.servers.save(it)
                SmbConnections.forget(it.id)
                pop()
            },
            onDelete = {
                library.servers.delete(it.id)
                SmbConnections.forget(it.id)
                pop()
            },
            onCancel = ::pop,
        )

        Screen.History -> HistoryScreen(
            onBack = ::pop,
            onPlay = { onPlay(Playlist.single(it.toPlaylistItem())) },
        )

        is Screen.EditDesktop -> DesktopScreen(
            initial = screen.server,
            onSave = {
                library.desktops.save(it)
                pop()
            },
            onDelete = {
                library.desktops.delete(it.id)
                pop()
            },
            onCancel = ::pop,
        )

        is Screen.EditDarkframe -> DarkframeScreen(
            initial = screen.server,
            onSave = {
                library.darkframes.save(it)
                pop()
            },
            onDelete = {
                library.darkframes.delete(it.id)
                pop()
            },
            onCancel = ::pop,
        )
    }
}

@Composable
private fun HomeScreen(
    library: MediaLibrary,
    embedded: Boolean,
    allowEditing: Boolean,
    onOpen: (MediaSourceRef, String) -> Unit,
    onEditServer: (SmbServer?) -> Unit,
    onEditDarkframe: (DarkframeServer?) -> Unit,
    onEditDesktop: (DesktopServer?) -> Unit,
    onPickFile: (Uri, String) -> Unit,
    onPlayHistory: (HistoryEntry) -> Unit,
    onOpenHistory: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val history = remember { History.get(context) }
    val servers = remember { library.servers.all() }
    val darkframes = remember { library.darkframes.all() }
    val desktops = remember { library.desktops.all() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onPickFile(uri, displayName(context, uri))
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().safeContentPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (!embedded) Text("AstraMatic", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            // Сверить с `git log -1`: тот же хеш — на телефоне последняя версия.
            Text(
                LocalVersionText.current,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }

        if (settings.keepHistory && history.entries.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Недавние", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenHistory) { Text("Вся история →") }
            }
            history.entries.take(RECENT_COUNT).forEach { HistoryRow(it) { onPlayHistory(it) } }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }

        Text("Источники", style = MaterialTheme.typography.titleMedium)
        SourceRow("📱", "Видео и фото на телефоне", null) { onOpen(MediaSourceRef.Phone, "Телефон") }
        servers.forEach { server ->
            SourceRow("🖥", server.title, "${server.host}\\${server.share}", onEdit = { onEditServer(server) }.takeIf { allowEditing }) {
                onOpen(MediaSourceRef.Smb(server.id), server.title)
            }
        }
        desktops.forEach { server ->
            SourceRow("💻", server.name, "AstraMatic · ${server.authority}", onEdit = { onEditDesktop(server) }.takeIf { allowEditing }) {
                onOpen(MediaSourceRef.Desktop(server.id), server.name)
            }
        }
        darkframes.forEach { server ->
            SourceRow("🖼", server.name, "Darkframe · ${server.authority}", onEdit = { onEditDarkframe(server) }.takeIf { allowEditing }) {
                onOpen(MediaSourceRef.Darkframe(server.id), server.name)
            }
        }
        if (allowEditing) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onEditDesktop(null) }) { Text("+ Компьютер (AstraMatic)") }
                OutlinedButton(onClick = { onEditServer(null) }) { Text("+ Папка Windows (SMB)") }
                OutlinedButton(onClick = { onEditDarkframe(null) }) { Text("+ Darkframe") }
                OutlinedButton(onClick = { picker.launch(arrayOf("video/*", "image/*")) }) { Text("Открыть файл…") }
            }
        } else {
            Text(
                "Добавить или изменить компьютер можно в 2D-режиме — там работает клавиатура.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        if (embedded) return@Column

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Button(onClick = { context.startActivity(Intent(context, PlayerActivity::class.java)) }) {
            Text("Войти в VR")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { context.startActivity(Intent(context, HeadsetActivity::class.java)) }) {
                Text("Настроить шлем")
            }
            OutlinedButton(onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) }) {
                Text("Настройки")
            }
        }
        Text(
            "В плеере: нажатие — полоска управления, долгое нажатие — рецентр, кнопка 2D/VR сверху — " +
                "переключение режима, шестерёнка — настройка шлема.\n" +
                "Кнопки и стики пульта назначаются в плеере: меню → «🎮 Пульт».",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SourceRow(icon: String, title: String, subtitle: String?, onEdit: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(icon, style = MaterialTheme.typography.headlineSmall)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        if (onEdit != null) TextButton(onClick = onEdit) { Text("Изменить") }
    }
}

@Composable
private fun BrowseScreen(
    library: MediaLibrary,
    screen: Screen.Browse,
    onBack: () -> Unit,
    onOpenFolder: (MediaEntry) -> Unit,
    onPlay: (entry: MediaEntry, folder: List<MediaEntry>) -> Unit,
) {
    val context = LocalContext.current
    var entries by remember(screen) { mutableStateOf<List<MediaEntry>?>(null) }
    var error by remember(screen) { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var permissionAsked by remember { mutableStateOf(false) }
    var query by remember(screen) { mutableStateOf("") }

    val needsPermission = screen.source == MediaSourceRef.Phone && MEDIA_PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload++ }

    LaunchedEffect(screen, reload) {
        if (needsPermission) {
            // Спрашиваем сами только один раз, иначе при «Запретить» диалог бы зациклился.
            if (!permissionAsked) {
                permissionAsked = true
                permission.launch(MEDIA_PERMISSIONS)
            }
            return@LaunchedEffect
        }
        error = null
        entries = null
        try {
            entries = library.list(screen.source, screen.path)
        } catch (e: Exception) {
            error = e.describe()
        }
    }

    Column(Modifier.fillMaxSize().safeContentPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text(
                screen.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!entries.isNullOrEmpty()) {
                AppTextField(query, { query = it }, label = "🔍 Поиск в папке", modifier = Modifier.width(260.dp))
            }
        }
        HorizontalDivider()

        val list = entries
        val shown = list?.filter { TextSearch.matches(it.name, query) }
        when {
            needsPermission -> CenterMessage("Нужен доступ к видео и фото на телефоне.") {
                Button(onClick = { permission.launch(MEDIA_PERMISSIONS) }) { Text("Разрешить") }
            }

            error != null -> CenterMessage(error!!) {
                Button(onClick = { reload++ }) { Text("Повторить") }
            }

            list == null -> CenterMessage("Загрузка…") { CircularProgressIndicator() }

            list.isEmpty() -> CenterMessage("Здесь нет папок, видео и фото.") {}

            shown.isNullOrEmpty() -> CenterMessage("Ничего не найдено: «${query.trim()}».") {
                TextButton(onClick = { query = "" }) { Text("Показать всё") }
            }

            // Список для «следующий / предыдущий» — вся папка, а не только найденное.
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.path }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (entry.isDirectory) onOpenFolder(entry) else onPlay(entry, list) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (entry.thumbnail != null) {
                            AsyncImage(
                                model = entry.thumbnail,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(6.dp)),
                            )
                        } else {
                            Text(
                                when {
                                    entry.isDirectory -> "📁"
                                    entry.isImage -> "🖼"
                                    else -> "🎬"
                                },
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (!entry.isDirectory) {
                                val format = VideoFormatDetector.detect(entry.formatHint)
                                val details = listOfNotNull(
                                    formatSize(entry.size).takeIf { entry.size > 0 },
                                    format?.let { "${projectionLabel(it.projection)} ${stereoLabel(it.stereo)}" },
                                )
                                if (details.isNotEmpty()) {
                                    Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterMessage(text: String, action: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text)
        action()
    }
}

@Composable
private fun EditServerScreen(
    initial: SmbServer?,
    onSave: (SmbServer) -> Unit,
    onDelete: (SmbServer) -> Unit,
    onCancel: () -> Unit,
) {
    val id = remember { initial?.id ?: UUID.randomUUID().toString() }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var address by remember { mutableStateOf(initial?.let { "\\\\${it.host}\\${it.share}" } ?: "") }
    var user by remember { mutableStateOf(initial?.user ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var domain by remember { mutableStateOf(initial?.domain ?: "") }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var checkRequest by remember { mutableIntStateOf(0) }

    val parsed = SmbServer.parseAddress(address)
    fun build(): SmbServer? = parsed?.let { (host, share) ->
        SmbServer(
            id = id,
            name = name.trim(),
            host = host,
            share = share,
            user = user.trim(),
            password = password,
            domain = domain.trim(),
        )
    }

    LaunchedEffect(checkRequest) {
        if (checkRequest == 0) return@LaunchedEffect
        val server = build() ?: return@LaunchedEffect
        checking = true
        status = withContext(Dispatchers.IO) {
            try {
                SmbConnections.forget(server.id)
                val count = SmbConnections.list(server, "").size
                "Подключено, в корне $count элементов."
            } catch (e: Exception) {
                e.describe()
            } finally {
                SmbConnections.forget(server.id)
            }
        }
        checking = false
    }

    Column(
        modifier = Modifier.fillMaxSize().safeContentPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (initial == null) "Новый компьютер" else "Компьютер", style = MaterialTheme.typography.headlineSmall)

        AppTextField(
            address, { address = it },
            label = "Адрес общей папки",
            placeholder = "\\\\MY-PC\\Videos  или  192.168.1.5/Videos",
            modifier = Modifier.fillMaxWidth(),
            isError = address.isNotBlank() && parsed == null,
        )
        Text(
            "Имя компьютера или IP и имя общей папки. Папку нужно открыть для общего доступа в Windows: " +
                "свойства папки → Доступ.",
            style = MaterialTheme.typography.bodySmall,
        )
        AppTextField(name, { name = it }, label = "Название (необязательно)", modifier = Modifier.fillMaxWidth())
        AppTextField(user, { user = it }, label = "Пользователь Windows", modifier = Modifier.fillMaxWidth())
        AppTextField(password, { password = it }, label = "Пароль", modifier = Modifier.fillMaxWidth(), secret = true)
        Text(
            "Если вход в Windows через учётную запись Microsoft — укажите её почту и пароль от неё (не PIN-код). " +
                "Пусто — гостевой вход.",
            style = MaterialTheme.typography.bodySmall,
        )
        AppTextField(domain, { domain = it }, label = "Домен (обычно пусто)", modifier = Modifier.fillMaxWidth())

        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = parsed != null, onClick = { build()?.let(onSave) }) { Text("Сохранить") }
            OutlinedButton(enabled = parsed != null && !checking, onClick = { checkRequest++ }) { Text("Проверить") }
            if (checking) CircularProgressIndicator()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel) { Text("Отмена") }
            if (initial != null) TextButton(onClick = { onDelete(initial) }) { Text("Удалить") }
        }
    }
}

@Composable
private fun <T> OptionRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

fun projectionLabel(projection: Projection) = when (projection) {
    Projection.Flat -> "Плоское"
    Projection.Dome180 -> "180°"
    Projection.Sphere360 -> "360°"
    Projection.Fisheye -> "Fisheye"
    Projection.Eac360 -> "360° EAC"
}

fun stereoLabel(stereo: StereoLayout) = when (stereo) {
    StereoLayout.Mono -> "Моно"
    StereoLayout.SideBySide -> "SBS"
    StereoLayout.TopBottom -> "Над/под"
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f ГБ".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f МБ".format(bytes / (1L shl 20).toDouble())
    else -> "${bytes / 1024} КБ"
}

private fun displayName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
        ?: uri.lastPathSegment ?: ""

private const val RECENT_COUNT = 4

/** Вся история просмотра. */
@Composable
private fun HistoryScreen(onBack: () -> Unit, onPlay: (HistoryEntry) -> Unit) {
    val context = LocalContext.current
    val history = remember { History.get(context) }
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().safeContentPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("История", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (history.entries.isNotEmpty()) {
                AppTextField(query, { query = it }, label = "🔍 Поиск", modifier = Modifier.width(240.dp))
                TextButton(onClick = { history.clear() }) { Text("Очистить") }
            }
        }
        HorizontalDivider()
        if (history.entries.isEmpty()) {
            CenterMessage("Здесь появится то, что вы смотрели.") {}
            return
        }
        val shown = history.entries.filter { TextSearch.matches(it.title, query) }
        if (shown.isEmpty()) {
            CenterMessage("Ничего не найдено: «${query.trim()}».") {
                TextButton(onClick = { query = "" }) { Text("Показать всё") }
            }
            return
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(shown, key = { it.uri.toString() }) { entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { HistoryRow(entry) { onPlay(entry) } }
                    TextButton(onClick = { history.remove(entry.uri) }) { Text("✕") }
                }
            }
        }
    }
}

/** Строка истории: превью, название, докуда досмотрено и когда. */
@Composable
private fun HistoryRow(entry: HistoryEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(6.dp))) {
            if (entry.thumbnail != null) {
                AsyncImage(
                    model = entry.thumbnail, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(if (entry.isImage) "🖼" else "🎬", Modifier.align(Alignment.Center), style = MaterialTheme.typography.titleLarge)
            }
            // Полоска «досмотрено» внизу превью.
            if (!entry.isImage && entry.durationMs > 0) {
                val progress = (entry.positionMs.toFloat() / entry.durationMs).coerceIn(0f, 1f)
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(3.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(
                if (!entry.isImage && entry.durationMs > 0) "${clock(entry.positionMs)} из ${clock(entry.durationMs)}" else null,
                DateUtils.getRelativeTimeSpanString(entry.watchedAt).toString(),
            )
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun clock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private val MEDIA_PERMISSIONS = arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)

/** Формат файлов без тегов в имени: свой для видео и для фото (меняется в «Настройках»). */
internal object DefaultFormat {
    fun forFile(context: Context, image: Boolean): VideoFormat {
        val settings = AppSettings.get(context)
        return VideoFormat.decode(if (image) settings.defaultImageFormat else settings.defaultVideoFormat) ?: VideoFormat()
    }
}

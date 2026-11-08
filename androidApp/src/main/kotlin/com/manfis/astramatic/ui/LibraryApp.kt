package com.manfis.astramatic.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
    data object Browser : Screen
}

/** Где пользователь находится в библиотеке. Вынесено наружу, чтобы переживать переключение вкладок меню. */
class LibraryState {
    internal val backStack = mutableStateListOf<Screen>(Screen.Home)

    /** Открыт корень библиотеки, а не папка или редактор. */
    val atRoot get() = backStack.last() == Screen.Home

    /** Браузер: открытая страница переживает уход в хаб. */
    val browser = BrowserState()

    /** Открыт браузер: ему нужно всё окно. */
    val inBrowser get() = backStack.last() == Screen.Browser

    /** Открыть браузер; с адресом — сразу на этой странице. */
    fun openBrowser(url: String? = null) {
        if (url != null) browser.open(url)
        if (backStack.last() != Screen.Browser) backStack.add(Screen.Browser)
    }
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
        tags: List<String> = emptyList(),
    ) = PlaylistItem(
        uri, name, DefaultFormat.resolve(context, hint, tags, image), darkframe,
        isImage = image, thumbnail = thumbnail,
    )

    /** Список — все видео и фото той же папки, чтобы работали «следующий / предыдущий». */
    fun play(entry: MediaEntry, folder: List<MediaEntry>) {
        val files = folder.filter { !it.isDirectory && it.playUri != null }
        val index = files.indexOf(entry).takeIf { it >= 0 } ?: return
        onPlay(Playlist(files.map { item(it.playUri!!, it.name, it.isImage, it.formatHint, it.darkframe, it.thumbnail, it.tags) }, index))
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
                    onBrowser = { push(Screen.Browser) },
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
            onOpenBrowser = { push(Screen.Browser) },
        )

        Screen.Browser -> BrowserScreen(state.browser, embedded, onClose = ::pop)

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
    onOpenBrowser: () -> Unit,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (settings.keepHistory && history.entries.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Недавние", Modifier.weight(1f))
                AppButton("Вся история", onOpenHistory, icon = Icons.Rounded.ChevronRight, style = ButtonStyle.Ghost)
            }
            history.entries.take(RECENT_COUNT).forEach { HistoryRow(it) { onPlayHistory(it) } }
        }

        SectionTitle("Источники")
        SourceRow(Icons.Rounded.PhoneAndroid, "Видео и фото на телефоне", null) { onOpen(MediaSourceRef.Phone, "Телефон") }
        SourceRow(Icons.Rounded.Language, "Браузер", "Сайты и поиск") { onOpenBrowser() }
        servers.forEach { server ->
            SourceRow(Icons.Rounded.Folder, server.title, "${server.host}\\${server.share}", onEdit = { onEditServer(server) }.takeIf { allowEditing }) {
                onOpen(MediaSourceRef.Smb(server.id), server.title)
            }
        }
        desktops.forEach { server ->
            SourceRow(Icons.Rounded.Computer, server.name, "AstraMatic · ${server.authority}", onEdit = { onEditDesktop(server) }.takeIf { allowEditing }) {
                onOpen(MediaSourceRef.Desktop(server.id), server.name)
            }
        }
        darkframes.forEach { server ->
            SourceRow(Icons.Rounded.PhotoLibrary, server.name, "Darkframe · ${server.authority}", onEdit = { onEditDarkframe(server) }.takeIf { allowEditing }) {
                onOpen(MediaSourceRef.Darkframe(server.id), server.name)
            }
        }
        if (allowEditing) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton("Компьютер", { onEditDesktop(null) }, icon = Icons.Rounded.Add)
                AppButton("Папка Windows", { onEditServer(null) }, icon = Icons.Rounded.Add)
                AppButton("Darkframe", { onEditDarkframe(null) }, icon = Icons.Rounded.Add)
                AppButton("Открыть файл", { picker.launch(arrayOf("video/*", "image/*")) }, icon = Icons.Rounded.FileOpen)
            }
        }

        if (embedded) return@Column

        Divider(Modifier.padding(vertical = 8.dp))

        AppButton(
            "Войти в VR",
            { context.startActivity(Intent(context, PlayerActivity::class.java)) },
            icon = Icons.Rounded.ViewInAr,
            style = ButtonStyle.Filled,
            height = 56.dp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton("Настроить шлем", { context.startActivity(Intent(context, HeadsetActivity::class.java)) }, icon = Icons.Rounded.Tune)
            AppButton("Настройки", { context.startActivity(Intent(context, SettingsActivity::class.java)) }, icon = Icons.Rounded.Settings)
        }
        Hint(
            "В плеере: нажатие — полоска управления, долгое нажатие — рецентр, кнопка 2D/VR сверху — " +
                "переключение режима, шестерёнка — настройка шлема.\n" +
                "Кнопки и стики пульта назначаются в плеере: меню → «Пульт».",
        )
    }
}

@Composable
private fun SourceRow(icon: ImageVector, title: String, subtitle: String?, onEdit: (() -> Unit)? = null, onClick: () -> Unit) {
    ListRow(onClick) {
        IconTile(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Hint(subtitle)
        }
        if (onEdit != null) AppIconButton(Icons.Rounded.Edit, "Изменить", onEdit)
    }
}

/** Строка списка: плашка с подсветкой под взглядом. */
@Composable
private fun ListRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by hoverState(interaction)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (hovered) CardHover else CardColor)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/** Шапка экрана библиотеки: назад, заголовок, справа — поиск и действия. */
@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onBack, style = ButtonStyle.Tonal)
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
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
        ScreenHeader(screen.title, onBack) {
            if (!entries.isNullOrEmpty()) {
                AppTextField(query, { query = it }, label = "Поиск в папке", modifier = Modifier.width(280.dp))
            }
        }
        Divider()

        val list = entries
        val shown = list?.filter { TextSearch.matches(it.name, query) }
        when {
            needsPermission -> CenterMessage("Нужен доступ к видео и фото на телефоне.") {
                AppButton("Разрешить", { permission.launch(MEDIA_PERMISSIONS) }, style = ButtonStyle.Filled)
            }

            error != null -> CenterMessage(error!!) {
                AppButton("Повторить", { reload++ }, icon = Icons.Rounded.Refresh)
            }

            list == null -> CenterMessage("Загрузка…") { CircularProgressIndicator() }

            list.isEmpty() -> CenterMessage("Здесь нет папок, видео и фото.") {}

            shown.isNullOrEmpty() -> CenterMessage("Ничего не найдено: «${query.trim()}».") {
                AppButton("Показать всё", { query = "" })
            }

            // Список для «следующий / предыдущий» — вся папка, а не только найденное.
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(shown, key = { it.path }) { entry ->
                    ListRow({ if (entry.isDirectory) onOpenFolder(entry) else onPlay(entry, list) }) {
                        Preview(
                            entry.thumbnail,
                            when {
                                entry.isDirectory -> Icons.Rounded.Folder
                                entry.isImage -> Icons.Rounded.Image
                                else -> Icons.Rounded.Movie
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (!entry.isDirectory) {
                                val format = VideoFormatDetector.detect(entry.formatHint, entry.tags, DefaultFormat.partial(context))
                                val details = listOfNotNull(
                                    formatSize(entry.size).takeIf { entry.size > 0 },
                                    format?.let { "${projectionLabel(it.projection)} ${stereoLabel(it.stereo)}" },
                                )
                                if (details.isNotEmpty()) Hint(details.joinToString(" · "))
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
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Hint(
            "Имя компьютера или IP и имя общей папки. Папку нужно открыть для общего доступа в Windows: " +
                "свойства папки → Доступ.",
        )
        AppTextField(name, { name = it }, label = "Название (необязательно)", modifier = Modifier.fillMaxWidth())
        AppTextField(user, { user = it }, label = "Пользователь Windows", modifier = Modifier.fillMaxWidth())
        AppTextField(password, { password = it }, label = "Пароль", modifier = Modifier.fillMaxWidth(), secret = true)
        Hint(
            "Если вход в Windows через учётную запись Microsoft — укажите её почту и пароль от неё (не PIN-код). " +
                "Пусто — гостевой вход.",
        )
        AppTextField(domain, { domain = it }, label = "Домен (обычно пусто)", modifier = Modifier.fillMaxWidth())

        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppButton("Сохранить", { build()?.let(onSave) }, style = ButtonStyle.Filled, enabled = parsed != null)
            AppButton("Проверить", { checkRequest++ }, enabled = parsed != null && !checking)
            if (checking) CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton("Отмена", onCancel, style = ButtonStyle.Ghost)
            if (initial != null) AppButton("Удалить", { onDelete(initial) }, icon = Icons.Rounded.Delete, style = ButtonStyle.Ghost)
        }
    }
}

@Composable
private fun <T> OptionRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Segmented(options, selected, label, onSelect)
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
        ScreenHeader("История", onBack) {
            if (history.entries.isNotEmpty()) {
                AppTextField(query, { query = it }, label = "Поиск", modifier = Modifier.width(240.dp))
                AppButton("Очистить", { history.clear() }, icon = Icons.Rounded.DeleteSweep, style = ButtonStyle.Ghost)
            }
        }
        Divider()
        if (history.entries.isEmpty()) {
            CenterMessage("Здесь появится то, что вы смотрели.") {}
            return
        }
        val shown = history.entries.filter { TextSearch.matches(it.title, query) }
        if (shown.isEmpty()) {
            CenterMessage("Ничего не найдено: «${query.trim()}».") {
                AppButton("Показать всё", { query = "" })
            }
            return
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(shown, key = { it.uri.toString() }) { entry ->
                HistoryRow(entry, onRemove = { history.remove(entry.uri) }) { onPlay(entry) }
            }
        }
    }
}

/** Строка истории: превью, название, докуда досмотрено и когда. */
@Composable
private fun HistoryRow(entry: HistoryEntry, onRemove: (() -> Unit)? = null, onClick: () -> Unit) {
    ListRow(onClick) {
        Box {
            Preview(entry.thumbnail, if (entry.isImage) Icons.Rounded.Image else Icons.Rounded.Movie)
            // Полоска «досмотрено» внизу превью.
            if (!entry.isImage && entry.durationMs > 0) {
                val progress = (entry.positionMs.toFloat() / entry.durationMs).coerceIn(0f, 1f)
                Box(
                    Modifier.align(Alignment.BottomStart).padding(horizontal = 6.dp, vertical = 4.dp)
                        .size(width = 84.dp * progress, height = 3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(
                if (!entry.isImage && entry.durationMs > 0) "${clock(entry.positionMs)} из ${clock(entry.durationMs)}" else null,
                DateUtils.getRelativeTimeSpanString(entry.watchedAt).toString(),
            )
            Hint(details.joinToString(" · "))
        }
        if (onRemove != null) AppIconButton(Icons.Rounded.Close, "Убрать из истории", onRemove)
    }
}

/** Превью 16:9 или значок на плашке, если превью нет. */
@Composable
private fun Preview(thumbnail: String?, icon: ImageVector) {
    Box(
        Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x1FFFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnail != null) {
            AsyncImage(model = thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
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
    /** Формат файла: по тегам в имени и у источника; тег VR без подробностей — формат частично распознанных; иначе по умолчанию. */
    fun resolve(context: Context, hint: String, tags: List<String>, image: Boolean): VideoFormat =
        VideoFormatDetector.detect(hint, tags, partial(context)) ?: forFile(context, image)

    fun partial(context: Context): VideoFormat = VideoFormat.decode(AppSettings.get(context).partialFormat) ?: VideoFormat()

    fun forFile(context: Context, image: Boolean): VideoFormat {
        val settings = AppSettings.get(context)
        return VideoFormat.decode(if (image) settings.defaultImageFormat else settings.defaultVideoFormat) ?: VideoFormat()
    }
}

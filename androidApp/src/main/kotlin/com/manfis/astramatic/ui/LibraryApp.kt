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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.manfis.astramatic.headset.HeadsetActivity
import coil3.compose.AsyncImage
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
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
import com.manfis.astramatic.remote.VideoFiles
import com.manfis.astramatic.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

internal sealed interface Screen {
    data object Home : Screen
    data class Browse(val source: MediaSourceRef, val path: String, val title: String) : Screen
    data class EditServer(val server: SmbServer?) : Screen
    data class EditDarkframe(val server: DarkframeServer?) : Screen
    data class EditDesktop(val server: DesktopServer?) : Screen
}

/** Где пользователь находится в библиотеке. Вынесено наружу, чтобы переживать переключение вкладок меню. */
class LibraryState {
    internal val backStack = mutableStateListOf<Screen>(Screen.Home)
}

/**
 * Источники видео, просмотр папок, формат по умолчанию.
 * [embedded] — внутри меню плеера (без кнопок «Войти в VR», шлема и справки),
 * [allowEditing] = false — в шлеме, где нельзя набирать текст: компьютеры только просматриваются.
 */
@Composable
fun LibraryApp(
    onPlay: (Playlist) -> Unit,
    state: LibraryState = remember { LibraryState() },
    embedded: Boolean = false,
    allowEditing: Boolean = true,
) {
    val context = LocalContext.current
    val library = remember { MediaLibrary(context) }
    val backStack = state.backStack

    fun push(screen: Screen) = backStack.add(screen)
    fun pop() = backStack.removeAt(backStack.lastIndex)

    BackHandler(enabled = backStack.size > 1) { pop() }

    fun item(uri: Uri, name: String, image: Boolean, hint: String = name, darkframe: DarkframeRef? = null) =
        PlaylistItem(
            uri, name, VideoFormatDetector.detect(hint) ?: DefaultFormat.forFile(context, image), darkframe, isImage = image,
        )

    /** Список — все видео и фото той же папки, чтобы работали «следующий / предыдущий». */
    fun play(entry: MediaEntry, folder: List<MediaEntry>) {
        val files = folder.filter { !it.isDirectory && it.playUri != null }
        val index = files.indexOf(entry).takeIf { it >= 0 } ?: return
        onPlay(Playlist(files.map { item(it.playUri!!, it.name, it.isImage, it.formatHint, it.darkframe) }, index))
    }

    when (val screen = backStack.last()) {
        Screen.Home -> HomeScreen(
            library = library,
            embedded = embedded,
            allowEditing = allowEditing,
            onOpen = { source, title -> push(Screen.Browse(source, "", title)) },
            onEditServer = { push(Screen.EditServer(it)) },
            onEditDarkframe = { push(Screen.EditDarkframe(it)) },
            onEditDesktop = { push(Screen.EditDesktop(it)) },
            onPickFile = { uri, name -> onPlay(Playlist.single(item(uri, name, VideoFiles.isImage(name)))) },
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
) {
    val context = LocalContext.current
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
        if (!embedded) Text("AstraMatic", style = MaterialTheme.typography.headlineMedium)

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
        }
        HorizontalDivider()

        val list = entries
        when {
            needsPermission -> CenterMessage("Нужен доступ к видео и фото на телефоне.") {
                Button(onClick = { permission.launch(MEDIA_PERMISSIONS) }) { Text("Разрешить") }
            }

            error != null -> CenterMessage(error!!) {
                Button(onClick = { reload++ }) { Text("Повторить") }
            }

            list == null -> CenterMessage("Загрузка…") { CircularProgressIndicator() }

            list.isEmpty() -> CenterMessage("Здесь нет папок, видео и фото.") {}

            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.path }) { entry ->
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

        OutlinedTextField(
            value = address, onValueChange = { address = it },
            label = { Text("Адрес общей папки") },
            placeholder = { Text("\\\\MY-PC\\Videos  или  192.168.1.5/Videos") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            isError = address.isNotBlank() && parsed == null,
        )
        Text(
            "Имя компьютера или IP и имя общей папки. Папку нужно открыть для общего доступа в Windows: " +
                "свойства папки → Доступ.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Название (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = user, onValueChange = { user = it },
            label = { Text("Пользователь Windows") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Пароль") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )
        Text(
            "Если вход в Windows через учётную запись Microsoft — укажите её почту и пароль от неё (не PIN-код). " +
                "Пусто — гостевой вход.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = domain, onValueChange = { domain = it },
            label = { Text("Домен (обычно пусто)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

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

/** Понятное сообщение для частых ошибок SMB. */
private fun Throwable.describe(): String {
    val chain = generateSequence(this) { it.cause }.toList()
    val text = chain.joinToString(" ") { "${it::class.simpleName} ${it.message}" }
    return when {
        "STATUS_LOGON_FAILURE" in text -> "Неверный логин или пароль."
        "STATUS_ACCESS_DENIED" in text -> "Нет доступа к папке для этого пользователя."
        "STATUS_BAD_NETWORK_NAME" in text -> "Общая папка с таким именем не найдена."
        "STATUS_OBJECT_NAME_NOT_FOUND" in text || "STATUS_OBJECT_PATH_NOT_FOUND" in text -> "Папка не найдена."
        chain.any { it is java.net.UnknownHostException } -> "Компьютер с таким именем не найден. Попробуйте IP-адрес."
        chain.any { it is java.net.ConnectException || it is java.net.SocketTimeoutException } ->
            "Компьютер не отвечает. Проверьте, что он включён, в той же сети и общий доступ разрешён в брандмауэре."
        else -> chain.last().message ?: chain.last()::class.simpleName ?: "Неизвестная ошибка"
    }
}

private fun displayName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
        ?: uri.lastPathSegment ?: ""

private val MEDIA_PERMISSIONS = arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)

/** Формат файлов без тегов в имени: свой для видео и для фото (меняется в «Настройках»). */
internal object DefaultFormat {
    fun forFile(context: Context, image: Boolean): VideoFormat {
        val settings = AppSettings.get(context)
        return VideoFormat.decode(if (image) settings.defaultImageFormat else settings.defaultVideoFormat) ?: VideoFormat()
    }
}

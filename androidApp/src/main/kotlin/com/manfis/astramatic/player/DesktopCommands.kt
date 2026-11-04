package com.manfis.astramatic.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import com.manfis.astramatic.library.DarkframeStore
import com.manfis.astramatic.library.DesktopClient
import com.manfis.astramatic.library.DesktopServer
import com.manfis.astramatic.library.MediaLibrary
import com.manfis.astramatic.library.MediaSourceRef
import com.manfis.astramatic.library.SmbThumbnailFetcher
import com.manfis.astramatic.model.VideoFormat
import com.manfis.astramatic.model.VideoFormatDetector
import com.manfis.astramatic.remote.RemoteCommand
import com.manfis.astramatic.remote.VideoFiles
import com.manfis.astramatic.ui.DefaultFormat
import com.manfis.astramatic.ui.VrMenuActions
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking

/** Файл, выбранный в окне AstraMatic на ПК. Реализует [DesktopPlaylists]. */
interface DesktopOpener {
    fun openFromDesktop(server: DesktopServer, command: RemoteCommand.Open)
    fun openFromDarkframe(server: DesktopServer, command: RemoteCommand.OpenDarkframe)
}

/** Команды пульта на ПК. */
class DesktopCommands(private val actions: VrMenuActions, private val opener: DesktopOpener) {
    fun handle(server: DesktopServer, command: RemoteCommand) {
        when (command) {
            RemoteCommand.TogglePlayback -> actions.togglePlayback()
            is RemoteCommand.SeekBy -> actions.seekBy(command.deltaMs)
            is RemoteCommand.SeekTo -> actions.seekTo(command.positionMs)
            RemoteCommand.PreviousFile -> actions.previousFile()
            RemoteCommand.NextFile -> actions.nextFile()
            RemoteCommand.Recenter -> actions.recenter()
            // 2D включается только пальцем на телефоне.
            RemoteCommand.ToggleStereo -> Unit
            is RemoteCommand.SetFormat -> VideoFormat.decode(command.format)?.let(actions::setFormat)
            is RemoteCommand.Open -> opener.openFromDesktop(server, command)
            is RemoteCommand.OpenDarkframe -> opener.openFromDarkframe(server, command)
        }
    }
}

/** Собирает очередь для файла, выбранного на ПК: сеть — в [io], результат — на главном потоке [main]. */
class DesktopPlaylists(
    private val context: Context,
    private val io: Executor,
    private val main: Handler,
    private val onOpen: (Playlist) -> Unit,
    private val onNotice: (String) -> Unit,
) : DesktopOpener {

    /**
     * Видео из Darkframe, выбранное в окне ПК. Телефон открывает его через своё подключение к Darkframe —
     * того, что работает на том же компьютере (или единственного).
     */
    override fun openFromDarkframe(server: DesktopServer, command: RemoteCommand.OpenDarkframe) {
        val all = DarkframeStore(context).all()
        val host = Uri.parse(server.baseUrl).host
        val darkframe = all.find { Uri.parse(it.baseUrl).host == host } ?: all.singleOrNull()
        if (darkframe == null) {
            onNotice("Подключите телефон к Darkframe: «+ Darkframe» на главном экране.")
            return
        }
        val library = MediaLibrary(context)
        io.execute {
            val videos = runCatching {
                runBlocking { library.list(MediaSourceRef.Darkframe(darkframe.id), command.collection) }
            }.getOrNull()?.filter { !it.isDirectory && it.playUri != null } ?: return@execute
            val index = videos.indexOfFirst { it.path == command.itemId }.takeIf { it >= 0 } ?: return@execute
            val items = videos.map {
                PlaylistItem(
                    it.playUri!!, it.name, VideoFormatDetector.detect(it.formatHint) ?: DefaultFormat.forFile(context, it.isImage),
                    it.darkframe, isImage = it.isImage, thumbnail = it.thumbnail,
                )
            }
            main.post { onOpen(Playlist(items, index)) }
        }
    }

    /** Видео, выбранное в окне AstraMatic на ПК, — вместе с остальными видео его папки. */
    override fun openFromDesktop(server: DesktopServer, command: RemoteCommand.Open) {
        io.execute {
            val parent = command.path.substringBeforeLast('/', "")
            val videos = runCatching { DesktopClient.list(server, command.folderId, parent) }.getOrNull()
                ?.filter { !it.isDirectory }
                ?: return@execute
            val index = videos.indexOfFirst { it.path == command.path }.takeIf { it >= 0 } ?: return@execute
            val items = videos.map {
                val image = VideoFiles.isImage(it.name)
                val uri = server.fileUri(command.folderId, it.path)
                PlaylistItem(
                    uri, it.name,
                    VideoFormatDetector.detect(it.name) ?: DefaultFormat.forFile(context, image), isImage = image,
                    thumbnail = if (image) uri.toString() else "${SmbThumbnailFetcher.HTTP_VIDEO_SCHEME}:$uri",
                )
            }
            main.post { onOpen(Playlist(items, index)) }
        }
    }
}

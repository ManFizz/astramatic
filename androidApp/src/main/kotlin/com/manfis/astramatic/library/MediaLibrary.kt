package com.manfis.astramatic.library

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.manfis.astramatic.remote.VideoFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Откуда берутся файлы. */
sealed interface MediaSourceRef {
    data object Phone : MediaSourceRef
    data class Smb(val serverId: String) : MediaSourceRef
    data class Darkframe(val serverId: String) : MediaSourceRef
    data class Desktop(val serverId: String) : MediaSourceRef
}

/** Видео из библиотеки Darkframe: позиция просмотра у него общая с Darkframe. */
data class DarkframeRef(val serverId: String, val itemId: String)

/** Папка, видео или фото. У папки [path] — путь для следующего [MediaLibrary.list], у файла есть [playUri]. */
data class MediaEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val playUri: Uri? = null,
    /** Картинка-превью (URL, content:// или особая схема для SMB), если источник её даёт. */
    val thumbnail: String? = null,
    /** По этой строке угадывается формат: у Darkframe теги могут быть в имени файла, а не в названии. */
    val formatHint: String = name,
    val darkframe: DarkframeRef? = null,
    val isImage: Boolean = false,
)

class MediaLibrary(context: Context) {
    private val context = context.applicationContext
    val servers = SmbServerStore(context)
    val darkframes = DarkframeStore(context)
    val desktops = DesktopStore(context)

    /** Содержимое папки: сначала папки, затем файлы, по алфавиту. Бросает исключение при ошибке сети или доступа. */
    suspend fun list(source: MediaSourceRef, path: String): List<MediaEntry> = withContext(Dispatchers.IO) {
        val entries = when (source) {
            MediaSourceRef.Phone -> listPhone(path)
            is MediaSourceRef.Smb -> listSmb(source.serverId, path)
            // У Darkframe свой порядок (ручной в коллекциях) — его и держим.
            is MediaSourceRef.Darkframe -> return@withContext listDarkframe(source.serverId, path)
            // ПК уже отдаёт папки первыми и по алфавиту.
            is MediaSourceRef.Desktop -> return@withContext listDesktop(source.serverId, path)
        }
        entries.sortedWith(compareBy<MediaEntry> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private fun listSmb(serverId: String, path: String): List<MediaEntry> {
        val server = servers.get(serverId) ?: error("Компьютер удалён из списка")
        return SmbConnections.list(server, path)
            .filter { it.isDirectory || VideoFiles.isMedia(it.name) }
            .map {
                val childPath = if (path.isEmpty()) it.name else "$path/${it.name}"
                val image = VideoFiles.isImage(it.name)
                val playUri = if (it.isDirectory) null else SmbUris.build(serverId, childPath)
                MediaEntry(
                    name = it.name,
                    path = childPath,
                    isDirectory = it.isDirectory,
                    size = it.size,
                    playUri = playUri,
                    // Фото целиком по сети ради превью не тянем каждый раз: уменьшенная копия кешируется.
                    thumbnail = playUri?.let { uri -> if (image) "${SmbThumbnailFetcher.SMB_THUMB_SCHEME}:$uri" else uri.toString() },
                    isImage = image,
                )
            }
    }

    /** Пути ПК: "" — общие папки, `<id папки>/<путь внутри>` — содержимое. */
    private fun listDesktop(serverId: String, path: String): List<MediaEntry> {
        val server = desktops.get(serverId) ?: error("Компьютер удалён из списка")
        if (path.isEmpty()) {
            return DesktopClient.info(server).folders.map { MediaEntry(it.name, "${it.id}/", isDirectory = true) }
        }
        val folderId = path.substringBefore('/')
        val inner = path.substringAfter('/', "")
        return DesktopClient.list(server, folderId, inner).map {
            val playUri = if (it.isDirectory) null else server.fileUri(folderId, it.path)
            val image = VideoFiles.isImage(it.name)
            MediaEntry(
                name = it.name,
                path = "$folderId/${it.path}",
                isDirectory = it.isDirectory,
                size = it.size,
                playUri = playUri,
                thumbnail = playUri?.let { uri -> if (image) uri.toString() else "${SmbThumbnailFetcher.HTTP_VIDEO_SCHEME}:$uri" },
                isImage = image,
            )
        }
    }

    /**
     * Пути Darkframe: "" — корневые коллекции, `c:<id>` — коллекция, `s:<id>` — умная коллекция,
     * `none` — без коллекции. В список попадают видео и фото.
     */
    private fun listDarkframe(serverId: String, path: String): List<MediaEntry> {
        val server = darkframes.get(serverId) ?: error("Darkframe удалён из списка")
        fun folder(c: DarkframeClient.Collection) = MediaEntry(
            name = c.name,
            path = if (c.smartFilter != null) "s:${c.id}" else "c:${c.id}",
            isDirectory = true,
        )
        fun file(item: DarkframeClient.Item) = MediaEntry(
            name = item.displayName,
            path = item.id,
            isDirectory = false,
            size = item.size,
            playUri = Uri.parse(item.videoUrl),
            thumbnail = item.thumbUrl,
            formatHint = "${item.fileName} ${item.title}",
            darkframe = DarkframeRef(serverId, item.id).takeIf { item.isVideo },
            isImage = item.isImage,
        )
        fun media(items: List<DarkframeClient.Item>) = items.filter { it.isVideo || it.isImage }.map(::file)
        return when {
            path.isEmpty() -> DarkframeClient.collections(server).filter { it.parentId == null }.map(::folder) +
                MediaEntry("Без коллекции", "none", isDirectory = true)
            path == "none" -> media(DarkframeClient.items(server, null).second)
            path.startsWith("s:") -> {
                val filter = DarkframeClient.collections(server).find { it.id == path.removePrefix("s:") }?.smartFilter
                    ?: error("Умная коллекция не найдена")
                media(DarkframeClient.smartItems(server, filter))
            }
            else -> {
                val (children, items) = DarkframeClient.items(server, path.removePrefix("c:"))
                children.map(::folder) + media(items)
            }
        }
    }

    /** Корень — папки, в которых Android нашёл видео или фото; внутри папки — они сами. */
    private fun listPhone(path: String): List<MediaEntry> =
        if (path.isEmpty()) {
            (phoneBuckets(MediaStore.Video.Media.EXTERNAL_CONTENT_URI) + phoneBuckets(MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
                .distinctBy { it.path }
        } else {
            phoneFiles(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, path, image = false) +
                phoneFiles(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, path, image = true)
        }

    private fun phoneBuckets(collection: Uri): List<MediaEntry> {
        val result = mutableListOf<MediaEntry>()
        val seen = mutableSetOf<String>()
        val projection = arrayOf(MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                if (seen.add(id)) result += MediaEntry(cursor.getString(1) ?: "Без имени", id, isDirectory = true)
            }
        }
        return result
    }

    private fun phoneFiles(collection: Uri, bucketId: String, image: Boolean): List<MediaEntry> {
        val result = mutableListOf<MediaEntry>()
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
        context.contentResolver.query(
            collection, projection, "${MediaStore.MediaColumns.BUCKET_ID} = ?", arrayOf(bucketId), null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: ""
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                result += MediaEntry(
                    name = name,
                    path = uri.toString(),
                    isDirectory = false,
                    size = cursor.getLong(2),
                    playUri = uri,
                    thumbnail = uri.toString(),
                    isImage = image,
                )
            }
        }
        return result
    }

    companion object {
        fun isVideo(name: String) = VideoFiles.isVideo(name)
    }
}

/** Ссылка на файл в общей папке: `smb://<id сервера>/<путь>`. Логин и пароль берутся из [SmbServerStore]. */
object SmbUris {
    const val SCHEME = "smb"

    fun build(serverId: String, path: String): Uri =
        Uri.Builder().scheme(SCHEME).authority(serverId).path(path).build()

    fun serverId(uri: Uri): String = uri.authority ?: error("Нет сервера в $uri")

    fun path(uri: Uri): String = uri.path?.trimStart('/') ?: ""
}

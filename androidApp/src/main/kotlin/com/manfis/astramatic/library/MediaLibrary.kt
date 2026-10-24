package com.manfis.astramatic.library

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Откуда берутся файлы. */
sealed interface MediaSourceRef {
    data object Phone : MediaSourceRef
    data class Smb(val serverId: String) : MediaSourceRef
    data class Darkframe(val serverId: String) : MediaSourceRef
}

/** Видео из библиотеки Darkframe: позиция просмотра у него общая с Darkframe. */
data class DarkframeRef(val serverId: String, val itemId: String)

/** Папка или видео. У папки [path] — путь для следующего [MediaLibrary.list], у видео есть [playUri]. */
data class MediaEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val playUri: Uri? = null,
    /** Картинка-превью (URL или content://), если источник её даёт. */
    val thumbnail: String? = null,
    /** По этой строке угадывается формат: у Darkframe теги могут быть в имени файла, а не в названии. */
    val formatHint: String = name,
    val darkframe: DarkframeRef? = null,
)

class MediaLibrary(context: Context) {
    private val context = context.applicationContext
    val servers = SmbServerStore(context)
    val darkframes = DarkframeStore(context)

    /** Содержимое папки: сначала папки, затем видео, по алфавиту. Бросает исключение при ошибке сети или доступа. */
    suspend fun list(source: MediaSourceRef, path: String): List<MediaEntry> = withContext(Dispatchers.IO) {
        val entries = when (source) {
            MediaSourceRef.Phone -> listPhone(path)
            is MediaSourceRef.Smb -> listSmb(source.serverId, path)
            // У Darkframe свой порядок (ручной в коллекциях) — его и держим.
            is MediaSourceRef.Darkframe -> return@withContext listDarkframe(source.serverId, path)
        }
        entries.sortedWith(compareBy<MediaEntry> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private fun listSmb(serverId: String, path: String): List<MediaEntry> {
        val server = servers.get(serverId) ?: error("Компьютер удалён из списка")
        return SmbConnections.list(server, path)
            .filter { it.isDirectory || isVideo(it.name) }
            .map {
                val childPath = if (path.isEmpty()) it.name else "$path/${it.name}"
                MediaEntry(
                    name = it.name,
                    path = childPath,
                    isDirectory = it.isDirectory,
                    size = it.size,
                    playUri = if (it.isDirectory) null else SmbUris.build(serverId, childPath),
                ).let { entry -> entry.copy(thumbnail = entry.playUri?.toString()) }
            }
    }

    /**
     * Пути Darkframe: "" — корневые коллекции, `c:<id>` — коллекция, `s:<id>` — умная коллекция,
     * `none` — видео без коллекции. В список попадают только видео.
     */
    private fun listDarkframe(serverId: String, path: String): List<MediaEntry> {
        val server = darkframes.get(serverId) ?: error("Darkframe удалён из списка")
        fun folder(c: DarkframeClient.Collection) = MediaEntry(
            name = c.name,
            path = if (c.smartFilter != null) "s:${c.id}" else "c:${c.id}",
            isDirectory = true,
        )
        fun video(item: DarkframeClient.Item) = MediaEntry(
            name = item.displayName,
            path = item.id,
            isDirectory = false,
            size = item.size,
            playUri = Uri.parse(item.videoUrl),
            thumbnail = item.thumbUrl,
            formatHint = "${item.fileName} ${item.title}",
            darkframe = DarkframeRef(serverId, item.id),
        )
        return when {
            path.isEmpty() -> DarkframeClient.collections(server).filter { it.parentId == null }.map(::folder) +
                MediaEntry("Без коллекции", "none", isDirectory = true)
            path == "none" -> DarkframeClient.items(server, null).second.filter { it.isVideo }.map(::video)
            path.startsWith("s:") -> {
                val filter = DarkframeClient.collections(server).find { it.id == path.removePrefix("s:") }?.smartFilter
                    ?: error("Умная коллекция не найдена")
                DarkframeClient.smartItems(server, filter).filter { it.isVideo }.map(::video)
            }
            else -> {
                val (children, items) = DarkframeClient.items(server, path.removePrefix("c:"))
                children.map(::folder) + items.filter { it.isVideo }.map(::video)
            }
        }
    }

    /** Корень — папки, в которых Android нашёл видео; внутри папки — сами видео. */
    private fun listPhone(path: String): List<MediaEntry> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )
        val selection = if (path.isEmpty()) null else "${MediaStore.Video.Media.BUCKET_ID} = ?"
        val args = if (path.isEmpty()) null else arrayOf(path)
        val result = mutableListOf<MediaEntry>()
        val buckets = mutableSetOf<String>()

        context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (path.isEmpty()) {
                    val bucketId = cursor.getString(bucketIdCol) ?: continue
                    if (buckets.add(bucketId)) {
                        result += MediaEntry(cursor.getString(bucketNameCol) ?: "Без имени", bucketId, isDirectory = true)
                    }
                } else {
                    result += MediaEntry(
                        name = cursor.getString(nameCol) ?: "",
                        path = cursor.getString(nameCol) ?: "",
                        isDirectory = false,
                        size = cursor.getLong(sizeCol),
                        playUri = ContentUris.withAppendedId(collection, cursor.getLong(idCol)),
                    ).let { it.copy(thumbnail = it.playUri.toString()) }
                }
            }
        }
        return result
    }

    companion object {
        private val videoExtensions = setOf(
            "mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts", "mts", "wmv", "flv", "3gp", "mpg", "mpeg",
        )

        fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in videoExtensions
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

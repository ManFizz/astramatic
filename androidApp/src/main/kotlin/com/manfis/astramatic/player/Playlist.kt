package com.manfis.astramatic.player

import android.content.Context
import android.net.Uri
import com.manfis.astramatic.model.VideoFormat

data class PlaylistItem(val uri: Uri, val title: String, val format: VideoFormat)

/** Видео папки, из которой открыли файл: для «следующий / предыдущий» и автоперехода. */
data class Playlist(val items: List<PlaylistItem>, val index: Int) {
    val current: PlaylistItem get() = items[index]
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index < items.lastIndex

    fun moved(delta: Int): Playlist? = (index + delta).takeIf { it in items.indices }?.let { copy(index = it) }

    companion object {
        fun single(item: PlaylistItem) = Playlist(listOf(item), 0)
    }
}

/**
 * Передача списка из главного экрана в плеер. Через Intent его не передать —
 * в папке могут быть тысячи файлов, а у Intent ограничение по размеру.
 */
object PendingPlaylist {
    @Volatile private var playlist: Playlist? = null

    fun put(value: Playlist) {
        playlist = value
    }

    fun take(): Playlist? = playlist.also { playlist = null }
}

/** Где остановились в каждом видео. Как в Darkframe: у самого начала и почти в конце не запоминаем. */
class ResumePositions(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("positions", Context.MODE_PRIVATE)

    fun get(uri: Uri): Long? = prefs.getLong(uri.toString(), -1).takeIf { it > 0 }

    fun save(uri: Uri, positionMs: Long, durationMs: Long) {
        val finished = durationMs > 0 && positionMs >= durationMs * END_FRACTION
        prefs.edit().apply {
            if (positionMs < MIN_POSITION_MS || finished) remove(uri.toString()) else putLong(uri.toString(), positionMs)
        }.apply()
    }

    private companion object {
        const val MIN_POSITION_MS = 5_000L
        const val END_FRACTION = 0.95
    }
}

/** Формат, выбранный вручную для конкретного файла: важнее того, что угадано по имени. */
class FormatMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("formats", Context.MODE_PRIVATE)

    fun get(uri: Uri): VideoFormat? = prefs.getString(uri.toString(), null)?.let(VideoFormat::decode)

    fun save(uri: Uri, format: VideoFormat) = prefs.edit().putString(uri.toString(), format.encode()).apply()

    fun clear(uri: Uri) = prefs.edit().remove(uri.toString()).apply()
}

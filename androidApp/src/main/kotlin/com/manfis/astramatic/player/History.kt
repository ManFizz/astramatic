package com.manfis.astramatic.player

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import com.manfis.astramatic.library.DarkframeRef
import com.manfis.astramatic.model.VideoFormat
import org.json.JSONArray
import org.json.JSONObject

/** Что смотрели: чтобы вернуться одним нажатием. */
data class HistoryEntry(
    val uri: Uri,
    val title: String,
    val isImage: Boolean,
    val format: VideoFormat,
    val thumbnail: String?,
    val darkframe: DarkframeRef?,
    val positionMs: Long,
    val durationMs: Long,
    /** Когда открывали, мс с 1970-го. */
    val watchedAt: Long,
) {
    fun toPlaylistItem() = PlaylistItem(uri, title, format, darkframe, isImage, thumbnail)
}

/**
 * История просмотра: последние [MAX] файлов, новые сверху, без повторов.
 * Список — Compose-состояние, экраны обновляются сами. Один экземпляр на процесс.
 */
class History private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("history", Context.MODE_PRIVATE)

    val entries = mutableStateListOf<HistoryEntry>().apply { addAll(read()) }

    /** Файл открыт: поднять наверх (или добавить). Позиция сохраняется прежняя, пока не придёт новая. */
    fun opened(item: PlaylistItem) {
        val previous = entries.find { it.uri == item.uri }
        entries.removeAll { it.uri == item.uri }
        entries.add(
            0,
            HistoryEntry(
                uri = item.uri,
                title = item.title,
                isImage = item.isImage,
                format = item.format,
                thumbnail = item.thumbnail ?: previous?.thumbnail,
                darkframe = item.darkframe,
                positionMs = previous?.positionMs ?: 0,
                durationMs = previous?.durationMs ?: 0,
                watchedAt = System.currentTimeMillis(),
            ),
        )
        while (entries.size > MAX) entries.removeAt(entries.lastIndex)
        write()
    }

    /** Докуда досмотрели — для полоски прогресса в списке. */
    fun progress(uri: Uri, positionMs: Long, durationMs: Long) {
        val index = entries.indexOfFirst { it.uri == uri }
        if (index < 0) return
        entries[index] = entries[index].copy(positionMs = positionMs, durationMs = durationMs)
        write()
    }

    fun remove(uri: Uri) {
        entries.removeAll { it.uri == uri }
        write()
    }

    fun clear() {
        entries.clear()
        write()
    }

    private fun write() {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(
                JSONObject()
                    .put("uri", e.uri.toString())
                    .put("title", e.title)
                    .put("image", e.isImage)
                    .put("format", e.format.encode())
                    .put("thumb", e.thumbnail ?: JSONObject.NULL)
                    .put("dfServer", e.darkframe?.serverId ?: JSONObject.NULL)
                    .put("dfItem", e.darkframe?.itemId ?: JSONObject.NULL)
                    .put("pos", e.positionMs)
                    .put("dur", e.durationMs)
                    .put("at", e.watchedAt),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun read(): List<HistoryEntry> {
        val array = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrNull() ?: return emptyList()
        return List(array.length()) { array.getJSONObject(it) }.mapNotNull { o ->
            runCatching {
                val dfServer = o.optString("dfServer").takeIf { it.isNotEmpty() && it != "null" }
                val dfItem = o.optString("dfItem").takeIf { it.isNotEmpty() && it != "null" }
                HistoryEntry(
                    uri = Uri.parse(o.getString("uri")),
                    title = o.optString("title"),
                    isImage = o.optBoolean("image"),
                    format = VideoFormat.decode(o.optString("format")) ?: VideoFormat(),
                    thumbnail = o.optString("thumb").takeIf { it.isNotEmpty() && it != "null" },
                    darkframe = if (dfServer != null && dfItem != null) DarkframeRef(dfServer, dfItem) else null,
                    positionMs = o.optLong("pos"),
                    durationMs = o.optLong("dur"),
                    watchedAt = o.optLong("at"),
                )
            }.getOrNull()
        }
    }

    companion object {
        private const val KEY = "entries"
        private const val MAX = 100

        @Volatile private var instance: History? = null

        fun get(context: Context): History = instance ?: synchronized(this) {
            instance ?: History(context).also { instance = it }
        }
    }
}

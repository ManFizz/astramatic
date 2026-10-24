package com.manfis.astramatic.library

import android.content.Context
import android.net.Uri
import android.os.Build
import com.manfis.astramatic.remote.VideoFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/** Библиотека Darkframe на компьютере, к которой телефон подключён как устройство. */
data class DarkframeServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** `http://192.168.0.126:7860` — без слеша в конце. */
    val baseUrl: String,
    /** Токен устройства из подключения по QR: Darkframe принимает его как `Authorization: Bearer`. */
    val token: String,
) {
    /** Хост и порт: по ним видео и превью узнают, какой токен приложить. */
    val authority: String get() = Uri.parse(baseUrl).authority ?: ""
}

class DarkframeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("darkframe", Context.MODE_PRIVATE)

    fun all(): List<DarkframeServer> {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        return List(array.length()) {
            val o = array.getJSONObject(it)
            DarkframeServer(o.getString("id"), o.optString("name"), o.getString("baseUrl"), o.getString("token"))
        }
    }

    fun get(id: String) = all().find { it.id == id }

    fun save(server: DarkframeServer) = write(all().filterNot { it.id == server.id } + server)

    fun delete(id: String) = write(all().filterNot { it.id == id })

    /** Токен для запроса к этому хосту и порту, если это наш Darkframe. */
    fun tokenFor(authority: String?): String? = all().find { it.authority == authority }?.token

    private fun write(servers: List<DarkframeServer>) {
        val array = JSONArray()
        servers.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name).put("baseUrl", it.baseUrl).put("token", it.token))
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "servers"
    }
}

/**
 * Обращения к серверу «доступ с телефона» Darkframe (server/remote/server.js):
 * POST /pair — подключение, POST /rpc — те же вызовы, что у окна Darkframe, /x/library/… — файлы.
 * Все вызовы блокирующие: только из фонового потока.
 */
object DarkframeClient {

    /** Ключ позиции просмотра, как у самого Darkframe (src/js/Services/mediaKey.js): раздел «библиотека». */
    private const val LIBRARY_SECTION = 6

    class Collection(val id: String, val name: String, val parentId: String?, val itemCount: Int, val smartFilter: JSONObject?)

    class Item(
        val id: String,
        val title: String,
        val fileName: String,
        val mimeType: String,
        val size: Long,
        val videoUrl: String,
        val thumbUrl: String,
    ) {
        val isVideo: Boolean get() = mimeType.startsWith("video/") || (mimeType.isEmpty() && VideoFiles.isVideo(fileName))
        val isImage: Boolean get() = mimeType.startsWith("image/") || (mimeType.isEmpty() && VideoFiles.isImage(fileName))
        val displayName: String get() = title.ifBlank { fileName }
    }

    /**
     * Подключение по ссылке из QR-кода (`http://<ip>:7860/pair?c=<код>`).
     * Darkframe отдаёт токен в cookie — забираем его оттуда.
     */
    fun pair(link: String): DarkframeServer {
        val uri = Uri.parse(link.trim())
        val code = uri.getQueryParameter("c") ?: throw IOException("В ссылке нет кода подключения")
        val base = "${uri.scheme ?: "http"}://${uri.authority ?: throw IOException("В ссылке нет адреса компьютера")}"
        val deviceName = "AstraMatic VR (${Build.MODEL})"

        val connection = (URL("$base/pair").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            val form = "c=${URLEncoder.encode(code, "UTF-8")}&name=${URLEncoder.encode(deviceName, "UTF-8")}"
            connection.outputStream.use { it.write(form.toByteArray()) }
            if (connection.responseCode == 403) throw IOException("Код устарел или уже использован — покажите в Darkframe новый QR-код")
            val token = connection.headerFields["Set-Cookie"].orEmpty()
                .firstNotNullOfOrNull { Regex("""df_remote=([^;]+)""").find(it)?.groupValues?.get(1) }
                ?.let { URLDecoder.decode(it, "UTF-8") }
                ?: throw IOException("Darkframe не выдал доступ (ответ ${connection.responseCode})")
            return DarkframeServer(name = "Darkframe ${uri.host}", baseUrl = base, token = token)
        } finally {
            connection.disconnect()
        }
    }

    fun collections(server: DarkframeServer): List<Collection> {
        val array = rpc(server, "library:getCollections") as? JSONArray ?: return emptyList()
        return List(array.length()) { array.getJSONObject(it) }.map { o ->
            Collection(
                id = o.getString("id"),
                name = o.optString("name"),
                parentId = o.optString("parentId").takeIf { it.isNotEmpty() && it != "null" },
                itemCount = o.optInt("itemCount"),
                smartFilter = if (o.optBoolean("isSmart")) {
                    runCatching { JSONObject(o.optString("filter", "{}")) }.getOrDefault(JSONObject())
                } else {
                    null
                },
            )
        }
    }

    /** Содержимое коллекции: подколлекции и элементы. [collectionId] = null — элементы без коллекции. */
    fun items(server: DarkframeServer, collectionId: String?): Pair<List<Collection>, List<Item>> {
        val params = JSONObject().put("collectionId", collectionId ?: JSONObject.NULL)
        val result = rpc(server, "library:getItems", params) as? JSONObject ?: return emptyList<Collection>() to emptyList()
        val children = result.optJSONArray("collections") ?: JSONArray()
        val collections = List(children.length()) { children.getJSONObject(it) }.map {
            Collection(it.getString("id"), it.optString("name"), collectionId, it.optInt("itemCount"), null)
        }
        return collections to parseItems(result.optJSONArray("items"))
    }

    fun smartItems(server: DarkframeServer, filter: JSONObject): List<Item> {
        val result = rpc(server, "library:getSmartItems", JSONObject().put("filter", filter)) as? JSONObject
        return parseItems(result?.optJSONArray("items"))
    }

    /** Позиция просмотра в Darkframe, мс. */
    fun position(server: DarkframeServer, itemId: String): Long? {
        val result = rpc(server, "library:getVideoPosition", positionKey(itemId)) as? JSONObject ?: return null
        return (result.optDouble("position", 0.0) * 1000).toLong().takeIf { it > 0 }
    }

    fun savePosition(server: DarkframeServer, itemId: String, positionMs: Long, durationMs: Long) {
        val params = positionKey(itemId).put("position", positionMs / 1000.0).put("duration", durationMs / 1000.0)
        rpc(server, "library:saveVideoPosition", params)
    }

    private fun positionKey(itemId: String) = JSONObject().put("section", LIBRARY_SECTION).put("key", "lib:$itemId")

    private fun parseItems(array: JSONArray?): List<Item> {
        if (array == null) return emptyList()
        return List(array.length()) { array.getJSONObject(it) }.map {
            Item(
                id = it.getString("id"),
                title = it.optString("title"),
                fileName = it.optString("fileName"),
                mimeType = it.optString("mimeType"),
                size = it.optLong("size"),
                videoUrl = it.optString("originalPath"),
                thumbUrl = it.optString("thumbPath"),
            )
        }
    }

    /** Вызов того же обработчика, что у окна Darkframe. Аргументы — как у ipcRenderer.invoke. */
    private fun rpc(server: DarkframeServer, channel: String, vararg args: Any): Any? {
        val connection = (URL("${server.baseUrl}/rpc").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${server.token}")
            // Darkframe принимает вызовы только с этим заголовком (защита от запросов с чужих сайтов).
            setRequestProperty("X-Darkframe", "1")
        }
        try {
            val body = JSONObject().put("channel", channel).put("args", JSONArray(args.toList()))
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            when (connection.responseCode) {
                401 -> throw IOException("Darkframe не узнаёт это устройство — подключите его заново по QR-коду")
                403 -> throw IOException("Darkframe запретил это действие для устройства")
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            if (json.has("error")) throw IOException(json.optString("error"))
            return json.opt("result")?.takeIf { it != JSONObject.NULL }
        } finally {
            connection.disconnect()
        }
    }

    private const val TIMEOUT_MS = 15_000
}

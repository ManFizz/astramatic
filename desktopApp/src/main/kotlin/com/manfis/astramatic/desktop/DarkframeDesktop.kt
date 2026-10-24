package com.manfis.astramatic.desktop

import com.manfis.astramatic.remote.VideoFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Darkframe с этого ПК — тем же путём, что и телефон: устройство в «Доступе с телефона»
 * (POST /pair → токен, POST /rpc → вызовы библиотеки). Блокирующие вызовы — только не из UI-потока.
 */
object DarkframeDesktop {

    /** Папка списка: коллекция (`c:<id>`, `s:<id>` для умной, `none` — без коллекции) или видео. */
    data class Entry(val name: String, val path: String, val isFolder: Boolean)

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Ссылка из Darkframe → «Устройства» → «Подключить устройство»: `http://<ip>:7860/pair?c=<код>`. */
    fun pair(link: String): DarkframeLink {
        val uri = URI(link.trim())
        val code = uri.rawQuery?.split('&')?.map { it.split('=', limit = 2) }
            ?.firstOrNull { it[0] == "c" }?.getOrNull(1)
            ?.let { URLDecoder.decode(it, Charsets.UTF_8) }
            ?: throw IOException("В ссылке нет кода подключения")
        val base = "${uri.scheme}://${uri.authority}"
        val name = "AstraMatic ПК"
        val body = "c=${URLEncoder.encode(code, Charsets.UTF_8)}&name=${URLEncoder.encode(name, Charsets.UTF_8)}"
        val response = http.send(
            HttpRequest.newBuilder(URI("$base/pair"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        if (response.statusCode() == 403) throw IOException("Код устарел или уже использован — нажмите в Darkframe «Подключить устройство» ещё раз")
        val token = response.headers().allValues("set-cookie")
            .firstNotNullOfOrNull { Regex("""df_remote=([^;]+)""").find(it)?.groupValues?.get(1) }
            ?.let { URLDecoder.decode(it, Charsets.UTF_8) }
            ?: throw IOException("Darkframe не выдал доступ (ответ ${response.statusCode()})")
        return DarkframeLink(base, token).also(::collections)
    }

    private class Collection(val id: String, val name: String, val parentId: String?, val smartFilter: JsonElement?)

    private fun collections(link: DarkframeLink): List<Collection> =
        (rpc(link, "library:getCollections") as? JsonArray).orEmpty().map { it.jsonObject }.map { o ->
            Collection(
                id = o.string("id")!!,
                name = o.string("name").orEmpty(),
                parentId = o.string("parentId"),
                smartFilter = if (o["isSmart"]?.jsonPrimitive?.booleanOrNull == true) {
                    runCatching { json.parseToJsonElement(o.string("filter") ?: "{}") }.getOrDefault(JsonObject(emptyMap()))
                } else {
                    null
                },
            )
        }

    /** Содержимое пути: как у телефона, чтобы команда «открыть» нашла ту же коллекцию. Только видео. */
    fun list(link: DarkframeLink, path: String): List<Entry> {
        fun folder(c: Collection) = Entry(c.name, if (c.smartFilter != null) "s:${c.id}" else "c:${c.id}", isFolder = true)
        return when {
            path.isEmpty() -> collections(link).filter { it.parentId == null }.map(::folder) + Entry("Без коллекции", "none", true)
            path == "none" -> videos(itemsOf(link, JsonNull))
            path.startsWith("s:") -> {
                val filter = collections(link).find { it.id == path.removePrefix("s:") }?.smartFilter ?: return emptyList()
                videos((rpc(link, "library:getSmartItems", buildJsonObject { put("filter", filter) }) as? JsonObject)?.get("items"))
            }
            else -> {
                val result = rpc(link, "library:getItems", buildJsonObject { put("collectionId", path.removePrefix("c:")) }) as? JsonObject
                val children = (result?.get("collections") as? JsonArray).orEmpty().map { it.jsonObject }
                    .map { Entry(it.string("name").orEmpty(), "c:${it.string("id")}", isFolder = true) }
                children + videos(result?.get("items"))
            }
        }
    }

    private fun itemsOf(link: DarkframeLink, collectionId: JsonElement): JsonElement? =
        (rpc(link, "library:getItems", buildJsonObject { put("collectionId", collectionId) }) as? JsonObject)?.get("items")

    private fun videos(items: JsonElement?): List<Entry> =
        (items as? JsonArray).orEmpty().map { it.jsonObject }
            .filter { it.string("mimeType").orEmpty().startsWith("video/") || VideoFiles.isVideo(it.string("fileName").orEmpty()) }
            .map { Entry(it.string("title").orEmpty().ifBlank { it.string("fileName").orEmpty() }, it.string("id")!!, isFolder = false) }

    private fun rpc(link: DarkframeLink, channel: String, vararg args: JsonElement): JsonElement? {
        val body = buildJsonObject {
            put("channel", channel)
            put("args", buildJsonArray { args.forEach { add(it) } })
        }
        val response = http.send(
            HttpRequest.newBuilder(URI("${link.baseUrl}/rpc"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${link.token}")
                // Darkframe принимает вызовы только с этим заголовком.
                .header("X-Darkframe", "1")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        when (response.statusCode()) {
            401 -> throw IOException("Darkframe не узнаёт этот ПК — подключите заново")
            403 -> throw IOException("Darkframe запретил это действие")
        }
        val result = json.parseToJsonElement(response.body()).jsonObject
        result.string("error")?.let { throw IOException(it) }
        return result["result"]?.takeIf { it != JsonNull }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }
}

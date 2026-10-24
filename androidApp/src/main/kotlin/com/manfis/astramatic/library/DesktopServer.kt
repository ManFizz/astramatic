package com.manfis.astramatic.library

import android.content.Context
import android.net.Uri
import android.os.Build
import com.manfis.astramatic.remote.FolderEntry
import com.manfis.astramatic.remote.RemoteApi
import com.manfis.astramatic.remote.ServerInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** AstraMatic на ПК (desktopApp): общие папки и пульт. */
data class DesktopServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** `http://192.168.0.126:47860` — без слеша в конце. */
    val baseUrl: String,
    /** Ключ из QR-кода, идёт в `Authorization: Bearer`. */
    val key: String,
) {
    val authority: String get() = Uri.parse(baseUrl).authority ?: ""

    fun fileUri(folderId: String, path: String): Uri =
        Uri.parse(baseUrl).buildUpon().appendPath("file").appendPath(folderId).apply {
            path.split('/').filter { it.isNotEmpty() }.forEach { appendPath(it) }
        }.build()

    val webSocketUrl: String get() = baseUrl.replaceFirst("http", "ws") + "/ws"
}

class DesktopStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("desktop", Context.MODE_PRIVATE)

    fun all(): List<DesktopServer> {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        return List(array.length()) {
            val o = array.getJSONObject(it)
            DesktopServer(o.getString("id"), o.optString("name"), o.getString("baseUrl"), o.getString("key"))
        }
    }

    fun get(id: String) = all().find { it.id == id }

    fun save(server: DesktopServer) = write(all().filterNot { it.id == server.id } + server)

    fun delete(id: String) = write(all().filterNot { it.id == id })

    private fun write(servers: List<DesktopServer>) {
        val array = JSONArray()
        servers.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name).put("baseUrl", it.baseUrl).put("key", it.key))
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "servers"
    }
}

/** Блокирующие вызовы к серверу на ПК: только из фонового потока. */
object DesktopClient {

    /** Ссылка из QR-кода на ПК: `http://<ip>:<порт>/pair?key=<ключ>`. Проверяет, что ключ подходит. */
    fun pair(link: String): DesktopServer {
        val uri = Uri.parse(link.trim())
        val key = uri.getQueryParameter("key") ?: throw IOException("В ссылке нет ключа подключения")
        val base = "${uri.scheme ?: "http"}://${uri.authority ?: throw IOException("В ссылке нет адреса компьютера")}"
        val probe = DesktopServer(name = "", baseUrl = base, key = key)
        return probe.copy(name = info(probe).name)
    }

    fun info(server: DesktopServer): ServerInfo = RemoteApi.json.decodeFromString(get(server, "/api/info"))

    fun list(server: DesktopServer, folderId: String, path: String): List<FolderEntry> {
        val query = "/api/list?folder=${Uri.encode(folderId)}&path=${Uri.encode(path)}"
        return RemoteApi.json.decodeFromString(get(server, query))
    }

    private fun get(server: DesktopServer, pathAndQuery: String): String {
        val connection = (URL(server.baseUrl + pathAndQuery).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer ${server.key}")
            // ПК показывает список подключённых телефонов по этому имени.
            setRequestProperty(RemoteApi.DEVICE_HEADER, Build.MODEL)
        }
        try {
            when (connection.responseCode) {
                200 -> return connection.inputStream.bufferedReader().use { it.readText() }
                401 -> throw IOException("Ключ не подходит — на ПК его сменили, подключите телефон заново")
                404 -> throw IOException("На ПК такой папки больше нет")
                else -> throw IOException("ПК ответил ошибкой ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private const val TIMEOUT_MS = 15_000
}

/** Какой заголовок авторизации приложить к запросу на этот хост: Darkframe или AstraMatic на ПК. */
class RemoteAuth(context: Context) {
    private val darkframes = DarkframeStore(context)
    private val desktops = DesktopStore(context)

    fun tokenFor(authority: String?): String? {
        if (authority == null) return null
        return darkframes.tokenFor(authority) ?: desktops.all().find { it.authority == authority }?.key
    }
}

package com.manfis.astramatic.library

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Общая папка на компьютере. Пустой [user] — гостевой вход. */
data class SmbServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val share: String,
    val user: String = "",
    val password: String = "",
    val domain: String = "",
) {
    val title: String get() = name.ifBlank { "$host\\$share" }

    companion object {
        /**
         * Разбирает адрес в любом привычном виде: `\\PC\Videos`, `PC/Videos`, `smb://192.168.1.5/Videos/VR`.
         * Возвращает хост и имя общей папки; вложенный путь отбрасывается.
         */
        fun parseAddress(address: String): Pair<String, String>? {
            val parts = address.trim()
                .removePrefix("smb://")
                .replace('\\', '/')
                .split('/')
                .filter { it.isNotBlank() }
            if (parts.size < 2) return null
            return parts[0] to parts[1]
        }
    }
}

/**
 * Список компьютеров в личных настройках приложения.
 * Пароли хранятся открытым текстом во внутренней памяти приложения, недоступной другим программам.
 */
class SmbServerStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("smb_servers", Context.MODE_PRIVATE)

    fun all(): List<SmbServer> {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        return List(array.length()) { array.getJSONObject(it).toServer() }
    }

    fun get(id: String): SmbServer? = all().find { it.id == id }

    fun save(server: SmbServer) {
        val servers = all().filterNot { it.id == server.id } + server
        write(servers)
    }

    fun delete(id: String) = write(all().filterNot { it.id == id })

    private fun write(servers: List<SmbServer>) {
        val array = JSONArray()
        servers.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun SmbServer.toJson() = JSONObject()
        .put("id", id).put("name", name).put("host", host).put("share", share)
        .put("user", user).put("password", password).put("domain", domain)

    private fun JSONObject.toServer() = SmbServer(
        id = getString("id"),
        name = optString("name"),
        host = getString("host"),
        share = getString("share"),
        user = optString("user"),
        password = optString("password"),
        domain = optString("domain"),
    )

    private companion object {
        const val KEY = "servers"
    }
}

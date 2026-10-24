package com.manfis.astramatic.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Телефон ⇄ AstraMatic на ПК.
 *
 * HTTP (заголовок `Authorization: Bearer <ключ>` из QR-кода):
 *   GET /api/info                        → [ServerInfo]
 *   GET /api/list?folder=<id>&path=<…>   → список [FolderEntry]
 *   GET /file/<папка>/<путь>             → файл, с Range
 *   WS  /ws                              → телефон шлёт [PlayerStatus], ПК — [RemoteCommand]
 * Ссылка подключения из QR: `http://<ip>:<порт>/pair?key=<ключ>`.
 */
object RemoteApi {
    const val DEFAULT_PORT = 47860

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }
}

@Serializable
data class ServerInfo(val name: String, val folders: List<SharedFolder>)

@Serializable
data class SharedFolder(val id: String, val name: String)

@Serializable
data class FolderEntry(val name: String, val path: String, val isDirectory: Boolean, val size: Long = 0)

/** Что играет на телефоне: ПК показывает это в пульте. */
@Serializable
data class PlayerStatus(
    val device: String,
    val title: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val stereo: Boolean = true,
    /** [com.manfis.astramatic.model.VideoFormat.encode]. */
    val format: String? = null,
)

/** Команды пульта с ПК. */
@Serializable
sealed interface RemoteCommand {
    @Serializable @SerialName("toggle") data object TogglePlayback : RemoteCommand
    @Serializable @SerialName("seekBy") data class SeekBy(val deltaMs: Long) : RemoteCommand
    @Serializable @SerialName("seekTo") data class SeekTo(val positionMs: Long) : RemoteCommand
    @Serializable @SerialName("prev") data object PreviousFile : RemoteCommand
    @Serializable @SerialName("next") data object NextFile : RemoteCommand
    @Serializable @SerialName("recenter") data object Recenter : RemoteCommand
    @Serializable @SerialName("stereo") data object ToggleStereo : RemoteCommand
    @Serializable @SerialName("format") data class SetFormat(val format: String) : RemoteCommand

    /** Открыть на телефоне видео из общей папки ПК (вместе с остальными видео этой папки). */
    @Serializable @SerialName("open") data class Open(val folderId: String, val path: String) : RemoteCommand
}

object VideoFiles {
    private val extensions = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts", "mts", "wmv", "flv", "3gp", "mpg", "mpeg",
    )

    fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in extensions
}

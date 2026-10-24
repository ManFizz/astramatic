package com.manfis.astramatic.desktop

import com.manfis.astramatic.remote.FolderEntry
import com.manfis.astramatic.remote.RemoteApi
import com.manfis.astramatic.remote.VideoFiles
import kotlinx.serialization.Serializable
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Папка на ПК, открытая телефону. */
@Serializable
data class FolderConfig(val id: String = UUID.randomUUID().toString(), val name: String, val path: String)

@Serializable
data class DesktopConfig(
    val port: Int = RemoteApi.DEFAULT_PORT,
    /** Секрет из QR-кода: без него сервер ничего не отдаёт. */
    val key: String = newKey(),
    val folders: List<FolderConfig> = emptyList(),
)

fun newKey(): String {
    val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** `%USERPROFILE%/.astramatic/desktop.json`. */
object ConfigStore {
    private val file = File(System.getProperty("user.home"), ".astramatic/desktop.json")

    fun load(): DesktopConfig =
        runCatching { RemoteApi.json.decodeFromString<DesktopConfig>(file.readText()) }.getOrNull()
            ?: DesktopConfig().also(::save)

    fun save(config: DesktopConfig) {
        file.parentFile.mkdirs()
        file.writeText(RemoteApi.json.encodeToString(config))
    }
}

/**
 * Содержимое папки внутри общей: подпапки и видео, папки сначала.
 * Путь не выходит за пределы общей папки (защита от `..`).
 */
object FolderBrowser {
    fun resolve(folder: FolderConfig, path: String): File? {
        val root = File(folder.path).canonicalFile
        val target = File(root, path).canonicalFile
        return target.takeIf { it == root || it.path.startsWith(root.path + File.separator) }
    }

    fun list(folder: FolderConfig, path: String): List<FolderEntry>? {
        val dir = resolve(folder, path)?.takeIf { it.isDirectory } ?: return null
        return dir.listFiles().orEmpty()
            .filter { !it.isHidden && (it.isDirectory || VideoFiles.isVideo(it.name)) }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .map {
                FolderEntry(
                    name = it.name,
                    path = if (path.isEmpty()) it.name else "$path/${it.name}",
                    isDirectory = it.isDirectory,
                    size = if (it.isFile) it.length() else 0,
                )
            }
    }
}

/** Адреса ПК в домашней сети. VPN и виртуальные адаптеры — в конце: телефон через них обычно не достучится. */
fun lanAddresses(): List<String> {
    val virtual = Regex("(?i)vpn|tun|tap|tailscale|radmin|zerotier|hamachi|virtual|vmware|vbox|hyper-v|vethernet|wsl|docker|karing")
    return NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nic to it } }
        .filter { (_, address) -> address.isSiteLocalAddress }
        .sortedBy { (nic, address) ->
            when {
                virtual.containsMatchIn(nic.displayName + nic.name) -> 2
                address.hostAddress.startsWith("192.168.") -> 0
                else -> 1
            }
        }
        .map { (_, address) -> address.hostAddress }
        .distinct()
}

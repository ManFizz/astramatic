package com.manfis.astramatic.library

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * Держит по одному подключению на сервер и переподключается, если оно оборвалось.
 * Пути внутри общей папки пишутся через `/`, корень — пустая строка.
 */
object SmbConnections {

    private val client = SMBClient(
        SmbConfig.builder()
            // Встроенная в Android криптография не умеет MD4, нужный для входа по NTLM.
            .withSecurityProvider(BCSecurityProvider())
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .withReadBufferSize(READ_CHUNK_BYTES)
            .build()
    )

    private val shares = mutableMapOf<String, DiskShare>()

    class Entry(val name: String, val isDirectory: Boolean, val size: Long)

    fun list(server: SmbServer, path: String): List<Entry> =
        share(server).list(path.toSmbPath())
            .filter { it.fileName != "." && it.fileName != ".." }
            .filterNot { EnumWithValue.EnumUtils.isSet(it.fileAttributes, FileAttributes.FILE_ATTRIBUTE_HIDDEN) }
            .map {
                Entry(
                    name = it.fileName,
                    isDirectory = EnumWithValue.EnumUtils.isSet(it.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
                    size = it.endOfFile,
                )
            }

    fun openFile(server: SmbServer, path: String): File =
        share(server).openFile(
            path.toSmbPath(),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )

    /** Сбросить подключение, например после изменения логина или пароля. */
    @Synchronized
    fun forget(serverId: String) {
        shares.remove(serverId)?.let { runCatching { it.close() } }
    }

    @Synchronized
    private fun share(server: SmbServer): DiskShare {
        shares[server.id]?.let { if (it.isConnected) return it }
        val connection = client.connect(server.host)
        val auth = if (server.user.isBlank()) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(server.user, server.password.toCharArray(), server.domain.ifBlank { null })
        }
        val share = connection.authenticate(auth).connectShare(server.share) as? DiskShare
            ?: error("«${server.share}» — не папка с файлами")
        shares[server.id] = share
        return share
    }

    private fun String.toSmbPath() = trim('/').replace('/', '\\')

    const val READ_CHUNK_BYTES = 4 * 1024 * 1024
}

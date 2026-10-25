package com.manfis.astramatic.library

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.SocketFactory

/**
 * Держит по одному подключению на сервер и переподключается, если оно оборвалось.
 * Пути внутри общей папки пишутся через `/`, корень — пустая строка.
 *
 * Пока телефон спал, сервер или роутер мог тихо закрыть соединение, а smbj всё ещё считает его живым:
 * первый же запрос падает с «Broken pipe». Поэтому при сетевой ошибке подключение выбрасываем и повторяем один раз.
 */
object SmbConnections {

    private const val TAG = "SmbConnections"

    private val client = SMBClient(
        SmbConfig.builder()
            // Встроенная в Android криптография не умеет MD4, нужный для входа по NTLM.
            .withSecurityProvider(BCSecurityProvider())
            .withSocketFactory(TimeoutSocketFactory)
            .withTimeout(15, TimeUnit.SECONDS)
            // Блок видео — 4 МБ, по слабому Wi-Fi он идёт дольше обычного ответа.
            .withReadTimeout(45, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .withReadBufferSize(READ_CHUNK_BYTES)
            .build()
    )

    private val shares = ConcurrentHashMap<String, DiskShare>()

    /** Подключаемся к каждому серверу под своим замком: пока один долго отвечает, другие не ждут. */
    private val locks = ConcurrentHashMap<String, Any>()

    class Entry(val name: String, val isDirectory: Boolean, val size: Long)

    fun list(server: SmbServer, path: String): List<Entry> = withShare(server) { share ->
        share.list(path.toSmbPath())
            .filter { it.fileName != "." && it.fileName != ".." }
            .filterNot { EnumWithValue.EnumUtils.isSet(it.fileAttributes, FileAttributes.FILE_ATTRIBUTE_HIDDEN) }
            .map {
                Entry(
                    name = it.fileName,
                    isDirectory = EnumWithValue.EnumUtils.isSet(it.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
                    size = it.endOfFile,
                )
            }
    }

    fun openFile(server: SmbServer, path: String): File = withShare(server) { share ->
        share.openFile(
            path.toSmbPath(),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
    }

    /** Сбросить подключение, например после изменения логина или пароля. */
    fun forget(serverId: String) {
        shares.remove(serverId)?.let(::close)
    }

    /** Оборвалась ли связь (а не, скажем, «нет доступа»): тогда есть смысл переподключиться и повторить. */
    fun Throwable.isConnectionLost(): Boolean {
        val chain = generateSequence(this) { it.cause }.toList()
        if (chain.any { it is SMBApiException }) return false
        return chain.any { it is IOException || it is TimeoutException }
    }

    private inline fun <T> withShare(server: SmbServer, block: (DiskShare) -> T): T {
        val share = share(server)
        return try {
            block(share)
        } catch (e: Exception) {
            if (!e.isConnectionLost()) throw e
            Log.w(TAG, "Связь с ${server.host} оборвалась, переподключаюсь: $e")
            // Убираем только то подключение, на котором упали: другой поток мог уже открыть новое.
            if (shares.remove(server.id, share)) close(share)
            block(share(server))
        }
    }

    private fun share(server: SmbServer): DiskShare {
        shares[server.id]?.let { if (it.isConnected) return it }
        synchronized(locks.getOrPut(server.id) { Any() }) {
            shares[server.id]?.let { if (it.isConnected) return it }
            shares.remove(server.id)?.let(::close)
            Log.i(TAG, "Подключаюсь к ${server.host}\\${server.share}")
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
    }

    /**
     * Закрываем само соединение, а не только папку: SMBClient хранит соединения по адресу
     * и иначе снова выдал бы то же мёртвое.
     */
    private fun close(share: DiskShare) {
        runCatching { share.treeConnect.session.connection.close(true) }
    }

    private fun String.toSmbPath() = trim('/').replace('/', '\\')

    const val READ_CHUNK_BYTES = 4 * 1024 * 1024

    /** smbj подключается без таймаута, и недоступный компьютер держал бы экран загрузки минуту и больше. */
    private object TimeoutSocketFactory : SocketFactory() {
        private const val CONNECT_TIMEOUT_MS = 10_000

        override fun createSocket(): Socket = Socket()

        override fun createSocket(host: String, port: Int): Socket =
            Socket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }

        override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            Socket().apply {
                bind(InetSocketAddress(localHost, localPort))
                connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }

        override fun createSocket(host: InetAddress, port: Int): Socket =
            Socket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            Socket().apply {
                bind(InetSocketAddress(localAddress, localPort))
                connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            }
    }
}

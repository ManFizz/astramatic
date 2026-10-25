@file:OptIn(UnstableApi::class)

package com.manfis.astramatic.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import com.hierynomus.smbj.share.File
import com.manfis.astramatic.library.RemoteAuth
import com.manfis.astramatic.library.SmbConnections
import com.manfis.astramatic.library.SmbServer
import com.manfis.astramatic.library.SmbServerStore
import com.manfis.astramatic.library.SmbUris
import java.io.IOException
import kotlin.math.min

/** Отдаёт `smb://` в [SmbDataSource], всё остальное (файлы, content://, http) — стандартному источнику Media3. */
class AppDataSource private constructor(
    private val smb: SmbDataSource,
    private val default: DataSource,
) : DataSource {

    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        smb.addTransferListener(transferListener)
        default.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == SmbUris.SCHEME) smb else default
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: error("Источник не открыт")

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }

    class Factory(context: Context) : DataSource.Factory {
        private val context = context.applicationContext
        private val servers = SmbServerStore(context)

        private val auth = RemoteAuth(context)

        override fun createDataSource(): DataSource {
            // Darkframe и AstraMatic на ПК пускают к файлам только подключённое устройство: прикладываем его токен.
            val withAuth = ResolvingDataSource.Factory(DefaultDataSource.Factory(context)) { spec ->
                val token = auth.tokenFor(spec.uri.authority)
                if (token == null) spec else spec.withAdditionalHeaders(mapOf("Authorization" to "Bearer $token"))
            }
            return AppDataSource(SmbDataSource(servers), withAuth.createDataSource())
        }
    }
}

/**
 * Чтение видео из общей папки. ExoPlayer просит данные маленькими порциями,
 * а каждый запрос по SMB — это поход по сети, поэтому читаем крупными блоками в буфер.
 */
class SmbDataSource(private val servers: SmbServerStore) : BaseDataSource(/* isNetwork = */ true) {

    private var dataSpec: DataSpec? = null
    private var server: SmbServer? = null
    private var file: File? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    private val buffer = ByteArray(SmbConnections.READ_CHUNK_BYTES)
    private var bufferStart = 0L
    private var bufferLength = 0

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)
        val server = servers.get(SmbUris.serverId(dataSpec.uri)) ?: throw IOException("Компьютер не найден в списке")
        this.server = server
        // Устаревшее подключение SmbConnections переоткроет сам.
        val file = try {
            SmbConnections.openFile(server, SmbUris.path(dataSpec.uri))
        } catch (e: Exception) {
            throw IOException("Не удалось открыть файл по SMB", e)
        }
        this.file = file

        val fileLength = file.fileInformation.standardInformation.endOfFile
        position = dataSpec.position
        if (position > fileLength) throw IOException("Позиция за концом файла")
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else fileLength - position
        bufferLength = 0

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        if (position < bufferStart || position >= bufferStart + bufferLength) fill()
        if (bufferLength == 0) return C.RESULT_END_OF_INPUT

        val inBuffer = (position - bufferStart).toInt()
        val count = min(min(length.toLong(), bytesRemaining), (bufferLength - inBuffer).toLong()).toInt()
        System.arraycopy(buffer, inBuffer, target, offset, count)
        position += count
        bytesRemaining -= count
        bytesTransferred(count)
        return count
    }

    private fun fill() {
        bufferStart = position
        bufferLength = 0
        val wanted = min(buffer.size.toLong(), bytesRemaining).toInt()
        var retries = 0
        // Сервер может отдать меньше, чем просили, поэтому дочитываем в цикле.
        while (bufferLength < wanted) {
            val read = try {
                val file = file ?: throw IOException("Файл не открыт")
                file.read(buffer, bufferStart + bufferLength, bufferLength, wanted - bufferLength)
            } catch (e: Exception) {
                // Связь оборвалась посреди видео (телефон уснул на паузе, Wi-Fi моргнул):
                // открываем файл заново и дочитываем блок с того же места.
                if (retries >= MAX_READ_RETRIES || !with(SmbConnections) { e.isConnectionLost() }) {
                    throw IOException("Ошибка чтения по SMB", e)
                }
                retries++
                Log.w(TAG, "Чтение по SMB оборвалось, открываю файл заново ($retries): $e")
                reopen(e)
                continue
            }
            if (read <= 0) break
            bufferLength += read
        }
    }

    private fun reopen(cause: Exception) {
        runCatching { file?.close() }
        file = null
        val server = server ?: throw IOException("Файл не открыт", cause)
        val uri = dataSpec?.uri ?: throw IOException("Файл не открыт", cause)
        file = try {
            SmbConnections.openFile(server, SmbUris.path(uri))
        } catch (e: Exception) {
            throw IOException("Не удалось заново открыть файл по SMB", e.apply { addSuppressed(cause) })
        }
    }

    override fun getUri(): Uri? = dataSpec?.uri

    override fun close() {
        dataSpec = null
        server = null
        bufferLength = 0
        try {
            file?.close()
        } catch (_: Exception) {
        } finally {
            file = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private companion object {
        const val TAG = "SmbDataSource"
        const val MAX_READ_RETRIES = 2
    }
}

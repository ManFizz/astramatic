package com.manfis.astramatic.library

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Превью для видео по SMB: системный извлекатель кадров читает файл с компьютера кусками по запросу.
 * Готовый кадр кладётся в кеш приложения, второй раз сеть не нужна.
 */
class SmbThumbnailFetcher(
    private val uri: android.net.Uri,
    private val context: Context,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val file = cacheFile()
        if (!file.exists()) {
            // Одновременно читать много файлов по сети — только мешать воспроизведению и друг другу.
            limit.withPermit { if (!file.exists()) extractTo(file) }
        }
        return SourceFetchResult(
            source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    private fun cacheFile(): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "smb-thumbs").apply { mkdirs() }, "$hash.jpg")
    }

    private fun extractTo(target: File) {
        val server = SmbServerStore(context).get(SmbUris.serverId(uri)) ?: throw IOException("Компьютер удалён")
        SmbConnections.openFile(server, SmbUris.path(uri)).use { smbFile ->
            val length = smbFile.fileInformation.standardInformation.endOfFile
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(object : MediaDataSource() {
                    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                        if (position >= length) return -1
                        return smbFile.read(buffer, position, offset, size)
                    }

                    override fun getSize() = length
                    override fun close() = Unit
                })
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                // Не самый первый кадр: в начале часто чёрный экран или заставка.
                val timeUs = (durationMs * 1000 / 10).coerceAtMost(60_000_000)
                val frame = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_WIDTH * 2, THUMB_WIDTH * 2)
                    ?: throw IOException("Не удалось достать кадр")
                val picture = oneEye(frame)
                target.outputStream().use { picture.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            } finally {
                retriever.release()
            }
        }
    }

    /** Стерео-кадр показываем одним глазом: левая половина у широких, верхняя — у высоких. */
    private fun oneEye(frame: Bitmap): Bitmap = when {
        frame.width >= frame.height * 1.9 -> Bitmap.createBitmap(frame, 0, 0, frame.width / 2, frame.height)
        frame.height > frame.width -> Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height / 2)
        else -> frame
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != SmbUris.SCHEME) return null
            return SmbThumbnailFetcher(android.net.Uri.parse(data.toString()), context.applicationContext)
        }
    }

    private companion object {
        const val THUMB_WIDTH = 320
        val limit = Semaphore(2)
    }
}

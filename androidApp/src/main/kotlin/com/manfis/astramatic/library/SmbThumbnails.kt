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
import android.graphics.BitmapFactory
import com.manfis.astramatic.remote.VideoFiles
import okio.Buffer
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Превью для видео по SMB и с AstraMatic на ПК (`httpvideo:`): системный извлекатель кадров читает файл с компьютера кусками по запросу.
 * Готовый кадр кладётся в кеш приложения, второй раз сеть не нужна.
 */
class SmbThumbnailFetcher(
    private val uri: android.net.Uri,
    private val context: Context,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        // Фото по SMB целиком (для просмотра): отдаём файл как есть, Coil сам его декодирует.
        if (uri.scheme == SmbUris.SCHEME && VideoFiles.isImage(uri.path.orEmpty())) {
            val bytes = limit.withPermit { readSmb(uri) }
            return SourceFetchResult(
                source = ImageSource(Buffer().write(bytes), FileSystem.SYSTEM),
                mimeType = null,
                dataSource = DataSource.NETWORK,
            )
        }
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

    private fun readSmb(smbUri: android.net.Uri): ByteArray {
        val server = SmbServerStore(context).get(SmbUris.serverId(smbUri)) ?: throw IOException("Компьютер удалён")
        return SmbConnections.openFile(server, SmbUris.path(smbUri)).use { file ->
            file.inputStream.use { it.readBytes() }
        }
    }

    /** Превью фото по SMB: уменьшенная копия, чтобы не тянуть оригинал при каждом показе папки. */
    private fun thumbnailOfImage(target: File) {
        val bytes = readSmb(android.net.Uri.parse(uri.toString().removePrefix("$SMB_THUMB_SCHEME:")))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_WIDTH * 2) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Не удалось прочитать фото")
        target.outputStream().use { oneEye(bitmap).compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    private fun extractTo(target: File) {
        if (uri.scheme == SMB_THUMB_SCHEME) return thumbnailOfImage(target)
        if (uri.scheme == HTTP_VIDEO_SCHEME) return extractHttp(target)
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
                saveFrame(retriever, target)
            } finally {
                retriever.release()
            }
        }
    }

    /** Видео с AstraMatic на ПК: извлекатель сам читает его по HTTP кусками (Range) с ключом. */
    private fun extractHttp(target: File) {
        val url = uri.toString().removePrefix("$HTTP_VIDEO_SCHEME:")
        val token = RemoteAuth(context).tokenFor(android.net.Uri.parse(url).authority)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(url, token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap())
            saveFrame(retriever, target)
        } finally {
            retriever.release()
        }
    }

    private fun saveFrame(retriever: MediaMetadataRetriever, target: File) {
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        // Не самый первый кадр: в начале часто чёрный экран или заставка.
        val timeUs = (durationMs * 1000 / 10).coerceAtMost(60_000_000)
        val frame = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_WIDTH * 2, THUMB_WIDTH * 2)
            ?: throw IOException("Не удалось достать кадр")
        target.outputStream().use { oneEye(frame).compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    /** Стерео-кадр показываем одним глазом: левая половина у широких, верхняя — у высоких. */
    private fun oneEye(frame: Bitmap): Bitmap = when {
        frame.width >= frame.height * 1.9 -> Bitmap.createBitmap(frame, 0, 0, frame.width / 2, frame.height)
        frame.height > frame.width -> Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height / 2)
        else -> frame
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != SmbUris.SCHEME && data.scheme != HTTP_VIDEO_SCHEME && data.scheme != SMB_THUMB_SCHEME) return null
            return SmbThumbnailFetcher(android.net.Uri.parse(data.toString()), context.applicationContext)
        }
    }

    companion object {
        /** Превью видео по HTTP: `httpvideo:<url>` — чтобы Coil не скачивал весь файл обычным загрузчиком. */
        const val HTTP_VIDEO_SCHEME = "httpvideo"

        /** Превью фото по SMB: `smbthumb:<smb-ссылка>`. */
        const val SMB_THUMB_SCHEME = "smbthumb"

        private const val THUMB_WIDTH = 320
        private val limit = Semaphore(2)
    }
}

package com.manfis.astramatic.ui

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource

/** Понятное сообщение для частых ошибок сети, SMB и доступа. */
fun Throwable.describe(): String {
    val chain = generateSequence(this) { it.cause }.toList()
    val text = chain.joinToString(" ") { "${it::class.simpleName} ${it.message}" }
    return when {
        "STATUS_LOGON_FAILURE" in text -> "Неверный логин или пароль."
        "STATUS_ACCESS_DENIED" in text -> "Нет доступа к папке для этого пользователя."
        "STATUS_BAD_NETWORK_NAME" in text -> "Общая папка с таким именем не найдена."
        "STATUS_OBJECT_NAME_NOT_FOUND" in text || "STATUS_OBJECT_PATH_NOT_FOUND" in text -> "Файл или папка не найдены."
        chain.any { it is java.net.UnknownHostException } -> "Компьютер с таким именем не найден. Попробуйте IP-адрес."
        chain.any { it is java.net.ConnectException || it is java.net.SocketTimeoutException } ->
            "Компьютер не отвечает. Проверьте, что он включён, в той же сети и доступ разрешён в брандмауэре."
        else -> chain.last().message ?: chain.last()::class.simpleName ?: "Неизвестная ошибка"
    }
}

/** Ошибка плеера человеческим языком: что случилось и что можно сделать. */
@OptIn(UnstableApi::class)
fun PlaybackException.describe(): String {
    val http = generateSequence<Throwable>(this) { it.cause }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
    return when (errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "Файл не найден — возможно, его удалили или переместили."
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            "Нет доступа к файлу."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when (http?.responseCode) {
            401, 403 -> "Компьютер не пускает: подключите телефон к нему заново."
            404 -> "На компьютере этого файла больше нет."
            else -> "Компьютер ответил ошибкой ${http?.responseCode ?: ""}."
        }
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Нет связи с компьютером. Проверьте, что он включён и телефон в той же сети."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
            "Файл повреждён или недокачан."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
            "Это не видео или формат файла не поддерживается."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
            "Телефон не тянет это видео: слишком большое разрешение или частота кадров для его декодера."
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "Телефон не умеет такой кодек."
        PlaybackException.ERROR_CODE_DECODING_FAILED ->
            "Ошибка при декодировании — скорее всего, файл повреждён."
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED ->
            (cause?.describe() ?: "Ошибка чтения файла.")
        else -> "Не удалось воспроизвести ($errorCodeName)."
    }
}

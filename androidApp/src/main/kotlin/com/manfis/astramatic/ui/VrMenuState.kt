package com.manfis.astramatic.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.manfis.astramatic.model.VideoFormat
import com.manfis.astramatic.player.Playlist
import com.manfis.astramatic.player.PlaylistItem
import com.manfis.astramatic.player.SideCards
import com.manfis.astramatic.settings.RemoteAction

/** Состояние интерфейса плеера; живёт в активности, поэтому переживает переключение VR ⇄ 2D. */
class VrMenuState {
    /** Компактная полоска управления воспроизведением или большое меню. */
    enum class Mode { Controls, Menu }

    /** Home — главная с файлами; Video — формат текущего видео; остальные — настройки. */
    enum class Tab { Home, Video, Headset, Remote, Settings }

    var mode by mutableStateOf(Mode.Menu)
    var tab by mutableStateOf(Tab.Home)

    /** Имя открытого видео, null — видео ещё не выбрано. */
    var title by mutableStateOf<String?>(null)

    /** Что отложено при выходе в хаб: можно продолжить. null — отложенного нет. */
    var parkedTitle by mutableStateOf<String?>(null)

    /** Соседние окна плоского экрана. */
    var sideCards by mutableStateOf<SideCards?>(null)
    var format by mutableStateOf(VideoFormat())
    var stereo by mutableStateOf(true)

    var hasPrevious by mutableStateOf(false)
    var hasNext by mutableStateOf(false)

    /** Открыто фото, а не видео: без перемотки и скорости. */
    var isImage by mutableStateOf(false)
    var zoom by mutableStateOf(1f)
    var speed by mutableStateOf(1f)

    /** Сообщение приложения (ошибка и т. п.) — показывается вверху меню, а не системной всплывашкой. */
    var notice by mutableStateOf<String?>(null)

    /** Последняя кнопка пульта: что именно он присылает. */
    var lastKey by mutableStateOf<String?>(null)

    /** Ждём кнопку пульта для этого действия. */
    var capturingKey by mutableStateOf<RemoteAction?>(null)

    /** Что раскрыто над полоской плеера: быстрые настройки вида или очередь; null — ничего. */
    enum class Sheet { View, Queue }

    var sheet by mutableStateOf<Sheet?>(null)

    /** Очередь — файлы папки, из которой открыт текущий, и номер текущего. */
    var queue by mutableStateOf<List<PlaylistItem>>(emptyList())
    var queueIndex by mutableStateOf(0)

    val library = LibraryState()

    /** Сейчас виден сам хаб: в шлеме он парит без общего фона. */
    val showingHub get() = stereo && mode == Mode.Menu && tab == Tab.Home && library.atRoot
}

/** Что интерфейс умеет делать с плеером. */
interface VrMenuActions {
    fun isPlaying(): Boolean
    fun positionMs(): Long
    fun durationMs(): Long
    fun togglePlayback()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)
    fun setFormat(format: VideoFormat)

    /** Забыть ручной выбор и вернуть формат, угаданный по имени файла. */
    fun resetFormat()
    fun toggleStereo()
    fun recenter()

    /** Рецентр из интерфейса: отсчёт, и центр встаёт туда, куда смотрят в его конце. */
    fun recenterAfterCountdown()
    fun setZoom(zoom: Float)
    fun setSpeed(speed: Float)

    /** Текущее фото — в окружение (своя панорама). Возвращает, что получилось, для подписи. */
    fun setAsEnvironment(): String
    fun play(playlist: Playlist)
    fun previousFile()
    fun nextFile()

    /** Повтор: выкл → папка → файл. */
    fun cycleRepeat()
    fun toggleShuffle()
    fun toggleSlideshow()

    /** Включить файл очереди с этим номером. */
    fun playQueueItem(index: Int)
    fun headsetSaved()
    fun openMenu(tab: VrMenuState.Tab)

    /** Выйти из просмотра в хаб. */
    fun toHub()

    /** Вернуться из хаба к отложенному просмотру. */
    fun resumeViewing()
    fun hide()
    fun exit()
}

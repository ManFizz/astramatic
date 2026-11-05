package com.manfis.astramatic.player

import com.manfis.astramatic.model.StereoLayout
import com.manfis.astramatic.model.VideoFormat
import com.manfis.astramatic.settings.AppSettings
import com.manfis.astramatic.settings.RepeatMode
import com.manfis.astramatic.ui.MAX_ZOOM
import com.manfis.astramatic.ui.MIN_ZOOM
import com.manfis.astramatic.ui.VrMenuActions
import com.manfis.astramatic.ui.VrMenuState
import kotlin.random.Random

/** Воспроизведение: декодер видео и показ фото. Реализует [PlayerMedia]. */
interface MediaEngine {
    val isPlaying: Boolean
    val positionMs: Long

    /** Длительность как есть: пока она неизвестна, значение отрицательное. */
    val durationMs: Long
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)

    /** Подготовить видео. [afterImage] — до него было фото, поверхность занята холстом. */
    fun openVideo(item: PlaylistItem, afterImage: Boolean)

    /**
     * Загрузить и показать фото. [isCurrent] проверяется по готовности: пока грузилось, могли открыть другое.
     * [onFailed] получает причину, если она известна.
     */
    fun openImage(item: PlaylistItem, isCurrent: () -> Boolean, onShown: () -> Unit, onFailed: (String?) -> Unit)
    fun cancelImage()

    /** Текущее фото — в окружение. Возвращает, что получилось, для подписи. */
    fun saveImageAsEnvironment(stereo: StereoLayout): String
}

/** Что запоминается про файлы. Реализует [PlaybackStore]. */
interface PlaybackMemory {
    fun savedFormat(item: PlaylistItem): VideoFormat?
    fun saveFormat(item: PlaylistItem, format: VideoFormat)
    fun clearFormat(item: PlaylistItem)

    /** Файл открыт: для истории. */
    fun opened(item: PlaylistItem)
    fun savePosition(item: PlaylistItem, positionMs: Long, durationMs: Long)

    /** С какого места продолжить; [seek] вызывается на главном потоке, если место известно. */
    fun resume(item: PlaylistItem, seek: (Long) -> Unit)
}

/**
 * Открытая папка и что в ней играет: следующий файл, формат, скорость, приближение, слайд-шоу.
 * Интерфейс плеера работает с этим классом через [VrMenuActions].
 */
class PlayerSession(
    private val menu: VrMenuState,
    private val settings: AppSettings,
    private val media: MediaEngine,
    private val memory: PlaybackMemory,
    private val scene: VrScene,
    private val panels: PanelController,
    private val ticks: Ticks,
    private val random: Random = Random,
    private val switchStereo: () -> Unit,
    private val applyHeadset: () -> Unit,
    private val closePlayer: () -> Unit,
) : VrMenuActions {

    private var playlist: Playlist? = null
    private var slideshowPaused = false

    /** Что смотрели в этой папке, по порядку: по этому списку идёт ⏮ в случайном порядке. */
    private val watched = ArrayDeque<Int>()

    private val currentIsImage get() = playlist?.current?.isImage == true

    /** Просмотр отложен: в хабе ничего не играет и не видно. */
    private val parked get() = menu.parkedTitle != null

    private val repeatMode get() = RepeatMode.entries.find { it.name == settings.repeatMode } ?: RepeatMode.Off

    private val queueModes get() = QueueModes(settings.shuffle, repeatMode, settings.autoplayNext)

    /** Идёт видео: фото и пауза не считаются. */
    val videoPlaying get() = media.isPlaying && !currentIsImage

    // У фото «играет» — значит идёт слайд-шоу.
    override fun isPlaying() = if (currentIsImage) slideshowRunning() else media.isPlaying
    override fun positionMs() = media.positionMs
    override fun durationMs() = media.durationMs.coerceAtLeast(0)

    override fun togglePlayback() {
        if (parked) return resumeViewing()
        if (currentIsImage) {
            // ⏯ у фото: включает слайд-шоу, если оно выключено, иначе ставит на паузу.
            if (!settings.slideshow) {
                settings.slideshow = true
                slideshowPaused = false
            } else {
                slideshowPaused = !slideshowPaused
            }
            scheduleSlideshow()
            return
        }
        if (media.isPlaying) media.pause() else media.play()
    }

    override fun seekTo(positionMs: Long) = media.seekTo(positionMs)

    override fun seekBy(deltaMs: Long) {
        val end = media.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        media.seekTo((media.positionMs + deltaMs).coerceIn(0, end))
    }

    override fun setFormat(format: VideoFormat) {
        applyFormat(format)
        // Выбран вручную — запоминаем для этого файла.
        playlist?.current?.let { memory.saveFormat(it, format) }
    }

    override fun resetFormat() {
        val item = playlist?.current ?: return
        memory.clearFormat(item)
        applyFormat(item.format)
    }

    override fun toggleStereo() = switchStereo()
    override fun recenter() = scene.recenter()

    override fun setZoom(zoom: Float) {
        val value = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        menu.zoom = value
        scene.zoom = value
    }

    override fun setSpeed(speed: Float) {
        menu.speed = speed
        media.setSpeed(speed)
    }

    override fun setAsEnvironment(): String = media.saveImageAsEnvironment(menu.format.stereo)

    // Выбрано в меню — видео встаёт там, где меню.
    override fun play(playlist: Playlist) = open(playlist, fromMenu = true)
    override fun previousFile() = step(-1)
    override fun nextFile() = step(1)

    override fun cycleRepeat() {
        settings.repeatMode = PlayQueue.nextRepeat(repeatMode).name
        updateNavigation()
    }

    override fun toggleShuffle() {
        settings.shuffle = !settings.shuffle
        updateNavigation()
    }

    override fun playQueueItem(index: Int) {
        val list = playlist ?: return
        PlayQueue.queueTarget(index, list.items.size, list.index)?.let { open(list.copy(index = it)) }
    }

    override fun toggleSlideshow() {
        settings.slideshow = !settings.slideshow
        slideshowPaused = false
        scheduleSlideshow()
    }

    override fun headsetSaved() = applyHeadset()
    override fun openMenu(tab: VrMenuState.Tab) = panels.showMenu(tab)
    override fun hide() = panels.hide()
    override fun exit() = closePlayer()

    /** Видео ставится на паузу и прячется, вокруг остаётся окружение. Центр не меняется: можно продолжить. */
    override fun toHub() {
        val title = menu.title
        if (title != null) {
            savePosition()
            media.pause()
            ticks.cancel(slideshowTick)
            menu.parkedTitle = title
            menu.title = null
            scene.hasVideo = false
        }
        panels.showMenu(VrMenuState.Tab.Home)
    }

    override fun resumeViewing() {
        val title = menu.parkedTitle ?: return
        menu.parkedTitle = null
        menu.title = title
        scene.hasVideo = true
        media.play()
        if (currentIsImage) scheduleSlideshow()
        panels.hide()
    }

    /** Нажали соседнее окно. Следующий файл открывается с тем же центром, как и по кнопке. */
    fun openSide(slot: SideSlot) {
        when (menu.sideCards?.get(slot)?.kind) {
            SideKind.Previous -> previousFile()
            SideKind.Next -> nextFile()
            SideKind.Hub -> toHub()
            null -> Unit
        }
    }

    /**
     * Видео открывается в том же направлении, что и предыдущее: смотрели вбок — центр не съезжает.
     * [fromMenu] — выбрано в меню: тогда видео встаёт там, где меню.
     */
    fun open(next: Playlist, fromMenu: Boolean = false) {
        rememberWatched(next)
        show(next, fromMenu)
    }

    private fun rememberWatched(next: Playlist) {
        val list = playlist ?: return
        when {
            list.items != next.items -> watched.clear()
            list.index != next.index -> watched.addLast(list.index)
        }
    }

    private fun show(next: Playlist, fromMenu: Boolean = false) {
        savePosition()
        val wasImage = currentIsImage
        playlist = next
        val item = next.current
        ticks.cancel(slideshowTick)
        media.cancelImage()
        setZoom(1f)
        if (item.isImage) {
            media.openImage(
                item,
                isCurrent = { playlist?.current == item },
                onShown = ::scheduleSlideshow,
                onFailed = { reason -> failed("Не удалось открыть фото${reason?.let { ": $it" } ?: "."}") },
            )
        } else {
            media.openVideo(item, afterImage = wasImage)
            if (settings.resumePlayback) {
                memory.resume(item) { position -> if (playlist?.current == item) media.seekTo(position) }
            }
            setSpeed(1f)
            media.play()
        }
        memory.opened(item)
        menu.isImage = item.isImage
        menu.title = item.title
        menu.parkedTitle = null
        updateNavigation()
        menu.queue = next.items
        menu.queueIndex = next.index
        applyFormat(memory.savedFormat(item) ?: item.format)
        ticks.cancel(saveTick)
        ticks.schedule(saveTick, SAVE_INTERVAL_MS)
        scene.hasVideo = true
        if (fromMenu) scene.centerOnPanel()
        menu.tab = VrMenuState.Tab.Home
        panels.hide()
    }

    /** Видео дошло до конца. */
    fun onVideoEnded() {
        if (currentIsImage) return
        savePosition()
        val list = playlist
        when (val end = PlayQueue.atEnd(list?.index ?: 0, list?.items?.size ?: 0, queueModes, random)) {
            AtEnd.RestartFile -> {
                media.seekTo(0)
                media.play()
            }
            is AtEnd.OpenFile -> open(list!!.copy(index = end.index))
            AtEnd.ShowControls -> panels.showControls()
        }
    }

    fun onPlaybackError(text: String) = failed(text)

    /** Смотреть нечего: сообщение в хабе, продолжать тоже нечего. */
    private fun failed(text: String) {
        menu.title = null
        menu.parkedTitle = null
        scene.hasVideo = false
        panels.showNotice(text, VrMenuState.Tab.Home)
    }

    /** Плеер уходит с экрана. */
    fun onStop() {
        savePosition()
        media.pause()
    }

    /** Плеер снова на экране: видео ждёт нажатия, а фото оживает само. */
    fun onStart() {
        if (!parked && currentIsImage) media.play()
    }

    /** Отдельная кнопка «играть» на пульте: из хаба она возвращает к просмотру. */
    fun playKey() {
        if (parked) resumeViewing() else media.play()
    }

    private fun step(delta: Int) {
        val target = neighbor(delta) ?: return
        if (delta < 0 && settings.shuffle) {
            // Возврат по истории: файл, с которого ушли, в неё не записывается.
            watched.removeLast()
            show(target)
        } else {
            open(target)
        }
    }

    private fun neighbor(delta: Int): Playlist? {
        val list = playlist ?: return null
        return PlayQueue.neighbor(list.index, list.items.size, delta, queueModes, random, watched.lastOrNull())
            ?.let { list.copy(index = it) }
    }

    private fun updateNavigation() {
        if (playlist == null) return
        val previous = neighbor(-1)
        val next = neighbor(1)
        menu.hasPrevious = previous != null
        menu.hasNext = next != null
        // В случайном порядке следующий файл выбирается при переходе: заранее его не показать.
        menu.sideCards = SideCards(
            left = sideCard(SideKind.Previous, previous),
            right = if (settings.shuffle && next != null) SideCard(SideKind.Next, "Случайный файл") else sideCard(SideKind.Next, next),
        )
    }

    /** Некуда идти — окно ведёт в хаб. */
    private fun sideCard(kind: SideKind, target: Playlist?): SideCard {
        val item = target?.current ?: return SideCard(SideKind.Hub)
        return SideCard(kind, item.title, item.thumbnail)
    }

    private fun applyFormat(format: VideoFormat) {
        menu.format = format
        scene.format = format
    }

    /** Позиция пишется всегда: включить «продолжать с места» можно и позже. */
    private fun savePosition() {
        val item = playlist?.current?.takeIf { !it.isImage } ?: return
        memory.savePosition(item, media.positionMs, media.durationMs)
    }

    private val saveTick = object : Runnable {
        override fun run() {
            if (media.isPlaying) savePosition()
            ticks.schedule(this, SAVE_INTERVAL_MS)
        }
    }

    private fun slideshowRunning() = settings.slideshow && !slideshowPaused

    private fun scheduleSlideshow() {
        ticks.cancel(slideshowTick)
        if (slideshowRunning()) ticks.schedule(slideshowTick, settings.slideshowSeconds.coerceAtLeast(2) * 1000L)
    }

    private val slideshowTick = Runnable {
        if (currentIsImage && slideshowRunning()) step(1)
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 5_000L
    }
}

package com.manfis.astramatic.player

import com.manfis.astramatic.settings.AppSettings
import com.manfis.astramatic.ui.VrMenuState

/**
 * Что сейчас видно: меню, полоска плеера или ничего, и как между ними ходить.
 * Здесь же всё, что зависит от взгляда на панель: подсветка, нажатие взглядом, автоскрытие полоски.
 */
class PanelController(
    private val menu: VrMenuState,
    private val settings: AppSettings,
    private val host: PanelHost,
    private val scene: VrScene,
    private val ticks: Ticks,
    private val clock: () -> Long,
    /** true — VR, false — 2D. */
    private val stereo: () -> Boolean,
    /** Идёт видео (фото и пауза не считаются). */
    private val videoPlaying: () -> Boolean,
    /** Закрыть плеер. */
    private val exit: () -> Unit,
    /** Выйти из просмотра в хаб: видео на паузе и спрятано. */
    private val leaveToHub: () -> Unit,
    /** Вернуться из хаба к отложенному просмотру. */
    private val resumeViewing: () -> Unit,
    /** Нажали соседнее окно у плоского экрана. */
    private val openSide: (SideSlot) -> Unit = {},
    /** Панель показали, спрятали или перенесли. */
    private val onShownChanged: () -> Unit = {},
) {
    private val press = PressTracker(host, scene, ticks)
    private val autoHide = ControlsAutoHide()
    private val dwell = DwellClick()
    private var counting = false

    /** Большое меню: видео, файлы, шлем. */
    fun showMenu(tab: VrMenuState.Tab? = null) {
        if (tab != null) menu.tab = tab
        showPanel(VrMenuState.Mode.Menu)
    }

    /** Полоска управления воспроизведением. Без видео управлять нечем — тогда меню. */
    fun showControls() {
        if (menu.title == null) showMenu(VrMenuState.Tab.Home) else showPanel(VrMenuState.Mode.Controls)
    }

    /** Сообщение приложения: в меню, где его видно и в шлеме, и на экране. */
    fun showNotice(text: String, tab: VrMenuState.Tab? = null) {
        menu.notice = text
        showMenu(tab)
    }

    /** Действие пульта: скрыто → полоска → хаб → снова просмотр. Без видео хаб не закрывается. */
    fun cycleControls() {
        when {
            host.place == PanelPlace.Hidden -> showControls()
            menu.mode == VrMenuState.Mode.Controls -> leaveToHub()
            menu.title != null -> hide()
            menu.parkedTitle != null -> resumeViewing()
        }
    }

    /**
     * «Назад» — шаг к хабу: отмена назначения кнопки → закрыть раскрытую панель → из просмотра в хаб →
     * из настроек в хаб → вверх по папкам. В хабе в VR ничего не делает, в 2D выходит.
     */
    fun goBack() {
        val library = menu.library.backStack
        when {
            menu.keyboard.isOpen -> menu.keyboard.close()
            menu.capturingKey != null -> menu.capturingKey = null
            menu.sheet != null -> menu.sheet = null
            menu.title != null -> leaveToHub()
            host.place == PanelPlace.Hidden -> showMenu(VrMenuState.Tab.Home)
            menu.tab != VrMenuState.Tab.Home -> menu.tab = VrMenuState.Tab.Home
            library.size > 1 -> library.removeAt(library.lastIndex)
            !stereo() -> exit()
        }
    }

    fun hide() {
        menu.keyboard.close()
        menu.notice = null
        menu.sheet = null
        press.dropGrab()
        ticks.cancel(hoverTick)
        host.hover(null, null)
        scene.panelVisible = false
        host.hide()
        updateCalibration()
        onShownChanged()
    }

    /** Режим VR ⇄ 2D уже переключён: та же панель в новом месте. */
    fun stereoChanged() {
        if (host.place != PanelPlace.Hidden) {
            if (!stereo()) {
                scene.panelVisible = false
                ticks.cancel(hoverTick)
            }
            showPanel(menu.mode)
        }
        onShownChanged()
    }

    /** Какую часть виртуального дисплея и где показывает рендерер: меню, полоску или полоску с панелью. */
    fun updateLayout() {
        scene.panelLayout = when {
            menu.mode == VrMenuState.Mode.Menu -> PanelLayout.Menu
            menu.sheet != null -> PanelLayout.ControlsSheet
            else -> PanelLayout.Controls
        }
    }

    /** Калибровочная комната вокруг — пока в шлеме открыта настройка шлема. */
    fun updateCalibration() {
        scene.calibration = stereo() && host.place == PanelPlace.Vr &&
            menu.mode == VrMenuState.Mode.Menu && menu.tab == VrMenuState.Tab.Headset
    }

    /** Кнопка Cardboard или действие «Нажатие» на пульте нажаты. */
    fun triggerDown(time: Long) = press.down(time)

    /** Отменить нажатие, если оно идёт: кнопка под взглядом не сработает. */
    fun cancelPress() {
        press.up(clock(), cancelled = true)
    }

    /**
     * Рецентр из интерфейса: панель уходит, идёт отсчёт, и центр встаёт туда, куда смотрят в конце.
     * За секунду можно спокойно повернуть голову: взгляд не привязан к кнопке, которую нажимали.
     */
    fun recenterAfterCountdown() {
        hide()
        counting = true
        scene.showRecenterRing(RECENTER_COUNTDOWN_MS)
        ticks.cancel(recenterTick)
        ticks.schedule(recenterTick, RECENTER_COUNTDOWN_MS)
    }

    private fun cancelCountdown() {
        if (!counting) return
        counting = false
        ticks.cancel(recenterTick)
        scene.showRecenterRing(null)
    }

    private val recenterTick = Runnable {
        counting = false
        scene.showRecenterRing(null)
        scene.recenter()
    }

    fun triggerUp(time: Long, cancelled: Boolean = false) {
        val tap = press.up(time, cancelled) ?: return
        val side = scene.hoverSide
        when {
            tap == Tap.HeldOnPanel -> scene.recenter()
            tap == Tap.Short && side != null -> openSide(side)
            // Нажатие мимо панели закрывает её, если есть что смотреть, иначе возвращает панель перед глаза.
            host.place == PanelPlace.Vr -> if (menu.title != null) hide() else scene.placePanel(PanelPlacement.InFront)
            host.place == PanelPlace.Screen -> Unit
            tap == Tap.Long -> scene.recenter()
            else -> showControls()
        }
    }

    /** Стрелка или OK — в интерфейс меню, мимо назначений пульта. */
    fun navKey(keyCode: Int) {
        if (host.place != PanelPlace.Hidden) host.pressKey(keyCode)
    }

    /** [amount] > 0 — вверх. В шлеме — там, куда смотрят, на экране — в центре. */
    fun scroll(amount: Float) {
        if (host.place == PanelPlace.Hidden) return
        val gaze = if (host.place == PanelPlace.Vr) scene.gaze else null
        host.scroll(gaze?.x, gaze?.y, amount)
    }

    private fun showPanel(mode: VrMenuState.Mode) {
        cancelCountdown()
        val menuAlreadyOpen = host.place == PanelPlace.Vr && menu.mode == VrMenuState.Mode.Menu
        menu.mode = mode
        if (mode == VrMenuState.Mode.Menu) menu.sheet = null
        autoHide.touch(clock())
        if (stereo()) {
            updateLayout()
            when {
                mode == VrMenuState.Mode.Controls -> scene.placePanel(PanelPlacement.AtGaze)
                // Открытое меню могли отодвинуть: смена раздела или сообщение его не двигают.
                !menuAlreadyOpen -> scene.placePanel(PanelPlacement.Center)
            }
            scene.panelVisible = true
            host.showInVr()
            ticks.cancel(hoverTick)
            ticks.schedule(hoverTick)
        } else {
            host.showOnScreen()
        }
        updateCalibration()
        onShownChanged()
    }

    /** Подсветка под взглядом, нажатие взглядом и автоскрытие, пока панель в шлеме. */
    private val hoverTick = object : Runnable {
        override fun run() {
            if (host.place != PanelPlace.Vr) {
                scene.dwellProgress = 0f
                return
            }
            val gaze = scene.gaze
            // Наведение идёт как мышь, а нажатие как касание: смена устройства посреди нажатия его отменяет.
            if (!press.pressedOnPanel) host.hover(gaze?.x, gaze?.y)
            updateDwell(gaze)
            if (autoHideControls(gaze)) return
            ticks.schedule(this, HOVER_INTERVAL_MS)
        }
    }

    /** Полоска уходит сама, если идёт видео, а смотрят не на неё: панель не раскрыта, ничего не держат. */
    private fun autoHideControls(gaze: Gaze?): Boolean {
        val unattended = menu.mode == VrMenuState.Mode.Controls && menu.sheet == null &&
            !press.pressedOnPanel && !press.grabbing && gaze == null && scene.hoverHandle == Handle.None && videoPlaying()
        if (!autoHide.shouldHide(clock(), unattended)) return false
        hide()
        return true
    }

    private fun updateDwell(gaze: Gaze?) {
        val enabled = settings.dwellClick && !press.pressedOnPanel
        val state = dwell.update(gaze, enabled, settings.dwellMs, clock())
        scene.dwellProgress = state.progress
        state.click?.let {
            host.touch(TouchPhase.Down, it.x, it.y)
            host.touch(TouchPhase.Up, it.x, it.y)
        }
    }

    companion object {
        private const val HOVER_INTERVAL_MS = 50L
        const val RECENTER_COUNTDOWN_MS = 1_000L
    }
}

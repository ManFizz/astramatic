package com.manfis.astramatic.player

/** Нажатие, которое не досталось панели: короткое или долгое. */
enum class Tap { Short, Long }

/**
 * Что происходит между нажатием и отпусканием: кнопка Cardboard или действие «Нажатие» на пульте.
 * Уголок или ручку можно взять, по панели — нажать и вести взглядом. Что не досталось панели, возвращается наверх.
 */
class PressTracker(
    private val host: PanelHost,
    private val scene: VrScene,
    private val ticks: Ticks,
) {
    private var pressStart = 0L
    private var lastGaze: Gaze? = null

    /** Нажатие держат на панели. */
    var pressedOnPanel = false
        private set

    /** Держат уголок или ручку меню: нажатие не уходит в интерфейс. */
    var grabbing = false
        private set

    /** Пока нажатие держат на панели, точка следует за взглядом: так прокручиваются списки и двигаются ползунки. */
    private val dragTick = object : Runnable {
        override fun run() {
            if (!pressedOnPanel) return
            scene.gaze?.let {
                lastGaze = it
                host.touch(TouchPhase.Move, it.x, it.y)
            }
            ticks.schedule(this, DRAG_INTERVAL_MS)
        }
    }

    fun down(time: Long) {
        pressStart = time
        val handle = scene.hoverHandle
        if (host.place == PanelPlace.Vr && handle != Handle.None) {
            grabbing = true
            scene.grab = handle
            return
        }
        val gaze = scene.gaze
        if (host.place == PanelPlace.Vr && gaze != null) {
            pressedOnPanel = true
            lastGaze = gaze
            host.touch(TouchPhase.Down, gaze.x, gaze.y)
            ticks.schedule(dragTick, DRAG_INTERVAL_MS)
        }
    }

    /** Отпустили. null — нажатие досталось панели или отменено. */
    fun up(time: Long, cancelled: Boolean): Tap? {
        if (grabbing) {
            dropGrab()
            return null
        }
        if (pressedOnPanel) {
            pressedOnPanel = false
            ticks.cancel(dragTick)
            val point = scene.gaze ?: lastGaze ?: return null
            host.touch(if (cancelled) TouchPhase.Cancel else TouchPhase.Up, point.x, point.y)
            return null
        }
        if (cancelled) return null
        return if (time - pressStart >= LONG_PRESS_MS) Tap.Long else Tap.Short
    }

    /** Панель закрылась, пока за неё держались. */
    fun dropGrab() {
        grabbing = false
        scene.grab = Handle.None
    }

    companion object {
        const val LONG_PRESS_MS = 500L
        const val DRAG_INTERVAL_MS = 16L
    }
}

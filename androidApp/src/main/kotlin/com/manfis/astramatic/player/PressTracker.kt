package com.manfis.astramatic.player

import kotlin.math.hypot

/**
 * Чем закончилось нажатие. [Short] и [Long] не достались панели.
 * [HeldOnPanel] — долго держали на панели и не вели взглядом: это рецентр, кнопка под взглядом не срабатывает.
 */
enum class Tap { Short, Long, HeldOnPanel }

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

    /** Нажатие идёт: отменённое или снятое закрытием панели при отпускании уже ничего не делает. */
    private var active = false
    private var lastGaze: Gaze? = null
    private var startGaze: Gaze? = null
    private var moved = false

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
                noteMove(it)
                host.touch(TouchPhase.Move, it.x, it.y)
            }
            ticks.schedule(this, DRAG_INTERVAL_MS)
        }
    }

    private fun noteMove(gaze: Gaze) {
        val start = startGaze ?: return
        if (hypot(gaze.x - start.x, gaze.y - start.y) > HOLD_TOLERANCE_PX) moved = true
    }

    fun down(time: Long) {
        pressStart = time
        active = true
        val handle = scene.hoverHandle
        // Экран и соседние окна можно взять и без открытой панели. В 2D ручек нет.
        if (host.place != PanelPlace.Screen && handle != Handle.None) {
            grabbing = true
            scene.grab = handle
            return
        }
        val gaze = scene.gaze
        if (host.place == PanelPlace.Vr && gaze != null) {
            pressedOnPanel = true
            lastGaze = gaze
            startGaze = gaze
            moved = false
            host.touch(TouchPhase.Down, gaze.x, gaze.y)
            ticks.schedule(dragTick, DRAG_INTERVAL_MS)
        }
    }

    /** Отпустили. null — нажатие досталось панели или отменено. */
    fun up(time: Long, cancelled: Boolean): Tap? {
        if (!active) return null
        active = false
        if (grabbing) {
            dropGrab()
            return null
        }
        if (pressedOnPanel) {
            pressedOnPanel = false
            ticks.cancel(dragTick)
            val point = scene.gaze ?: lastGaze ?: return null
            noteMove(point)
            val held = !cancelled && !moved && time - pressStart >= LONG_PRESS_MS
            host.touch(if (cancelled || held) TouchPhase.Cancel else TouchPhase.Up, point.x, point.y)
            return if (held) Tap.HeldOnPanel else null
        }
        if (cancelled) return null
        return if (time - pressStart >= LONG_PRESS_MS) Tap.Long else Tap.Short
    }

    /** Панель закрылась, пока за неё держались. */
    fun dropGrab() {
        if (grabbing) active = false
        grabbing = false
        scene.grab = Handle.None
    }

    companion object {
        const val LONG_PRESS_MS = 500L
        const val DRAG_INTERVAL_MS = 16L

        /** Голова немного гуляет, даже когда её держат неподвижно: примерно 2° на панели. */
        const val HOLD_TOLERANCE_PX = 64f
    }
}

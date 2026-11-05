package com.manfis.astramatic.player

import com.manfis.astramatic.model.VideoFormat
import java.util.PriorityQueue

/** Время и отложенные задачи под управлением теста. */
class FakeTime(start: Long = 1_000L) : Ticks {
    var now = start
        private set

    private class Entry(val at: Long, val order: Long, val task: Runnable)

    private var counter = 0L
    private val queue = PriorityQueue<Entry>(compareBy<Entry> { it.at }.thenBy { it.order })

    override fun schedule(task: Runnable, delayMs: Long) {
        queue += Entry(now + delayMs, counter++, task)
    }

    override fun cancel(task: Runnable) {
        queue.removeAll { it.task === task }
    }

    fun scheduled(task: Runnable) = queue.any { it.task === task }

    /** Прожить [ms] миллисекунд, выполняя задачи вовремя. */
    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = queue.peek() ?: break
            if (next.at > end) break
            queue.poll()
            now = maxOf(now, next.at)
            next.task.run()
        }
        now = end
    }
}

class FakeHost : PanelHost {
    override var place = PanelPlace.Hidden
    val touches = mutableListOf<Pair<TouchPhase, Gaze>>()
    val keys = mutableListOf<Int>()
    val hovers = mutableListOf<Gaze?>()
    val scrolls = mutableListOf<Float>()

    override fun showInVr() {
        place = PanelPlace.Vr
    }
    override fun showOnScreen() {
        place = PanelPlace.Screen
    }
    override fun hide() {
        place = PanelPlace.Hidden
    }
    override fun touch(phase: TouchPhase, x: Float, y: Float) {
        touches += phase to Gaze(x, y)
    }
    override fun hover(x: Float?, y: Float?) {
        hovers += if (x == null || y == null) null else Gaze(x, y)
    }
    override fun pressKey(keyCode: Int) {
        keys += keyCode
    }
    override fun scroll(x: Float?, y: Float?, amount: Float) {
        scrolls += amount
    }
}

class FakeScene : VrScene {
    override var panelLayout = PanelLayout.Menu
    override var panelVisible = false
    override var calibration = false
    override var dwellProgress = 0f
    override var grab = Handle.None
    override var gaze: Gaze? = null
    override var hoverHandle = Handle.None
    override var format = VideoFormat()
    override var zoom = 1f
    override var hasVideo = false
    var recenters = 0
    var centersOnPanel = 0
    val placements = mutableListOf<PanelPlacement>()
    val placedInFront get() = placements.count { it == PanelPlacement.InFront }
    override var hoverSide: SideSlot? = null

    override fun recenter() {
        recenters++
    }
    override fun centerOnPanel() {
        centersOnPanel++
    }
    override fun placePanel(placement: PanelPlacement) {
        placements += placement
    }
}

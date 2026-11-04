package com.manfis.astramatic.player

import kotlin.math.hypot

/** Нажатие взглядом: точка простояла на месте заданное время — нажимаем. Следующее — только после сдвига взгляда. */
class DwellClick(private val radiusPx: Float = 18f) {

    /** [progress] — заполнение кольца 0..1; [click] — куда нажать в этом кадре. */
    data class State(val progress: Float, val click: Gaze? = null)

    private var anchor: Gaze? = null
    private var startedAt = 0L
    private var fired = false

    /** [gaze] — точка взгляда; [enabled] — настройка включена и обычное нажатие сейчас не держат. */
    fun update(gaze: Gaze?, enabled: Boolean, dwellMs: Int, now: Long): State {
        if (!enabled || gaze == null) {
            anchor = null
            return State(0f)
        }
        val current = anchor
        if (current == null || hypot(gaze.x - current.x, gaze.y - current.y) > radiusPx) {
            anchor = gaze
            startedAt = now
            fired = false
        }
        if (fired) return State(0f)
        val progress = (now - startedAt).toFloat() / dwellMs
        if (progress < 1f) return State(progress)
        fired = true
        return State(0f, click = anchor ?: gaze)
    }
}

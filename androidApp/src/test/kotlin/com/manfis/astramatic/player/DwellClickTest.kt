package com.manfis.astramatic.player

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Сценарий VR-05 из docs/scenarios/vr-menu.md. */
class DwellClickTest {

    private val dwell = DwellClick()
    private val point = Gaze(100f, 100f)

    private fun at(gaze: Gaze?, now: Long, enabled: Boolean = true) = dwell.update(gaze, enabled, 1_000, now)

    @Test
    fun `progress fills and then clicks where the gaze stayed`() {
        at(point, 0)
        assertEquals(0.5f, at(point, 500).progress)

        val done = at(Gaze(105f, 100f), 1_000)

        assertEquals(point, done.click)
        assertEquals(0f, done.progress)
    }

    @Test
    fun `next click only after the gaze moves`() {
        at(point, 0)
        at(point, 1_000)

        val after = at(point, 5_000)
        assertNull(after.click)
        assertEquals(0f, after.progress)

        val moved = Gaze(200f, 100f)
        at(moved, 5_100)
        assertEquals(moved, at(moved, 6_100).click)
    }

    @Test
    fun `moving away restarts the countdown`() {
        at(point, 0)
        at(point, 900)

        assertEquals(0f, at(Gaze(150f, 100f), 950).progress)
        assertNull(at(Gaze(150f, 100f), 1_500).click)
    }

    @Test
    fun `small tremor keeps the countdown`() {
        at(point, 0)

        assertEquals(0.5f, at(Gaze(110f, 110f), 500).progress)
    }

    @Test
    fun `off or looking away cancels`() {
        at(point, 0)
        assertEquals(0f, at(null, 500).progress)
        assertEquals(0f, at(point, 600).progress)

        assertEquals(0f, at(point, 5_000, enabled = false).progress)
        assertNull(at(point, 5_000, enabled = false).click)
    }
}

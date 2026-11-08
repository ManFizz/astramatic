package com.manfis.astramatic.player

import com.manfis.astramatic.ui.MAX_ZOOM
import com.manfis.astramatic.ui.MIN_ZOOM
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Полоска приближения и перемотки, PL-23, PL-24. */
class HudTest {
    @Test
    fun `zoom bar fills from the 100 percent mark`() {
        val bar = HudBar.zoom(2f)

        assertEquals("200%", bar.label)
        assertEquals(bar.mark, bar.from)
        assertTrue(bar.value > bar.from)
        assertFalse(bar.onMark)
    }

    @Test
    fun `zoom scale is even on both sides of the mark`() {
        val mark = HudBar.zoom(1f).value

        assertEquals(mark - HudBar.zoom(0.5f).value, HudBar.zoom(2f).value - mark, 0.0001f)
        assertEquals(0f, HudBar.zoom(MIN_ZOOM).value, 0.0001f)
        assertEquals(1f, HudBar.zoom(MAX_ZOOM).value, 0.0001f)
    }

    @Test
    fun `100 percent stands on the mark`() {
        val bar = HudBar.zoom(1f)

        assertEquals("100%", bar.label)
        assertTrue(bar.onMark)
        assertEquals(bar.mark!!, bar.value, 0.0001f)
    }

    @Test
    fun `seek bar shows the direction, the distance and the time`() {
        assertEquals("+30 с  ·  12:34 / 1:02:00", HudBar.seek(754_000, 3_720_000, 30_000).label)
        assertEquals("−1:30  ·  0:05 / 10:00", HudBar.seek(5_000, 600_000, -90_000).label)
        assertEquals(0.5f, HudBar.seek(300_000, 600_000, 1).value, 0.0001f)
        assertNull(HudBar.seek(0, 600_000, 1).mark)
    }

    @Test
    fun `seek bar without known duration does not fall over`() {
        val bar = HudBar.seek(65_000, 0, 5_000)

        assertEquals("+5 с  ·  1:05", bar.label)
        assertEquals(0f, bar.value)
    }

    private var now = 0L
    private val detent = ZoomDetent { now }

    @Test
    fun `zoom stops on 100 percent when passing it and stays for a moment`() {
        assertEquals(1f, detent.next(1.1f, 0.95f))

        now += 100
        assertEquals(1f, detent.next(1f, 0.9f))

        now += 500
        assertEquals(0.9f, detent.next(1f, 0.9f))
    }

    @Test
    fun `zoom away from 100 percent is free`() {
        assertEquals(1.2f, detent.next(1f, 1.2f))
        assertEquals(1.3f, detent.next(1.2f, 1.3f))
        assertEquals(0.8f, detent.next(0.9f, 0.8f))
    }
}

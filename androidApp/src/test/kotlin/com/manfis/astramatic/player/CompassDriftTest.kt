package com.manfis.astramatic.player

import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Сценарии HS-08…HS-12 из docs/scenarios/headset.md. */
class CompassDriftTest {

    private var now = 1_000L
    private val source = FakeSource()
    private val drift = CompassDrift(source, clock = { now }).also { it.start() }

    private class FakeSource : RotationSource {
        var listener: ((FloatArray) -> Unit)? = null
        override fun start(listener: (FloatArray) -> Unit) {
            this.listener = listener
        }
        override fun stop() {
            listener = null
        }
    }

    /** Вид головы от трекинга: поворот влево на [yawDeg]. */
    private fun headView(yawDeg: Float): FloatArray {
        val a = Math.toRadians(yawDeg.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        return floatArrayOf(c, 0f, s, 0f, 0f, 1f, 0f, 0f, -s, 0f, c, 0f, 0f, 0f, 0f, 1f)
    }

    /** Показание компаса: тот же поворот влево; [up] — насколько взгляд вертикален, 0..1. */
    private fun compass(yawDeg: Float, up: Float = 0f) {
        val a = Math.toRadians(yawDeg.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        // Столбец Z телефона — против взгляда, столбец Y — против длинной стороны (она смотрит вправо).
        source.listener!!(floatArrayOf(0f, -c, s, 0f, s, -c, 0f, 0f, up))
    }

    /** Прожить [seconds] секунд кадрами 60 Гц; возвращает последнюю оценку ухода. */
    private fun run(seconds: Float, tracker: (Float) -> Float, compassYaw: (Float) -> Float, up: Float = 0f): Float {
        var last = 0f
        val frames = (seconds * 60).toInt().coerceAtLeast(1)
        for (i in 0 until frames) {
            val t = i / 60f
            now += 16
            compass(compassYaw(t), up)
            last = drift.update(headView(tracker(t)))
        }
        return last
    }

    @Test
    fun `steady tracker drift is taken out and does not grow`() {
        val rate = 0.5f
        val atMinute = run(60f, { rate * it }, { 0f })
        val residualMinute = abs(rate * 60f - atMinute)
        val atTwoMinutes = run(60f, { rate * (60f + it) }, { 0f })
        val residualTwoMinutes = abs(rate * 120f - atTwoMinutes)

        // Без поправки за две минуты ушло бы на 60°. С ней остаток постоянный: скорость × постоянная времени 8 с.
        assertTrue(residualTwoMinutes < 5f, "остаток $residualTwoMinutes°")
        assertTrue(residualTwoMinutes <= residualMinute + 0.5f, "остаток растёт: $residualMinute → $residualTwoMinutes")
    }

    @Test
    fun `no drift means no correction`() {
        assertEquals(0f, run(30f, { 42f }, { 42f }), 0.001f)
    }

    @Test
    fun `constant mismatch between tracker and compass is not drift`() {
        assertEquals(0f, run(30f, { 100f }, { 10f }), 0.001f)
    }

    @Test
    fun `switching to the long side at vertical gaze does not jump`() {
        val rate = 0.5f
        val before = run(20f, { rate * it }, { 0f })
        val next = run(1 / 60f, { rate * 20f }, { 0f }, up = 0.9f)

        assertEquals(before, next, 0.01f)
    }

    @Test
    fun `long side keeps correcting at vertical gaze`() {
        run(0.1f, { 0f }, { 0f }, up = 0.9f)
        val estimate = run(60f, { 0.5f * it }, { 0f }, up = 0.9f)

        assertTrue(estimate > 20f, "оценка $estimate° при уходе 30°")
    }

    @Test
    fun `axis switch has hysteresis`() {
        val rate = 0.5f
        run(0.1f, { 0f }, { 0f }, up = 0.9f)
        val atSide = run(20f, { rate * it }, { 0f }, up = 0.9f)
        // 0,7 — между порогами: остаёмся на длинной стороне, поэтому оценка идёт плавно дальше.
        val at07 = run(1 / 60f, { rate * 20f }, { 0f }, up = 0.7f)
        // Ниже 0,6 — обратно на взгляд; при смене оси оценка не скачет.
        val at05 = run(1 / 60f, { rate * 20f }, { 0f }, up = 0.5f)

        assertEquals(atSide, at07, 0.05f)
        assertEquals(at07, at05, 0.001f)
    }

    @Test
    fun `compass spike moves the estimate no faster than the limit`() {
        run(5f, { 0f }, { 0f })
        val start = drift.driftDeg
        var previous = start
        var fastest = 0f
        for (i in 0 until 60) {
            now += 16
            compass(90f)
            val value = drift.update(headView(0f))
            fastest = maxOf(fastest, abs(value - previous) / 0.016f)
            previous = value
        }

        assertTrue(fastest <= 1.5f + 0.01f, "скорость $fastest °/с")
        assertTrue(abs(previous - start) <= 1.5f, "за секунду ушло на ${previous - start}°")
    }

    @Test
    fun `long pause between frames is not treated as long correction`() {
        run(1f, { 0f }, { 0f })
        now += 60_000
        compass(90f)

        assertTrue(abs(drift.update(headView(0f))) <= 0.15f + 0.001f)
    }

    @Test
    fun `turning correction off resets the estimate`() {
        run(30f, { 0.5f * it }, { 0f })
        assertTrue(drift.driftDeg != 0f)

        assertEquals(0f, drift.correction(headView(15f), enabled = false))
        assertEquals(0f, drift.driftDeg)

        // Включили снова: уже набежавшая разница больше не считается уходом.
        now += 16
        compass(0f)
        assertEquals(0f, drift.correction(headView(15f), enabled = true))
    }

    @Test
    fun `missing sensor means no correction`() {
        val none = CompassDrift(null, clock = { now })
        none.start()

        assertFalse(none.available)
        assertEquals(0f, none.update(headView(30f)))
        assertEquals(0f, none.correction(headView(30f), enabled = true))
        none.stop()
    }

    @Test
    fun `sensor gone keeps the current estimate`() {
        val estimate = run(30f, { 0.5f * it }, { 0f })
        drift.stop()
        now += 16

        assertEquals(estimate, drift.update(headView(15f)))
    }

    @Test
    fun `estimate wraps around 180 degrees`() {
        // Трекер и компас идут вместе через 180°: разница не должна выглядеть как прыжок на 360°.
        val estimate = run(10f, { 170f + 3f * it }, { 170f + 3f * it })

        assertEquals(0f, estimate, 0.01f)
    }
}

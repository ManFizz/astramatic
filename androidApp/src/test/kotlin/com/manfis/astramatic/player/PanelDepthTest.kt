package com.manfis.astramatic.player

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Глубина стерео рядом с панелью, HS-13. */
class PanelDepthTest {
    private val width = 128
    private val height = 72

    /** Кадр с мелкой фактурой; значение зависит только от x + сдвиг, y. */
    private fun texture(seed: Int = 1): IntArray {
        val random = Random(seed)
        return IntArray((width + 64) * height) { random.nextInt(256) }
    }

    private fun image(source: IntArray, offset: Int) = ByteArray(width * height) { i ->
        val x = i % width
        val y = i / width
        source[y * (width + 64) + x + offset + 32].toByte()
    }

    private fun estimate(left: ByteArray, right: ByteArray) = StereoDisparity.estimate(left, right, width, height, -3, 18)

    @Test
    fun `finds how far the left eye image is shifted`() {
        val source = texture()
        for (shift in listOf(-2, 0, 1, 5, 17)) {
            // Точка x правого глаза — это точка x + shift левого.
            val left = image(source, -shift)
            val right = image(source, 0)

            assertEquals(shift.toFloat(), assertNotNull(estimate(left, right)), 0.5f, "сдвиг $shift")
        }
    }

    @Test
    fun `sign is positive for an object closer than infinity`() {
        // Близкий предмет: у левого глаза он правее, чем у правого.
        val left = ByteArray(width * height)
        val right = ByteArray(width * height)
        val source = texture()
        for (y in 0 until height) for (x in 0 until width) {
            val value = source[y * (width + 64) + x + 32].toByte()
            right[y * width + x] = value
            if (x + 4 < width) left[y * width + x + 4] = value
        }

        assertTrue(assertNotNull(estimate(left, right)) > 3f)
    }

    @Test
    fun `plain colour tells nothing`() {
        val flat = ByteArray(width * height) { 90 }

        assertNull(estimate(flat, flat))
    }

    @Test
    fun `unrelated images tell nothing`() {
        val left = image(texture(1), 0)
        val right = image(texture(2), 0)

        assertNull(estimate(left, right))
    }

    @Test
    fun `shift at the very end of the range is not trusted`() {
        val source = texture()

        assertNull(estimate(image(source, -18), image(source, 0)))
    }

    @Test
    fun `first sample is taken at once and the next ones smoothly`() {
        val depth = PanelDepth()
        assertFalse(depth.measured)

        depth.sample(0.02f)
        assertEquals(0.02f, depth.radians, 0.00001f)
        assertTrue(depth.measured)

        repeat(2) { depth.sample(0.06f) }
        assertTrue(depth.radians > 0.02f && depth.radians < 0.06f, "плавно: ${depth.radians}")
    }

    @Test
    fun `a single wrong match does not move the panel`() {
        val depth = PanelDepth()
        repeat(4) { depth.sample(0.05f) }

        depth.sample(-0.03f)

        assertEquals(0.05f, depth.radians, 0.00001f)
    }

    @Test
    fun `failed sample keeps the value and new scene starts over`() {
        val depth = PanelDepth()
        depth.sample(0.03f)

        depth.sample(null)
        assertEquals(0.03f, depth.radians, 0.00001f)

        depth.reset()
        assertFalse(depth.measured)
        depth.sample(0.01f)
        assertTrue(abs(depth.radians - 0.01f) < 0.00001f, "после сброса сразу новое значение")
    }

    @Test
    fun `gif, webp and the like may be animated, others not`() {
        assertTrue(AnimatedImages.mayAnimate("кот.GIF"))
        assertTrue(AnimatedImages.mayAnimate("a.b.webp"))
        assertFalse(AnimatedImages.mayAnimate("фото.jpg"))
        assertFalse(AnimatedImages.mayAnimate("без расширения"))
    }
}

package com.manfis.astramatic.player

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Сценарии VR-15, VR-16 из docs/scenarios/vr-menu.md: сам счётчик. Условия проверяются в PanelControllerTest. */
class ControlsAutoHideTest {

    private val autoHide = ControlsAutoHide()

    @Test
    fun `hides four seconds after being opened`() {
        autoHide.touch(1_000)

        assertFalse(autoHide.shouldHide(4_999, unattended = true))
        assertTrue(autoHide.shouldHide(5_000, unattended = true))
    }

    @Test
    fun `any attention restarts the count`() {
        autoHide.touch(0)
        assertFalse(autoHide.shouldHide(3_000, unattended = false))

        assertFalse(autoHide.shouldHide(6_999, unattended = true))
        assertTrue(autoHide.shouldHide(7_000, unattended = true))
    }

    @Test
    fun `never hides while attended`() {
        autoHide.touch(0)

        assertFalse(autoHide.shouldHide(1_000_000, unattended = false))
    }
}

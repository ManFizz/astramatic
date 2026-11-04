package com.manfis.astramatic.settings

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Сценарии RC-02, RC-05, RC-07, RC-08, RC-09, RC-18, RC-20 из docs/scenarios/remote.md. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KeyBindingsTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var bindings: KeyBindings

    private val stickX = AxisBinding(MotionEvent.AXIS_X, inverted = false)

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("test_keys", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        bindings = KeyBindings(prefs)
    }

    @Test
    fun `defaults include back on the back key`() {
        assertEquals(RemoteAction.Back, bindings.actionFor(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun `key moves from its previous action`() {
        bindings.assignKey(RemoteAction.Recenter, KeyEvent.KEYCODE_BUTTON_A)
        bindings.assignKey(RemoteAction.PlayPause, KeyEvent.KEYCODE_BUTTON_A)

        assertEquals(RemoteAction.PlayPause, bindings.actionFor(KeyEvent.KEYCODE_BUTTON_A))
        assertTrue(KeyEvent.KEYCODE_BUTTON_A !in bindings.keysOf(RemoteAction.Recenter))
    }

    @Test
    fun `action can hold several keys`() {
        bindings.assignKey(RemoteAction.Recenter, KeyEvent.KEYCODE_BUTTON_A)
        bindings.assignKey(RemoteAction.Recenter, KeyEvent.KEYCODE_BUTTON_B)

        assertEquals(setOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B), bindings.keysOf(RemoteAction.Recenter))
    }

    @Test
    fun `same axis works in menu and while viewing`() {
        bindings.assignAxis(RemoteAction.AxisScroll, stickX)
        bindings.assignAxis(RemoteAction.AxisSeek, stickX)

        assertEquals(setOf(stickX), bindings.axesOf(RemoteAction.AxisScroll))
        assertEquals(setOf(stickX), bindings.axesOf(RemoteAction.AxisSeek))
    }

    @Test
    fun `axis leaves an action active at the same time`() {
        bindings.assignAxis(RemoteAction.AxisScroll, stickX)
        bindings.assignAxis(RemoteAction.AxisNavHorizontal, stickX)

        assertTrue(bindings.axesOf(RemoteAction.AxisScroll).isEmpty())
        assertEquals(setOf(stickX), bindings.axesOf(RemoteAction.AxisNavHorizontal))
    }

    @Test
    fun `axis on a button action overlaps everything`() {
        bindings.assignAxis(RemoteAction.AxisScroll, stickX)
        bindings.assignAxis(RemoteAction.AxisSeek, stickX)
        bindings.assignAxis(RemoteAction.Click, stickX)

        assertTrue(bindings.axesOf(RemoteAction.AxisScroll).isEmpty())
        assertTrue(bindings.axesOf(RemoteAction.AxisSeek).isEmpty())
        assertEquals(setOf(stickX), bindings.axesOf(RemoteAction.Click))
    }

    @Test
    fun `invert flips the direction`() {
        bindings.assignAxis(RemoteAction.AxisSeek, stickX)
        bindings.invertAxis(RemoteAction.AxisSeek, stickX)

        assertEquals(setOf(stickX.copy(inverted = true)), bindings.axesOf(RemoteAction.AxisSeek))
    }

    @Test
    fun `reset returns defaults and clear removes everything`() {
        bindings.assignKey(RemoteAction.Recenter, KeyEvent.KEYCODE_BUTTON_A)
        bindings.assignAxis(RemoteAction.AxisSeek, stickX)

        bindings.resetAll()
        assertNull(bindings.actionFor(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(RemoteAction.Back, bindings.actionFor(KeyEvent.KEYCODE_BACK))
        assertTrue(bindings.axisBindings().isEmpty())

        bindings.clearAll()
        assertNull(bindings.actionFor(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun `bindings survive restart`() {
        bindings.assignKey(RemoteAction.Recenter, KeyEvent.KEYCODE_BUTTON_A)
        bindings.assignAxis(RemoteAction.AxisSeek, stickX.copy(inverted = true, rest = -1))

        val restored = KeyBindings(prefs)

        assertEquals(RemoteAction.Recenter, restored.actionFor(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(setOf(stickX.copy(inverted = true, rest = -1)), restored.axesOf(RemoteAction.AxisSeek))
    }

    @Test
    fun `old schema drops saved axes but keeps keys`() {
        prefs.edit()
            .putInt("bindings_schema", 2)
            .putString("key_Recenter", KeyEvent.KEYCODE_BUTTON_A.toString())
            .putString("axis_AxisSeek", stickX.encode())
            .commit()

        val migrated = KeyBindings(prefs)

        assertEquals(RemoteAction.Recenter, migrated.actionFor(KeyEvent.KEYCODE_BUTTON_A))
        assertTrue(migrated.axesOf(RemoteAction.AxisSeek).isEmpty())
    }

    @Test
    fun `stick value follows rest and direction`() {
        assertEquals(0.5f, AxisBinding(0, inverted = false).valueOf(0.5f))
        assertEquals(-0.5f, AxisBinding(0, inverted = true).valueOf(0.5f))
        // Курок с покоем −1: полный ход от −1 до 1 даёт от 0 до 1.
        assertEquals(0f, AxisBinding(0, inverted = false, rest = -1).valueOf(-1f))
        assertEquals(1f, AxisBinding(0, inverted = false, rest = -1).valueOf(1f))
    }

    @Test
    fun `axis binding round trips through text`() {
        val binding = AxisBinding(MotionEvent.AXIS_RTRIGGER, inverted = true, rest = -1)

        assertEquals(binding, AxisBinding.decode(binding.encode()))
        assertNull(AxisBinding.decode("мусор"))
        assertFalse(AxisBinding.decode("11:0")!!.inverted)
    }
}

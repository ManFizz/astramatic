package com.manfis.astramatic.player

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.manfis.astramatic.model.VideoFormat
import com.manfis.astramatic.settings.AppSettings
import com.manfis.astramatic.settings.AxisBinding
import com.manfis.astramatic.settings.RemoteAction
import com.manfis.astramatic.ui.VrMenuActions
import com.manfis.astramatic.ui.VrMenuState
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Кнопки и оси: RC-03, RC-04, RC-05, RC-07, RC-11, RC-12, RC-13, RC-14, RC-19, NAV-08, NAV-09 из docs/scenarios.
 * Панели настоящие: так видно всю цепочку от кнопки до того, что откроется.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RemoteInputTest {

    private val settings = AppSettings(
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("test_remote", Context.MODE_PRIVATE),
    )
    private val bindings get() = settings.keys
    private val menu = VrMenuState()
    private val host = FakeHost()
    private val scene = FakeScene()
    private val time = FakeTime()

    private val log = mutableListOf<String>()
    private var seekedMs = 0L

    private val actions = object : VrMenuActions {
        override fun isPlaying() = true
        override fun positionMs() = 0L
        override fun durationMs() = 0L
        override fun togglePlayback() {
            log += "playPause"
        }
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(deltaMs: Long) {
            seekedMs += deltaMs
        }
        override fun setFormat(format: VideoFormat) = Unit
        override fun resetFormat() = Unit
        override fun toggleStereo() {
            log += "toggleStereo"
        }
        override fun recenter() {
            log += "recenter"
            scene.recenter()
        }
        override fun setZoom(zoom: Float) {
            menu.zoom = zoom
        }
        override fun setSpeed(speed: Float) = Unit
        override fun setAsEnvironment() = ""
        override fun play(playlist: Playlist) = Unit
        override fun previousFile() {
            log += "previous"
        }
        override fun nextFile() {
            log += "next"
        }
        override fun cycleRepeat() = Unit
        override fun toggleShuffle() = Unit
        override fun toggleSlideshow() = Unit
        override fun playQueueItem(index: Int) = Unit
        override fun headsetSaved() = Unit
        override fun openMenu(tab: VrMenuState.Tab) = Unit
        override fun toHub() = Unit
        override fun resumeViewing() = Unit
        override fun hide() = Unit
        override fun exit() {
            log += "exit"
        }
    }

    private val panels: PanelController = PanelController(
        menu, settings, host, scene, time, clock = { time.now },
        stereo = { true },
        videoPlaying = { true },
        exit = { log += "exit" },
        leaveToHub = {
            log += "hub"
            menu.parkedTitle = menu.title
            menu.title = null
            panels.showMenu(VrMenuState.Tab.Home)
        },
        resumeViewing = {
            menu.title = menu.parkedTitle
            menu.parkedTitle = null
            panels.hide()
        },
    )

    private val input = RemoteInput(
        menu, settings.keys, host, panels, actions, time, clock = { time.now },
        systemBack = { panels.goBack() },
        volume = { log += if (it) "louder" else "quieter" },
    )

    private val stick = AxisRange(MotionEvent.AXIS_X, -1f)
    private val trigger = AxisRange(MotionEvent.AXIS_RTRIGGER, 0f)
    private val minusOneTrigger = AxisRange(MotionEvent.AXIS_RTRIGGER, -1f)
    private val ranges = listOf(stick, trigger)

    @Before
    fun clean() {
        bindings.clearAll()
    }

    private fun axes(vararg values: Pair<Int, Float>, ranges: List<AxisRange> = this.ranges) {
        input.onAxes(time.now, ranges, mapOf(*values))
    }

    private fun stick(value: Float) = axes(MotionEvent.AXIS_X to value)

    private fun trigger(value: Float) = axes(MotionEvent.AXIS_RTRIGGER to value)

    private fun keyDown(code: Int, repeat: Int = 0) = input.onKey(code, true, repeat, time.now)

    private fun keyUp(code: Int) = input.onKey(code, false, 0, time.now)

    private fun tap(code: Int, holdMs: Long = 50) {
        keyDown(code)
        time.advance(holdMs)
        keyUp(code)
    }

    private fun startCapture(action: RemoteAction) {
        menu.capturingKey = action
        input.captureChanged()
    }

    /** Назначить курок на действие: начать, дождаться конца «успокоения», нажать, отпустить. */
    private fun bindTrigger(action: RemoteAction) {
        startCapture(action)
        trigger(0f)
        time.advance(500)
        trigger(1f)
        trigger(0f)
    }

    private fun videoOpen() {
        menu.title = "видео.mp4"
    }

    // --- Кнопки: NAV-08, RC-13, RC-14 ----------------------------------------------------------

    @Test
    fun `assigned key performs its action`() {
        bindings.assignKey(RemoteAction.NextFile, KeyEvent.KEYCODE_BUTTON_R1)

        assertEquals(KeyRoute.Consumed, keyDown(KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals(listOf("next"), log)
    }

    @Test
    fun `RC-14 seek repeats while held, others fire once`() {
        bindings.assignKey(RemoteAction.SeekForward, KeyEvent.KEYCODE_BUTTON_R1)
        bindings.assignKey(RemoteAction.NextFile, KeyEvent.KEYCODE_BUTTON_L1)

        repeat(3) { keyDown(KeyEvent.KEYCODE_BUTTON_R1, repeat = it) }
        repeat(3) { keyDown(KeyEvent.KEYCODE_BUTTON_L1, repeat = it) }

        assertEquals(30_000L, seekedMs)
        assertEquals(listOf("next"), log)
    }

    @Test
    fun `RC-13 free arrows and OK go to the panel in VR`() {
        panels.showMenu(VrMenuState.Tab.Home)

        assertEquals(KeyRoute.ToPanel(KeyEvent.KEYCODE_DPAD_DOWN), keyDown(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(KeyRoute.ToPanel(KeyEvent.KEYCODE_ENTER), keyDown(KeyEvent.KEYCODE_ENTER))
        assertEquals(KeyRoute.ToPanel(KeyEvent.KEYCODE_DPAD_CENTER), keyDown(KeyEvent.KEYCODE_BUTTON_A))
    }

    @Test
    fun `free arrows are left to the system with no panel or in 2D`() {
        assertEquals(KeyRoute.System, keyDown(KeyEvent.KEYCODE_DPAD_DOWN))

        host.showOnScreen()
        assertEquals(KeyRoute.System, keyDown(KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test
    fun `assigned key wins over menu navigation`() {
        bindings.assignKey(RemoteAction.NextFile, KeyEvent.KEYCODE_DPAD_RIGHT)
        panels.showMenu(VrMenuState.Tab.Home)

        assertEquals(KeyRoute.Consumed, keyDown(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(listOf("next"), log)
    }

    @Test
    fun `unknown key is left to the system`() {
        assertEquals(KeyRoute.System, keyDown(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun `volume actions`() {
        bindings.assignKey(RemoteAction.VolumeUp, KeyEvent.KEYCODE_BUTTON_R1)
        bindings.assignKey(RemoteAction.VolumeDown, KeyEvent.KEYCODE_BUTTON_L1)

        keyDown(KeyEvent.KEYCODE_BUTTON_R1)
        keyDown(KeyEvent.KEYCODE_BUTTON_L1)

        assertEquals(listOf("louder", "quieter"), log)
    }

    @Test
    fun `zoom actions step from the current value`() {
        bindings.assignKey(RemoteAction.ZoomIn, KeyEvent.KEYCODE_BUTTON_R1)
        bindings.assignKey(RemoteAction.ZoomOut, KeyEvent.KEYCODE_BUTTON_L1)

        keyDown(KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(1.15f, menu.zoom, 0.001f)

        keyDown(KeyEvent.KEYCODE_BUTTON_L1)
        assertEquals(1f, menu.zoom, 0.001f)
    }

    @Test
    fun `scroll and navigation actions reach the panel`() {
        bindings.assignKey(RemoteAction.NavSelect, KeyEvent.KEYCODE_BUTTON_R1)
        bindings.assignKey(RemoteAction.ScrollDown, KeyEvent.KEYCODE_BUTTON_L1)
        panels.showMenu(VrMenuState.Tab.Home)

        keyDown(KeyEvent.KEYCODE_BUTTON_R1)
        keyDown(KeyEvent.KEYCODE_BUTTON_L1)

        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_CENTER), host.keys)
        assertEquals(listOf(-1f), host.scrolls)
    }

    @Test
    fun `RC-19 wheel scrolls the menu only in the headset`() {
        assertEquals(false, input.onWheel(1f))

        panels.showMenu(VrMenuState.Tab.Home)
        assertEquals(true, input.onWheel(1f))
        assertEquals(listOf(1f), host.scrolls)

        host.showOnScreen()
        assertEquals(false, input.onWheel(1f))
    }

    @Test
    fun `next key press during capture is assigned, not performed`() {
        bindings.assignKey(RemoteAction.NextFile, KeyEvent.KEYCODE_BUTTON_R1)
        startCapture(RemoteAction.Recenter)

        assertEquals(KeyRoute.Consumed, keyDown(KeyEvent.KEYCODE_BUTTON_R1))

        assertEquals(RemoteAction.Recenter, bindings.actionFor(KeyEvent.KEYCODE_BUTTON_R1))
        assertNull(menu.capturingKey)
        assertTrue(log.isEmpty())
    }

    @Test
    fun `NAV-08 system back cancels capture and stays unassigned`() {
        bindings.resetAll()
        panels.showMenu(VrMenuState.Tab.Remote)
        startCapture(RemoteAction.Recenter)

        keyDown(KeyEvent.KEYCODE_BACK)

        assertNull(menu.capturingKey)
        assertEquals(VrMenuState.Tab.Remote, menu.tab)
        assertEquals(RemoteAction.Back, bindings.actionFor(KeyEvent.KEYCODE_BACK))
        assertTrue(bindings.keysOf(RemoteAction.Recenter).isEmpty())
    }

    @Test
    fun `axis capture ignores key presses`() {
        startCapture(RemoteAction.AxisSeek)

        keyDown(KeyEvent.KEYCODE_BUTTON_R1)

        assertEquals(RemoteAction.AxisSeek, menu.capturingKey)
    }

    @Test
    fun `back key on the remote never leaves VR`() {
        bindings.resetAll()
        videoOpen()

        repeat(10) { tap(KeyEvent.KEYCODE_BACK) }

        assertEquals(PanelPlace.Vr, host.place)
        assertTrue("exit" !in log)
    }

    // --- Назначение осей: RC-03, RC-04, RC-05 --------------------------------------------------

    @Test
    fun `RC-03 trigger is assigned as a button and fires only after release`() {
        startCapture(RemoteAction.PlayPause)
        trigger(0f)
        time.advance(500)

        trigger(1f)
        assertNull(menu.capturingKey)
        val binding = bindings.axesOf(RemoteAction.PlayPause).single()
        assertEquals(MotionEvent.AXIS_RTRIGGER, binding.axis)
        assertEquals(false, binding.inverted)
        assertTrue(log.isEmpty())

        // Отпустили: это ещё не нажатие.
        trigger(0f)
        assertTrue(log.isEmpty())

        // Теперь работает.
        trigger(1f)
        assertEquals(listOf("playPause"), log)
        trigger(0f)
        trigger(1f)
        assertEquals(listOf("playPause", "playPause"), log)
    }

    @Test
    fun `RC-04 releasing the trigger within 400 ms is not an assignment`() {
        // Курок, которым нажали «+», отпускается уже после начала назначения.
        trigger(1f)
        startCapture(RemoteAction.PlayPause)

        time.advance(100)
        trigger(0.5f)
        time.advance(100)
        trigger(0f)

        assertEquals(RemoteAction.PlayPause, menu.capturingKey)
        assertTrue(bindings.axesOf(RemoteAction.PlayPause).isEmpty())
    }

    @Test
    fun `settling position becomes the baseline, so the next move still assigns`() {
        trigger(1f)
        startCapture(RemoteAction.PlayPause)
        time.advance(100)
        trigger(0f)

        time.advance(400)
        trigger(1f)

        assertEquals(MotionEvent.AXIS_RTRIGGER, bindings.axesOf(RemoteAction.PlayPause).single().axis)
    }

    @Test
    fun `RC-05 stick direction comes from the movement`() {
        startCapture(RemoteAction.AxisSeek)
        stick(0f)
        time.advance(500)

        stick(-0.9f)

        val binding = bindings.axesOf(RemoteAction.AxisSeek).single()
        assertEquals(MotionEvent.AXIS_X, binding.axis)
        assertEquals(true, binding.inverted)
        assertEquals(0, binding.rest)
        assertNull(menu.capturingKey)
    }

    @Test
    fun `RC-05 trigger resting at minus one keeps its rest`() {
        val triggerOnly = listOf(minusOneTrigger)
        startCapture(RemoteAction.AxisSeek)
        axes(MotionEvent.AXIS_RTRIGGER to -1f, ranges = triggerOnly)
        time.advance(500)

        axes(MotionEvent.AXIS_RTRIGGER to 1f, ranges = triggerOnly)

        val binding = bindings.axesOf(RemoteAction.AxisSeek).single()
        assertEquals(-1, binding.rest)
        assertEquals(false, binding.inverted)
        assertEquals(0f, binding.valueOf(-1f))
        assertEquals(1f, binding.valueOf(1f))
    }

    @Test
    fun `small movement does not assign`() {
        startCapture(RemoteAction.AxisSeek)
        stick(0f)
        time.advance(500)

        stick(0.5f)

        assertEquals(RemoteAction.AxisSeek, menu.capturingKey)
    }

    @Test
    fun `assigned stick does not act until the next movement`() {
        startCapture(RemoteAction.AxisSeek)
        stick(0f)
        time.advance(500)

        stick(0.9f)
        time.advance(1_000)

        assertEquals(0L, seekedMs)
    }

    @Test
    fun `strongest axis is reported past the threshold only`() {
        assertNull(input.strongestAxis(ranges, mapOf(MotionEvent.AXIS_X to 0.4f, MotionEvent.AXIS_RTRIGGER to 0f)))

        assertEquals(
            MotionEvent.AXIS_X to -0.9f,
            input.strongestAxis(ranges, mapOf(MotionEvent.AXIS_X to -0.9f, MotionEvent.AXIS_RTRIGGER to 0.2f)),
        )
    }

    // --- Ось на меню и на просмотре: RC-07 ----------------------------------------------------------

    private val stickBinding = AxisBinding(MotionEvent.AXIS_X, inverted = false)

    @Test
    fun `RC-07 the same axis seeks while viewing and scrolls in the menu`() {
        bindings.assignAxis(RemoteAction.AxisScroll, stickBinding)
        bindings.assignAxis(RemoteAction.AxisSeek, stickBinding)

        stick(1f)
        time.advance(100)
        assertTrue(seekedMs > 0)
        assertTrue(host.scrolls.isEmpty())
        stick(0f)

        val seeked = seekedMs
        panels.showMenu(VrMenuState.Tab.Home)
        stick(1f)
        time.advance(100)
        assertEquals(seeked, seekedMs)
        assertTrue(host.scrolls.isNotEmpty())
    }

    @Test
    fun `RC-07 menu axis does nothing while viewing`() {
        bindings.assignAxis(RemoteAction.AxisNavHorizontal, stickBinding)

        stick(1f)
        assertTrue(host.keys.isEmpty())
    }

    @Test
    fun `button on an axis works whatever is on screen`() {
        bindings.assignAxis(RemoteAction.PlayPause, AxisBinding(MotionEvent.AXIS_RTRIGGER, inverted = false))

        trigger(1f)
        trigger(0f)
        panels.showMenu(VrMenuState.Tab.Home)
        trigger(1f)

        assertEquals(listOf("playPause", "playPause"), log)
    }

    // --- Плавные оси: RC-10, RC-11, RC-12 ---------------------------------------------------------------

    @Test
    fun `RC-11 full stick seeks about 20 seconds per second`() {
        bindings.assignAxis(RemoteAction.AxisSeek, stickBinding)

        stick(1f)
        time.advance(1_000)
        stick(0f)

        assertEquals(20_000f, seekedMs.toFloat(), 1_500f)
    }

    @Test
    fun `RC-11 half stick seeks slower, backwards when inverted`() {
        bindings.assignAxis(RemoteAction.AxisSeek, stickBinding.copy(inverted = true))

        stick(0.5f)
        time.advance(1_000)

        assertEquals(-10_000f, seekedMs.toFloat(), 1_000f)
    }

    @Test
    fun `seek stops when the stick returns`() {
        bindings.assignAxis(RemoteAction.AxisSeek, stickBinding)

        stick(1f)
        time.advance(200)
        stick(0.1f)
        val seeked = seekedMs
        time.advance(5_000)

        assertEquals(seeked, seekedMs)
    }

    /**
     * Известное расхождение с RC-11, закреплено как есть: каждое событие оси сразу запускает лишний шаг,
     * поэтому стик, шлющий события непрерывно, мотает быстрее обещанных 20 с/с. См. docs/scenarios/README.md.
     */
    @Test
    fun `every axis event adds a step, so a noisy stick seeks faster than promised`() {
        bindings.assignAxis(RemoteAction.AxisSeek, stickBinding)

        repeat(20) {
            stick(1f)
            time.advance(50)
        }
        stick(0f)

        assertEquals(40_000L, seekedMs)
    }

    @Test
    fun `RC-12 full stick about doubles the zoom per second`() {
        bindings.assignAxis(RemoteAction.AxisZoom, stickBinding)

        stick(1f)
        time.advance(1_000)
        stick(0f)

        assertEquals(2f, menu.zoom, 0.15f)
    }

    @Test
    fun `RC-10 stick scrolls smoothly, down means content up`() {
        bindings.assignAxis(RemoteAction.AxisScroll, stickBinding)
        panels.showMenu(VrMenuState.Tab.Home)

        stick(1f)
        time.advance(100)

        assertTrue(host.scrolls.size >= 2)
        assertTrue(host.scrolls.all { it < 0f })
    }

    @Test
    fun `menu stick steps once per deflection`() {
        bindings.assignAxis(RemoteAction.AxisNavVertical, stickBinding)
        panels.showMenu(VrMenuState.Tab.Home)

        stick(1f)
        stick(0.9f)
        stick(0.1f)
        stick(-1f)

        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP), host.keys)
    }

    @Test
    fun `axis button has a margin against chatter`() {
        bindings.assignAxis(RemoteAction.PlayPause, AxisBinding(MotionEvent.AXIS_RTRIGGER, inverted = false))

        trigger(0.7f)
        trigger(0.5f)
        trigger(0.7f)
        trigger(0.2f)
        trigger(0.7f)

        assertEquals(listOf("playPause", "playPause"), log)
    }

    // --- Рецентр и полоска: NAV-09, RC-15…RC-17 ----------------------------------------------------

    @Test
    fun `NAV-09 the recenter action shows nothing, on a key`() {
        bindings.assignKey(RemoteAction.Recenter, KeyEvent.KEYCODE_BUTTON_R1)
        videoOpen()

        tap(KeyEvent.KEYCODE_BUTTON_R1)

        assertEquals(1, scene.recenters)
        assertEquals(PanelPlace.Hidden, host.place)
    }

    @Test
    fun `NAV-09 the recenter action shows nothing, on an axis`() {
        videoOpen()
        bindings.assignAxis(RemoteAction.Recenter, AxisBinding(MotionEvent.AXIS_RTRIGGER, inverted = false))

        trigger(1f)
        trigger(0f)

        assertEquals(1, scene.recenters)
        assertEquals(PanelPlace.Hidden, host.place)
    }

    /** Гипотеза из docs/scenarios/README.md: рецентр долгим «Нажатием», а отпустили раньше 500 мс. */
    @Test
    fun `NAV-09 hypothesis, click key released early opens the controls instead of recentering`() {
        bindings.assignKey(RemoteAction.Click, KeyEvent.KEYCODE_BUTTON_R2)
        videoOpen()

        tap(KeyEvent.KEYCODE_BUTTON_R2, holdMs = 300)

        assertEquals(0, scene.recenters)
        assertEquals(PanelPlace.Vr, host.place)
        assertEquals(VrMenuState.Mode.Controls, menu.mode)
    }

    @Test
    fun `NAV-09 hypothesis, click trigger released early opens the controls instead of recentering`() {
        bindings.assignAxis(RemoteAction.Click, AxisBinding(MotionEvent.AXIS_RTRIGGER, inverted = false))
        videoOpen()

        trigger(1f)
        time.advance(300)
        trigger(0f)

        assertEquals(0, scene.recenters)
        assertEquals(PanelPlace.Vr, host.place)
    }

    @Test
    fun `RC-17 click trigger held long enough recenters and shows nothing`() {
        bindings.assignAxis(RemoteAction.Click, AxisBinding(MotionEvent.AXIS_RTRIGGER, inverted = false))
        videoOpen()

        trigger(1f)
        time.advance(500)
        trigger(0f)

        assertEquals(1, scene.recenters)
        assertEquals(PanelPlace.Hidden, host.place)
    }

    @Test
    fun `RC-17 click key held long enough recenters and shows nothing, despite key repeats`() {
        bindings.assignKey(RemoteAction.Click, KeyEvent.KEYCODE_BUTTON_R2)
        videoOpen()

        keyDown(KeyEvent.KEYCODE_BUTTON_R2)
        repeat(10) {
            time.advance(50)
            keyDown(KeyEvent.KEYCODE_BUTTON_R2, repeat = it + 1)
        }
        keyUp(KeyEvent.KEYCODE_BUTTON_R2)

        assertEquals(1, scene.recenters)
        assertEquals(PanelPlace.Hidden, host.place)
    }

    /**
     * Другой путь к тому же симптому: многие геймпады шлют за один курок и кнопку, и ось.
     * Рецентр на кнопке и «Нажатие» на оси того же курка считаются разными назначениями и срабатывают вместе.
     */
    @Test
    fun `NAV-09 same trigger as a recenter key and a click axis opens the controls too`() {
        bindings.assignKey(RemoteAction.Recenter, KeyEvent.KEYCODE_BUTTON_R2)
        bindings.assignAxis(RemoteAction.Click, AxisBinding(MotionEvent.AXIS_RTRIGGER, inverted = false))
        videoOpen()

        keyDown(KeyEvent.KEYCODE_BUTTON_R2)
        trigger(1f)
        time.advance(100)
        keyUp(KeyEvent.KEYCODE_BUTTON_R2)
        trigger(0f)

        assertEquals(1, scene.recenters)
        assertEquals(PanelPlace.Vr, host.place)
        assertEquals(VrMenuState.Mode.Controls, menu.mode)
    }

    @Test
    fun `RC-15 controls action on a key`() {
        bindings.assignKey(RemoteAction.Controls, KeyEvent.KEYCODE_BUTTON_Y)
        videoOpen()

        tap(KeyEvent.KEYCODE_BUTTON_Y)
        assertEquals(VrMenuState.Mode.Controls, menu.mode)
        tap(KeyEvent.KEYCODE_BUTTON_Y)
        assertEquals(VrMenuState.Mode.Menu, menu.mode)
        tap(KeyEvent.KEYCODE_BUTTON_Y)
        assertEquals(PanelPlace.Hidden, host.place)
    }

    @Test
    fun `no remote action switches to 2D or leaves VR`() {
        RemoteAction.entries.filter { !it.isAxis && it != RemoteAction.Click }.forEachIndexed { i, action ->
            bindings.assignKey(action, KeyEvent.KEYCODE_BUTTON_1 + i)
        }
        videoOpen()

        RemoteAction.entries.indices.forEach { tap(KeyEvent.KEYCODE_BUTTON_1 + it) }

        assertTrue("toggleStereo" !in log)
        assertTrue("exit" !in log)
    }
}

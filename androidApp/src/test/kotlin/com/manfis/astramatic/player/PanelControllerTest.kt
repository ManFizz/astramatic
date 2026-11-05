package com.manfis.astramatic.player

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.manfis.astramatic.settings.AppSettings
import com.manfis.astramatic.settings.RemoteAction
import com.manfis.astramatic.ui.Screen
import com.manfis.astramatic.ui.VrMenuState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Панели и нажатие: NAV-01…NAV-08, RC-15…RC-17, VR-05, VR-15, VR-16, VR-18 из docs/scenarios.
 * Состояние настоящее, вместо дисплея и рендерера подставные объекты.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PanelControllerTest {

    private val settings = AppSettings(
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("test_panels", Context.MODE_PRIVATE),
    )
    private val menu = VrMenuState()
    private val host = FakeHost()
    private val scene = FakeScene()
    private val time = FakeTime()
    private var stereo = true
    private var playing = true
    private var exits = 0
    private var hubs = 0
    private val sideOpened = mutableListOf<SideSlot>()

    private val panels: PanelController = PanelController(
        menu, settings, host, scene, time, clock = { time.now },
        stereo = { stereo },
        videoPlaying = { playing },
        exit = { exits++ },
        leaveToHub = {
            hubs++
            menu.parkedTitle = menu.title
            menu.title = null
            panels.showMenu(VrMenuState.Tab.Home)
        },
        resumeViewing = {
            menu.title = menu.parkedTitle
            menu.parkedTitle = null
            panels.hide()
        },
        openSide = { sideOpened += it },
    )

    private val point = Gaze(100f, 200f)

    private fun videoOpen() {
        menu.title = "видео.mp4"
    }

    private fun press(durationMs: Long) {
        panels.triggerDown(time.now)
        time.advance(durationMs)
        panels.triggerUp(time.now)
    }

    private fun assertHidden() {
        assertEquals(PanelPlace.Hidden, host.place)
        assertFalse(scene.panelVisible)
    }

    private fun assertMenuOnHome() {
        assertEquals(PanelPlace.Vr, host.place)
        assertEquals(VrMenuState.Mode.Menu, menu.mode)
        assertEquals(VrMenuState.Tab.Home, menu.tab)
        assertEquals(PanelLayout.Menu, scene.panelLayout)
    }

    private fun assertControls() {
        assertEquals(PanelPlace.Vr, host.place)
        assertEquals(VrMenuState.Mode.Controls, menu.mode)
        assertEquals(PanelLayout.Controls, scene.panelLayout)
    }

    // --- «Назад» ---------------------------------------------------------------------------------

    @Test
    fun `NAV-01 back while watching goes to the hub and stays in VR`() {
        videoOpen()

        panels.goBack()

        assertMenuOnHome()
        assertEquals(0, exits)
    }

    @Test
    fun `NAV-02 back on the controls opens home`() {
        videoOpen()
        panels.showControls()

        panels.goBack()

        assertMenuOnHome()
    }

    @Test
    fun `NAV-03 back from a settings section or format opens home`() {
        videoOpen()
        for (tab in listOf(
            VrMenuState.Tab.Settings, VrMenuState.Tab.Headset, VrMenuState.Tab.Remote, VrMenuState.Tab.Video,
        )) {
            panels.showMenu(tab)
            panels.goBack()
            assertMenuOnHome()
        }
        assertEquals(0, exits)
    }

    @Test
    fun `NAV-04 back with an open sheet closes it and keeps the controls`() {
        videoOpen()
        panels.showControls()
        menu.sheet = VrMenuState.Sheet.Queue
        panels.updateLayout()
        assertEquals(PanelLayout.ControlsSheet, scene.panelLayout)

        panels.goBack()

        assertNull(menu.sheet)
        assertEquals(VrMenuState.Mode.Controls, menu.mode)
        assertEquals(PanelPlace.Vr, host.place)
        panels.updateLayout()
        assertEquals(PanelLayout.Controls, scene.panelLayout)
    }

    @Test
    fun `NAV-04 next back after closing the sheet opens home`() {
        videoOpen()
        panels.showControls()
        menu.sheet = VrMenuState.Sheet.View

        panels.goBack()
        panels.goBack()

        assertMenuOnHome()
    }

    @Test
    fun `NAV-04 key capture is cancelled before the sheet is closed`() {
        videoOpen()
        panels.showControls()
        menu.sheet = VrMenuState.Sheet.View
        menu.capturingKey = RemoteAction.Recenter

        panels.goBack()

        assertNull(menu.capturingKey)
        assertEquals(VrMenuState.Sheet.View, menu.sheet)
    }

    @Test
    fun `NAV-05 back on home inside a folder goes up one level`() {
        panels.showMenu(VrMenuState.Tab.Home)
        val stack = menu.library.backStack
        stack += Screen.History
        stack += Screen.EditServer(null)

        panels.goBack()
        assertEquals(2, stack.size)
        assertMenuOnHome()

        panels.goBack()
        assertEquals(1, stack.size)
    }

    @Test
    fun `NAV-06 back on the root of home does nothing in VR`() {
        panels.showMenu(VrMenuState.Tab.Home)

        repeat(5) { panels.goBack() }

        assertMenuOnHome()
        assertEquals(0, exits)
    }

    @Test
    fun `NAV-07 back on the root of home closes the player in 2D`() {
        stereo = false
        panels.showMenu(VrMenuState.Tab.Home)

        panels.goBack()

        assertEquals(1, exits)
    }

    @Test
    fun `2D back follows the same steps before closing`() {
        stereo = false
        videoOpen()
        panels.showControls()
        panels.goBack()
        assertEquals(VrMenuState.Mode.Menu, menu.mode)
        assertEquals(0, exits)

        panels.showMenu(VrMenuState.Tab.Settings)
        panels.goBack()
        assertEquals(VrMenuState.Tab.Home, menu.tab)
        assertEquals(0, exits)
    }

    @Test
    fun `NAV-08 back during key capture only cancels it`() {
        videoOpen()
        panels.showMenu(VrMenuState.Tab.Remote)
        menu.capturingKey = RemoteAction.Recenter

        panels.goBack()

        assertNull(menu.capturingKey)
        assertEquals(VrMenuState.Tab.Remote, menu.tab)
        assertEquals(PanelPlace.Vr, host.place)
    }

    @Test
    fun `back never leaves VR whatever the state`() {
        videoOpen()
        panels.showControls()
        repeat(10) { panels.goBack() }
        panels.hide()
        repeat(10) { panels.goBack() }

        assertEquals(0, exits)
        assertEquals(PanelPlace.Vr, host.place)
    }

    // --- Действие «Полоска → меню → скрыть» ---------------------------------------------------------

    @Test
    fun `RC-15 controls action cycles hidden, controls, hub, back to watching`() {
        videoOpen()

        panels.cycleControls()
        assertControls()

        panels.cycleControls()
        assertMenuOnHome()

        panels.cycleControls()
        assertHidden()

        panels.cycleControls()
        assertControls()
    }

    @Test
    fun `RC-15 without a video the menu cannot be hidden`() {
        panels.cycleControls()
        assertMenuOnHome()

        panels.cycleControls()
        assertMenuOnHome()
    }

    // --- Нажатие мимо панели ---------------------------------------------------------------------------

    @Test
    fun `RC-16 short press with no panels opens the controls`() {
        videoOpen()

        press(100)

        assertControls()
        assertEquals(0, scene.recenters)
    }

    @Test
    fun `RC-16 short press with no video opens home`() {
        press(100)

        assertMenuOnHome()
    }

    @Test
    fun `RC-17 long press with no panels recenters and shows nothing`() {
        videoOpen()

        press(PressTracker.LONG_PRESS_MS)

        assertEquals(1, scene.recenters)
        assertHidden()
    }

    @Test
    fun `RC-17 just under the limit is still a short press`() {
        videoOpen()

        press(PressTracker.LONG_PRESS_MS - 1)

        assertEquals(0, scene.recenters)
        assertControls()
    }

    @Test
    fun `VR-18 press past the panel closes it when there is a video`() {
        videoOpen()
        panels.showMenu(VrMenuState.Tab.Home)

        press(100)

        assertHidden()
    }

    @Test
    fun `VR-18 press past the panel brings it back when there is nothing to watch`() {
        panels.showMenu(VrMenuState.Tab.Home)
        val before = scene.placedInFront

        press(100)

        assertEquals(PanelPlace.Vr, host.place)
        assertEquals(before + 1, scene.placedInFront)
    }

    @Test
    fun `long press past an open panel closes it and does not recenter`() {
        videoOpen()
        panels.showControls()

        press(PressTracker.LONG_PRESS_MS + 100)

        assertHidden()
        assertEquals(0, scene.recenters)
    }

    @Test
    fun `cancelled press past the panel does nothing`() {
        videoOpen()
        panels.triggerDown(time.now)
        time.advance(600)
        panels.triggerUp(time.now, cancelled = true)

        assertHidden()
        assertEquals(0, scene.recenters)
    }

    @Test
    fun `press on the phone screen in 2D goes to the interface itself`() {
        stereo = false
        videoOpen()
        panels.showControls()

        press(100)

        assertEquals(PanelPlace.Screen, host.place)
        assertEquals(VrMenuState.Mode.Controls, menu.mode)
    }

    // --- Нажатие по панели -------------------------------------------------------------------------------

    @Test
    fun `press on the panel goes to the interface at the gaze point`() {
        panels.showMenu(VrMenuState.Tab.Home)
        scene.gaze = point

        press(100)

        assertEquals(TouchPhase.Down to point, host.touches.first())
        assertEquals(TouchPhase.Up to point, host.touches.last())
        assertEquals(PanelPlace.Vr, host.place)
    }

    @Test
    fun `holding on the panel follows the gaze`() {
        panels.showMenu(VrMenuState.Tab.Home)
        scene.gaze = point
        panels.triggerDown(time.now)

        val moved = Gaze(300f, 200f)
        scene.gaze = moved
        time.advance(100)

        assertEquals(TouchPhase.Move to moved, host.touches.last())
    }

    @Test
    fun `no more movement after release`() {
        panels.showMenu(VrMenuState.Tab.Home)
        scene.gaze = point
        press(50)
        val count = host.touches.size

        time.advance(1_000)

        assertEquals(count, host.touches.size)
    }

    @Test
    fun `gaze lost at release uses the last point`() {
        panels.showMenu(VrMenuState.Tab.Home)
        scene.gaze = point
        panels.triggerDown(time.now)
        scene.gaze = null
        panels.triggerUp(time.now + 10)

        assertEquals(TouchPhase.Up to point, host.touches.last())
    }

    @Test
    fun `cancelled press on the panel is cancelled in the interface`() {
        panels.showMenu(VrMenuState.Tab.Home)
        scene.gaze = point
        panels.triggerDown(time.now)
        panels.triggerUp(time.now + 10, cancelled = true)

        assertEquals(TouchPhase.Cancel, host.touches.last().first)
    }

    @Test
    fun `corner under the gaze is grabbed instead of pressing the interface`() {
        panels.showMenu(VrMenuState.Tab.Home)
        scene.hoverHandle = Handle.TopLeft
        scene.gaze = point

        panels.triggerDown(time.now)
        assertEquals(Handle.TopLeft, scene.grab)
        assertTrue(host.touches.isEmpty())

        panels.triggerUp(time.now + 700)
        assertEquals(Handle.None, scene.grab)
        assertEquals(PanelPlace.Vr, host.place)
        assertEquals(0, scene.recenters)
    }

    @Test
    fun `hiding the panel lets go of a corner`() {
        panels.showMenu(VrMenuState.Tab.Home)
        scene.hoverHandle = Handle.Move
        panels.triggerDown(time.now)

        panels.hide()

        assertEquals(Handle.None, scene.grab)
    }

    // --- Автоскрытие: VR-15, VR-16 ---------------------------------------------------------------------

    private fun controlsWithVideo() {
        videoOpen()
        panels.showControls()
    }

    @Test
    fun `VR-15 controls hide after four seconds when the gaze is away`() {
        controlsWithVideo()

        time.advance(3_900)
        assertControls()

        time.advance(200)
        assertHidden()
    }

    @Test
    fun `VR-16 paused video keeps the controls`() {
        controlsWithVideo()
        playing = false

        time.advance(60_000)

        assertControls()
    }

    @Test
    fun `VR-16 open sheet keeps the controls`() {
        controlsWithVideo()
        menu.sheet = VrMenuState.Sheet.View

        time.advance(60_000)

        assertEquals(PanelPlace.Vr, host.place)
    }

    @Test
    fun `VR-16 gaze on the controls keeps them and restarts the count`() {
        controlsWithVideo()
        scene.gaze = point

        time.advance(60_000)
        assertControls()

        scene.gaze = null
        time.advance(3_900)
        assertControls()
        time.advance(200)
        assertHidden()
    }

    @Test
    fun `VR-16 menu never hides by itself`() {
        videoOpen()
        panels.showMenu(VrMenuState.Tab.Home)

        time.advance(60_000)

        assertMenuOnHome()
    }

    @Test
    fun `hold on the panel keeps the controls`() {
        controlsWithVideo()
        scene.gaze = point
        panels.triggerDown(time.now)
        scene.gaze = null

        time.advance(60_000)

        assertControls()
    }

    @Test
    fun `reopening the controls restarts the count`() {
        controlsWithVideo()
        time.advance(3_000)

        panels.showControls()
        time.advance(3_000)

        assertControls()
    }

    @Test
    fun `controls are not hidden in 2D`() {
        stereo = false
        controlsWithVideo()

        time.advance(60_000)

        assertEquals(PanelPlace.Screen, host.place)
    }

    // --- Подсветка и нажатие взглядом ---------------------------------------------------------------

    @Test
    fun `gaze is shown as hover while the panel is in the headset`() {
        panels.showMenu(VrMenuState.Tab.Home)
        scene.gaze = point
        time.advance(60)

        assertEquals(point, host.hovers.last())

        panels.hide()
        assertNull(host.hovers.last())
    }

    @Test
    fun `VR-05 dwell clicks the interface once`() {
        settings.dwellClick = true
        settings.dwellMs = 1_000
        panels.showMenu(VrMenuState.Tab.Home)
        scene.gaze = point

        time.advance(500)
        assertTrue(scene.dwellProgress > 0.3f)
        assertTrue(host.touches.isEmpty())

        time.advance(600)
        assertEquals(listOf(TouchPhase.Down to point, TouchPhase.Up to point), host.touches)
        assertEquals(0f, scene.dwellProgress)

        time.advance(5_000)
        assertEquals(2, host.touches.size)
    }

    @Test
    fun `dwell is off by default`() {
        panels.showMenu(VrMenuState.Tab.Home)
        scene.gaze = point

        time.advance(10_000)

        assertTrue(host.touches.isEmpty())
    }

    // --- Прочее ------------------------------------------------------------------------------------------

    @Test
    fun `calibration room only for the headset tab in VR`() {
        panels.showMenu(VrMenuState.Tab.Headset)
        assertTrue(scene.calibration)

        menu.tab = VrMenuState.Tab.Home
        panels.updateCalibration()
        assertFalse(scene.calibration)

        menu.tab = VrMenuState.Tab.Headset
        stereo = false
        panels.stereoChanged()
        assertEquals(PanelPlace.Screen, host.place)
        assertFalse(scene.calibration)
    }

    @Test
    fun `switching to 2D keeps the same panel and stops the headset work`() {
        videoOpen()
        panels.showControls()

        stereo = false
        panels.stereoChanged()

        assertEquals(PanelPlace.Screen, host.place)
        assertEquals(VrMenuState.Mode.Controls, menu.mode)
        assertFalse(scene.panelVisible)

        stereo = true
        panels.stereoChanged()
        assertControls()
        assertTrue(scene.panelVisible)
    }

    @Test
    fun `switching mode with nothing shown shows nothing`() {
        videoOpen()
        stereo = false
        panels.stereoChanged()

        assertHidden()
    }

    @Test
    fun `navigation keys go to the panel only when it is shown`() {
        panels.navKey(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(host.keys.isEmpty())

        panels.showMenu(VrMenuState.Tab.Home)
        panels.navKey(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_UP), host.keys)
    }

    @Test
    fun `scroll goes to the panel only when it is shown`() {
        panels.scroll(1f)
        assertTrue(host.scrolls.isEmpty())

        panels.showMenu(VrMenuState.Tab.Home)
        panels.scroll(1f)
        assertEquals(listOf(1f), host.scrolls)
    }

    @Test
    fun `hide clears the notice and the sheet`() {
        videoOpen()
        panels.showNotice("ошибка")
        assertEquals("ошибка", menu.notice)
        assertEquals(PanelPlace.Vr, host.place)

        panels.hide()

        assertNull(menu.notice)
    }

    // --- Хаб и место панели ---------------------------------------------------------------------------

    @Test
    fun `NAV-16 every way out of watching leads to the hub`() {
        videoOpen()
        panels.goBack()
        assertEquals(1, hubs)

        menu.title = "видео.mp4"
        menu.parkedTitle = null
        panels.showControls()
        panels.goBack()
        assertEquals(2, hubs)

        menu.title = "видео.mp4"
        menu.parkedTitle = null
        panels.showMenu(VrMenuState.Tab.Headset)
        panels.goBack()
        assertEquals(3, hubs)
        assertMenuOnHome()
    }

    @Test
    fun `VR-22 controls open where you look`() {
        videoOpen()

        panels.showControls()

        assertEquals(listOf(PanelPlacement.AtGaze), scene.placements.takeLast(1))
    }

    @Test
    fun `VR-23 menu from the controls opens on the centre, not at the press`() {
        videoOpen()
        panels.showControls()

        panels.showMenu(VrMenuState.Tab.Home)

        assertEquals(listOf(PanelPlacement.Center), scene.placements.takeLast(1))
    }

    @Test
    fun `VR-23 open menu stays where it is when the section changes or a notice comes`() {
        panels.showMenu(VrMenuState.Tab.Home)
        val placed = scene.placements.size

        panels.showMenu(VrMenuState.Tab.Headset)
        panels.showNotice("сообщение")

        assertEquals(placed, scene.placements.size)
    }

    // --- Соседние окна -----------------------------------------------------------------------------------

    @Test
    fun `PL-21 press on a side window opens it, with or without the controls`() {
        videoOpen()
        scene.hoverSide = SideSlot.Left
        press(100)
        assertEquals(listOf(SideSlot.Left), sideOpened)
        assertHidden()

        scene.hoverSide = null
        panels.showControls()
        scene.hoverSide = SideSlot.Right
        press(100)
        assertEquals(listOf(SideSlot.Left, SideSlot.Right), sideOpened)
    }

    @Test
    fun `PL-21 long press on a side window is still a recenter`() {
        videoOpen()
        scene.hoverSide = SideSlot.Left

        press(PressTracker.LONG_PRESS_MS + 10)

        assertEquals(emptyList(), sideOpened)
        assertEquals(1, scene.recenters)
    }

    @Test
    fun `VR-03 no hover goes to the interface while a press is held`() {
        panels.showMenu(VrMenuState.Tab.Home)
        scene.gaze = point
        time.advance(100)

        panels.triggerDown(time.now)
        val hovers = host.hovers.size
        time.advance(300)
        assertEquals(hovers, host.hovers.size)

        panels.triggerUp(time.now)
        time.advance(100)
        assertTrue(host.hovers.size > hovers)
    }
}

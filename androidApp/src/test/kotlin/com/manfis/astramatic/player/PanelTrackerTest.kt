package com.manfis.astramatic.player

import android.opengl.Matrix
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Сценарии VR-07, VR-08, VR-11, VR-17, а также центр видео (PL-04, PL-05, HS-07) из docs/scenarios.
 * Панель на базовом расстоянии: 2,5 м, поэтому размеры в метрах такие же, как в константах.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PanelTrackerTest {

    private class Prefs : PanelPreferences {
        override var recenterPitch = false
        override var menuDistance = PANEL_DISTANCE
        override var menuSize = 1f
    }

    private val prefs = Prefs()
    private val savedSizes = mutableListOf<Float>()
    private val tracker = PanelTracker(prefs, onMenuSizeChanged = { savedSizes += it }).apply { panelVisible = true }

    /** Голова повёрнута влево на [yaw], вверх на [pitch]; [frames] кадров. */
    private fun look(yaw: Float, pitch: Float = 0f, frames: Int = 1) {
        val y = Math.toRadians(yaw.toDouble())
        val p = Math.toRadians(pitch.toDouble())
        repeat(frames) {
            tracker.update(
                (-sin(y) * cos(p)).toFloat(),
                sin(p).toFloat(),
                (-cos(y) * cos(p)).toFloat(),
            )
        }
    }

    /** Трекинг устоялся, панель стоит прямо перед взглядом. */
    private fun settle(yaw: Float = 0f, pitch: Float = 0f) = look(yaw, pitch, PanelTracker.SETTLE_FRAMES + 1)

    /** Взгляд в точку плоскости панели [x], [y] (метры от её оси). Панель стоит без наклона в направлении [panelYaw]. */
    private fun lookAtPlane(x: Float, y: Float, panelYaw: Float = 0f) {
        val z = PANEL_DISTANCE * prefs.menuDistance / PANEL_DISTANCE
        val yaw = panelYaw - Math.toDegrees(atan((x / z).toDouble())).toFloat()
        val along = kotlin.math.hypot(x, z)
        val pitch = Math.toDegrees(atan((y / along).toDouble())).toFloat()
        look(yaw, pitch)
    }

    /** Точка рядом с уголком: чуть снаружи панели. */
    private fun cornerX(sign: Int) = sign * (tracker.panelW / 2 + 0.05f)
    private fun cornerY(sign: Int) = tracker.panelCy + sign * (tracker.panelH / 2 + 0.05f)

    private fun grabAt(handle: Handle) {
        assertEquals(handle, tracker.hoverHandle)
        tracker.grab = handle
    }

    private fun release() {
        tracker.grab = Handle.None
    }

    // --- Взгляд и панель ---------------------------------------------------------------------------

    @Test
    fun `panel appears once in front of the settled gaze and does not follow it`() {
        settle(yaw = 40f)
        assertEquals(40f, tracker.panelYawDeg, 0.01f)

        look(yaw = 60f)
        assertEquals(40f, tracker.panelYawDeg, 0.01f)
    }

    @Test
    fun `no gaze while the tracker is settling`() {
        look(0f, frames = PanelTracker.SETTLE_FRAMES - 1)

        assertNull(tracker.gaze)
        assertEquals(Handle.None, tracker.hoverHandle)
    }

    @Test
    fun `gaze at the centre of the panel maps to the centre of the display`() {
        settle()

        val gaze = assertNotNull(tracker.gaze)
        assertEquals(PANEL_WIDTH_PX / 2f, gaze.x, 0.5f)
        assertEquals(PANEL_HEIGHT_PX / 2f, gaze.y, 0.5f)
    }

    @Test
    fun `gaze uses only the visible part of the display`() {
        tracker.layout = PanelLayout.Controls
        settle()
        lookAtPlane(0f, PanelLayout.Controls.centerY)

        val gaze = assertNotNull(tracker.gaze)
        assertEquals(PanelLayout.Controls.visibleHeightPx / 2f, gaze.y, 1f)
    }

    @Test
    fun `gaze past the panel is null`() {
        settle()
        look(yaw = 60f)
        assertNull(tracker.gaze)

        look(yaw = 170f)
        assertNull(tracker.gaze)
        assertTrue(tracker.hitX.isNaN())
    }

    @Test
    fun `hit point agrees with the matrices the renderer draws with`() {
        settle(yaw = 25f, pitch = 0f)
        prefs.recenterPitch = true
        tracker.placePanelInFront()
        look(yaw = 25f, pitch = 15f)
        val yaw = 31f
        val pitch = 22f
        look(yaw, pitch)

        // Настоящая модель панели, как в VrRenderer.onNewFrame.
        val rotation = FloatArray(16)
        Matrix.setRotateM(rotation, 0, tracker.panelYawDeg, 0f, 1f, 0f)
        Matrix.rotateM(rotation, 0, tracker.panelPitchDeg, 1f, 0f, 0f)
        val world = FloatArray(4)
        Matrix.multiplyMV(world, 0, rotation, 0, floatArrayOf(tracker.hitX, tracker.hitY, tracker.panelZ, 0f), 0)
        val length = kotlin.math.sqrt(world[0] * world[0] + world[1] * world[1] + world[2] * world[2])
        val y = Math.toRadians(yaw.toDouble())
        val p = Math.toRadians(pitch.toDouble())

        assertEquals((-sin(y) * cos(p)).toFloat(), world[0] / length, 0.0005f)
        assertEquals(sin(p).toFloat(), world[1] / length, 0.0005f)
        assertEquals((-cos(y) * cos(p)).toFloat(), world[2] / length, 0.0005f)
    }

    @Test
    fun `angles for a panel point lead the gaze exactly there`() {
        settle(yaw = 25f)
        prefs.recenterPitch = true
        tracker.placePanelInFront()
        look(yaw = 25f, pitch = 15f)

        val (yaw, pitch) = tracker.anglesForPanelPoint(320f, 200f)
        look(yaw, pitch)

        val gaze = assertNotNull(tracker.gaze)
        assertEquals(320f, gaze.x, 0.5f)
        assertEquals(200f, gaze.y, 0.5f)
    }

    // --- Ручки ---------------------------------------------------------------------------------------

    @Test
    fun `corners are found just outside the menu`() {
        settle()
        lookAtPlane(cornerX(-1), cornerY(1))
        assertEquals(Handle.TopLeft, tracker.hoverHandle)
        lookAtPlane(cornerX(1), cornerY(1))
        assertEquals(Handle.TopRight, tracker.hoverHandle)
        lookAtPlane(cornerX(-1), cornerY(-1))
        assertEquals(Handle.BottomLeft, tracker.hoverHandle)
        lookAtPlane(cornerX(1), cornerY(-1))
        assertEquals(Handle.BottomRight, tracker.hoverHandle)
    }

    @Test
    fun `buttons at the edge of the menu are not intercepted by a corner`() {
        settle()
        // Точка внутри меню, чуть дальше отступа от края: уголка тут нет, взгляд попадает в меню.
        lookAtPlane(tracker.panelW / 2 - 0.04f, tracker.panelH / 2 - 0.04f)

        assertEquals(Handle.None, tracker.hoverHandle)
        assertNotNull(tracker.gaze)
    }

    @Test
    fun `move bar sits under the menu`() {
        settle()
        lookAtPlane(0f, -tracker.panelH / 2 - 0.08f)
        assertEquals(Handle.Move, tracker.hoverHandle)

        // В стороне от центра полоски её нет.
        lookAtPlane(0.6f, -tracker.panelH / 2 - 0.08f)
        assertEquals(Handle.None, tracker.hoverHandle)
    }

    @Test
    fun `handles exist only for the menu`() {
        tracker.layout = PanelLayout.Controls
        settle()
        lookAtPlane(cornerX(1), cornerY(1))

        assertEquals(Handle.None, tracker.hoverHandle)
    }

    @Test
    fun `handles disappear with the panel`() {
        settle()
        lookAtPlane(cornerX(1), cornerY(1))
        assertEquals(Handle.TopRight, tracker.hoverHandle)

        tracker.panelVisible = false
        look(0f)

        assertEquals(Handle.None, tracker.hoverHandle)
        assertNull(tracker.gaze)
    }

    // --- VR-07: растягивание ------------------------------------------------------------------------------

    /** Размер считается по попаданию взгляда предыдущего кадра, поэтому кадра два. */
    private fun dragTo(x: Float, y: Float) {
        lookAtPlane(x, y)
        lookAtPlane(x, y)
    }

    @Test
    fun `dragging a corner away from the centre grows the menu and keeps proportions`() {
        settle()
        val width = tracker.panelW
        val height = tracker.panelH
        val x = cornerX(1)
        val y = cornerY(1)
        lookAtPlane(x, y)
        grabAt(Handle.TopRight)
        lookAtPlane(x, y)

        dragTo(x * 1.2f, y * 1.2f)

        assertEquals(width * 1.2f, tracker.panelW, 0.02f)
        assertEquals(width / height, tracker.panelW / tracker.panelH, 0.001f)
    }

    @Test
    fun `dragging towards the centre shrinks the menu`() {
        settle()
        val width = tracker.panelW
        val x = cornerX(1)
        val y = cornerY(1)
        lookAtPlane(x, y)
        grabAt(Handle.TopRight)
        lookAtPlane(x, y)

        dragTo(x * 0.8f, y * 0.8f)

        assertEquals(width * 0.8f, tracker.panelW, 0.02f)
    }

    @Test
    fun `size stops at the limits`() {
        settle()
        val width = tracker.panelW
        val x = cornerX(1)
        val y = cornerY(1)
        lookAtPlane(x, y)
        grabAt(Handle.TopRight)
        lookAtPlane(x, y)

        dragTo(x * 3f, y * 3f)
        assertEquals(width * MAX_MENU_SIZE, tracker.panelW, 0.001f)

        dragTo(x * 0.1f, y * 0.1f)
        assertEquals(width * MIN_MENU_SIZE, tracker.panelW, 0.001f)
    }

    @Test
    fun `released size is saved and stays until settings catch up`() {
        settle()
        val x = cornerX(1)
        val y = cornerY(1)
        lookAtPlane(x, y)
        grabAt(Handle.TopRight)
        lookAtPlane(x, y)
        dragTo(x * 1.2f, y * 1.2f)
        val width = tracker.panelW

        release()
        look(tracker.panelYawDeg)

        assertEquals(1, savedSizes.size)
        assertEquals(1.2f, savedSizes.single(), 0.02f)
        // Настройка ещё не записана (другой поток), а меню уже не прыгает обратно.
        assertEquals(width, tracker.panelW, 0.001f)

        prefs.menuSize = savedSizes.single()
        look(tracker.panelYawDeg)
        assertEquals(width, tracker.panelW, 0.001f)

        // Дальше снова по настройке.
        prefs.menuSize = 1f
        look(tracker.panelYawDeg)
        assertEquals(PanelLayout.Menu.width, tracker.panelW, 0.001f)
    }

    @Test
    fun `gaze is not reported while holding a corner`() {
        settle()
        lookAtPlane(cornerX(1), cornerY(1))
        grabAt(Handle.TopRight)
        lookAtPlane(0f, 0f)

        assertEquals(Handle.TopRight, tracker.hoverHandle)
        assertNull(tracker.gaze)
    }

    // --- VR-08: перетаскивание --------------------------------------------------------------------------

    @Test
    fun `move bar drags the menu with the offset kept`() {
        settle()
        val barY = -tracker.panelH / 2 - 0.08f
        lookAtPlane(0f, barY)
        val startPitch = -Math.toDegrees(atan((-barY / PANEL_DISTANCE).toDouble())).toFloat()
        grabAt(Handle.Move)
        look(0f, startPitch)

        look(30f, startPitch + 10f)

        assertEquals(30f, tracker.panelYawDeg, 0.01f)
        assertEquals(10f, tracker.panelPitchDeg, 0.01f)
    }

    @Test
    fun `dragged menu stops at 70 degrees up and down`() {
        settle()
        lookAtPlane(0f, -tracker.panelH / 2 - 0.08f)
        grabAt(Handle.Move)
        look(0f, 0f)

        look(0f, 89f)
        assertEquals(70f, tracker.panelPitchDeg, 0.01f)

        look(0f, -89f)
        assertEquals(-70f, tracker.panelPitchDeg, 0.01f)
    }

    @Test
    fun `dragging across 180 degrees does not jump`() {
        settle(yaw = 170f)
        lookAtPlane(0f, -tracker.panelH / 2 - 0.08f, panelYaw = 170f)
        grabAt(Handle.Move)
        look(170f, 0f)

        look(190f, 0f)

        assertEquals(-170f, tracker.panelYawDeg, 0.01f)
    }

    @Test
    fun `moving the menu does not touch its size or the video`() {
        settle()
        val width = tracker.panelW
        lookAtPlane(0f, -tracker.panelH / 2 - 0.08f)
        grabAt(Handle.Move)
        look(0f)
        look(30f)
        release()
        look(30f)

        assertEquals(width, tracker.panelW, 0.001f)
        assertEquals(0f, tracker.videoYawDeg, 0.01f)
        assertTrue(savedSizes.isEmpty())
    }

    // --- VR-11: размер и расстояние из настроек ---------------------------------------------------

    @Test
    fun `size setting changes only the menu and matches the corners`() {
        settle()
        prefs.menuSize = 1.5f
        look(0f)

        assertEquals(PanelLayout.Menu.width * 1.5f, tracker.panelW, 0.001f)
        assertEquals(PanelLayout.Menu.height * 1.5f, tracker.panelH, 0.001f)
        assertEquals(-PANEL_DISTANCE, tracker.panelZ, 0.001f)

        tracker.layout = PanelLayout.Controls
        look(0f)
        assertEquals(PanelLayout.Controls.width, tracker.panelW, 0.001f)
    }

    @Test
    fun `size setting out of range is clamped`() {
        settle()
        prefs.menuSize = 5f
        look(0f)
        assertEquals(PanelLayout.Menu.width * MAX_MENU_SIZE, tracker.panelW, 0.001f)
    }

    @Test
    fun `further menu is proportionally bigger and looks the same`() {
        settle()
        val width = tracker.panelW
        val angleBefore = atan(tracker.panelW / 2 / -tracker.panelZ)

        prefs.menuDistance = PANEL_DISTANCE * 2
        look(0f)

        assertEquals(width * 2, tracker.panelW, 0.001f)
        assertEquals(angleBefore, atan(tracker.panelW / 2 / -tracker.panelZ), 0.0001f)
    }

    // --- VR-17: полоска догоняет взгляд ---------------------------------------------------------------

    @Test
    fun `controls catch up with the gaze when you turn far away`() {
        tracker.layout = PanelLayout.Controls
        settle()

        look(20f, frames = 60)
        assertEquals(0f, tracker.panelYawDeg, 0.01f)

        look(60f, frames = 1)
        val first = tracker.panelYawDeg
        assertTrue(first > 0f && first < 10f, "мягко, а не рывком: $first°")

        look(60f, frames = 120)
        assertTrue(abs(tracker.panelYawDeg - 60f) < 1f, "встаёт по центру: ${tracker.panelYawDeg}°")
    }

    @Test
    fun `menu stays where it is when you turn away`() {
        settle()
        look(90f, frames = 120)

        assertEquals(0f, tracker.panelYawDeg, 0.01f)
    }

    @Test
    fun `sheet over the controls follows too`() {
        tracker.layout = PanelLayout.ControlsSheet
        settle()
        look(60f, frames = 120)

        assertTrue(abs(tracker.panelYawDeg - 60f) < 1f)
    }

    // --- Центр видео: PL-04, PL-05, HS-07 ------------------------------------------------------------

    @Test
    fun `video centre stays until recenter is asked`() {
        settle()
        look(90f, frames = 30)
        assertEquals(0f, tracker.videoYawDeg, 0.01f)

        tracker.recenter()
        look(90f)
        assertEquals(90f, tracker.videoYawDeg, 0.01f)

        look(120f, frames = 30)
        assertEquals(90f, tracker.videoYawDeg, 0.01f)
    }

    @Test
    fun `recenter tilts the video only in lying mode`() {
        settle()
        tracker.recenter()
        look(0f, 30f)
        assertEquals(0f, tracker.videoPitchDeg, 0.01f)

        prefs.recenterPitch = true
        tracker.recenter()
        look(0f, 30f)
        assertEquals(30f, tracker.videoPitchDeg, 0.01f)
    }

    @Test
    fun `file chosen in the menu starts where the menu is`() {
        settle()
        look(90f)
        tracker.placePanelInFront()
        look(90f)
        look(120f)

        tracker.centerOnPanel()
        look(120f)

        assertEquals(90f, tracker.videoYawDeg, 0.01f)
    }

    @Test
    fun `panel goes in front of the gaze when asked and the video does not move`() {
        settle()
        tracker.placePanelInFront()
        look(50f)

        assertEquals(50f, tracker.panelYawDeg, 0.01f)
        assertEquals(0f, tracker.videoYawDeg, 0.01f)
    }
}

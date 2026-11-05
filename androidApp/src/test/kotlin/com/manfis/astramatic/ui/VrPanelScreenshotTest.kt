package com.manfis.astramatic.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.manfis.astramatic.model.Projection
import com.manfis.astramatic.model.StereoLayout
import com.manfis.astramatic.model.VideoFormat
import com.manfis.astramatic.player.Playlist
import com.manfis.astramatic.player.PlaylistItem
import com.manfis.astramatic.player.PANEL_DENSITY_DPI
import com.manfis.astramatic.player.PANEL_HEIGHT_PX
import com.manfis.astramatic.player.PANEL_WIDTH_PX
import com.manfis.astramatic.player.PanelLayout
import com.manfis.astramatic.settings.RemoteAction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Панели шлема с настоящей геометрией виртуального дисплея: 1280×800 при 240 dpi,
 * видна только верхняя часть — как в [PanelLayout]. Эталоны лежат в `src/test/screenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w900dp-h600dp-land-hdpi")
class VrPanelScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val actions = object : VrMenuActions {
        override fun isPlaying() = true
        override fun positionMs() = 754_000L
        override fun durationMs() = 3_725_000L
        override fun togglePlayback() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(deltaMs: Long) = Unit
        override fun setFormat(format: VideoFormat) = Unit
        override fun resetFormat() = Unit
        override fun toggleStereo() = Unit
        override fun recenter() = Unit
        override fun setZoom(zoom: Float) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setAsEnvironment() = ""
        override fun play(playlist: Playlist) = Unit
        override fun previousFile() = Unit
        override fun nextFile() = Unit
        override fun cycleRepeat() = Unit
        override fun toggleShuffle() = Unit
        override fun toggleSlideshow() = Unit
        override fun playQueueItem(index: Int) = Unit
        override fun headsetSaved() = Unit
        override fun openMenu(tab: VrMenuState.Tab) = Unit
        override fun toHub() = Unit
        override fun resumeViewing() = Unit
        override fun hide() = Unit
        override fun exit() = Unit
    }

    private fun video(name: String) = PlaylistItem(
        uri = Uri.parse("file:///storage/$name"),
        title = name,
        format = VideoFormat(Projection.Dome180, StereoLayout.SideBySide),
    )

    private fun playing(): VrMenuState = VrMenuState().apply {
        mode = VrMenuState.Mode.Controls
        title = "Прогулка по набережной на закате, длинное название файла_180_LR.mp4"
        hasPrevious = true
        hasNext = true
        queue = List(8) { video("Видео ${it + 1}_180_LR.mp4") }
        queueIndex = 2
    }

    @Composable
    private fun PanelFrame(visibleHeightPx: Int, content: @Composable () -> Unit) {
        val density = Density(PANEL_DENSITY_DPI / 160f, fontScale = 1f)
        CompositionLocalProvider(LocalDensity provides density) {
            val width = (PANEL_WIDTH_PX / density.density).dp
            Box(
                Modifier
                    .testTag(PANEL)
                    .requiredSize(width, (visibleHeightPx / density.density).dp)
                    .clipToBounds()
                    .background(Color.Black),
            ) {
                // Без wrapContentSize дисплей выше рамки встал бы по её центру, а не от верхнего края.
                Box(
                    Modifier
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .requiredSize(width, (PANEL_HEIGHT_PX / density.density).dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    CompositionLocalProvider(LocalVersionText provides "v1.0 · тест") { content() }
                }
            }
        }
    }

    private fun shoot(layout: PanelLayout, name: String, state: VrMenuState) {
        compose.setContent { PanelFrame(layout.visibleHeightPx) { VrMenu(state, actions) } }
        compose.waitForIdle()
        compose.onNodeWithTag(PANEL).captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun home() = shoot(PanelLayout.Menu, "vr_home", VrMenuState())

    @Test
    fun homeWithVideoOpen() = shoot(PanelLayout.Menu, "vr_home_video_open", playing().apply { mode = VrMenuState.Mode.Menu })

    @Test
    fun hubWithParkedVideo() = shoot(
        PanelLayout.Menu,
        "vr_home_parked",
        VrMenuState().apply { parkedTitle = "Очень длинное имя файла, которое не помещается на кнопке в одну строку, совсем никак_180_LR.mp4" },
    )

    @Test
    fun notice() = shoot(
        PanelLayout.Menu,
        "vr_home_notice",
        VrMenuState().apply { notice = "Не удалось подключиться к компьютеру: соединение прервано." },
    )

    @Test
    fun controlsVideo() = shoot(PanelLayout.Controls, "vr_controls_video", playing())

    @Test
    fun controlsImage() = shoot(PanelLayout.Controls, "vr_controls_image", playing().apply { isImage = true })

    @Test
    fun sheetView() = shoot(PanelLayout.ControlsSheet, "vr_sheet_view", playing().apply { sheet = VrMenuState.Sheet.View })

    @Test
    fun sheetViewFlat() = shoot(
        PanelLayout.ControlsSheet,
        "vr_sheet_view_flat",
        playing().apply {
            sheet = VrMenuState.Sheet.View
            format = VideoFormat(Projection.Flat, StereoLayout.Mono)
        },
    )

    @Test
    fun sheetQueue() = shoot(PanelLayout.ControlsSheet, "vr_sheet_queue", playing().apply { sheet = VrMenuState.Sheet.Queue })

    @Test
    fun formatTab() = shoot(
        PanelLayout.Menu,
        "vr_tab_format",
        playing().apply {
            mode = VrMenuState.Mode.Menu
            tab = VrMenuState.Tab.Video
        },
    )

    @Test
    fun settingsTab() = shoot(PanelLayout.Menu, "vr_tab_settings", VrMenuState().apply { tab = VrMenuState.Tab.Settings })

    @Test
    fun headsetTab() = shoot(PanelLayout.Menu, "vr_tab_headset", VrMenuState().apply { tab = VrMenuState.Tab.Headset })

    @Test
    fun remoteTab() = shoot(PanelLayout.Menu, "vr_tab_remote", VrMenuState().apply { tab = VrMenuState.Tab.Remote })

    @Test
    fun remoteCapture() = shoot(
        PanelLayout.Menu,
        "vr_tab_remote_capture",
        VrMenuState().apply {
            tab = VrMenuState.Tab.Remote
            capturingKey = RemoteAction.Click
        },
    )

    /** VR-19: рендерер показывает 360 px; что ниже, в шлеме не видно. */
    @Test
    fun controlsFitVisibleArea() {
        val state = playing()
        compose.setContent { PanelFrame(PanelLayout.Controls.visibleHeightPx) { VrMenu(state, actions) } }
        compose.waitForIdle()
        val panel = compose.onNodeWithTag(PANEL).fetchSemanticsNode().boundsInRoot
        // Настоящие размеры: boundsInRoot обрезан родителем и за край выйти не может,
        // а колонка фиксированной высоты сжимает последний ряд вместо того, чтобы вывести его наружу.
        val chip = compose.onNodeWithText("▤ Очередь").fetchSemanticsNode()
        val bottom = chip.positionInRoot.y + chip.size.height
        val visible = PanelLayout.Controls.visibleHeightPx
        assertEquals(visible.toFloat(), panel.height, "рамка теста должна совпадать с видимой частью дисплея")
        assertTrue(bottom - panel.top <= visible, "ряд кнопок ниже видимой части: ${bottom - panel.top} из $visible px")
        assertTrue(chip.size.height >= MIN_LABEL_HEIGHT_PX, "подпись кнопки сжата: ${chip.size.height} px")
    }

    private companion object {
        const val PANEL = "panel"

        /** Подпись 14sp при плотности 1,5 — около 30 px; заметно меньше значит, что ряду не хватило места. */
        const val MIN_LABEL_HEIGHT_PX = 24
    }
}

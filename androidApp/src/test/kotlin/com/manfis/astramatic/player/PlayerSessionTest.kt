package com.manfis.astramatic.player

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.manfis.astramatic.library.DesktopServer
import com.manfis.astramatic.model.Projection
import com.manfis.astramatic.model.StereoLayout
import com.manfis.astramatic.model.VideoFormat
import com.manfis.astramatic.remote.RemoteCommand
import com.manfis.astramatic.settings.AppSettings
import com.manfis.astramatic.settings.RepeatMode
import com.manfis.astramatic.ui.VrMenuState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Файлы и что с ними происходит: PL-03…PL-07, PL-10…PL-12, PL-14, PL-16, PL-19 из docs/scenarios/player.md. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerSessionTest {

    private class FakeMedia : MediaEngine {
        override var isPlaying = false
        override var positionMs = 0L
        override var durationMs = 600_000L
        var lastSpeed = 1f
        var fastSeek = false
        val seeks = mutableListOf<Long>()
        val opened = mutableListOf<PlaylistItem>()
        val openedAfterImage = mutableListOf<Boolean>()
        var imageResult: ((Boolean, String?) -> Unit)? = null
        var imagesRequested = 0
        var imageCancels = 0

        override fun play() {
            isPlaying = true
        }
        override fun pause() {
            isPlaying = false
        }
        override fun seekTo(positionMs: Long) {
            this.positionMs = positionMs
            seeks += positionMs
        }
        override fun setScrubbing(on: Boolean) {
            fastSeek = on
        }
        override fun setSpeed(speed: Float) {
            lastSpeed = speed
        }
        override fun openVideo(item: PlaylistItem, afterImage: Boolean) {
            opened += item
            openedAfterImage += afterImage
        }
        override fun openImage(item: PlaylistItem, isCurrent: () -> Boolean, onShown: () -> Unit, onFailed: (String?) -> Unit) {
            opened += item
            imagesRequested++
            imageResult = { ok, reason ->
                if (!ok) onFailed(reason) else if (isCurrent()) onShown()
            }
        }
        override fun cancelImage() {
            imageCancels++
        }
        override fun saveImageAsEnvironment(stereo: StereoLayout) = "сохранено $stereo"
    }

    private class FakeMemory : PlaybackMemory {
        val formats = HashMap<Uri, VideoFormat>()
        val positions = mutableListOf<Pair<String, Long>>()
        val historyLog = mutableListOf<String>()
        var resumeAt: Long? = null

        override fun savedFormat(item: PlaylistItem) = formats[item.uri]
        override fun saveFormat(item: PlaylistItem, format: VideoFormat) {
            formats[item.uri] = format
        }
        override fun clearFormat(item: PlaylistItem) {
            formats.remove(item.uri)
        }
        override fun opened(item: PlaylistItem) {
            historyLog += item.title
        }
        override fun savePosition(item: PlaylistItem, positionMs: Long, durationMs: Long) {
            positions += item.title to positionMs
        }
        override fun resume(item: PlaylistItem, seek: (Long) -> Unit) {
            resumeAt?.let(seek)
        }
    }

    private val settings = AppSettings(
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("test_session", Context.MODE_PRIVATE),
    )
    private val menu = VrMenuState()
    private val host = FakeHost()
    private val scene = FakeScene()
    private val time = FakeTime()
    private val media = FakeMedia()
    private val memory = FakeMemory()
    private var stereoSwitches = 0
    private var headsetApplied = 0
    private var closed = 0

    private lateinit var session: PlayerSession

    private val panels = PanelController(
        menu, settings, host, scene, time, clock = { time.now },
        stereo = { true },
        videoPlaying = { session.videoPlaying },
        exit = {},
        leaveToHub = { session.toHub() },
        resumeViewing = { session.resumeViewing() },
        openSide = { session.openSide(it) },
    )

    init {
        session = PlayerSession(
            menu, settings, media, memory, scene, panels, time,
            random = Random(7),
            switchStereo = { stereoSwitches++ },
            applyHeadset = { headsetApplied++ },
            closePlayer = { closed++ },
        )
    }

    private val sphere = VideoFormat(Projection.Dome180, StereoLayout.SideBySide)
    private val flat = VideoFormat(Projection.Flat, StereoLayout.Mono)

    private fun video(i: Int) = PlaylistItem(Uri.parse("file:///v$i.mp4"), "v$i", sphere)

    private fun photo(i: Int) = PlaylistItem(Uri.parse("file:///p$i.jpg"), "p$i", flat, isImage = true)

    private fun folder(index: Int = 0, size: Int = 3) = Playlist(List(size) { video(it) }, index)

    private fun photos(index: Int = 0, size: Int = 3) = Playlist(List(size) { photo(it) }, index)

    // --- Открытие -----------------------------------------------------------------------------------

    @Test
    fun `opening a video plays it and hides the panels`() {
        panels.showMenu(VrMenuState.Tab.Home)

        session.open(folder(1))

        assertEquals("v1", media.opened.single().title)
        assertTrue(media.isPlaying)
        assertEquals("v1", menu.title)
        assertEquals(1, menu.queueIndex)
        assertEquals(3, menu.queue.size)
        assertEquals(PanelPlace.Hidden, host.place)
        assertTrue(scene.hasVideo)
        assertEquals(listOf("v1"), memory.historyLog)
    }

    @Test
    fun `PL-04 next file keeps the centre`() {
        session.open(folder())

        session.nextFile()

        assertEquals("v1", menu.title)
        assertEquals(0, scene.centersOnPanel)
        assertEquals(0, scene.recenters)
    }

    @Test
    fun `PL-05 file chosen in the menu stands where the menu is`() {
        session.play(folder())

        assertEquals(1, scene.centersOnPanel)
        assertEquals(0, scene.recenters)
    }

    @Test
    fun `opening from the desktop or the home screen does not recenter`() {
        session.open(folder())

        assertEquals(0, scene.centersOnPanel)
        assertEquals(0, scene.recenters)
    }

    @Test
    fun `position of the previous file is saved when switching`() {
        session.open(folder())
        media.positionMs = 42_000

        session.nextFile()

        assertEquals("v0" to 42_000L, memory.positions.first())
    }

    @Test
    fun `position is saved every five seconds while playing only`() {
        session.open(folder())
        media.positionMs = 1_000
        time.advance(5_000)
        assertEquals(1, memory.positions.size)

        media.pause()
        time.advance(20_000)
        assertEquals(1, memory.positions.size)
    }

    @Test
    fun `leaving the screen saves the position and pauses`() {
        session.open(folder())
        media.positionMs = 7_000

        session.onStop()

        assertFalse(media.isPlaying)
        assertEquals("v0" to 7_000L, memory.positions.last())
    }

    // --- Формат: PL-03 --------------------------------------------------------------------------------

    @Test
    fun `PL-03 chosen format applies at once and is remembered for the file`() {
        session.open(folder())

        session.setFormat(flat)

        assertEquals(flat, menu.format)
        assertEquals(flat, scene.format)
        assertEquals(flat, memory.formats[video(0).uri])

        session.nextFile()
        assertEquals(sphere, menu.format)

        session.previousFile()
        assertEquals(flat, menu.format)
    }

    @Test
    fun `PL-03 reset returns the format from the name and forgets the choice`() {
        session.open(folder())
        session.setFormat(flat)

        session.resetFormat()

        assertEquals(sphere, scene.format)
        assertNull(memory.formats[video(0).uri])
    }

    // --- Конец файла: PL-06, PL-07 ---------------------------------------------------------------------

    @Test
    fun `PL-06 end of file goes to the next one with autoplay`() {
        session.open(folder())

        session.onVideoEnded()

        assertEquals("v1", menu.title)
    }

    @Test
    fun `PL-06 end of file shows the controls without autoplay`() {
        settings.autoplayNext = false
        session.open(folder())

        session.onVideoEnded()

        assertEquals("v0", menu.title)
        assertEquals(PanelPlace.Vr, host.place)
        assertEquals(VrMenuState.Mode.Controls, menu.mode)
    }

    @Test
    fun `PL-06 end of the last file shows the controls`() {
        session.open(folder(2))

        session.onVideoEnded()

        assertEquals("v2", menu.title)
        assertEquals(VrMenuState.Mode.Controls, menu.mode)
        assertEquals(PanelPlace.Vr, host.place)
    }

    @Test
    fun `PL-06 shuffle respects autoplay being off`() {
        settings.autoplayNext = false
        settings.shuffle = true
        session.open(folder())

        session.onVideoEnded()

        assertEquals("v0", menu.title)
        assertEquals(1, media.opened.size)
        assertEquals(VrMenuState.Mode.Controls, menu.mode)
        assertEquals(PanelPlace.Vr, host.place)
    }

    @Test
    fun `PL-06 shuffle goes on with autoplay`() {
        settings.autoplayNext = true
        settings.shuffle = true
        session.open(folder())

        session.onVideoEnded()

        assertTrue(menu.title != "v0")
        assertEquals(PanelPlace.Hidden, host.place)
    }

    @Test
    fun `PL-07 file repeat starts the same file again`() {
        settings.repeatMode = RepeatMode.One.name
        session.open(folder())
        media.positionMs = 600_000
        media.pause()

        session.onVideoEnded()

        assertEquals("v0", menu.title)
        assertEquals(0L, media.positionMs)
        assertTrue(media.isPlaying)
        assertEquals(1, media.opened.size)
    }

    @Test
    fun `PL-07 folder repeat goes from the last file to the first`() {
        settings.repeatMode = RepeatMode.All.name
        session.open(folder(2))

        session.onVideoEnded()

        assertEquals("v0", menu.title)
    }

    @Test
    fun `PL-07 repeat button cycles and updates the buttons`() {
        session.open(folder(0))
        assertFalse(menu.hasPrevious)

        session.cycleRepeat()
        assertEquals(RepeatMode.All.name, settings.repeatMode)
        assertTrue(menu.hasPrevious)

        session.cycleRepeat()
        assertEquals(RepeatMode.One.name, settings.repeatMode)
        assertFalse(menu.hasPrevious)

        session.cycleRepeat()
        assertEquals(RepeatMode.Off.name, settings.repeatMode)
    }

    @Test
    fun `unknown saved repeat mode means off`() {
        settings.repeatMode = "мусор"
        session.open(folder(0))

        assertFalse(menu.hasPrevious)
    }

    // --- Кнопки файлов: PL-08, PL-10, PL-11 -----------------------------------------------------------

    @Test
    fun `PL-11 previous is unavailable on the first file without repeat`() {
        session.open(folder(0))

        assertFalse(menu.hasPrevious)
        assertTrue(menu.hasNext)

        session.previousFile()
        assertEquals("v0", menu.title)
        assertEquals(1, media.opened.size)
    }

    @Test
    fun `PL-11 next is unavailable on the last file`() {
        session.open(folder(2))

        assertTrue(menu.hasPrevious)
        assertFalse(menu.hasNext)
    }

    @Test
    fun `PL-08 shuffle previous goes back through the files watched`() {
        settings.shuffle = true
        session.open(folder(0, size = 50))
        session.nextFile()
        val second = menu.title
        session.nextFile()
        assertTrue(menu.hasPrevious)

        session.previousFile()
        assertEquals(second, menu.title)
        assertTrue(menu.hasPrevious)

        session.previousFile()
        assertEquals("v0", menu.title)
        assertFalse(menu.hasPrevious)
    }

    @Test
    fun `PL-08 nothing to go back to on the first file watched`() {
        settings.shuffle = true
        session.open(folder(1))

        assertFalse(menu.hasPrevious)
        session.previousFile()

        assertEquals("v1", menu.title)
        assertEquals(1, media.opened.size)
    }

    @Test
    fun `PL-08 next after going back picks a new file and history goes on`() {
        settings.shuffle = true
        session.open(folder(0, size = 50))
        session.nextFile()
        session.previousFile()

        session.nextFile()
        assertTrue(menu.title != "v0")

        session.previousFile()
        assertEquals("v0", menu.title)
    }

    @Test
    fun `PL-08 history includes the queue and the end of a video`() {
        settings.shuffle = true
        settings.autoplayNext = true
        session.open(folder(0, size = 50))
        session.playQueueItem(7)
        session.onVideoEnded()

        session.previousFile()
        assertEquals("v7", menu.title)

        session.previousFile()
        assertEquals("v0", menu.title)
    }

    @Test
    fun `PL-08 turning shuffle on goes back to the file really watched`() {
        session.open(folder(4, size = 10))
        session.playQueueItem(8)

        session.toggleShuffle()
        session.previousFile()

        assertEquals("v4", menu.title)
    }

    @Test
    fun `PL-08 another folder starts a new history`() {
        settings.shuffle = true
        session.open(folder(0, size = 50))
        session.nextFile()

        session.open(photos(0))

        assertFalse(menu.hasPrevious)
    }

    @Test
    fun `PL-08 the same file opened again is not history`() {
        settings.shuffle = true
        session.open(folder(0))

        session.open(folder(0))

        assertFalse(menu.hasPrevious)
    }

    @Test
    fun `shuffle button makes next available, previous waits for history`() {
        session.open(folder(2))
        assertFalse(menu.hasNext)
        assertTrue(menu.hasPrevious)

        session.toggleShuffle()

        assertTrue(settings.shuffle)
        assertTrue(menu.hasNext)
        assertFalse(menu.hasPrevious)
    }

    @Test
    fun `PL-10 queue item press opens it, the current one does nothing`() {
        session.open(folder(0))

        session.playQueueItem(0)
        assertEquals(1, media.opened.size)

        session.playQueueItem(2)
        assertEquals("v2", menu.title)
        assertEquals(0, scene.centersOnPanel)

        session.playQueueItem(99)
        assertEquals("v2", menu.title)
    }

    // --- Продолжение: PL-12 --------------------------------------------------------------------------

    @Test
    fun `PL-12 resume seeks only when the setting is on`() {
        memory.resumeAt = 90_000
        session.open(folder())
        assertEquals(0L, media.positionMs)

        settings.resumePlayback = true
        session.open(folder(1))
        assertEquals(90_000L, media.positionMs)
    }

    // --- Ошибка: PL-14 ------------------------------------------------------------------------------------

    @Test
    fun `PL-14 playback error is shown on home`() {
        session.open(folder())
        panels.showMenu(VrMenuState.Tab.Settings)

        session.onPlaybackError("файл не открылся")

        assertEquals("файл не открылся", menu.notice)
        assertEquals(VrMenuState.Tab.Home, menu.tab)
        assertEquals(PanelPlace.Vr, host.place)
    }

    // --- Приближение и скорость: PL-19 -----------------------------------------------------------------

    @Test
    fun `PL-19 zoom stays within limits`() {
        session.setZoom(100f)
        assertEquals(4f, menu.zoom)
        assertEquals(4f, scene.zoom)

        session.setZoom(0.01f)
        assertEquals(0.5f, menu.zoom)
    }

    @Test
    fun `PL-19 zoom and speed reset with the next file`() {
        session.open(folder())
        session.setZoom(2f)
        session.setSpeed(1.5f)
        assertEquals(1.5f, media.lastSpeed)

        session.nextFile()

        assertEquals(1f, menu.zoom)
        assertEquals(1f, scene.zoom)
        assertEquals(1f, menu.speed)
        assertEquals(1f, media.lastSpeed)
    }

    // --- Перемотка -------------------------------------------------------------------------------------------

    @Test
    fun `seek stays inside the file`() {
        session.open(folder())
        media.positionMs = 5_000

        session.seekBy(-10_000)
        assertEquals(0L, media.positionMs)
        time.advance(SeekScrubber.FINISH_AFTER_MS)

        // До самого конца не доходит: иначе файл закончился бы и открылся следующий.
        media.positionMs = 595_000
        session.seekBy(10_000)
        assertEquals(599_000L, media.positionMs)
    }

    // --- Перемотка с пульта: PL-23, PL-24, RC-11 --------------------------------------------------------

    @Test
    fun `seek shows the target at once and does not flood the decoder`() {
        session.open(folder())
        media.positionMs = 100_000
        media.seeks.clear()

        repeat(20) {
            session.seekBy(1_000)
            assertEquals(100_000L + (it + 1) * 1_000, session.positionMs())
            time.advance(50)
        }

        assertTrue(media.fastSeek)
        assertTrue(media.seeks.size <= 7, "декодеру досталось ${media.seeks.size} шагов из 20")
        assertTrue(media.seeks.size >= 3, "картинка должна двигаться: ${media.seeks.size}")
        assertEquals(20, scene.huds.count { it.kind == HudBar.Kind.Seek })
        assertEquals("+20 с  ·  2:00 / 10:00", scene.huds.last().label)
    }

    @Test
    fun `seek ends exactly on the target and the normal mode returns`() {
        session.open(folder())
        media.positionMs = 100_000
        session.seekBy(1_000)
        session.seekBy(1_000)

        time.advance(SeekScrubber.FINISH_AFTER_MS)

        assertEquals(102_000L, media.positionMs)
        assertEquals(102_000L, media.seeks.last())
        assertFalse(media.fastSeek)
        assertEquals(102_000L, session.positionMs())
    }

    @Test
    fun `opening another file stops the seek`() {
        session.open(folder())
        session.seekBy(10_000)

        session.open(folder(1))
        val after = media.seeks.size
        time.advance(1_000)

        assertFalse(media.fastSeek)
        assertEquals(after, media.seeks.size)
    }

    @Test
    fun `photo is not seeked`() {
        session.open(photos())
        media.seeks.clear()

        session.seekBy(10_000)

        assertTrue(media.seeks.isEmpty())
        assertTrue(scene.huds.isEmpty())
    }

    @Test
    fun `zoom shows the bar, a new file resets it silently`() {
        session.open(folder())

        session.setZoom(2f)
        assertEquals("200%", scene.huds.single().label)

        session.open(folder(1))
        assertEquals(1f, menu.zoom)
        assertEquals(1, scene.huds.size)
    }

    @Test
    fun `seek works while the duration is unknown`() {
        session.open(folder())
        media.durationMs = -1
        media.positionMs = 1_000

        session.seekBy(10_000)

        assertEquals(11_000L, media.positionMs)
        assertEquals(0L, session.durationMs())
    }

    @Test
    fun `play and pause toggle the video`() {
        session.open(folder())

        session.togglePlayback()
        assertFalse(session.isPlaying())

        session.togglePlayback()
        assertTrue(session.isPlaying())
    }

    // --- Фото и слайд-шоу: PL-15, PL-16 ----------------------------------------------------------------

    private fun showImage() = media.imageResult!!(true, null)

    @Test
    fun `photo does not start the player`() {
        session.open(photos())

        assertEquals(1, media.imagesRequested)
        assertFalse(media.isPlaying)
        assertTrue(menu.isImage)
        assertFalse(session.videoPlaying)
    }

    @Test
    fun `PL-16 slideshow goes on after the interval`() {
        settings.slideshow = true
        settings.slideshowSeconds = 5
        session.open(photos())
        showImage()

        time.advance(4_900)
        assertEquals("p0", menu.title)

        time.advance(200)
        assertEquals("p1", menu.title)
    }

    @Test
    fun `PL-16 slideshow stops on the last photo without repeat`() {
        settings.slideshow = true
        session.open(photos(2))
        showImage()

        time.advance(60_000)

        assertEquals("p2", menu.title)
    }

    @Test
    fun `PL-16 interval below two seconds is not allowed`() {
        settings.slideshow = true
        settings.slideshowSeconds = 1
        session.open(photos())
        showImage()

        time.advance(1_900)
        assertEquals("p0", menu.title)
        time.advance(200)
        assertEquals("p1", menu.title)
    }

    @Test
    fun `slideshow waits for the photo to load`() {
        settings.slideshow = true
        session.open(photos())

        time.advance(60_000)

        assertEquals("p0", menu.title)
    }

    @Test
    fun `PL-15 play button on a photo controls the slideshow`() {
        session.open(photos())
        showImage()
        assertFalse(session.isPlaying())

        session.togglePlayback()
        assertTrue(settings.slideshow)
        assertTrue(session.isPlaying())

        session.togglePlayback()
        assertFalse(session.isPlaying())
        time.advance(60_000)
        assertEquals("p0", menu.title)

        session.togglePlayback()
        assertTrue(session.isPlaying())
    }

    @Test
    fun `slideshow button toggles the setting`() {
        session.open(photos())
        showImage()

        session.toggleSlideshow()
        assertTrue(settings.slideshow)

        session.toggleSlideshow()
        assertFalse(settings.slideshow)
        time.advance(60_000)
        assertEquals("p0", menu.title)
    }

    @Test
    fun `failed photo is reported in the menu`() {
        session.open(photos())

        media.imageResult!!(false, "нет файла")

        assertEquals("Не удалось открыть фото: нет файла", menu.notice)
        assertEquals(PanelPlace.Vr, host.place)
    }

    @Test
    fun `photo that loaded after switching away does not start the slideshow`() {
        settings.slideshow = true
        settings.slideshowSeconds = 5
        session.open(photos())
        val late = media.imageResult!!
        session.nextFile()
        showImage()

        late(true, null)
        time.advance(5_100)

        assertEquals("p2", menu.title)
    }

    @Test
    fun `video after a photo asks for a fresh surface and stops the slideshow`() {
        settings.slideshow = true
        session.open(photos())
        showImage()
        val mixed = Playlist(listOf(photo(0), video(1)), 1)

        session.open(mixed)

        assertEquals(true, media.openedAfterImage.single())
        time.advance(60_000)
        assertEquals("v1", menu.title)
    }

    @Test
    fun `photo position is not saved`() {
        session.open(photos())
        showImage()

        session.nextFile()

        assertTrue(memory.positions.isEmpty())
    }

    @Test
    fun `saving the environment passes the current stereo layout`() {
        session.open(photos())
        session.setFormat(VideoFormat(Projection.Flat, StereoLayout.TopBottom))

        assertEquals("сохранено TopBottom", session.setAsEnvironment())
    }

    // --- Команды с ПК ----------------------------------------------------------------------------------

    private class FakeOpener : DesktopOpener {
        val opened = mutableListOf<String>()
        override fun openFromDesktop(server: DesktopServer, command: RemoteCommand.Open) {
            opened += "desktop"
        }
        override fun openFromDarkframe(server: DesktopServer, command: RemoteCommand.OpenDarkframe) {
            opened += "darkframe"
        }
    }

    private val server = DesktopServer(id = "1", name = "ПК", baseUrl = "http://localhost:1", key = "k")

    @Test
    fun `NAV-rule stereo command from the desktop is ignored`() {
        val commands = DesktopCommands(session, FakeOpener())
        session.open(folder())

        commands.handle(server, RemoteCommand.ToggleStereo)

        assertEquals(0, stereoSwitches)
    }

    @Test
    fun `DSK recenter from the desktop shows no panel`() {
        val commands = DesktopCommands(session, FakeOpener())
        session.open(folder())

        commands.handle(server, RemoteCommand.Recenter)

        assertEquals(1, scene.recenters)
        assertEquals(PanelPlace.Hidden, host.place)
    }

    @Test
    fun `DSK commands reach the player`() {
        val opener = FakeOpener()
        val commands = DesktopCommands(session, opener)
        session.open(folder())

        commands.handle(server, RemoteCommand.SeekTo(5_000))
        assertEquals(5_000L, media.positionMs)

        commands.handle(server, RemoteCommand.NextFile)
        assertEquals("v1", menu.title)

        commands.handle(server, RemoteCommand.SetFormat(flat.encode()))
        assertEquals(flat, menu.format)

        commands.handle(server, RemoteCommand.SetFormat("мусор"))
        assertEquals(flat, menu.format)

        commands.handle(server, RemoteCommand.TogglePlayback)
        assertFalse(media.isPlaying)
    }

    // --- Прочее ---------------------------------------------------------------------------------------------

    @Test
    fun `interface actions reach their owners`() {
        session.toggleStereo()
        session.headsetSaved()
        session.exit()

        assertEquals(1, stereoSwitches)
        assertEquals(1, headsetApplied)
        assertEquals(1, closed)

        session.openMenu(VrMenuState.Tab.Headset)
        assertEquals(VrMenuState.Tab.Headset, menu.tab)
        session.hide()
        assertEquals(PanelPlace.Hidden, host.place)
    }

    // --- Хаб: NAV-16, NAV-17 ------------------------------------------------------------------------------

    @Test
    fun `NAV-16 leaving to the hub pauses, hides the video and keeps the centre`() {
        session.open(folder(1))
        media.positionMs = 42_000L

        session.toHub()

        assertFalse(media.isPlaying)
        assertFalse(scene.hasVideo)
        assertEquals(null, menu.title)
        assertEquals("v1", menu.parkedTitle)
        assertEquals(PanelPlace.Vr, host.place)
        assertEquals(VrMenuState.Mode.Menu, menu.mode)
        assertEquals(VrMenuState.Tab.Home, menu.tab)
        assertEquals("v1" to 42_000L, memory.positions.last())
        assertEquals(0, scene.recenters)
    }

    @Test
    fun `NAV-17 continuing brings the same file back with the same centre`() {
        session.open(folder(1))
        session.toHub()
        val opened = media.opened.size

        session.resumeViewing()

        assertEquals(opened, media.opened.size)
        assertTrue(media.isPlaying)
        assertTrue(scene.hasVideo)
        assertEquals("v1", menu.title)
        assertEquals(null, menu.parkedTitle)
        assertEquals(PanelPlace.Hidden, host.place)
        assertEquals(0, scene.recenters)
        assertEquals(0, scene.centersOnPanel)
    }

    @Test
    fun `NAV-17 play and pause in the hub also bring the file back`() {
        session.open(folder())
        session.toHub()

        session.togglePlayback()

        assertTrue(media.isPlaying)
        assertEquals("v0", menu.title)

        session.toHub()
        session.playKey()
        assertEquals("v0", menu.title)
    }

    @Test
    fun `picking another file in the hub drops the parked one and starts where the menu is`() {
        session.open(folder())
        session.toHub()

        session.play(folder(2))

        assertEquals(null, menu.parkedTitle)
        assertEquals("v2", menu.title)
        assertTrue(scene.hasVideo)
        assertEquals(1, scene.centersOnPanel)
    }

    @Test
    fun `slideshow waits in the hub and goes on after it`() {
        settings.slideshow = true
        settings.slideshowSeconds = 5
        session.open(photos())
        media.imageResult?.invoke(true, null)

        session.toHub()
        time.advance(60_000)
        assertEquals("p0", menu.parkedTitle)

        session.resumeViewing()
        time.advance(5_000)
        assertEquals("p1", menu.title)
        settings.slideshow = false
    }

    @Test
    fun `PL-14 after a playback error there is nothing to continue`() {
        session.open(folder())

        session.onPlaybackError("ошибка")

        assertEquals(null, menu.title)
        assertEquals(null, menu.parkedTitle)
        assertFalse(scene.hasVideo)
        assertEquals("ошибка", menu.notice)
        assertEquals(VrMenuState.Tab.Home, menu.tab)
    }

    // --- Соседние окна: PL-21 ------------------------------------------------------------------------------

    @Test
    fun `PL-21 side windows show the neighbours and lead to the hub at the ends`() {
        session.open(folder(1))
        assertEquals(SideCard(SideKind.Previous, "v0"), menu.sideCards?.left)
        assertEquals(SideCard(SideKind.Next, "v2"), menu.sideCards?.right)

        session.open(folder(0))
        assertEquals(SideKind.Hub, menu.sideCards?.left?.kind)

        session.open(folder(2))
        assertEquals(SideKind.Hub, menu.sideCards?.right?.kind)
    }

    @Test
    fun `PL-21 side window with shuffle does not promise a file`() {
        settings.shuffle = true
        session.open(folder(1))

        assertEquals(SideCard(SideKind.Next, "Случайный файл"), menu.sideCards?.right)
        settings.shuffle = false
    }

    @Test
    fun `PL-21 side windows open neighbours with the same centre, or the hub`() {
        session.open(folder(1))

        session.openSide(SideSlot.Right)
        assertEquals("v2", menu.title)
        assertEquals(0, scene.recenters)
        assertEquals(0, scene.centersOnPanel)

        session.openSide(SideSlot.Left)
        assertEquals("v1", menu.title)

        session.open(folder(2))
        session.openSide(SideSlot.Right)
        assertEquals("v2", menu.parkedTitle)
        assertEquals(PanelPlace.Vr, host.place)
    }
}

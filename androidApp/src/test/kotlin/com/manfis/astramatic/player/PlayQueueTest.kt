package com.manfis.astramatic.player

import com.manfis.astramatic.settings.RepeatMode
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Сценарии PL-06, PL-07, PL-08, PL-10, PL-11 из docs/scenarios/player.md. */
class PlayQueueTest {

    private val plain = QueueModes(shuffle = false, repeat = RepeatMode.Off, autoplayNext = true)
    private val random = Random(1)

    @Test
    fun `next and previous inside the folder`() {
        assertEquals(3, PlayQueue.neighbor(2, 5, 1, plain))
        assertEquals(1, PlayQueue.neighbor(2, 5, -1, plain))
    }

    @Test
    fun `no previous on the first file and no next on the last without repeat`() {
        assertNull(PlayQueue.neighbor(0, 5, -1, plain))
        assertNull(PlayQueue.neighbor(4, 5, 1, plain))
    }

    @Test
    fun `folder repeat goes around`() {
        val modes = plain.copy(repeat = RepeatMode.All)

        assertEquals(4, PlayQueue.neighbor(0, 5, -1, modes))
        assertEquals(0, PlayQueue.neighbor(4, 5, 1, modes))
    }

    @Test
    fun `folder repeat with a single file has nowhere to go`() {
        val modes = plain.copy(repeat = RepeatMode.All)

        assertNull(PlayQueue.neighbor(0, 1, 1, modes))
        assertNull(PlayQueue.neighbor(0, 1, -1, modes))
    }

    @Test
    fun `file repeat does not change next and previous`() {
        val modes = plain.copy(repeat = RepeatMode.One)

        assertEquals(1, PlayQueue.neighbor(0, 5, 1, modes))
        assertNull(PlayQueue.neighbor(0, 5, -1, modes))
    }

    @Test
    fun `shuffle next picks any other file`() {
        val modes = plain.copy(shuffle = true)
        val seen = HashSet<Int>()
        repeat(500) {
            val next = PlayQueue.neighbor(2, 5, 1, modes, random)!!
            assertNotEquals(2, next)
            assertTrue(next in 0 until 5)
            seen += next
        }

        assertEquals(setOf(0, 1, 3, 4), seen)
    }

    @Test
    fun `shuffle previous is the file watched before`() {
        val modes = plain.copy(shuffle = true)

        assertEquals(4, PlayQueue.neighbor(2, 5, -1, modes, random, lastWatched = 4))
        assertNull(PlayQueue.neighbor(2, 5, -1, modes, random))
    }

    @Test
    fun `without shuffle previous ignores the watched file`() {
        assertEquals(1, PlayQueue.neighbor(2, 5, -1, plain, random, lastWatched = 4))
    }

    @Test
    fun `shuffle with a single file has nowhere to go`() {
        assertNull(PlayQueue.neighbor(0, 1, 1, plain.copy(shuffle = true), random))
    }

    @Test
    fun `end of file opens the next one with autoplay`() {
        assertEquals(AtEnd.OpenFile(3), PlayQueue.atEnd(2, 5, plain))
    }

    @Test
    fun `end of file shows controls without autoplay`() {
        assertEquals(AtEnd.ShowControls, PlayQueue.atEnd(2, 5, plain.copy(autoplayNext = false)))
    }

    @Test
    fun `end of the last file shows controls`() {
        assertEquals(AtEnd.ShowControls, PlayQueue.atEnd(4, 5, plain))
    }

    @Test
    fun `folder repeat continues even with autoplay off`() {
        val modes = plain.copy(autoplayNext = false, repeat = RepeatMode.All)

        assertEquals(AtEnd.OpenFile(3), PlayQueue.atEnd(2, 5, modes))
        assertEquals(AtEnd.OpenFile(0), PlayQueue.atEnd(4, 5, modes))
    }

    @Test
    fun `shuffle stops with autoplay off`() {
        val end = PlayQueue.atEnd(2, 5, plain.copy(autoplayNext = false, shuffle = true), random)

        assertEquals(AtEnd.ShowControls, end)
    }

    @Test
    fun `shuffle goes on with autoplay`() {
        val end = PlayQueue.atEnd(2, 5, plain.copy(shuffle = true), random)

        assertTrue(end is AtEnd.OpenFile && end.index != 2)
    }

    @Test
    fun `shuffle with folder repeat goes on with autoplay off`() {
        val end = PlayQueue.atEnd(2, 5, plain.copy(autoplayNext = false, shuffle = true, repeat = RepeatMode.All), random)

        assertTrue(end is AtEnd.OpenFile && end.index != 2)
    }

    @Test
    fun `file repeat wins over everything`() {
        val modes = plain.copy(repeat = RepeatMode.One, shuffle = true)

        assertEquals(AtEnd.RestartFile, PlayQueue.atEnd(2, 5, modes, random))
    }

    @Test
    fun `repeat button cycles off, folder, file`() {
        assertEquals(RepeatMode.All, PlayQueue.nextRepeat(RepeatMode.Off))
        assertEquals(RepeatMode.One, PlayQueue.nextRepeat(RepeatMode.All))
        assertEquals(RepeatMode.Off, PlayQueue.nextRepeat(RepeatMode.One))
    }

    @Test
    fun `queue item press opens it but pressing the current one does nothing`() {
        assertEquals(4, PlayQueue.queueTarget(4, 5, current = 2))
        assertNull(PlayQueue.queueTarget(2, 5, current = 2))
        assertNull(PlayQueue.queueTarget(9, 5, current = 2))
    }
}

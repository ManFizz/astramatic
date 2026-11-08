package com.manfis.astramatic.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class VideoFormatDetectorTest {

    private fun detect(name: String) = VideoFormatDetector.detect(name)

    @Test
    fun vr180SideBySide() {
        assertEquals(VideoFormat(Projection.Dome180, StereoLayout.SideBySide), detect("Trip_180_LR.mp4"))
        assertEquals(VideoFormat(Projection.Dome180, StereoLayout.SideBySide), detect("concert-VR180.mkv"))
    }

    @Test
    fun sphere360() {
        assertEquals(VideoFormat(Projection.Sphere360, StereoLayout.Mono), detect("city 360.mp4"))
        assertEquals(VideoFormat(Projection.Sphere360, StereoLayout.TopBottom), detect("dive_360_TB.mp4"))
    }

    @Test
    fun stereoOnlyIsFlat3dMovie() {
        assertEquals(VideoFormat(Projection.Flat, StereoLayout.SideBySide), detect("Avatar.2009.3D.HSBS.mkv"))
        assertEquals(VideoFormat(Projection.Flat, StereoLayout.TopBottom), detect("movie_OU.mp4"))
    }

    @Test
    fun fisheyeAndEac() {
        assertEquals(VideoFormat(Projection.Fisheye, StereoLayout.SideBySide), detect("hike_FISHEYE_LR.mp4"))
        assertEquals(VideoFormat(Projection.Fisheye, StereoLayout.SideBySide, fovDeg = 190), detect("cam_fisheye190.mp4"))
        assertEquals(VideoFormat(Projection.Eac360, StereoLayout.Mono), detect("yt_360_eac.mp4"))
    }

    @Test
    fun formatSurvivesStorage() {
        val format = VideoFormat(Projection.Fisheye, StereoLayout.TopBottom, fovDeg = 200, tiltDeg = -7)
        assertEquals(format, VideoFormat.decode(format.encode()))
        assertNull(VideoFormat.decode("garbage"))
    }

    @Test
    fun noTags() {
        assertNull(detect("holiday.mp4"))
        // Число в названии — не тег проекции.
        assertNull(detect("Top 1800 goals.mp4"))
    }

    // --- Частично распознанные: LIB-19 ----------------------------------------------------------------

    private val partial = VideoFormat(Projection.Sphere360, StereoLayout.TopBottom)

    @Test
    fun vrTagWithoutDetailsTakesThePartialFormat() {
        for (tag in listOf("vr", "VR", "virtual reality", "Virtual-Reality", "virtualreality", "virtual_reality", "VR video", "виртуальная реальность")) {
            assertEquals(partial, VideoFormatDetector.detect("clip.mp4 Прогулка", listOf("пляж", tag), partial), tag)
        }
    }

    @Test
    fun vrInTheNameCountsToo() {
        assertEquals(partial, VideoFormatDetector.detect("Trip_VR.mp4", emptyList(), partial))
        assertEquals(partial, VideoFormatDetector.detect("clip.mp4 Концерт в VR", emptyList(), partial))
    }

    @Test
    fun exactTagsWinOverThePartialFormat() {
        val dome = VideoFormat(Projection.Dome180, StereoLayout.SideBySide)
        assertEquals(dome, VideoFormatDetector.detect("clip.mp4", listOf("vr", "180", "sbs"), partial))
        assertEquals(dome, VideoFormatDetector.detect("clip_180_LR.mp4", listOf("vr"), partial))
        assertEquals(VideoFormat(Projection.Sphere360, StereoLayout.Mono), VideoFormatDetector.detect("clip.mp4", listOf("360"), partial))
    }

    @Test
    fun knownStereoStaysWithTheVrTag() {
        assertEquals(
            VideoFormat(Projection.Sphere360, StereoLayout.SideBySide),
            VideoFormatDetector.detect("clip.mp4", listOf("vr", "sbs"), partial),
        )
    }

    @Test
    fun wordsThatOnlyContainVrAreNotVr() {
        for (tag in listOf("vrata", "overview", "survey", "drvr", "время")) {
            assertEquals(null, VideoFormatDetector.detect("clip.mp4", listOf(tag), partial), tag)
            assertFalse(VideoFormatDetector.isVrTag(tag), tag)
        }
    }

    @Test
    fun noTagsKeepsTheOldBehaviour() {
        assertEquals(null, VideoFormatDetector.detect("holiday.mp4", emptyList(), partial))
        assertEquals(detect("Trip_180_LR.mp4"), VideoFormatDetector.detect("Trip_180_LR.mp4", emptyList(), partial))
    }
}

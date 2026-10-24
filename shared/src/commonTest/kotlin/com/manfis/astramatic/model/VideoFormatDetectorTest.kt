package com.manfis.astramatic.model

import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun noTags() {
        assertNull(detect("holiday.mp4"))
        // Число в названии — не тег проекции.
        assertNull(detect("Top 1800 goals.mp4"))
    }
}

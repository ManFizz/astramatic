package com.manfis.astramatic.model

/**
 * Распознаёт формат по тегам в имени файла, как принято у VR-видео:
 * `trip_180_LR.mp4`, `concert_360_TB.mkv`, `movie_3dh.mp4`.
 */
object VideoFormatDetector {

    private val sphere360 = setOf("360", "360x180", "360mono", "mono360", "sphere")
    private val dome180 = setOf("180", "180x180", "vr180", "180lr", "180sbs", "180tb", "fisheye180", "dome")
    private val sideBySide = setOf("lr", "rl", "sbs", "3dh", "hsbs", "fsbs", "180lr", "180sbs")
    private val topBottom = setOf("tb", "bt", "ou", "3dv", "htb", "hou", "overunder", "topbottom", "180tb")
    private val mono = setOf("mono", "2d", "360mono", "mono360")

    /** null, если в имени нет ни одного известного тега. */
    fun detect(fileName: String): VideoFormat? {
        val tokens = fileName.substringBeforeLast('.').lowercase().split(Regex("[^a-z0-9]+")).toSet()

        val projection = when {
            tokens.any { it in sphere360 } -> Projection.Sphere360
            tokens.any { it in dome180 } -> Projection.Dome180
            else -> null
        }
        val stereo = when {
            tokens.any { it in sideBySide } -> StereoLayout.SideBySide
            tokens.any { it in topBottom } -> StereoLayout.TopBottom
            tokens.any { it in mono } -> StereoLayout.Mono
            else -> null
        }
        if (projection == null && stereo == null) return null

        return VideoFormat(
            // Только стерео-тег — это обычный 3D-фильм на плоском экране.
            projection = projection ?: Projection.Flat,
            stereo = stereo ?: when (projection) {
                // VR180 почти всегда стерео, 360 — чаще моно.
                Projection.Dome180 -> StereoLayout.SideBySide
                else -> StereoLayout.Mono
            },
        )
    }
}

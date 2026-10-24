package com.manfis.astramatic.model

/** Как видео натянуто на сцену вокруг зрителя. */
enum class Projection { Flat, Dome180, Sphere360 }

/** Как кадры левого и правого глаза упакованы в один видеокадр. */
enum class StereoLayout { Mono, SideBySide, TopBottom }

data class VideoFormat(
    val projection: Projection = Projection.Dome180,
    val stereo: StereoLayout = StereoLayout.SideBySide,
)

package com.manfis.astramatic.model

/** Как видео натянуто на сцену вокруг зрителя. */
enum class Projection {
    Flat,
    Dome180,
    Sphere360,

    /** Круглый кадр с объектива «рыбий глаз», как пишут камеры VR180. */
    Fisheye,

    /** Equi-angular cubemap — формат 360 у YouTube: шесть граней куба сеткой 3×2. */
    Eac360,
}

/** Как кадры левого и правого глаза упакованы в один видеокадр. */
enum class StereoLayout { Mono, SideBySide, TopBottom }

data class VideoFormat(
    val projection: Projection = Projection.Dome180,
    val stereo: StereoLayout = StereoLayout.SideBySide,
    /** Угол, который покрывает кадр, для 180° и fisheye: подстройка «кривых» видео. */
    val fovDeg: Int = 180,
    /** Наклон картинки вверх / вниз в градусах: если горизонт съехал. */
    val tiltDeg: Int = 0,
) {
    /** Строка для хранения: «проекция|стерео|угол|наклон». */
    fun encode(): String = "${projection.name}|${stereo.name}|$fovDeg|$tiltDeg"

    companion object {
        fun decode(text: String): VideoFormat? {
            val parts = text.split('|')
            val projection = Projection.entries.find { it.name == parts.getOrNull(0) } ?: return null
            val stereo = StereoLayout.entries.find { it.name == parts.getOrNull(1) } ?: return null
            return VideoFormat(
                projection = projection,
                stereo = stereo,
                fovDeg = parts.getOrNull(2)?.toIntOrNull() ?: 180,
                tiltDeg = parts.getOrNull(3)?.toIntOrNull() ?: 0,
            )
        }
    }
}

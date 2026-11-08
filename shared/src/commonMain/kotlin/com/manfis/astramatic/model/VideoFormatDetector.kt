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
    private val fisheye = setOf("fisheye", "fisheye180", "fisheye190", "fisheye200", "f180", "mkx200", "mkx", "rf52")
    private val eac = setOf("eac", "eac360", "cubemap")

    private val fovTag = Regex("(?:fisheye|mkx)(1[6-9]0|2[0-4]0)")

    /** null, если в имени нет ни одного известного тега. */
    fun detect(fileName: String): VideoFormat? =
        detect(fileName.substringBeforeLast('.').lowercase().split(Regex("[^a-z0-9]+")).toSet())

    /**
     * Формат по имени и тегам (Darkframe). Теги вроде `vr`, `virtual reality` говорят, что это VR,
     * но не какой: тогда берётся [partial] — формат частично распознанных медиа. Если при этом
     * известна раскладка стерео (`sbs`, `tb`), она остаётся своей.
     * null — ничего не распознано, нужен формат по умолчанию.
     */
    fun detect(hint: String, tags: Collection<String>, partial: VideoFormat): VideoFormat? {
        val words = (listOf(hint) + tags).flatMap { words(it) }.toSet()
        val found = detect(words)
        val vr = tags.any(::isVrTag) || isVrTag(hint)
        return when {
            !vr -> found
            found == null -> partial
            // Только стерео-тег: проекция неизвестна, а «плоское» для VR неверно.
            found.projection == Projection.Flat -> partial.copy(stereo = found.stereo)
            else -> found
        }
    }

    /** Тег или имя говорят «это VR»: `vr`, `VR video`, `virtual-reality`, `виртуальная реальность`. */
    fun isVrTag(text: String): Boolean {
        val words = words(text)
        if (words.any { it in vrWords }) return true
        return words.joinToString("") in vrPhrases
    }

    private val vrWords = setOf("vr", "вр", "virtualreality", "vrvideo", "vrfilm", "vrmovie", "3dvr", "vr3d")
    private val vrPhrases = setOf("virtualreality", "виртуальнаяреальность", "vrvideo", "vrфильм", "vrвидео")

    private fun words(text: String) = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    private fun detect(tokens: Set<String>): VideoFormat? {
        val projection = when {
            tokens.any { it in eac } -> Projection.Eac360
            tokens.any { it in fisheye } -> Projection.Fisheye
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
                // VR180 (и с объектива fisheye) почти всегда стерео, 360 — чаще моно.
                Projection.Dome180, Projection.Fisheye -> StereoLayout.SideBySide
                else -> StereoLayout.Mono
            },
            // Объективы шире 180° пишут это в имени: fisheye190, mkx200.
            fovDeg = tokens.firstNotNullOfOrNull { fovTag.matchEntire(it)?.groupValues?.get(1)?.toInt() } ?: 180,
        )
    }
}

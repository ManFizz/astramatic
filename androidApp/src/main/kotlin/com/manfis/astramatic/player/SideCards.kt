package com.manfis.astramatic.player

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Соседнее окно у плоского экрана: слева или справа от него. */
enum class SideSlot { Left, Right }

/** Что откроет соседнее окно. */
enum class SideKind { Previous, Next, Hub }

data class SideCard(val kind: SideKind, val title: String? = null, val thumbnail: String? = null)

data class SideCards(val left: SideCard, val right: SideCard) {
    operator fun get(slot: SideSlot) = if (slot == SideSlot.Left) left else right
}

/**
 * Где стоят соседние окна относительно плоского экрана: на той же глубине, сразу за его краями.
 * Углы и размеры — в системе видео, где вперёд — −Z, а влево — положительный поворот.
 */
class SideLayout private constructor(
    /** Половина ширины экрана по горизонтали, градусы. */
    val screenHalfDeg: Float,
    val distance: Float,
    val cardWidth: Float,
    val cardHeight: Float,
    /** На сколько центр окна повёрнут от центра экрана, градусы. */
    val cardYawDeg: Float,
) {
    /** Угол окна: левое — в положительную сторону. */
    fun yawOf(slot: SideSlot) = if (slot == SideSlot.Left) cardYawDeg else -cardYawDeg

    /** На какое окно попадает взгляд [x], [y], [z], заданный в системе видео. */
    fun slotAt(x: Float, y: Float, z: Float): SideSlot? = SideSlot.entries.firstOrNull { hits(it, x, y, z) }

    private fun hits(slot: SideSlot, x: Float, y: Float, z: Float): Boolean {
        val yaw = Math.toRadians(yawOf(slot).toDouble())
        val localX = (cos(yaw) * x - sin(yaw) * z).toFloat()
        val localZ = (sin(yaw) * x + cos(yaw) * z).toFloat()
        if (localZ > -0.01f) return false
        val t = -distance / localZ
        return abs(localX * t) <= cardWidth / 2 && abs(y * t) <= cardHeight / 2
    }

    companion object {
        /** Окно занимает примерно 28° по ширине, какой бы ни был экран: иначе на большом экране оно огромное. */
        private const val CARD_WIDTH_PER_DISTANCE = 0.5f
        private const val CARD_ASPECT = 1.6f
        private const val GAP_DEG = 3f

        /** [width] — ширина экрана по дуге, [curve] — изгиб из настроек, как в [Meshes.screen]. */
        fun of(width: Float, distance: Float, curve: Float): SideLayout {
            val cardWidth = distance * CARD_WIDTH_PER_DISTANCE
            val halfDeg = screenHalfDeg(width, distance, curve)
            val cardHalfDeg = Math.toDegrees(atan(CARD_WIDTH_PER_DISTANCE / 2.0)).toFloat()
            return SideLayout(halfDeg, distance, cardWidth, cardWidth / CARD_ASPECT, halfDeg + GAP_DEG + cardHalfDeg)
        }

        private fun screenHalfDeg(width: Float, distance: Float, curve: Float): Float {
            if (curve <= 0.001f) return Math.toDegrees(atan2(width / 2.0, distance.toDouble())).toFloat()
            // Та же дуга, что и в сетке экрана: касается плоскости экрана в его центре.
            val radius = distance / curve
            val arc = width / 2 / radius
            val x = radius * sin(arc)
            val depth = distance - radius + radius * cos(arc)
            return Math.toDegrees(atan2(x.toDouble(), depth.toDouble())).toFloat()
        }
    }
}

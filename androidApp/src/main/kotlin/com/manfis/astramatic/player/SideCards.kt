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

/** Куда соседние окна отодвинули от обычного места, градусы. Живёт, пока открыт плеер. */
class SideOffsets {
    private val yaws = FloatArray(2)
    private val pitches = FloatArray(2)

    fun yaw(slot: SideSlot) = yaws[slot.ordinal]
    fun pitch(slot: SideSlot) = pitches[slot.ordinal]

    fun set(slot: SideSlot, yaw: Float, pitch: Float) {
        yaws[slot.ordinal] = yaw
        pitches[slot.ordinal] = pitch
    }
}

/** Плоский экран, чтобы найти его ручку: половина высоты и расстояние, метры. */
data class ScreenGrip(val halfHeight: Float, val distance: Float) {
    val y get() = -halfHeight - distance * SideLayout.GRIP_GAP_PER_DISTANCE
    val width get() = distance * WIDTH_PER_DISTANCE
    val reach get() = distance * REACH_PER_DISTANCE

    private companion object {
        const val WIDTH_PER_DISTANCE = 0.3f
        const val REACH_PER_DISTANCE = 0.05f
    }
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

    /**
     * На какое окно попадает взгляд [x], [y], [z], заданный относительно поворота экрана вокруг вертикали.
     * [basePitch] — наклон экрана: окна стоят на его высоте.
     */
    fun slotAt(x: Float, y: Float, z: Float, moved: SideOffsets = SideOffsets(), basePitch: Float = 0f): SideSlot? =
        SideSlot.entries.firstOrNull { slot ->
            pointOn(slot, x, y, z, moved, basePitch)?.let { (px, py) -> abs(px) <= cardWidth / 2 && abs(py) <= cardHeight / 2 } == true
        }

    /** На ручку какого окна попадает взгляд. */
    fun gripAt(x: Float, y: Float, z: Float, moved: SideOffsets = SideOffsets(), basePitch: Float = 0f): SideSlot? =
        SideSlot.entries.firstOrNull { slot ->
            pointOn(slot, x, y, z, moved, basePitch)?.let { (px, py) ->
                abs(px) <= gripWidth / 2 + gripReach && abs(py - gripY) <= gripReach
            } == true
        }

    /** Ширина ручки под окном. */
    val gripWidth get() = cardWidth * GRIP_WIDTH

    /** Где ручка относительно центра окна. */
    val gripY get() = -cardHeight / 2 - distance * GRIP_GAP_PER_DISTANCE
    private val gripReach get() = distance * GRIP_REACH_PER_DISTANCE

    /** Куда взгляд попадает на плоскости окна, метры от его центра; null — мимо плоскости. */
    private fun pointOn(slot: SideSlot, x: Float, y: Float, z: Float, moved: SideOffsets, basePitch: Float): Pair<Float, Float>? {
        val yaw = Math.toRadians((yawOf(slot) + moved.yaw(slot)).toDouble())
        val pitch = Math.toRadians((basePitch + moved.pitch(slot)).toDouble())
        val localX = cos(yaw) * x - sin(yaw) * z
        val turnedZ = sin(yaw) * x + cos(yaw) * z
        val localY = cos(pitch) * y + sin(pitch) * turnedZ
        val localZ = cos(pitch) * turnedZ - sin(pitch) * y
        if (localZ > -0.01) return null
        val t = -distance / localZ
        return (localX * t).toFloat() to (localY * t).toFloat()
    }

    companion object {
        /** Окно занимает примерно 28° по ширине, какой бы ни был экран: иначе на большом экране оно огромное. */
        private const val CARD_WIDTH_PER_DISTANCE = 0.5f
        private const val CARD_ASPECT = 1.6f
        private const val GAP_DEG = 3f
        private const val GRIP_WIDTH = 0.3f
        const val GRIP_GAP_PER_DISTANCE = 0.03f
        const val GRIP_THICKNESS_PER_DISTANCE = 0.013f
        private const val GRIP_REACH_PER_DISTANCE = 0.03f

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

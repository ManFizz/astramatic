package com.manfis.astramatic.player

import kotlin.math.abs

/**
 * Сдвиг картинки между глазами в стерео: на сколько пикселей точка у левого глаза правее, чем у правого.
 * Больше нуля — ближе бесконечности. Ищется один общий сдвиг, то есть глубина большей части области.
 */
object StereoDisparity {
    /** Ниже этого перепада яркости (из 255) сравнивать нечего: сплошной цвет совпадает при любом сдвиге. */
    private const val MIN_CONTRAST = 4f

    /** Лучший сдвиг должен быть заметно лучше среднего, иначе это случайное совпадение. */
    private const val MAX_BEST_TO_MEAN = 0.8f

    /** [left] и [right] — яркость, [width] × [height]. null — по этой картинке не определить. */
    fun estimate(left: ByteArray, right: ByteArray, width: Int, height: Int, minShift: Int, maxShift: Int): Float? {
        val from = maxOf(0, -minShift)
        val until = width - maxOf(0, maxShift)
        if (until <= from || contrast(right, width, height, from, until) < MIN_CONTRAST) return null
        val costs = FloatArray(maxShift - minShift + 1) { index ->
            cost(left, right, width, height, from, until, minShift + index)
        }
        val best = costs.indices.minByOrNull { costs[it] } ?: return null
        if (best == 0 || best == costs.lastIndex) return null
        if (costs[best] > costs.average().toFloat() * MAX_BEST_TO_MEAN) return null
        // Между пикселями — по параболе через три соседних значения.
        val before = costs[best - 1]
        val after = costs[best + 1]
        val bend = before - 2 * costs[best] + after
        val fraction = if (bend > 0f) (before - after) / (2 * bend) else 0f
        return minShift + best + fraction.coerceIn(-0.5f, 0.5f)
    }

    private fun cost(left: ByteArray, right: ByteArray, width: Int, height: Int, from: Int, until: Int, shift: Int): Float {
        var sum = 0L
        for (y in 0 until height) {
            val row = y * width
            for (x in from until until) {
                sum += abs((right[row + x].toInt() and 0xFF) - (left[row + x + shift].toInt() and 0xFF))
            }
        }
        return sum.toFloat() / ((until - from) * height)
    }

    private fun contrast(image: ByteArray, width: Int, height: Int, from: Int, until: Int): Float {
        var sum = 0L
        for (y in 0 until height) for (x in from until until) sum += image[y * width + x].toInt() and 0xFF
        val count = (until - from) * height
        val mean = sum.toFloat() / count
        var deviation = 0f
        for (y in 0 until height) for (x in from until until) deviation += abs((image[y * width + x].toInt() and 0xFF) - mean)
        return deviation / count
    }
}

/**
 * Сведение глаз на видео рядом с панелью, радианы. Первые замеры применяются сразу, пока панель ещё проявляется.
 * Дальше мелкие колебания замера не двигают её совсем, а заметная смена глубины отрабатывается медленно и
 * равномерно: иначе панель дрожит, потому что замер всегда немного шумит.
 */
class PanelDepth {
    var radians = 0f
        private set

    /** После [reset] нужно новое измерение: сцена за это время могла смениться. */
    var measured = false
        private set

    /** Первые замеры идут чаще обычного. */
    val wantsQuickSample get() = attempts < QUICK_SAMPLES

    private val recent = ArrayDeque<Float>()
    private var target = 0f
    private var attempts = 0

    fun reset() {
        measured = false
        attempts = 0
        recent.clear()
    }

    /** null — измерить не удалось: остаётся прежнее значение. */
    fun sample(value: Float?) {
        attempts++
        if (value == null) return
        if (recent.size == WINDOW) recent.removeFirst()
        recent.addLast(value)
        val median = recent.sorted()[recent.size / 2]
        if (attempts <= QUICK_SAMPLES || !measured) {
            radians = median
            target = median
            measured = true
        } else if (kotlin.math.abs(median - target) > DEAD_ZONE) {
            target = median
        }
    }

    /** Раз в кадр: плавно к цели. */
    fun step(dt: Float) {
        val limit = MAX_RATE * dt
        radians += (target - radians).coerceIn(-limit, limit)
    }

    private companion object {
        const val WINDOW = 5
        const val QUICK_SAMPLES = 3

        /** 0,35°: столько шумит замер. */
        const val DEAD_ZONE = 0.0061f

        /** 2° в секунду. */
        const val MAX_RATE = 0.035f
    }
}

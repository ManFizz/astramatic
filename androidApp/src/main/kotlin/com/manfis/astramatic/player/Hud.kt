package com.manfis.astramatic.player

import com.manfis.astramatic.ui.MAX_ZOOM
import com.manfis.astramatic.ui.MIN_ZOOM
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Полоска поверх всего вверху поля зрения: приближение или перемотка. Положения — доли ширины 0..1.
 * Заполнение идёт от [from] до [value]; [mark] — метка, к которой удобно возвращаться; [kind] отличает
 * один показатель от другого, чтобы при смене полоска не «ехала» от старого значения.
 */
data class HudBar(
    val kind: Kind,
    val value: Float,
    val from: Float,
    val mark: Float?,
    val label: String,
    /** Значение стоит ровно на метке. */
    val onMark: Boolean = false,
) {
    enum class Kind { Zoom, Seek }

    companion object {
        /** Шкала логарифмическая: 50% и 200% на равном расстоянии от 100%. */
        private fun zoomPosition(zoom: Float) = ln(zoom / MIN_ZOOM) / ln(MAX_ZOOM / MIN_ZOOM)

        fun zoom(zoom: Float): HudBar {
            val hundred = zoomPosition(1f)
            return HudBar(
                kind = Kind.Zoom,
                value = zoomPosition(zoom).coerceIn(0f, 1f),
                from = hundred,
                mark = hundred,
                label = "${(zoom * 100).roundToInt()}%",
                onMark = abs(zoom - 1f) < 0.005f,
            )
        }

        /** [deltaMs] — на сколько ушли за эту перемотку. */
        fun seek(positionMs: Long, durationMs: Long, deltaMs: Long): HudBar {
            val arrows = if (deltaMs < 0) "◀◀" else "▶▶"
            val moved = "$arrows ${shortDelta(abs(deltaMs))}"
            val time = if (durationMs > 0) "${clock(positionMs)} / ${clock(durationMs)}" else clock(positionMs)
            return HudBar(
                kind = Kind.Seek,
                value = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                from = 0f,
                mark = null,
                label = "$moved  ·  $time",
            )
        }

        private fun shortDelta(ms: Long): String {
            val seconds = (ms + 500) / 1000
            return if (seconds < 60) "$seconds с" else "${seconds / 60}:%02d".format(seconds % 60)
        }

        private fun clock(ms: Long): String {
            val total = ms.coerceAtLeast(0) / 1000
            val h = total / 3600
            val m = total % 3600 / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}

/**
 * Упор на 100% при приближении: значение, проходя через 100%, останавливается на нём и какое-то время
 * держится, поэтому вернуться к исходному размеру можно не целясь.
 */
class ZoomDetent(private val clock: () -> Long) {
    private var holdUntil = 0L

    fun next(current: Float, wanted: Float): Float {
        val now = clock()
        if (now < holdUntil) return current
        val crosses = current != 1f && (current - 1f) * (wanted - 1f) <= 0f
        if (!crosses) return wanted
        holdUntil = now + HOLD_MS
        return 1f
    }

    private companion object {
        const val HOLD_MS = 450L
    }
}

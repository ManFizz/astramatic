package com.manfis.astramatic.player

/**
 * Перемотка с пульта: позиция меняется сразу, а декодер получает не каждый шаг, а не чаще раза в [SEEK_EVERY_MS].
 * Иначе он начинал бы каждый кадр заново и картинка стояла бы до конца перемотки.
 * Пока идёт перемотка, поиск идёт по ближайшим опорным кадрам; когда шаги кончаются, встаём точно на цель.
 */
class SeekScrubber(
    private val media: MediaEngine,
    private val ticks: Ticks,
    /** Цель сдвинулась: новая цель и на сколько она ушла от начала перемотки. */
    private val onMoved: (targetMs: Long, deltaMs: Long) -> Unit,
) {
    /** Куда мотаем; null — перемотки сейчас нет. */
    var target: Long? = null
        private set

    private var startedAt = 0L
    private var unsent = false

    fun by(deltaMs: Long) {
        val current = target
        val base = current ?: media.positionMs
        val duration = media.durationMs
        // Не до самого конца: иначе файл закончился бы и открылся следующий.
        val end = if (duration > 0) (duration - END_MARGIN_MS).coerceAtLeast(0) else Long.MAX_VALUE
        val next = (base + deltaMs).coerceIn(0, maxOf(end, base.coerceAtMost(duration.coerceAtLeast(0))))
        target = next
        if (current == null) {
            startedAt = base
            media.setScrubbing(true)
            media.seekTo(next)
            ticks.schedule(sendTick, SEEK_EVERY_MS)
        } else {
            unsent = true
        }
        ticks.cancel(finishTick)
        ticks.schedule(finishTick, FINISH_AFTER_MS)
        onMoved(next, next - startedAt)
    }

    /** Перемотка больше не нужна: открыли другое или встали на место вручную. */
    fun cancel() {
        if (target == null) return
        stop()
    }

    private fun stop() {
        ticks.cancel(sendTick)
        ticks.cancel(finishTick)
        target = null
        unsent = false
        media.setScrubbing(false)
    }

    private val sendTick = object : Runnable {
        override fun run() {
            val position = target ?: return
            if (unsent) {
                media.seekTo(position)
                unsent = false
            }
            ticks.schedule(this, SEEK_EVERY_MS)
        }
    }

    private val finishTick = Runnable {
        val position = target ?: return@Runnable
        stop()
        media.seekTo(position)
    }

    companion object {
        const val SEEK_EVERY_MS = 180L
        const val FINISH_AFTER_MS = 320L
        const val END_MARGIN_MS = 1_000L
    }
}

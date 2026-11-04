package com.manfis.astramatic.player

/**
 * Полоска плеера сама уходит, если идёт видео, а смотрят не на неё.
 * Отсчёт идёт, только пока это условие держится непрерывно.
 */
class ControlsAutoHide(private val timeoutMs: Long = 4_000L) {

    private var lastSeen = 0L

    /** На полоску посмотрели, или она только что открылась. */
    fun touch(now: Long) {
        lastSeen = now
    }

    /** [unattended] — идёт видео, панель не раскрыта, ничего не держат и взгляд мимо. true — пора спрятать. */
    fun shouldHide(now: Long, unattended: Boolean): Boolean {
        if (!unattended) {
            lastSeen = now
            return false
        }
        return now - lastSeen >= timeoutMs
    }
}

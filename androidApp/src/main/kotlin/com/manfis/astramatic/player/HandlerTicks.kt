package com.manfis.astramatic.player

import android.os.Handler

/** Отложенные задачи на потоке [handler]. */
class HandlerTicks(private val handler: Handler) : Ticks {
    override fun schedule(task: Runnable, delayMs: Long) {
        handler.postDelayed(task, delayMs)
    }

    override fun cancel(task: Runnable) = handler.removeCallbacks(task)
}

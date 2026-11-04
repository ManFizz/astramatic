package com.manfis.astramatic.player

import com.manfis.astramatic.settings.RepeatMode
import kotlin.random.Random

/** Режимы воспроизведения папки. */
data class QueueModes(
    val shuffle: Boolean,
    val repeat: RepeatMode,
    /** После конца файла открывать следующий. */
    val autoplayNext: Boolean,
)

/** Что делать, когда видео закончилось. */
sealed interface AtEnd {
    /** «Повтор файла»: тот же файл с начала. */
    data object RestartFile : AtEnd

    data class OpenFile(val index: Int) : AtEnd

    /** Дальше некуда или автопереход выключен: показать полоску плеера. */
    data object ShowControls : AtEnd
}

/** Правила очереди: какой файл следующий. */
object PlayQueue {

    /**
     * Соседний файл с учётом режимов: «повтор папки» — по кругу, случайный порядок вперёд — любой другой,
     * назад — [lastWatched], файл, который смотрели перед этим.
     * null — дальше некуда.
     */
    fun neighbor(
        index: Int,
        size: Int,
        delta: Int,
        modes: QueueModes,
        random: Random = Random,
        lastWatched: Int? = null,
    ): Int? {
        if (modes.shuffle && size > 1) {
            if (delta < 0) return lastWatched
            val other = random.nextInt(size - 1)
            return if (other >= index) other + 1 else other
        }
        val moved = index + delta
        if (moved in 0 until size) return moved
        if (modes.repeat != RepeatMode.All || size < 2) return null
        return if (delta > 0) 0 else size - 1
    }

    /** Видео закончилось: повтор файла важнее всего, повтор папки идёт дальше и без автоперехода. */
    fun atEnd(index: Int, size: Int, modes: QueueModes, random: Random = Random): AtEnd {
        if (modes.repeat == RepeatMode.One) return AtEnd.RestartFile
        val next = neighbor(index, size, 1, modes, random)
        val auto = modes.autoplayNext || modes.repeat == RepeatMode.All
        return if (auto && next != null) AtEnd.OpenFile(next) else AtEnd.ShowControls
    }

    /** Кнопка повтора: выкл → папка → файл → выкл. */
    fun nextRepeat(current: RepeatMode): RepeatMode =
        RepeatMode.entries[(current.ordinal + 1) % RepeatMode.entries.size]

    /** Файл очереди, который включает нажатие; null — нажат текущий или такого нет. */
    fun queueTarget(index: Int, size: Int, current: Int): Int? = index.takeIf { it in 0 until size && it != current }
}

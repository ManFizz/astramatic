package com.manfis.astramatic.player

import com.manfis.astramatic.model.VideoFormat

/** Где сейчас живёт интерфейс плеера. */
enum class PanelPlace { Hidden, Vr, Screen }

/** Фаза нажатия по панели. */
enum class TouchPhase { Down, Move, Up, Cancel }

/** Интерфейс плеера: виртуальный дисплей в шлеме или слой на экране. Реализует [VrUiHost]. */
interface PanelHost {
    val place: PanelPlace
    fun showInVr()
    fun showOnScreen()
    fun hide()

    /** Нажатие по точке взгляда, в пикселях панели. */
    fun touch(phase: TouchPhase, x: Float, y: Float)

    /** Подсветка элемента под взглядом; null — взгляд ушёл с панели. */
    fun hover(x: Float?, y: Float?)

    /** Нажать и отпустить кнопку [keyCode] в интерфейсе: стрелки и OK. */
    fun pressKey(keyCode: Int)

    /** Прокрутка как колесом мыши в точке (x, y); [amount] > 0 — вверх. Без точки — в центре. */
    fun scroll(x: Float?, y: Float?, amount: Float)
}

/** Что логика плеера меняет в трёхмерной сцене. Реализует [VrRenderer]. */
interface VrScene {
    var panelLayout: PanelLayout
    var panelVisible: Boolean
    var calibration: Boolean
    var dwellProgress: Float
    var grab: Handle
    val gaze: Gaze?
    val hoverHandle: Handle
    var format: VideoFormat
    var zoom: Float
    var hasVideo: Boolean
    fun recenter()
    fun centerOnPanel()
    fun placePanelInFront()
}

/** Отложенные задачи на главном потоке. Одна и та же задача не ставится повторно, снимается по ссылке. */
interface Ticks {
    fun schedule(task: Runnable, delayMs: Long = 0L)
    fun cancel(task: Runnable)
}

package com.manfis.astramatic.player

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View

/**
 * Взгляд и нажатие для интерфейса панели: наведение идёт как мышь, нажатие как касание.
 *
 * Для Compose это два разных указателя. Если наведение перед нажатием не закончить, кнопка под взглядом
 * увидит в нажатии оба, не посчитает его началом жеста и не сработает. Поэтому касание сначала
 * заканчивает наведение, а следующий [hover] начинает его заново.
 */
class PanelPointer(private val root: () -> View?) {
    private var touchDownTime = 0L
    private var hovering = false

    fun touch(phase: TouchPhase, x: Float, y: Float) {
        val view = root() ?: return
        if (phase == TouchPhase.Down) hover(null, null)
        val now = SystemClock.uptimeMillis()
        val action = when (phase) {
            TouchPhase.Down -> MotionEvent.ACTION_DOWN
            TouchPhase.Move -> MotionEvent.ACTION_MOVE
            TouchPhase.Up -> MotionEvent.ACTION_UP
            TouchPhase.Cancel -> MotionEvent.ACTION_CANCEL
        }
        if (phase == TouchPhase.Down) touchDownTime = now
        val event = MotionEvent.obtain(touchDownTime, now, action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    /** null — взгляд ушёл с панели. */
    fun hover(x: Float?, y: Float?) {
        val view = root() ?: return
        val action = when {
            x == null || y == null -> if (hovering) MotionEvent.ACTION_HOVER_EXIT else return
            !hovering -> MotionEvent.ACTION_HOVER_ENTER
            else -> MotionEvent.ACTION_HOVER_MOVE
        }
        hovering = action != MotionEvent.ACTION_HOVER_EXIT
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x ?: 0f, y ?: 0f, 0).apply {
            source = InputDevice.SOURCE_MOUSE
        }
        view.dispatchGenericMotionEvent(event)
        event.recycle()
    }
}

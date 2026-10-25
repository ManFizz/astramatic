package com.manfis.astramatic.player

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Поправка ухода поворота головы по компасу.
 *
 * Трекинг Cardboard считает поворот по гироскопу: наклоны выправляет акселерометр, а поворот влево-вправо
 * ничем не выправляется, и ошибка гироскопа копится — центр со временем уплывает в одну сторону.
 * Компас ошибку не копит, но шумит и врёт рядом с железом. Поэтому поворот от гироскопа остаётся главным,
 * а к компасу он подтягивается очень медленно, незаметно для глаза.
 *
 * Сравниваются направления одной и той же оси телефона в мире трекинга и в мире компаса. Обычно это
 * взгляд (задняя сторона телефона); когда он почти вертикален (лёжа, взгляд в потолок, телефон на столе),
 * его направление по сторонам света не определено — тогда берётся длинная сторона телефона, она горизонтальна.
 */
class CompassDrift(context: Context) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val matrix = FloatArray(9)

    /** Направление по компасу (рост — поворот влево, как у трекинга) взгляда и длинной стороны, градусы. */
    @Volatile private var compassLookDeg = Float.NaN
    @Volatile private var compassSideDeg = Float.NaN

    /** Насколько взгляд вертикален по компасу, 0..1. */
    @Volatile private var compassLookUp = 0f

    /** Сейчас сравниваем по длинной стороне, а не по взгляду. */
    private var useSide = false

    /** Расхождение трекинга с компасом, при котором ухода нет: дальше следим только за изменением. */
    private var baseOffsetDeg = Float.NaN

    /** Накопленный уход трекинга, градусы (только GL-поток). */
    var driftDeg = 0f
        private set

    private var lastUpdate = 0L

    val available get() = sensor != null

    fun start() {
        sensor?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensors?.unregisterListener(this)
        compassLookDeg = Float.NaN
        compassSideDeg = Float.NaN
    }

    /** Начать заново: например, после выключения поправки. */
    fun reset() {
        baseOffsetDeg = Float.NaN
        driftDeg = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        // Мир компаса: X — восток, Y — север, Z — вверх. Взгляд — −Z телефона,
        // длинная сторона — −Y телефона (в горизонтальном положении шлема она смотрит вправо).
        compassLookUp = abs(matrix[8])
        compassLookDeg = heading(-matrix[2], -matrix[5])
        compassSideDeg = heading(-matrix[1], -matrix[4])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Обновить оценку ухода по виду головы от трекинга [headView] (мир → голова, по столбцам).
     * Возвращает, на сколько повернуть мир обратно.
     */
    fun update(headView: FloatArray): Float {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastUpdate == 0L) 0f else ((now - lastUpdate) / 1000f).coerceAtMost(0.1f)
        lastUpdate = now

        // Строки вида — оси головы в мире трекинга: X — вправо, Z — назад (взгляд — −Z).
        val side = if (useSide) compassLookUp > SWITCH_BACK else compassLookUp > SWITCH_TO_SIDE
        val trackerDeg = if (side) {
            trackerHeading(headView[0], headView[8])
        } else {
            trackerHeading(-headView[2], -headView[10])
        }
        val compassDeg = if (side) compassSideDeg else compassLookDeg
        if (compassDeg.isNaN() || trackerDeg.isNaN()) return driftDeg

        val offset = wrap(trackerDeg - compassDeg)
        // Сменили ось или только начали — продолжаем с текущей оценки, без скачка.
        if (baseOffsetDeg.isNaN() || side != useSide) {
            useSide = side
            baseOffsetDeg = wrap(offset - driftDeg)
            return driftDeg
        }
        val measured = wrap(offset - baseOffsetDeg)
        val error = wrap(measured - driftDeg)
        // Медленно и не быстрее MAX_RATE: шум компаса и рывки головы не видны, а уход гироскопа выбирается.
        val step = (error * dt / TIME_CONSTANT_S).coerceIn(-MAX_RATE_DEG_S * dt, MAX_RATE_DEG_S * dt)
        driftDeg = wrap(driftDeg + step)
        return driftDeg
    }

    /** Направление вектора мира компаса по сторонам света; рост — поворот влево. */
    private fun heading(east: Float, north: Float): Float =
        -Math.toDegrees(atan2(east, north).toDouble()).toFloat()

    /** То же в мире трекинга: вперёд — −Z, вправо — X. */
    private fun trackerHeading(x: Float, z: Float): Float = Math.toDegrees(atan2(-x, -z).toDouble()).toFloat()

    private fun wrap(deg: Float): Float = ((deg + 540f) % 360f) - 180f

    private companion object {
        const val TIME_CONSTANT_S = 8f
        const val MAX_RATE_DEG_S = 1.5f

        /** Взгляд вертикальнее этого — сравниваем по длинной стороне; обратно — когда ниже SWITCH_BACK. */
        const val SWITCH_TO_SIDE = 0.8f
        const val SWITCH_BACK = 0.6f
    }
}

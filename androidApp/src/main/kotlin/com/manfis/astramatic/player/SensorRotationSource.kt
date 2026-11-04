package com.manfis.astramatic.player

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/** Датчик поворота телефона (гироскоп, акселерометр и компас вместе). */
class SensorRotationSource private constructor(
    private val sensors: SensorManager,
    private val sensor: Sensor,
) : RotationSource, SensorEventListener {

    private val matrix = FloatArray(9)
    private var listener: ((FloatArray) -> Unit)? = null

    override fun start(listener: (FloatArray) -> Unit) {
        this.listener = listener
        sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun stop() {
        sensors.unregisterListener(this)
        listener = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        listener?.invoke(matrix)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** null — такого датчика на телефоне нет. */
        fun of(context: Context): SensorRotationSource? {
            val sensors = context.getSystemService(SensorManager::class.java) ?: return null
            val sensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return null
            return SensorRotationSource(sensors, sensor)
        }
    }
}

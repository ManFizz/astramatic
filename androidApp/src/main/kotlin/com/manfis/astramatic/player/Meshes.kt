package com.manfis.astramatic.player

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Вершины подряд по 5 float: x, y, z, u, v. Рисуется как GL_TRIANGLES без индексов. */
class Mesh(val vertices: FloatBuffer, val vertexCount: Int) {
    companion object {
        const val FLOATS_PER_VERTEX = 5
        const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4
    }
}

object Meshes {

    /**
     * Часть сферы вокруг камеры. [lonSpan] = 2π для 360°, π для 180°.
     * Центр видео смотрит в -Z, v = 0 внизу (как у SurfaceTexture после transform-матрицы).
     */
    fun sphere(radius: Float, lonSpan: Float, latSegments: Int = 64, lonSegments: Int = 128): Mesh {
        val data = FloatArray(latSegments * lonSegments * 6 * Mesh.FLOATS_PER_VERTEX)
        var i = 0
        fun put(latIdx: Int, lonIdx: Int) {
            val v = latIdx.toFloat() / latSegments
            val u = lonIdx.toFloat() / lonSegments
            val lat = (v - 0.5f) * PI.toFloat()
            val lon = (u - 0.5f) * lonSpan
            data[i++] = radius * cos(lat) * sin(lon)
            data[i++] = radius * sin(lat)
            data[i++] = -radius * cos(lat) * cos(lon)
            data[i++] = u
            data[i++] = v
        }
        for (lat in 0 until latSegments) {
            for (lon in 0 until lonSegments) {
                put(lat, lon); put(lat + 1, lon); put(lat + 1, lon + 1)
                put(lat, lon); put(lat + 1, lon + 1); put(lat, lon + 1)
            }
        }
        return Mesh(data.toBuffer(), data.size / Mesh.FLOATS_PER_VERTEX)
    }

    /** Прямоугольник 1×1 в плоскости z = 0; размер и положение задаёт матрица модели. */
    fun quad(): Mesh {
        val data = floatArrayOf(
            -0.5f, -0.5f, 0f, 0f, 0f,
            0.5f, -0.5f, 0f, 1f, 0f,
            0.5f, 0.5f, 0f, 1f, 1f,
            -0.5f, -0.5f, 0f, 0f, 0f,
            0.5f, 0.5f, 0f, 1f, 1f,
            -0.5f, 0.5f, 0f, 0f, 1f,
        )
        return Mesh(data.toBuffer(), 6)
    }

    private fun FloatArray.toBuffer(): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also {
            it.put(this)
            it.position(0)
        }
}

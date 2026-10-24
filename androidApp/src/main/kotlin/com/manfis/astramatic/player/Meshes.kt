package com.manfis.astramatic.player

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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

    /**
     * Видео с объектива «рыбий глаз»: круглый кадр, угол от центра кадра пропорционален углу от оси.
     * [fovDeg] — полный угол объектива (180, 190, 200…).
     */
    fun fisheye(radius: Float, fovDeg: Int, rings: Int = 64, segments: Int = 128): Mesh {
        val maxTheta = Math.toRadians(fovDeg / 2.0).toFloat()
        val data = FloatArray(rings * segments * 6 * Mesh.FLOATS_PER_VERTEX)
        var i = 0
        fun put(ring: Int, segment: Int) {
            val r = ring.toFloat() / rings
            val theta = r * maxTheta
            val phi = segment.toFloat() / segments * 2 * PI.toFloat()
            data[i++] = radius * sin(theta) * cos(phi)
            data[i++] = radius * sin(theta) * sin(phi)
            data[i++] = -radius * cos(theta)
            data[i++] = 0.5f + 0.5f * r * cos(phi)
            data[i++] = 0.5f + 0.5f * r * sin(phi)
        }
        for (ring in 0 until rings) {
            for (segment in 0 until segments) {
                put(ring, segment); put(ring + 1, segment); put(ring + 1, segment + 1)
                put(ring, segment); put(ring + 1, segment + 1); put(ring, segment + 1)
            }
        }
        return Mesh(data.toBuffer(), data.size / Mesh.FLOATS_PER_VERTEX)
    }

    /**
     * EAC (YouTube 360): куб, грани сеткой 3×2. Верхний ряд кадра — левая, передняя, правая грани;
     * нижний — нижняя, задняя, верхняя, повёрнутые на 90° по часовой.
     * Внутри грани шаг равноугловой: координата куба a → atan(a) / (π/4).
     */
    fun eac(radius: Float, steps: Int = 32): Mesh {
        // Грань: направление по локальным (s, t) ∈ [-1, 1] (s — вправо, t — вверх, если смотреть изнутри),
        // столбец и ряд в кадре, повёрнута ли.
        class Face(val col: Int, val row: Int, val rotated: Boolean, val dir: (Float, Float) -> FloatArray)
        val faces = listOf(
            Face(0, 1, false) { s, t -> floatArrayOf(-1f, t, -s) },   // левая
            Face(1, 1, false) { s, t -> floatArrayOf(s, t, -1f) },    // передняя
            Face(2, 1, false) { s, t -> floatArrayOf(1f, t, s) },     // правая
            Face(0, 0, true) { s, t -> floatArrayOf(s, -1f, -t) },    // нижняя
            Face(1, 0, true) { s, t -> floatArrayOf(-s, t, 1f) },     // задняя
            Face(2, 0, true) { s, t -> floatArrayOf(s, 1f, t) },      // верхняя
        )
        val data = FloatArray(faces.size * steps * steps * 6 * Mesh.FLOATS_PER_VERTEX)
        var i = 0
        val quarter = (PI / 4).toFloat()
        for (face in faces) {
            fun put(a: Int, b: Int) {
                val s = a.toFloat() / steps * 2 - 1
                val t = b.toFloat() / steps * 2 - 1
                val d = face.dir(s, t)
                val len = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
                data[i++] = radius * d[0] / len
                data[i++] = radius * d[1] / len
                data[i++] = radius * d[2] / len
                // Равноугловой шаг, затем поворот грани нижнего ряда.
                val es = atan(s) / quarter
                val et = atan(t) / quarter
                val (x, y) = if (face.rotated) et to -es else es to et
                data[i++] = (face.col + (x + 1) / 2) / 3
                data[i++] = (face.row + (y + 1) / 2) / 2
            }
            for (a in 0 until steps) {
                for (b in 0 until steps) {
                    put(a, b); put(a + 1, b); put(a + 1, b + 1)
                    put(a, b); put(a + 1, b + 1); put(a, b + 1)
                }
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

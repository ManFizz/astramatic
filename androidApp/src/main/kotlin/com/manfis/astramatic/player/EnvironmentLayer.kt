package com.manfis.astramatic.player

import android.graphics.BitmapFactory
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import java.io.File
import kotlin.math.PI

/** Что вокруг, когда видео не закрывает всё поле зрения: в меню, у плоского экрана, за 180°. */
enum class Environment(val title: String) {
    Black("Чёрный"),
    Cinema("Кинозал"),
    Space("Космос"),
    Sunset("Закат"),
    Custom("Своя панорама"),
}

/**
 * Окружение — сфера вокруг зрителя с процедурным шейдером или своей панорамой 360°.
 * Кинозал подсвечивается средним цветом кадра, как ambilight у телевизоров.
 * Только с GL-потока.
 */
class EnvironmentLayer(private val customFile: File) {

    private val sphere = Meshes.sphere(RADIUS, 2 * PI.toFloat(), latSegments = 32, lonSegments = 64)

    private var program = 0
    private var aPosition = 0
    private var uMvp = 0
    private var uMode = 0
    private var uTint = 0
    private var uTexMatrix = 0
    private var uUvRect = 0
    private var uVideo = 0
    private var uCustom = 0

    private var customTexture = 0
    private var customLoaded = -1L

    /** Своя панорама сменилась: перечитать файл в следующем кадре. */
    @Volatile private var customVersion = 0L

    fun customChanged() {
        customVersion++
    }

    fun create() {
        // Если какой-то видеочип не соберёт шейдер — живём без окружения, а не падаем.
        program = runCatching { GlUtil.program(VERTEX_SHADER, FRAGMENT_SHADER) }
            .onFailure { android.util.Log.e("AstraMatic", "Окружение отключено: шейдер не собрался", it) }
            .getOrDefault(0)
        if (program == 0) return
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uMode = GLES20.glGetUniformLocation(program, "uMode")
        uTint = GLES20.glGetUniformLocation(program, "uTint")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uUvRect = GLES20.glGetUniformLocation(program, "uUvRect")
        uVideo = GLES20.glGetUniformLocation(program, "uVideo")
        uCustom = GLES20.glGetUniformLocation(program, "uCustom")
        // Новый GL-контекст — старые текстуры недействительны.
        customTexture = 0
        customLoaded = -1L
    }

    /**
     * [videoTexture] и [videoTransform] — текущий кадр для подсветки кинозала ([tint]),
     * [uvRect] — часть кадра этого глаза.
     */
    fun draw(
        environment: Environment,
        mvp: FloatArray,
        videoTexture: Int,
        videoTransform: FloatArray,
        uvRect: FloatArray,
        tint: Boolean,
    ) {
        var mode = environment
        if (mode == Environment.Black || program == 0) return
        if (mode == Environment.Custom && !ensureCustom()) mode = Environment.Space

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform1i(uMode, mode.ordinal)
        GLES20.glUniform1f(uTint, if (tint) 1f else 0f)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, videoTransform, 0)
        GLES20.glUniform4f(uUvRect, uvRect[0], uvRect[1], uvRect[2], uvRect[3])

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexture)
        GLES20.glUniform1i(uVideo, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, customTexture)
        GLES20.glUniform1i(uCustom, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        val vertices = sphere.vertices
        vertices.position(0)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, Mesh.STRIDE_BYTES, vertices)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, sphere.vertexCount)
        GLES20.glDisableVertexAttribArray(aPosition)
    }

    /** Своя панорама в текстуре; false — файла нет или не читается. */
    private fun ensureCustom(): Boolean {
        val version = customVersion
        if (customLoaded == version) return customTexture != 0
        customLoaded = version
        if (customTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(customTexture), 0)
            customTexture = 0
        }
        val bitmap = BitmapFactory.decodeFile(customFile.path) ?: return false
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        customTexture = ids[0]
        return true
    }

    companion object {
        const val RADIUS = 30f

        /** Сторона панорамы окружения: больше — тяжелее, а разницы в шлеме почти нет. */
        const val CUSTOM_MAX_WIDTH = 4096

        private const val VERTEX_SHADER = """
uniform mat4 uMvp;
attribute vec3 aPosition;
varying vec3 vDir;
void main() {
    vDir = aPosition;
    gl_Position = uMvp * vec4(aPosition, 1.0);
}
"""

        // Режимы — порядковые номера Environment: 1 кинозал, 2 космос, 3 закат, 4 своя панорама.
        private const val FRAGMENT_SHADER = """#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform int uMode;
uniform float uTint;
uniform samplerExternalOES uVideo;
uniform sampler2D uCustom;
uniform mat4 uTexMatrix;
uniform vec4 uUvRect;
varying vec3 vDir;

float hash(vec3 p) {
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

// Средний цвет кадра по сетке 4×4 точек.
vec3 videoAverage() {
    vec3 sum = vec3(0.0);
    for (int x = 0; x < 4; x++) {
        for (int y = 0; y < 4; y++) {
            vec2 uv = uUvRect.xy + (vec2(float(x), float(y)) + 0.5) / 4.0 * uUvRect.zw;
            sum += texture2D(uVideo, (uTexMatrix * vec4(uv, 0.0, 1.0)).xy).rgb;
        }
    }
    return sum / 16.0;
}

void main() {
    vec3 d = normalize(vDir);
    vec3 color = vec3(0.0);
    if (uMode == 1) {
        // Кинозал: тёмные стены, пол чуть светлее, свет от экрана — сильнее со стороны экрана.
        float ground = smoothstep(0.0, -0.3, d.y);
        color = mix(vec3(0.025, 0.025, 0.035), vec3(0.05, 0.04, 0.05), ground);
        if (uTint > 0.5) {
            float front = max(0.0, -d.z);
            color += videoAverage() * (0.04 + 0.32 * front * front);
        }
    } else if (uMode == 2) {
        // Космос: тёмно-синий фон, лёгкая туманность и звёзды разной яркости.
        float nebula = pow(0.5 + 0.5 * sin(d.x * 3.0 + d.y * 5.0) * sin(d.z * 4.0 - d.y * 2.0), 3.0);
        color = vec3(0.004, 0.006, 0.018) + vec3(0.05, 0.02, 0.08) * nebula;
        vec3 p = d * 220.0;
        float h = hash(floor(p));
        if (h > 0.985) {
            float dist = length(fract(p) - 0.5);
            color += vec3(0.9, 0.92, 1.0) * smoothstep(0.35, 0.0, dist) * (h - 0.985) * 66.0;
        }
    } else if (uMode == 3) {
        // Закат: небо от оранжевого к фиолетовому, солнце у горизонта, внизу светящаяся сетка.
        vec3 sunDir = normalize(vec3(0.0, 0.06, -1.0));
        float toSun = max(dot(d, sunDir), 0.0);
        if (d.y >= 0.0) {
            color = mix(vec3(1.0, 0.5, 0.28), vec3(0.1, 0.06, 0.28), smoothstep(0.0, 0.6, d.y));
            color += vec3(1.0, 0.85, 0.55) * smoothstep(0.996, 0.999, toSun);
            color += vec3(1.0, 0.45, 0.2) * pow(toSun, 30.0) * 0.5;
        } else {
            vec2 g = d.xz / max(-d.y, 0.03) * 1.5;
            vec2 f = abs(fract(g) - 0.5);
            float line = smoothstep(0.46, 0.5, max(f.x, f.y)) * smoothstep(0.0, -0.3, d.y);
            color = mix(vec3(0.06, 0.02, 0.1), vec3(0.95, 0.3, 0.8), line);
            color = mix(vec3(0.6, 0.28, 0.32), color, smoothstep(0.0, -0.08, d.y));
        }
    } else if (uMode == 4) {
        // Своя панорама (равнопромежуточная): строка 0 картинки — верх.
        vec2 uv = vec2(atan(d.x, -d.z) / 6.2831853 + 0.5, asin(clamp(d.y, -1.0, 1.0)) / 3.1415927 + 0.5);
        color = texture2D(uCustom, vec2(uv.x, 1.0 - uv.y)).rgb;
    }
    gl_FragColor = vec4(color, 1.0);
}
"""
    }
}

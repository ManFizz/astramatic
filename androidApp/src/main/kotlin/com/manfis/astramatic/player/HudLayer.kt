package com.manfis.astramatic.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * То, что движется вместе с головой и рисуется поверх всего: полоска приближения и перемотки вверху поля зрения
 * и кольцо рецентра посередине. Размеры в метрах на расстоянии [DISTANCE]. Только с GL-потока, кроме [show], [ring], [flash].
 */
class HudLayer {
    @Volatile private var bar: HudBar? = null
    @Volatile private var barShownAt = 0L
    @Volatile private var ringStartedAt = 0L
    @Volatile private var ringDurationMs = 0L
    @Volatile private var flashAt = 0L

    private val quad = Meshes.quad()
    private val model = FloatArray(16)
    private val viewModel = FloatArray(16)
    private val mvp = FloatArray(16)

    private var shapeProgram = 0
    private var shapePosition = 0
    private var shapeUv = 0
    private var shapeMvp = 0
    private var shapeSize = 0
    private var shapeColor = 0

    private var ringProgram = 0
    private var ringPosition = 0
    private var ringUv = 0
    private var ringMvp = 0
    private var ringProgress = 0
    private var ringAlpha = 0

    private var labelProgram = 0
    private var labelPosition = 0
    private var labelUv = 0
    private var labelMvp = 0
    private var labelAlpha = 0
    private val textures = IntArray(2)
    private var uploadedLabel: String? = null
    private var ringLabelReady = false

    /** Значение на экране догоняет настоящее: полоска едет, а не прыгает. */
    private var shownValue = 0f
    private var shownKind: HudBar.Kind? = null
    private var barAlpha = 0f

    fun show(value: HudBar) {
        bar = value
        barShownAt = SystemClock.uptimeMillis()
    }

    /** Показать отсчёт длиной [durationMs]; null — убрать. */
    fun ring(durationMs: Long?) {
        ringDurationMs = durationMs ?: 0L
        ringStartedAt = if (durationMs != null) SystemClock.uptimeMillis() else 0L
    }

    /** Рецентр выполнен: кольцо вспыхивает и гаснет. */
    fun flash() {
        flashAt = SystemClock.uptimeMillis()
    }

    fun create() {
        shapeProgram = GlUtil.program(VERTEX_SHADER, SHAPE_SHADER)
        shapePosition = GLES20.glGetAttribLocation(shapeProgram, "aPosition")
        shapeUv = GLES20.glGetAttribLocation(shapeProgram, "aUv")
        shapeMvp = GLES20.glGetUniformLocation(shapeProgram, "uMvp")
        shapeSize = GLES20.glGetUniformLocation(shapeProgram, "uSize")
        shapeColor = GLES20.glGetUniformLocation(shapeProgram, "uColor")

        ringProgram = GlUtil.program(VERTEX_SHADER, RING_SHADER)
        ringPosition = GLES20.glGetAttribLocation(ringProgram, "aPosition")
        ringUv = GLES20.glGetAttribLocation(ringProgram, "aUv")
        ringMvp = GLES20.glGetUniformLocation(ringProgram, "uMvp")
        ringProgress = GLES20.glGetUniformLocation(ringProgram, "uProgress")
        ringAlpha = GLES20.glGetUniformLocation(ringProgram, "uAlpha")

        labelProgram = GlUtil.program(VERTEX_SHADER, LABEL_SHADER)
        labelPosition = GLES20.glGetAttribLocation(labelProgram, "aPosition")
        labelUv = GLES20.glGetAttribLocation(labelProgram, "aUv")
        labelMvp = GLES20.glGetUniformLocation(labelProgram, "uMvp")
        labelAlpha = GLES20.glGetUniformLocation(labelProgram, "uAlpha")
        GLES20.glGenTextures(2, textures, 0)
        uploadedLabel = null
        ringLabelReady = false
    }

    /** Раз в кадр. */
    fun advance(dt: Float) {
        val now = SystemClock.uptimeMillis()
        val current = bar
        if (current == null) {
            barAlpha = 0f
        } else {
            if (current.kind != shownKind) {
                shownKind = current.kind
                shownValue = current.value
            }
            shownValue += (current.value - shownValue) * min(1f, dt * FOLLOW_PER_S)
            val wanted = now - barShownAt < BAR_HOLD_MS
            barAlpha = if (wanted) min(1f, barAlpha + dt / FADE_IN_S) else max(0f, barAlpha - dt / FADE_OUT_S)
        }
        if (current != null && current.label != uploadedLabel) {
            upload(textures[0], label(current.label))
            uploadedLabel = current.label
        }
        if (!ringLabelReady) {
            upload(textures[1], label(RING_TEXT))
            ringLabelReady = true
        }
    }

    /** [view] — вид глаза без поворота головы. Смешивание уже включено. */
    fun draw(view: FloatArray, projection: FloatArray) {
        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)
        drawRing()
        drawBar()
    }

    private fun drawBar() {
        val current = bar ?: return
        if (barAlpha <= 0f) return
        val a = barAlpha
        val left = -BAR_WIDTH / 2
        fun x(position: Float) = left + BAR_WIDTH * position

        shape(0f, BAR_Y + BACK_LIFT, BAR_WIDTH + BACK_MARGIN, BACK_HEIGHT, 0.04f, 0.03f, 0.08f, 0.6f * a)
        label(textures[0], 0f, BAR_Y + LABEL_LIFT, LABEL_WIDTH, LABEL_HEIGHT, a)

        shape(0f, BAR_Y, BAR_WIDTH, TRACK_HEIGHT, 1f, 1f, 1f, 0.28f * a)
        val from = x(current.from)
        val to = x(shownValue)
        val fillLeft = min(from, to)
        val fillRight = max(from, to)
        if (fillRight - fillLeft > 0.001f) {
            shape((fillLeft + fillRight) / 2, BAR_Y, fillRight - fillLeft, TRACK_HEIGHT * 1.3f, ACCENT[0], ACCENT[1], ACCENT[2], a)
        }
        current.mark?.let { mark ->
            val onMark = abs(shownValue - mark) < 0.004f
            val h = if (onMark) MARK_HEIGHT * 1.5f else MARK_HEIGHT
            shape(x(mark), BAR_Y, MARK_WIDTH, h, 1f, 1f, 1f, (if (onMark) 1f else 0.75f) * a)
        }
        shape(to, BAR_Y, THUMB, THUMB, 1f, 1f, 1f, a)
    }

    private fun drawRing() {
        val now = SystemClock.uptimeMillis()
        val duration = ringDurationMs
        if (duration > 0) {
            val progress = ((now - ringStartedAt).toFloat() / duration).coerceIn(0f, 1f)
            ring(progress, 1f)
            label(textures[1], 0f, -RING_SIZE * 0.72f, LABEL_WIDTH, LABEL_HEIGHT, 1f)
            return
        }
        val since = now - flashAt
        if (flashAt != 0L && since < FLASH_MS) ring(1f, 1f - since.toFloat() / FLASH_MS)
    }

    private fun ring(progress: Float, alpha: Float) {
        place(0f, 0f, RING_SIZE, RING_SIZE)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(ringProgram)
        GLES20.glUniformMatrix4fv(ringMvp, 1, false, viewModel, 0)
        GLES20.glUniform1f(ringProgress, progress)
        GLES20.glUniform1f(ringAlpha, alpha)
        GlUtil.drawMesh(quad, ringPosition, ringUv)
    }

    private fun shape(cx: Float, cy: Float, w: Float, h: Float, r: Float, g: Float, b: Float, alpha: Float) {
        place(cx, cy, w, h)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(shapeProgram)
        GLES20.glUniformMatrix4fv(shapeMvp, 1, false, viewModel, 0)
        GLES20.glUniform2f(shapeSize, w, h)
        GLES20.glUniform4f(shapeColor, r, g, b, alpha)
        GlUtil.drawMesh(quad, shapePosition, shapeUv)
    }

    private fun label(texture: Int, cx: Float, cy: Float, w: Float, h: Float, alpha: Float) {
        place(cx, cy, w, h)
        // Картинка с премультиплицированной альфой.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(labelProgram)
        GLES20.glUniformMatrix4fv(labelMvp, 1, false, viewModel, 0)
        GLES20.glUniform1f(labelAlpha, alpha)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GlUtil.drawMesh(quad, labelPosition, labelUv)
    }

    /** В [viewModel] — итоговая матрица предмета: центр и размер в метрах. */
    private fun place(cx: Float, cy: Float, w: Float, h: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, cx, cy, -DISTANCE)
        Matrix.scaleM(model, 0, w, h, 1f)
        Matrix.multiplyMM(viewModel, 0, mvp, 0, model, 0)
    }

    private fun label(text: String): Bitmap {
        val bitmap = Bitmap.createBitmap(LABEL_PX_W, LABEL_PX_H, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = LABEL_PX_H * 0.62f
            textAlign = Paint.Align.CENTER
            setShadowLayer(6f, 0f, 2f, Color.argb(200, 0, 0, 0))
        }
        val baseline = LABEL_PX_H / 2f - (paint.descent() + paint.ascent()) / 2
        Canvas(bitmap).drawText(text, LABEL_PX_W / 2f, baseline, paint)
        return bitmap
    }

    private fun upload(texture: Int, bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
    }

    companion object {
        /** На этом расстоянии заданы размеры; глаза сводятся как на видео, поэтому двоиться не должно. */
        const val DISTANCE = 2.5f

        private const val BAR_Y = 0.72f
        private const val BAR_WIDTH = 1.0f
        private const val TRACK_HEIGHT = 0.014f
        private const val MARK_WIDTH = 0.010f
        private const val MARK_HEIGHT = 0.05f
        private const val THUMB = 0.034f
        private const val BACK_MARGIN = 0.16f
        private const val BACK_HEIGHT = 0.2f
        private const val BACK_LIFT = 0.05f
        private const val LABEL_LIFT = 0.08f
        private const val LABEL_WIDTH = 1.0f
        private const val LABEL_HEIGHT = 0.1f
        private const val LABEL_PX_W = 800
        private const val LABEL_PX_H = 80

        private const val RING_SIZE = 0.34f
        private const val RING_TEXT = "Новый центр здесь"

        private const val BAR_HOLD_MS = 1_300L
        private const val FLASH_MS = 400L
        private const val FADE_IN_S = 0.1f
        private const val FADE_OUT_S = 0.3f
        private const val FOLLOW_PER_S = 18f

        private val ACCENT = floatArrayOf(0.72f, 0.62f, 1f)

        private const val VERTEX_SHADER = """
uniform mat4 uMvp;
attribute vec3 aPosition;
attribute vec2 aUv;
varying vec2 vUv;
void main() {
    vUv = aUv;
    gl_Position = uMvp * vec4(aPosition, 1.0);
}
"""

        private const val SHAPE_SHADER = """
precision mediump float;
uniform vec2 uSize;
uniform vec4 uColor;
varying vec2 vUv;
void main() {
    vec2 hs = uSize * 0.5;
    vec2 p = (vUv - 0.5) * uSize;
    float r = min(hs.x, min(hs.y, 0.06));
    vec2 q = abs(p) - hs + vec2(r);
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
    float a = 1.0 - smoothstep(-r * 0.3, 0.0, d);
    gl_FragColor = vec4(uColor.rgb, uColor.a * a);
}
"""

        // Заполняется по часовой стрелке от верха.
        private const val RING_SHADER = """
precision mediump float;
uniform float uProgress;
uniform float uAlpha;
varying vec2 vUv;
void main() {
    vec2 p = vUv - 0.5;
    float d = length(p);
    float inside = 1.0 - smoothstep(0.37, 0.39, d);
    float outer = 1.0 - smoothstep(0.47, 0.49, d);
    float angle = fract(atan(p.x, p.y) / 6.2831853 + 1.0);
    vec4 inner = vec4(0.03, 0.02, 0.06, 0.4);
    vec4 back = vec4(0.05, 0.04, 0.1, 0.8);
    vec4 fill = vec4(0.84, 0.78, 1.0, 1.0);
    vec4 c = mix(angle <= uProgress ? fill : back, inner, inside);
    c.a *= outer;
    float point = 1.0 - smoothstep(0.03, 0.045, d);
    c = mix(c, vec4(1.0), point);
    gl_FragColor = vec4(c.rgb, c.a * uAlpha);
}
"""

        private const val LABEL_SHADER = """
precision mediump float;
uniform sampler2D uTexture;
uniform float uAlpha;
varying vec2 vUv;
void main() {
    gl_FragColor = texture2D(uTexture, vec2(vUv.x, 1.0 - vUv.y)) * uAlpha;
}
"""
    }
}

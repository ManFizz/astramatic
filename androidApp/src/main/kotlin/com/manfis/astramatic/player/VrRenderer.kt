package com.manfis.astramatic.player

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import com.google.cardboard.sdk.CardboardView
import com.google.cardboard.sdk.HeadTransform
import com.google.cardboard.sdk.Viewport
import com.manfis.astramatic.model.Projection
import com.manfis.astramatic.model.StereoLayout
import com.manfis.astramatic.model.VideoFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Рисует для каждого глаза видео (на сферу или плоскость) и панель интерфейса с курсором взгляда.
 * Трекинг головы, матрицы глаз и коррекцию линз даёт Cardboard SDK.
 * CardboardView создаёт контекст OpenGL ES 2.0, поэтому шейдеры на GLSL ES 1.00.
 */
class VrRenderer(
    /** Вызывается на GL-потоке, когда готова поверхность для декодера (в том числе после пересоздания контекста). */
    private val onVideoSurfaceReady: (Surface) -> Unit,
    /** То же для панели интерфейса: в эту поверхность рисует виртуальный дисплей. */
    private val onPanelSurfaceReady: (Surface) -> Unit,
) : CardboardView.Renderer {

    @Volatile var format = VideoFormat()

    /** Ширина / высота всего видеокадра (с обоими глазами). */
    @Volatile var videoAspect = 16f / 9f

    /** Пока видео не открыто, вокруг пустота с одной панелью. */
    @Volatile var hasVideo = false

    @Volatile var panelVisible = false

    /**
     * Какую часть виртуального дисплея показывать и где.
     * [visibleHeightPx] — сколько пикселей сверху дисплея видно, [width] — ширина в метрах,
     * [centerY] — высота центра относительно глаз в метрах.
     */
    enum class PanelLayout(val visibleHeightPx: Int, val width: Float, val centerY: Float) {
        Menu(PANEL_HEIGHT_PX, 2.2f, 0f),
        /** Полоска управления: верхние 300 px (200 dp при плотности панели), чуть ниже взгляда. */
        Controls(300, 1.8f, -0.55f);

        val height: Float get() = width * visibleHeightPx / PANEL_WIDTH_PX
        val visibleFraction: Float get() = visibleHeightPx.toFloat() / PANEL_HEIGHT_PX
    }

    @Volatile var panelLayout = PanelLayout.Menu

    /** Точка взгляда на панели в пикселях панели, null — взгляд мимо. Обновляется каждый кадр. */
    @Volatile var gaze: Gaze? = null
        private set

    data class Gaze(val x: Float, val y: Float)

    private val meshes = mapOf(
        Projection.Flat to Meshes.quad(),
        Projection.Dome180 to Meshes.sphere(SPHERE_RADIUS, PI.toFloat()),
        Projection.Sphere360 to Meshes.sphere(SPHERE_RADIUS, 2 * PI.toFloat()),
    )
    private val quad = Meshes.quad()

    private val video = ExternalTexture()
    private val panel = ExternalTexture()

    private var textureProgram = 0
    private var aPosition = 0
    private var aUv = 0
    private var uMvp = 0
    private var uTexMatrix = 0
    private var uUvRect = 0

    private var cursorProgram = 0
    private var cursorPosition = 0
    private var cursorUv = 0
    private var cursorMvp = 0

    private val recenterRequested = AtomicBoolean(true)
    /**
     * Сколько кадров подряд ставить панель перед взглядом. Не один: сразу после запуска
     * трекинг головы ещё не определился с направлением, и панель оказалась бы где-то сбоку.
     */
    private val placePanelFrames = AtomicInteger(PLACE_PANEL_FRAMES)

    private val headView = FloatArray(16)
    private var videoYawDeg = 0f
    private var panelYawDeg = 0f
    private var controlsFollowing = false
    private val videoModel = FloatArray(16)
    private val panelModel = FloatArray(16)
    private val cursorModel = FloatArray(16)
    private val viewModel = FloatArray(16)
    private val mvp = FloatArray(16)
    private val rotation = FloatArray(16)
    private val direction = FloatArray(4)
    private val localDirection = FloatArray(4)

    /** Повернуть видео так, чтобы его центр оказался там, куда сейчас смотрит голова (только по горизонтали). */
    fun recenter() = recenterRequested.set(true)

    /** Поставить панель перед взглядом. */
    fun placePanelInFront() = placePanelFrames.set(PLACE_PANEL_FRAMES)

    override fun onSurfaceCreated(config: EGLConfig?) {
        textureProgram = GlUtil.program(TEXTURE_VERTEX_SHADER, TEXTURE_FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(textureProgram, "aPosition")
        aUv = GLES20.glGetAttribLocation(textureProgram, "aUv")
        uMvp = GLES20.glGetUniformLocation(textureProgram, "uMvp")
        uTexMatrix = GLES20.glGetUniformLocation(textureProgram, "uTexMatrix")
        uUvRect = GLES20.glGetUniformLocation(textureProgram, "uUvRect")

        cursorProgram = GlUtil.program(CURSOR_VERTEX_SHADER, CURSOR_FRAGMENT_SHADER)
        cursorPosition = GLES20.glGetAttribLocation(cursorProgram, "aPosition")
        cursorUv = GLES20.glGetAttribLocation(cursorProgram, "aUv")
        cursorMvp = GLES20.glGetUniformLocation(cursorProgram, "uMvp")

        onVideoSurfaceReady(video.create())
        onPanelSurfaceReady(panel.create(PANEL_WIDTH_PX, PANEL_HEIGHT_PX))
    }

    override fun onSurfaceChanged(width: Int, height: Int) = Unit

    override fun onNewFrame(headTransform: HeadTransform) {
        video.update()
        panel.update()

        headTransform.getHeadView(headView, 0)
        // Направление взгляда в мире: третья строка матрицы вида со знаком минус.
        direction[0] = -headView[2]
        direction[1] = -headView[6]
        direction[2] = -headView[10]
        direction[3] = 0f
        val lookYawDeg = Math.toDegrees(atan2(-direction[0], -direction[2]).toDouble()).toFloat()

        if (!lookYawDeg.isNaN()) {
            if (recenterRequested.getAndSet(false)) videoYawDeg = lookYawDeg
            if (placePanelFrames.get() > 0) {
                placePanelFrames.decrementAndGet()
                panelYawDeg = lookYawDeg
            } else if (panelLayout == PanelLayout.Controls) {
                // Полоска мягко догоняет взгляд, если отвернуться от неё далеко в сторону, и встаёт по центру.
                val diff = wrapDegrees(lookYawDeg - panelYawDeg)
                if (abs(diff) > CONTROLS_FOLLOW_DEG) controlsFollowing = true
                if (controlsFollowing) {
                    panelYawDeg = wrapDegrees(panelYawDeg + diff * CONTROLS_FOLLOW_SPEED)
                    if (abs(diff) < 1f) controlsFollowing = false
                }
            }
        }

        updateVideoModel(format)
        val layout = panelLayout
        Matrix.setRotateM(panelModel, 0, panelYawDeg, 0f, 1f, 0f)
        Matrix.translateM(panelModel, 0, 0f, layout.centerY, -PANEL_DISTANCE)
        Matrix.scaleM(panelModel, 0, layout.width, layout.height, 1f)

        gaze = if (panelVisible) computeGaze(layout) else null
    }

    private fun wrapDegrees(deg: Float): Float = ((deg + 540f) % 360f) - 180f

    /** Пересечение луча взгляда с плоскостью панели, в координатах панели. */
    private fun computeGaze(layout: PanelLayout): Gaze? {
        Matrix.setRotateM(rotation, 0, -panelYawDeg, 0f, 1f, 0f)
        Matrix.multiplyMV(localDirection, 0, rotation, 0, direction, 0)
        val dz = localDirection[2]
        if (dz > -0.01f) return null
        val t = -PANEL_DISTANCE / dz
        val u = localDirection[0] * t / layout.width + 0.5f
        val v = 0.5f - (localDirection[1] * t - layout.centerY) / layout.height
        if (u !in 0f..1f || v !in 0f..1f) return null

        Matrix.setRotateM(cursorModel, 0, panelYawDeg, 0f, 1f, 0f)
        Matrix.translateM(
            cursorModel, 0,
            (u - 0.5f) * layout.width, (0.5f - v) * layout.height + layout.centerY, -PANEL_DISTANCE + CURSOR_LIFT,
        )
        Matrix.scaleM(cursorModel, 0, CURSOR_SIZE, CURSOR_SIZE, 1f)
        return Gaze(u * PANEL_WIDTH_PX, v * layout.visibleHeightPx)
    }

    override fun onDrawEye(eye: CardboardView.Eye) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        // SDK сам не подставляет положение головы: без этого вызова матрица глаза нулевая и кадр пустой.
        eye.applyHeadView(headView)
        val projection = eye.getPerspective(Z_NEAR, Z_FAR)

        if (hasVideo) drawVideo(eye, projection)

        if (panelVisible && panel.isReady) {
            GLES20.glEnable(GLES20.GL_BLEND)
            // Кадры виртуального дисплея с премультиплицированной альфой (прозрачные углы панели).
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            // Верхняя часть кадра дисплея; v = 0 внизу текстуры.
            val visible = panelLayout.visibleFraction
            GLES20.glUseProgram(textureProgram)
            GLES20.glUniform4f(uUvRect, 0f, 1f - visible, 1f, visible)
            drawTextured(panel, quad, eye, projection, panelModel, setUvRect = false)
            if (gaze != null) {
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                drawCursor(eye, projection)
            }
            GLES20.glDisable(GLES20.GL_BLEND)
        }
    }

    private fun drawVideo(eye: CardboardView.Eye, projection: FloatArray) {
        if (!video.isReady) return
        val format = format
        val rightEye = eye.eyeType == CardboardView.Eye.RIGHT
        GLES20.glUseProgram(textureProgram)
        when (format.stereo) {
            StereoLayout.Mono -> GLES20.glUniform4f(uUvRect, 0f, 0f, 1f, 1f)
            StereoLayout.SideBySide -> GLES20.glUniform4f(uUvRect, if (rightEye) 0.5f else 0f, 0f, 0.5f, 1f)
            // Левый глаз сверху; v = 0 внизу кадра.
            StereoLayout.TopBottom -> GLES20.glUniform4f(uUvRect, 0f, if (rightEye) 0f else 0.5f, 1f, 0.5f)
        }
        drawTextured(video, meshes.getValue(format.projection), eye, projection, videoModel, setUvRect = false)
    }

    private fun drawTextured(
        texture: ExternalTexture,
        mesh: Mesh,
        eye: CardboardView.Eye,
        projection: FloatArray,
        model: FloatArray,
        setUvRect: Boolean = true,
    ) {
        Matrix.multiplyMM(viewModel, 0, eye.eyeView, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0)

        GLES20.glUseProgram(textureProgram)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texture.transform, 0)
        if (setUvRect) GLES20.glUniform4f(uUvRect, 0f, 0f, 1f, 1f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture.id)
        drawMesh(mesh, aPosition, aUv)
    }

    private fun drawCursor(eye: CardboardView.Eye, projection: FloatArray) {
        Matrix.multiplyMM(viewModel, 0, eye.eyeView, 0, cursorModel, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0)
        GLES20.glUseProgram(cursorProgram)
        GLES20.glUniformMatrix4fv(cursorMvp, 1, false, mvp, 0)
        drawMesh(quad, cursorPosition, cursorUv)
    }

    private fun drawMesh(mesh: Mesh, positionAttr: Int, uvAttr: Int) {
        val vertices = mesh.vertices
        vertices.position(0)
        GLES20.glVertexAttribPointer(positionAttr, 3, GLES20.GL_FLOAT, false, Mesh.STRIDE_BYTES, vertices)
        GLES20.glEnableVertexAttribArray(positionAttr)
        vertices.position(3)
        GLES20.glVertexAttribPointer(uvAttr, 2, GLES20.GL_FLOAT, false, Mesh.STRIDE_BYTES, vertices)
        GLES20.glEnableVertexAttribArray(uvAttr)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount)

        GLES20.glDisableVertexAttribArray(positionAttr)
        GLES20.glDisableVertexAttribArray(uvAttr)
    }

    override fun onFinishFrame(viewport: Viewport) = Unit

    override fun onRendererShutdown() = release()

    private fun updateVideoModel(format: VideoFormat) {
        Matrix.setRotateM(videoModel, 0, videoYawDeg, 0f, 1f, 0f)
        if (format.projection != Projection.Flat) return
        val eyeAspect = when (format.stereo) {
            StereoLayout.Mono -> videoAspect
            StereoLayout.SideBySide -> videoAspect / 2
            StereoLayout.TopBottom -> videoAspect * 2
        }
        Matrix.translateM(videoModel, 0, 0f, 0f, -FLAT_SCREEN_DISTANCE)
        Matrix.scaleM(videoModel, 0, FLAT_SCREEN_HEIGHT * eyeAspect, FLAT_SCREEN_HEIGHT, 1f)
    }

    fun release() {
        video.release()
        panel.release()
    }

    /** OES-текстура с SurfaceTexture: в неё пишет декодер видео или виртуальный дисплей. */
    private class ExternalTexture : SurfaceTexture.OnFrameAvailableListener {
        var id = 0
            private set
        val transform = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        private var surfaceTexture: SurfaceTexture? = null
        private var surface: Surface? = null
        private val frameAvailable = AtomicBoolean(false)

        /** Кадр хотя бы раз пришёл: до этого текстура пустая. */
        @Volatile var isReady = false
            private set

        fun create(width: Int = 0, height: Int = 0): Surface {
            release()
            id = GlUtil.externalTexture()
            val texture = SurfaceTexture(id).also { it.setOnFrameAvailableListener(this) }
            if (width > 0) texture.setDefaultBufferSize(width, height)
            surfaceTexture = texture
            return Surface(texture).also { surface = it }
        }

        fun update() {
            val texture = surfaceTexture ?: return
            if (frameAvailable.getAndSet(false)) {
                texture.updateTexImage()
                texture.getTransformMatrix(transform)
                isReady = true
            }
        }

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture) = frameAvailable.set(true)

        fun release() {
            surface?.release()
            surfaceTexture?.release()
            surface = null
            surfaceTexture = null
            isReady = false
        }
    }

    companion object {
        /** Размер виртуального дисплея панели. */
        const val PANEL_WIDTH_PX = 1280
        const val PANEL_HEIGHT_PX = 800
        const val PANEL_DENSITY_DPI = 240

        private const val PANEL_DISTANCE = 2.5f
        private const val PLACE_PANEL_FRAMES = 45
        /** Полоска шириной ±20° от центра; дальше 30° — уже точно смотрят не на неё. */
        private const val CONTROLS_FOLLOW_DEG = 30f
        private const val CONTROLS_FOLLOW_SPEED = 0.08f
        private const val CURSOR_SIZE = 0.04f
        private const val CURSOR_LIFT = 0.01f

        private const val Z_NEAR = 0.1f
        private const val Z_FAR = 100f
        private const val SPHERE_RADIUS = 10f
        private const val FLAT_SCREEN_DISTANCE = 3f
        private const val FLAT_SCREEN_HEIGHT = 2f

        private const val TEXTURE_VERTEX_SHADER = """
uniform mat4 uMvp;
uniform mat4 uTexMatrix;
uniform vec4 uUvRect; // xy: смещение, zw: масштаб области кадра для текущего глаза
attribute vec3 aPosition;
attribute vec2 aUv;
varying vec2 vUv;
void main() {
    vec2 uv = uUvRect.xy + aUv * uUvRect.zw;
    vUv = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
    gl_Position = uMvp * vec4(aPosition, 1.0);
}
"""

        private const val TEXTURE_FRAGMENT_SHADER = """#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTexture;
varying vec2 vUv;
void main() {
    gl_FragColor = texture2D(uTexture, vUv);
}
"""

        private const val CURSOR_VERTEX_SHADER = """
uniform mat4 uMvp;
attribute vec3 aPosition;
attribute vec2 aUv;
varying vec2 vUv;
void main() {
    vUv = aUv;
    gl_Position = uMvp * vec4(aPosition, 1.0);
}
"""

        // Белая точка с тёмной обводкой, чтобы была видна на любом фоне.
        private const val CURSOR_FRAGMENT_SHADER = """
precision mediump float;
varying vec2 vUv;
void main() {
    float d = length(vUv - 0.5);
    if (d > 0.5) discard;
    gl_FragColor = d > 0.32 ? vec4(0.0, 0.0, 0.0, 0.7) : vec4(1.0, 1.0, 1.0, 1.0);
}
"""
    }
}

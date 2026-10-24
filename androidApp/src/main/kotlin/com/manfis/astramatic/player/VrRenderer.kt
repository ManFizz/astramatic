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
import javax.microedition.khronos.egl.EGLConfig
import kotlin.math.PI
import kotlin.math.atan2

/**
 * Рисует видеокадр на сферу или плоскость для каждого глаза.
 * Трекинг головы, матрицы глаз и коррекцию линз даёт Cardboard SDK.
 * CardboardView создаёт контекст OpenGL ES 2.0, поэтому шейдеры на GLSL ES 1.00.
 */
class VrRenderer(
    /** Вызывается на GL-потоке, когда готова поверхность для декодера (в том числе после пересоздания контекста). */
    private val onSurfaceReady: (Surface) -> Unit,
) : CardboardView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    @Volatile var format = VideoFormat()

    /** Ширина / высота всего видеокадра (с обоими глазами). */
    @Volatile var videoAspect = 16f / 9f

    private val meshes = mapOf(
        Projection.Flat to Meshes.quad(),
        Projection.Dome180 to Meshes.sphere(SPHERE_RADIUS, PI.toFloat()),
        Projection.Sphere360 to Meshes.sphere(SPHERE_RADIUS, 2 * PI.toFloat()),
    )

    private var program = 0
    private var aPosition = 0
    private var aUv = 0
    private var uMvp = 0
    private var uTexMatrix = 0
    private var uUvRect = 0
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private val frameAvailable = AtomicBoolean(false)
    private val recenterRequested = AtomicBoolean(true)

    private val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val headView = FloatArray(16)
    private var yawOffsetDeg = 0f
    private val model = FloatArray(16)
    private val viewModel = FloatArray(16)
    private val mvp = FloatArray(16)

    /** Повернуть сцену так, чтобы центр видео оказался там, куда сейчас смотрит голова (только по горизонтали). */
    fun recenter() = recenterRequested.set(true)

    override fun onSurfaceCreated(config: EGLConfig?) {
        program = GlUtil.program(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uUvRect = GLES20.glGetUniformLocation(program, "uUvRect")

        textureId = GlUtil.externalTexture()
        release()
        val texture = SurfaceTexture(textureId).also { it.setOnFrameAvailableListener(this) }
        surfaceTexture = texture
        surface = Surface(texture).also(onSurfaceReady)
    }

    override fun onSurfaceChanged(width: Int, height: Int) = Unit

    override fun onNewFrame(headTransform: HeadTransform) {
        surfaceTexture?.let { texture ->
            if (frameAvailable.getAndSet(false)) {
                texture.updateTexImage()
                texture.getTransformMatrix(texMatrix)
            }
        }

        headTransform.getHeadView(headView, 0)
        if (recenterRequested.getAndSet(false)) {
            // Направление взгляда в координатах сцены: третья строка матрицы вида со знаком минус.
            val forwardX = -headView[2]
            val forwardZ = -headView[10]
            yawOffsetDeg = Math.toDegrees(atan2(-forwardX, -forwardZ).toDouble()).toFloat()
        }
        modelMatrix(format)
    }

    override fun onDrawEye(eye: CardboardView.Eye) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        if (surfaceTexture == null) return

        val format = format
        val mesh = meshes.getValue(format.projection)

        // SDK сам не подставляет положение головы: без этого вызова матрица глаза нулевая и кадр пустой.
        eye.applyHeadView(headView)
        Matrix.multiplyMM(viewModel, 0, eye.eyeView, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, eye.getPerspective(Z_NEAR, Z_FAR), 0, viewModel, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        val rightEye = eye.eyeType == CardboardView.Eye.RIGHT
        when (format.stereo) {
            StereoLayout.Mono -> GLES20.glUniform4f(uUvRect, 0f, 0f, 1f, 1f)
            StereoLayout.SideBySide -> GLES20.glUniform4f(uUvRect, if (rightEye) 0.5f else 0f, 0f, 0.5f, 1f)
            // Левый глаз сверху; v = 0 внизу кадра.
            StereoLayout.TopBottom -> GLES20.glUniform4f(uUvRect, 0f, if (rightEye) 0f else 0.5f, 1f, 0.5f)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        val vertices = mesh.vertices
        vertices.position(0)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, Mesh.STRIDE_BYTES, vertices)
        GLES20.glEnableVertexAttribArray(aPosition)
        vertices.position(3)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, Mesh.STRIDE_BYTES, vertices)
        GLES20.glEnableVertexAttribArray(aUv)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aUv)
    }

    override fun onFinishFrame(viewport: Viewport) = Unit

    override fun onRendererShutdown() = release()

    private fun modelMatrix(format: VideoFormat) {
        Matrix.setRotateM(model, 0, yawOffsetDeg, 0f, 1f, 0f)
        if (format.projection != Projection.Flat) return
        val eyeAspect = when (format.stereo) {
            StereoLayout.Mono -> videoAspect
            StereoLayout.SideBySide -> videoAspect / 2
            StereoLayout.TopBottom -> videoAspect * 2
        }
        Matrix.translateM(model, 0, 0f, 0f, -FLAT_SCREEN_DISTANCE)
        Matrix.scaleM(model, 0, FLAT_SCREEN_HEIGHT * eyeAspect, FLAT_SCREEN_HEIGHT, 1f)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        frameAvailable.set(true)
    }

    fun release() {
        surface?.release()
        surfaceTexture?.release()
        surface = null
        surfaceTexture = null
    }

    private companion object {
        const val Z_NEAR = 0.1f
        const val Z_FAR = 100f
        const val SPHERE_RADIUS = 10f
        const val FLAT_SCREEN_DISTANCE = 3f
        const val FLAT_SCREEN_HEIGHT = 2f

        const val VERTEX_SHADER = """
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

        const val FRAGMENT_SHADER = """#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTexture;
varying vec2 vUv;
void main() {
    gl_FragColor = texture2D(uTexture, vUv);
}
"""
    }
}

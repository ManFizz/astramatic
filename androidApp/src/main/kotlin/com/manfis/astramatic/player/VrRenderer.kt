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
import com.manfis.astramatic.settings.AppSettings
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import java.io.File

/**
 * Рисует для каждого глаза видео (на сферу или плоскость) и панель интерфейса с курсором взгляда.
 * Трекинг головы, матрицы глаз и коррекцию линз даёт Cardboard SDK.
 * CardboardView создаёт контекст OpenGL ES 2.0, поэтому шейдеры на GLSL ES 1.00.
 */
class VrRenderer(
    /** Размер и расстояние плоского экрана, подсветка: читаются каждый кадр, меняются на лету. */
    private val settings: AppSettings,
    /** Своя панорама окружения. */
    environmentFile: File,
    /** Вызывается на GL-потоке, когда готова поверхность для декодера (в том числе после пересоздания контекста). */
    private val onVideoSurfaceReady: (Surface) -> Unit,
    /** То же для панели интерфейса: в эту поверхность рисует виртуальный дисплей. */
    private val onPanelSurfaceReady: (Surface) -> Unit,
    /** Поправка ухода поворота по компасу; null — компаса нет. */
    private val compass: CompassDrift? = null,
) : CardboardView.Renderer {

    @Volatile var format = VideoFormat()

    /** Ширина / высота всего видеокадра (с обоими глазами). */
    @Volatile var videoAspect = 16f / 9f

    /** Пока видео (или фото) не открыто, вокруг только окружение и панель. */
    @Volatile var hasVideo = false

    /** Приближение видео и окружения: 1 — как есть, больше — крупнее. Меню не приближается. */
    @Volatile var zoom = 1f

    @Volatile var panelVisible = false

    /** Настройка шлема: вокруг калибровочная комната, видео не показываем. */
    @Volatile var calibration = false

    /**
     * Какую часть виртуального дисплея показывать и где.
     * [visibleHeightPx] — сколько пикселей сверху дисплея видно, [width] — ширина в метрах,
     * [centerY] — высота центра относительно глаз в метрах.
     */
    enum class PanelLayout(val visibleHeightPx: Int, val width: Float, val centerY: Float) {
        Menu(PANEL_HEIGHT_PX, 2.2f, 0f),
        /** Полоска управления: верхние 360 px (240 dp при плотности панели), чуть ниже взгляда. */
        Controls(360, 2.0f, -0.6f);

        val height: Float get() = width * visibleHeightPx / PANEL_WIDTH_PX
        val visibleFraction: Float get() = visibleHeightPx.toFloat() / PANEL_HEIGHT_PX
    }

    @Volatile var panelLayout = PanelLayout.Menu

    /** Точка взгляда на панели в пикселях панели, null — взгляд мимо. Обновляется каждый кадр. */
    @Volatile var gaze: Gaze? = null
        private set

    data class Gaze(val x: Float, val y: Float)

    /** Заполнение кольца вокруг курсора при нажатии взглядом, 0..1. */
    @Volatile var dwellProgress = 0f

    /** Геометрия зависит от проекции и угла: строится один раз на сочетание (только на GL-потоке). */
    private val meshes = HashMap<Pair<Projection, Int>, Mesh>()
    private val quad = Meshes.quad()
    private val environment = EnvironmentLayer(environmentFile)

    /** Своя панорама окружения сменилась. */
    fun environmentChanged() = environment.customChanged()

    private var screenMesh: Mesh? = null
    private var screenKey: List<Float>? = null

    private fun meshFor(format: VideoFormat): Mesh {
        if (format.projection == Projection.Flat) return flatScreen(format)
        val fov = when (format.projection) {
            Projection.Dome180, Projection.Fisheye -> format.fovDeg
            else -> 0
        }
        return meshes.getOrPut(format.projection to fov) {
            when (format.projection) {
                Projection.Flat -> Meshes.quad()
                Projection.Dome180 -> Meshes.sphere(SPHERE_RADIUS, Math.toRadians(fov.toDouble()).toFloat())
                Projection.Sphere360 -> Meshes.sphere(SPHERE_RADIUS, 2 * PI.toFloat())
                Projection.Fisheye -> Meshes.fisheye(SPHERE_RADIUS, fov)
                Projection.Eac360 -> Meshes.eac(SPHERE_RADIUS)
            }
        }
    }

    /** Плоский экран в метрах: пересобирается, когда меняются размер, расстояние, изгиб или пропорции кадра. */
    private fun flatScreen(format: VideoFormat): Mesh {
        val eyeAspect = when (format.stereo) {
            StereoLayout.Mono -> videoAspect
            StereoLayout.SideBySide -> videoAspect / 2
            StereoLayout.TopBottom -> videoAspect * 2
        }
        val height = settings.screenHeight
        val key = listOf(height * eyeAspect, height, settings.screenDistance, settings.screenCurve)
        if (key != screenKey) {
            screenKey = key
            screenMesh = Meshes.screen(key[0], key[1], key[2], key[3])
        }
        return screenMesh!!
    }

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
    private var cursorProgress = 0

    private var uBrightness = 0

    private val recenterRequested = AtomicBoolean(true)
    private val centerOnPanelRequested = AtomicBoolean(false)
    private val placePanelRequested = AtomicBoolean(true)

    /**
     * Первые кадры после запуска трекинг головы ещё не определился с направлением. Пока он устаканивается,
     * панель не показываем (иначе она «тянется» за взглядом), а ставим и центрируем видео один раз — после.
     */
    private val settleFrames = AtomicInteger(SETTLE_FRAMES)

    private val headView = FloatArray(16)

    /** Во сколько раз меню дальше базовых [PANEL_DISTANCE] (и во столько же раз больше). */
    private var panelScale = 1f
    private var videoYawDeg = 0f
    private var videoPitchDeg = 0f
    private var panelYawDeg = 0f
    private var panelPitchDeg = 0f
    private var controlsFollowing = false
    private val videoModel = FloatArray(16)
    private val panelModel = FloatArray(16)
    private val cursorModel = FloatArray(16)
    private val viewModel = FloatArray(16)
    private val mvp = FloatArray(16)
    private val zoomed = FloatArray(16)
    private val environmentModel = FloatArray(16)
    private val panelRotation = FloatArray(16)
    private val rotation = FloatArray(16)
    private val direction = FloatArray(4)
    private val localDirection = FloatArray(4)

    /**
     * Повернуть видео так, чтобы его центр оказался там, куда сейчас смотрит голова:
     * по горизонтали, а в режиме «лёжа» — и по вертикали.
     */
    fun recenter() = recenterRequested.set(true)

    /** Повернуть видео к панели: файл выбран в меню — пусть начнётся там, где меню. */
    fun centerOnPanel() = centerOnPanelRequested.set(true)

    /** Поставить панель перед взглядом. */
    fun placePanelInFront() = placePanelRequested.set(true)

    /** Трекинг ещё устаканивается после запуска: панель не видна. */
    private val settling get() = settleFrames.get() > 0

    override fun onSurfaceCreated(config: EGLConfig?) {
        textureProgram = GlUtil.program(TEXTURE_VERTEX_SHADER, TEXTURE_FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(textureProgram, "aPosition")
        aUv = GLES20.glGetAttribLocation(textureProgram, "aUv")
        uMvp = GLES20.glGetUniformLocation(textureProgram, "uMvp")
        uTexMatrix = GLES20.glGetUniformLocation(textureProgram, "uTexMatrix")
        uUvRect = GLES20.glGetUniformLocation(textureProgram, "uUvRect")
        uBrightness = GLES20.glGetUniformLocation(textureProgram, "uBrightness")

        cursorProgram = GlUtil.program(CURSOR_VERTEX_SHADER, CURSOR_FRAGMENT_SHADER)
        cursorPosition = GLES20.glGetAttribLocation(cursorProgram, "aPosition")
        cursorUv = GLES20.glGetAttribLocation(cursorProgram, "aUv")
        cursorMvp = GLES20.glGetUniformLocation(cursorProgram, "uMvp")
        cursorProgress = GLES20.glGetUniformLocation(cursorProgram, "uProgress")

        environment.create()

        onVideoSurfaceReady(video.create())
        onPanelSurfaceReady(panel.create(PANEL_WIDTH_PX, PANEL_HEIGHT_PX))
    }

    override fun onSurfaceChanged(width: Int, height: Int) = Unit

    override fun onNewFrame(headTransform: HeadTransform) {
        video.update()
        panel.update()

        headTransform.getHeadView(headView, 0)
        correctDrift()
        // Направление взгляда в мире: третья строка матрицы вида со знаком минус.
        direction[0] = -headView[2]
        direction[1] = -headView[6]
        direction[2] = -headView[10]
        direction[3] = 0f
        val lookYawDeg = Math.toDegrees(atan2(-direction[0], -direction[2]).toDouble()).toFloat()
        val lookPitchDeg = if (settings.recenterPitch) {
            Math.toDegrees(asin(direction[1].coerceIn(-1f, 1f).toDouble())).toFloat()
        } else {
            0f
        }

        if (!lookYawDeg.isNaN()) {
            if (settling) {
                settleFrames.decrementAndGet()
                if (recenterRequested.get()) {
                    videoYawDeg = lookYawDeg
                    videoPitchDeg = lookPitchDeg
                }
            } else if (recenterRequested.getAndSet(false)) {
                videoYawDeg = lookYawDeg
                videoPitchDeg = lookPitchDeg
            }
            if (settling || placePanelRequested.getAndSet(false)) {
                panelYawDeg = lookYawDeg
                panelPitchDeg = lookPitchDeg
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

        if (!settling && centerOnPanelRequested.getAndSet(false)) {
            videoYawDeg = panelYawDeg
            videoPitchDeg = panelPitchDeg
        }

        updateVideoModel(format)
        val layout = panelLayout
        Matrix.setRotateM(panelRotation, 0, panelYawDeg, 0f, 1f, 0f)
        if (panelPitchDeg != 0f) Matrix.rotateM(panelRotation, 0, panelPitchDeg, 1f, 0f, 0f)
        // Меню дальше — то же по видимому размеру, но глазам меньше сводиться при переходе от видео к меню.
        val scale = settings.menuDistance.coerceIn(MIN_MENU_DISTANCE, MAX_MENU_DISTANCE) / PANEL_DISTANCE
        panelScale = scale
        Matrix.translateM(panelModel, 0, panelRotation, 0, 0f, layout.centerY * scale, -PANEL_DISTANCE * scale)
        Matrix.scaleM(panelModel, 0, layout.width * scale, layout.height * scale, 1f)

        gaze = if (panelVisible && !settling) computeGaze(layout) else null
    }

    /**
     * Гироскоп со временем уводит поворот в сторону — поворачиваем мир обратно на оценку ухода по компасу.
     * Дальше всё (взгляд, глаза) считается уже по исправленному виду.
     */
    private fun correctDrift() {
        val compass = compass ?: return
        if (!settings.compassCorrection) {
            compass.reset()
            return
        }
        val drift = compass.update(headView)
        if (drift != 0f) Matrix.rotateM(headView, 0, drift, 0f, 1f, 0f)
    }

    private fun wrapDegrees(deg: Float): Float = ((deg + 540f) % 360f) - 180f

    /** Пересечение луча взгляда с плоскостью панели, в координатах панели. */
    private fun computeGaze(layout: PanelLayout): Gaze? {
        // Поворот панели — чистое вращение, обратное к нему — транспонированное.
        Matrix.transposeM(rotation, 0, panelRotation, 0)
        Matrix.multiplyMV(localDirection, 0, rotation, 0, direction, 0)
        val dz = localDirection[2]
        if (dz > -0.01f) return null
        val s = panelScale
        val t = -PANEL_DISTANCE * s / dz
        val u = localDirection[0] * t / (layout.width * s) + 0.5f
        val v = 0.5f - (localDirection[1] * t - layout.centerY * s) / (layout.height * s)
        if (u !in 0f..1f || v !in 0f..1f) return null

        Matrix.translateM(
            cursorModel, 0, panelRotation, 0,
            (u - 0.5f) * layout.width * s, ((0.5f - v) * layout.height + layout.centerY) * s, (-PANEL_DISTANCE + CURSOR_LIFT) * s,
        )
        Matrix.scaleM(cursorModel, 0, CURSOR_SIZE * s, CURSOR_SIZE * s, 1f)
        return Gaze(u * PANEL_WIDTH_PX, v * layout.visibleHeightPx)
    }

    override fun onDrawEye(eye: CardboardView.Eye) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        // SDK сам не подставляет положение головы: без этого вызова матрица глаза нулевая и кадр пустой.
        eye.applyHeadView(headView)
        val projection = eye.getPerspective(Z_NEAR, Z_FAR)
        // Приближение — сужение поля зрения: масштаб x и y проекции.
        projection.copyInto(zoomed)
        zoomed[0] *= zoom
        zoomed[5] *= zoom

        drawEnvironment(eye)
        if (hasVideo && !calibration) drawVideo(eye, zoomed)

        if (panelVisible && panel.isReady && !settling) {
            GLES20.glEnable(GLES20.GL_BLEND)
            // Кадры виртуального дисплея с премультиплицированной альфой (прозрачные углы панели).
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            // Верхняя часть кадра дисплея; v = 0 внизу текстуры.
            val visible = panelLayout.visibleFraction
            GLES20.glUseProgram(textureProgram)
            GLES20.glUniform4f(uUvRect, 0f, 1f - visible, 1f, visible)
            GLES20.glUniform1f(uBrightness, 1f)
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
        val rect = eyeRect(format.stereo, eye.eyeType == CardboardView.Eye.RIGHT)
        GLES20.glUseProgram(textureProgram)
        GLES20.glUniform4f(uUvRect, rect[0], rect[1], rect[2], rect[3])
        GLES20.glUniform1f(uBrightness, settings.brightness)
        drawTextured(
            video, meshFor(format), eye, projection, videoModel, setUvRect = false,
            atInfinity = format.projection != Projection.Flat,
        )
    }

    /** Окружение не рисуем там, где его всё равно целиком закроет видео 360°. */
    private fun drawEnvironment(eye: CardboardView.Eye) {
        if (calibration) {
            drawCalibration(eye)
            return
        }
        val format = format
        val covered = hasVideo && video.isReady &&
            (format.projection == Projection.Sphere360 || format.projection == Projection.Eac360)
        if (covered) return
        val mode = Environment.entries.find { it.name == settings.environment } ?: Environment.Black
        val rect = eyeRect(format.stereo, eye.eyeType == CardboardView.Eye.RIGHT)
        // Окружение стоит вокруг зрителя вслед за видео (кинозал — «за экраном»), но без наклона самого видео.
        Matrix.setRotateM(environmentModel, 0, videoYawDeg, 0f, 1f, 0f)
        if (videoPitchDeg != 0f) Matrix.rotateM(environmentModel, 0, videoPitchDeg, 1f, 0f, 0f)
        // Без переноса головы: окружение «на бесконечности», к нему не подойти.
        eye.eyeView.copyInto(viewModel)
        viewModel[12] = 0f
        viewModel[13] = 0f
        viewModel[14] = 0f
        Matrix.multiplyMM(rotation, 0, viewModel, 0, environmentModel, 0)
        Matrix.multiplyMM(mvp, 0, zoomed, 0, rotation, 0)
        val tint = settings.ambientLight && hasVideo && video.isReady && format.projection == Projection.Flat
        environment.draw(mode, mvp, video.id, video.transform, rect, tint)
    }

    /** Комната стоит передней стеной к меню и без приближения: так честно видны линзы. */
    private fun drawCalibration(eye: CardboardView.Eye) {
        Matrix.setRotateM(environmentModel, 0, panelYawDeg, 0f, 1f, 0f)
        eye.eyeView.copyInto(viewModel)
        viewModel[12] = 0f
        viewModel[13] = 0f
        viewModel[14] = 0f
        Matrix.multiplyMM(rotation, 0, viewModel, 0, environmentModel, 0)
        Matrix.multiplyMM(mvp, 0, eye.getPerspective(Z_NEAR, Z_FAR), 0, rotation, 0)
        environment.drawCalibration(mvp, video.id, video.transform, floatArrayOf(0f, 0f, 1f, 1f))
    }

    /** Часть кадра для глаза: смещение и размер по u и v. Левый глаз сверху; v = 0 внизу кадра. */
    private fun eyeRect(stereo: StereoLayout, rightEye: Boolean): FloatArray = when (stereo) {
        StereoLayout.Mono -> floatArrayOf(0f, 0f, 1f, 1f)
        StereoLayout.SideBySide -> floatArrayOf(if (rightEye) 0.5f else 0f, 0f, 0.5f, 1f)
        StereoLayout.TopBottom -> floatArrayOf(0f, if (rightEye) 0f else 0.5f, 1f, 0.5f)
    }

    private fun drawTextured(
        texture: ExternalTexture,
        mesh: Mesh,
        eye: CardboardView.Eye,
        projection: FloatArray,
        model: FloatArray,
        setUvRect: Boolean = true,
        /** Без сдвига глаз: для видео на сфере, у которого своя глубина. */
        atInfinity: Boolean = false,
    ) {
        if (atInfinity) {
            eye.eyeView.copyInto(rotation)
            rotation[12] = 0f
            rotation[13] = 0f
            rotation[14] = 0f
            Matrix.multiplyMM(viewModel, 0, rotation, 0, model, 0)
        } else {
            Matrix.multiplyMM(viewModel, 0, eye.eyeView, 0, model, 0)
        }
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
        GLES20.glUniform1f(cursorProgress, dwellProgress)
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

    /** Плоский экран уже в метрах (см. [flatScreen]), здесь только поворот: рецентр и наклон. */
    private fun updateVideoModel(format: VideoFormat) {
        Matrix.setRotateM(videoModel, 0, videoYawDeg, 0f, 1f, 0f)
        if (videoPitchDeg != 0f) Matrix.rotateM(videoModel, 0, videoPitchDeg, 1f, 0f, 0f)
        if (format.tiltDeg != 0) Matrix.rotateM(videoModel, 0, format.tiltDeg.toFloat(), 1f, 0f, 0f)
    }

    /**
     * Размер буфера кадра: декодер видео задаёт его сам, а для фото, которое рисуется
     * в ту же поверхность холстом, его нужно указать.
     */
    fun setVideoBufferSize(width: Int, height: Int) = video.setBufferSize(width, height)

    /**
     * Новая поверхность для того же кадра. Поверхность помнит, кто в неё рисует (декодер или холст),
     * и второго не пустит — при смене видео ⇄ фото берём свежую.
     */
    fun newVideoSurface(): Surface? = video.newSurface()

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

        @Synchronized
        fun setBufferSize(width: Int, height: Int) {
            surfaceTexture?.setDefaultBufferSize(width, height)
        }

        @Synchronized
        fun newSurface(): Surface? {
            val texture = surfaceTexture ?: return null
            surface?.release()
            return Surface(texture).also { surface = it }
        }

        @Synchronized
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

        @Synchronized
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
        const val MIN_MENU_DISTANCE = 1.5f
        const val MAX_MENU_DISTANCE = 12f
        private const val SETTLE_FRAMES = 45
        /** Полоска шириной ±20° от центра; дальше 30° — уже точно смотрят не на неё. */
        private const val CONTROLS_FOLLOW_DEG = 30f
        private const val CONTROLS_FOLLOW_SPEED = 0.08f
        private const val CURSOR_SIZE = 0.05f
        private const val CURSOR_LIFT = 0.01f

        private const val Z_NEAR = 0.1f
        private const val Z_FAR = 100f
        private const val SPHERE_RADIUS = 10f
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
uniform float uBrightness;
varying vec2 vUv;
void main() {
    vec4 c = texture2D(uTexture, vUv);
    gl_FragColor = vec4(c.rgb * uBrightness, c.a);
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
        // При нажатии взглядом обводка заполняется по часовой стрелке от верха.
        private const val CURSOR_FRAGMENT_SHADER = """
precision mediump float;
uniform float uProgress;
varying vec2 vUv;
void main() {
    vec2 p = vUv - 0.5;
    float d = length(p);
    if (d > 0.5) discard;
    if (d <= 0.32) {
        gl_FragColor = vec4(1.0, 1.0, 1.0, 1.0);
        return;
    }
    float angle = fract(atan(p.x, p.y) / 6.2831853 + 1.0);
    gl_FragColor = angle < uProgress ? vec4(0.6, 0.45, 1.0, 1.0) : vec4(0.0, 0.0, 0.0, 0.7);
}
"""
    }
}

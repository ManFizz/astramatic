package com.manfis.astramatic.player

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.SystemClock
import android.view.Surface
import com.google.cardboard.sdk.CardboardView
import com.google.cardboard.sdk.HeadTransform
import com.google.cardboard.sdk.Viewport
import com.manfis.astramatic.BuildConfig
import com.manfis.astramatic.model.Projection
import com.manfis.astramatic.model.StereoLayout
import com.manfis.astramatic.model.VideoFormat
import com.manfis.astramatic.settings.AppSettings
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.tan
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    /** Меню растянули за уголок и отпустили (GL-поток): новый размер — запомнить. */
    onMenuSizeChanged: (Float) -> Unit = {},
) : CardboardView.Renderer, VrScene {

    /** Положение видео и панели, попадание взгляда: всё, что не про рисование. */
    private val tracker = PanelTracker(
        prefs = object : PanelPreferences {
            override val recenterPitch get() = settings.recenterPitch
            override val menuDistance get() = settings.menuDistance
            override val menuSize get() = settings.menuSize
        },
        onMenuSizeChanged = onMenuSizeChanged,
    )

    @Volatile override var format = VideoFormat()

    /** Ширина / высота всего видеокадра (с обоими глазами). */
    @Volatile var videoAspect = 16f / 9f

    /** Пока видео (или фото) не открыто, вокруг только окружение и панель. */
    @Volatile override var hasVideo = false

    /** Приближение видео и окружения: 1 — как есть, больше — крупнее. Меню не приближается. */
    @Volatile override var zoom = 1f

    override var panelVisible by tracker::panelVisible

    /** Настройка шлема: вокруг калибровочная комната, видео не показываем. */
    @Volatile override var calibration = false

    override var panelLayout by tracker::layout

    /** Точка взгляда на панели в пикселях панели, null — взгляд мимо. Обновляется каждый кадр. */
    override val gaze: Gaze? get() = tracker.gaze

    /** Что из [Handle] сейчас под взглядом. Обновляется каждый кадр. */
    override val hoverHandle: Handle get() = tracker.hoverHandle

    /** Взялись (из UI-потока): что держат; [Handle.None] — отпустили. */
    override var grab by tracker::grab

    /** Сколько раз просили рецентр: видно в отладочной команде. */
    @Volatile private var recenterCount = 0

    /** Заполнение кольца вокруг курсора при нажатии взглядом, 0..1. */
    @Volatile override var dwellProgress = 0f

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
        val height = settings.screenHeight
        val key = listOf(height * eyeAspect(format), height, settings.screenDistance, settings.screenCurve)
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
    private var uAlpha = 0

    private var sideProgram = 0
    private var sidePosition = 0
    private var sideUv = 0
    private var sideMvp = 0
    private var sideAlphaUniform = 0
    private val sideTextures = IntArray(2)
    private var sideUploaded: SideImages? = null
    private var sideLayoutKey: List<Float>? = null
    private var sideLayoutCached: SideLayout? = null
    private var sideAlpha = 0f
    private val sideModel = FloatArray(16)

    /** Вид глаза для панели: сдвиг глаз подобран так, чтобы сводить их как на видео. */
    private val panelEyeView = FloatArray(16)
    private val sideEyeView = FloatArray(16)

    private val hud = HudLayer()
    private val hudView = FloatArray(16)
    private var frameDt = 0f
    private val screenBase = FloatArray(16)
    private val sideBase = FloatArray(16)

    private val depth = PanelDepth()
    private var depthProbeAt = 0L
    private var probeFramebuffer = 0
    private var probeTexture = 0
    private val probePixels: ByteBuffer =
        ByteBuffer.allocateDirect(PROBE_WIDTH * 2 * PROBE_HEIGHT * 4).order(ByteOrder.nativeOrder())
    private val probeLeft = ByteArray(PROBE_WIDTH * PROBE_HEIGHT)
    private val probeRight = ByteArray(PROBE_WIDTH * PROBE_HEIGHT)
    private val probeProjection = FloatArray(16)
    private val probeRotation = FloatArray(16)
    private val probeView = FloatArray(16)

    private var shapeProgram = 0
    private var shapePosition = 0
    private var shapeUv = 0
    private var shapeMvp = 0
    private var shapeSize = 0
    private var shapeColor = 0
    private val shapeModel = FloatArray(16)

    private val headView = FloatArray(16)

    /** Проявление панели 0..1: появляется плавно, а не выскакивает. */
    private var appear = 0f
    private var lastFrameNanos = 0L
    private var lastLayout = PanelLayout.Menu
    private val videoModel = FloatArray(16)
    private val panelModel = FloatArray(16)
    private val cursorModel = FloatArray(16)
    private val viewModel = FloatArray(16)
    private val mvp = FloatArray(16)
    private val zoomed = FloatArray(16)
    private val environmentModel = FloatArray(16)
    private val panelRotation = FloatArray(16)
    private val rotation = FloatArray(16)

    /**
     * Повернуть видео так, чтобы его центр оказался там, куда сейчас смотрит голова:
     * по горизонтали, а в режиме «лёжа» — и по вертикали.
     */
    override fun recenter() {
        recenterCount++
        tracker.recenter()
        hud.flash()
    }

    override fun showHud(bar: HudBar) = hud.show(bar)

    override fun showRecenterRing(durationMs: Long?) = hud.ring(durationMs)

    /** Повернуть видео к панели: файл выбран в меню — пусть начнётся там, где меню. */
    override fun centerOnPanel() = tracker.centerOnPanel()

    /** Поставить панель в следующем кадре. */
    override fun placePanel(placement: PanelPlacement) = tracker.placePanel(placement)

    override val hoverSide: SideSlot? get() = tracker.hoverSide

    /** Включён VR, а не плоский показ на экране телефона: соседние окна есть только в шлеме. */
    @Volatile var stereoView = true

    /** Картинки соседних окон плоского экрана; null — окон нет. */
    @Volatile var sideImages: SideImages? = null

    /** Трекинг ещё устаканивается после запуска: панель не видна. */
    private val settling get() = tracker.settling

    override fun onSurfaceCreated(config: EGLConfig?) {
        textureProgram = GlUtil.program(TEXTURE_VERTEX_SHADER, TEXTURE_FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(textureProgram, "aPosition")
        aUv = GLES20.glGetAttribLocation(textureProgram, "aUv")
        uMvp = GLES20.glGetUniformLocation(textureProgram, "uMvp")
        uTexMatrix = GLES20.glGetUniformLocation(textureProgram, "uTexMatrix")
        uUvRect = GLES20.glGetUniformLocation(textureProgram, "uUvRect")
        uBrightness = GLES20.glGetUniformLocation(textureProgram, "uBrightness")
        uAlpha = GLES20.glGetUniformLocation(textureProgram, "uAlpha")

        shapeProgram = GlUtil.program(CURSOR_VERTEX_SHADER, SHAPE_FRAGMENT_SHADER)
        shapePosition = GLES20.glGetAttribLocation(shapeProgram, "aPosition")
        shapeUv = GLES20.glGetAttribLocation(shapeProgram, "aUv")
        shapeMvp = GLES20.glGetUniformLocation(shapeProgram, "uMvp")
        shapeSize = GLES20.glGetUniformLocation(shapeProgram, "uSize")
        shapeColor = GLES20.glGetUniformLocation(shapeProgram, "uColor")

        cursorProgram = GlUtil.program(CURSOR_VERTEX_SHADER, CURSOR_FRAGMENT_SHADER)
        cursorPosition = GLES20.glGetAttribLocation(cursorProgram, "aPosition")
        cursorUv = GLES20.glGetAttribLocation(cursorProgram, "aUv")
        cursorMvp = GLES20.glGetUniformLocation(cursorProgram, "uMvp")
        cursorProgress = GLES20.glGetUniformLocation(cursorProgram, "uProgress")

        sideProgram = GlUtil.program(CURSOR_VERTEX_SHADER, SIDE_FRAGMENT_SHADER)
        sidePosition = GLES20.glGetAttribLocation(sideProgram, "aPosition")
        sideUv = GLES20.glGetAttribLocation(sideProgram, "aUv")
        sideMvp = GLES20.glGetUniformLocation(sideProgram, "uMvp")
        sideAlphaUniform = GLES20.glGetUniformLocation(sideProgram, "uAlpha")
        GLES20.glGenTextures(2, sideTextures, 0)
        sideUploaded = null
        createProbe()
        hud.create()

        environment.create()

        onVideoSurfaceReady(video.create())
        onPanelSurfaceReady(panel.create(PANEL_WIDTH_PX, PANEL_HEIGHT_PX))
    }

    override fun onSurfaceChanged(width: Int, height: Int) = Unit

    override fun onNewFrame(headTransform: HeadTransform) {
        video.update()
        panel.update()

        headTransform.getHeadView(headView, 0)
        if (!(BuildConfig.DEBUG && applyDebugLook())) correctDrift()
        tracker.sideLayout = currentSideLayout()
        tracker.screenGrip = currentScreenGrip()
        // Направление взгляда в мире: третья строка матрицы вида со знаком минус.
        tracker.update(-headView[2], -headView[6], -headView[10])

        updateVideoModel(format)
        val layout = panelLayout
        updateAppear(layout)
        depth.step(frameDt)
        hud.advance(frameDt)
        Matrix.setRotateM(panelRotation, 0, tracker.panelYawDeg, 0f, 1f, 0f)
        if (tracker.panelPitchDeg != 0f) Matrix.rotateM(panelRotation, 0, tracker.panelPitchDeg, 1f, 0f, 0f)
        // Появляясь, панель чуть «подплывает»: из 97% размера в 100%.
        val pop = 0.97f + 0.03f * ease(appear)
        Matrix.translateM(panelModel, 0, panelRotation, 0, 0f, tracker.panelCy, tracker.panelZ)
        Matrix.scaleM(panelModel, 0, tracker.panelW * pop, tracker.panelH * pop, 1f)

        measureDepth()

        val interactive = panelVisible && !settling
        if (interactive && !tracker.hitX.isNaN()) {
            val scale = tracker.panelScale
            Matrix.translateM(cursorModel, 0, panelRotation, 0, tracker.hitX, tracker.hitY, tracker.panelZ + CURSOR_LIFT * scale)
            Matrix.scaleM(cursorModel, 0, CURSOR_SIZE * scale, CURSOR_SIZE * scale, 1f)
        }
    }

    private fun updateAppear(layout: PanelLayout) {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / 1e9f).coerceAtMost(0.1f)
        lastFrameNanos = now
        frameDt = dt
        // Смена раскладки (полоска ⇄ меню) — короткое проявление заново, без мигания в ноль.
        if (layout != lastLayout) {
            lastLayout = layout
            appear = appear.coerceAtMost(0.4f)
        }
        appear = if (panelVisible && !settling) (appear + dt / APPEAR_S).coerceAtMost(1f) else 0f
        val sideStep = dt / SIDE_FADE_S
        sideAlpha = if (tracker.sideWanted) (sideAlpha + sideStep).coerceAtMost(1f) else (sideAlpha - sideStep).coerceAtLeast(0f)
    }

    private fun ease(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

    /** Взгляд, заданный командой, вместо трекинга: без него в эмуляторе нельзя точно навести. Только debug. */
    @Volatile var debugLook: DebugLook? = null

    /** true — вид головы задан командой, поправка по компасу не нужна. */
    private fun applyDebugLook(): Boolean {
        val look = when (val requested = debugLook ?: return false) {
            is DebugLook.Angles -> requested
            // Точка панели переводится в углы один раз: сама панель потом может сдвинуться или вырасти.
            is DebugLook.PanelPoint -> tracker.anglesForPanelPoint(requested.x, requested.y)
                .let { (yaw, pitch) -> DebugLook.Angles(yaw, pitch) }
                .also { debugLook = it }
        }
        // Вид — обратный поворот головы: сначала рысканье назад, потом наклон.
        Matrix.setRotateM(headView, 0, -look.pitchDeg, 1f, 0f, 0f)
        Matrix.rotateM(headView, 0, -look.yawDeg, 0f, 1f, 0f)
        return true
    }

    /** Состояние для отладочной команды. */
    fun debugState(): String = tracker.describe() + " recenters=$recenterCount" +
        " hasVideo=$hasVideo side=${tracker.hoverSide} sideWanted=${tracker.sideWanted}" +
        " disparityDeg=${"%.2f".format(java.util.Locale.ROOT, Math.toDegrees(depth.radians.toDouble()))} measured=${depth.measured}"

    /**
     * Гироскоп со временем уводит поворот в сторону — поворачиваем мир обратно на оценку ухода по компасу.
     * Дальше всё (взгляд, глаза) считается уже по исправленному виду.
     */
    private fun correctDrift() {
        val drift = compass?.correction(headView, settings.compassCorrection) ?: return
        if (drift != 0f) Matrix.rotateM(headView, 0, drift, 0f, 1f, 0f)
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
        drawSideCards(eye)
        drawScreenGrip(eye)

        if (panelVisible && panel.isReady && !settling) {
            fillDepthView(eye, -tracker.panelZ, panelEyeView)
            GLES20.glEnable(GLES20.GL_BLEND)
            // Кадры виртуального дисплея с премультиплицированной альфой (прозрачные углы панели).
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            // Верхняя часть кадра дисплея; v = 0 внизу текстуры.
            val visible = panelLayout.visibleFraction
            GLES20.glUseProgram(textureProgram)
            GLES20.glUniform4f(uUvRect, 0f, 1f - visible, 1f, visible)
            GLES20.glUniform1f(uBrightness, 1f)
            GLES20.glUniform1f(uAlpha, ease(appear))
            Matrix.multiplyMM(viewModel, 0, panelEyeView, 0, panelModel, 0)
            Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0)
            drawPrepared(panel, quad, setUvRect = false)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            val handle = hoverHandle
            val onPanel = gaze != null || handle.resizes || handle == Handle.Move
            if (onPanel) drawHandles(projection, handle)
            if (onPanel) drawCursor(eye, projection)
            GLES20.glDisable(GLES20.GL_BLEND)
        }
        drawHud(eye, projection)
    }

    /** То, что движется с головой. Глаза сводятся как на видео, поэтому при взгляде на него не двоится. */
    private fun drawHud(eye: CardboardView.Eye, projection: FloatArray) {
        Matrix.setIdentityM(hudView, 0)
        val eyeFromHead = eye.eyeFromHead
        val scale = depthScale(eye, HudLayer.DISTANCE)
        hudView[12] = eyeFromHead[12] * scale
        hudView[13] = eyeFromHead[13] * scale
        hudView[14] = eyeFromHead[14] * scale
        GLES20.glEnable(GLES20.GL_BLEND)
        hud.draw(hudView, projection)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Плоский экран можно взять за ручку под ним. При приближении её положение не совпало бы со взглядом. */
    private fun currentScreenGrip(): ScreenGrip? {
        val format = format
        if (!hasVideo || calibration || !stereoView || format.projection != Projection.Flat || zoom != 1f) return null
        return ScreenGrip(settings.screenHeight / 2, settings.screenDistance)
    }

    private fun drawScreenGrip(eye: CardboardView.Eye) {
        val grip = tracker.screenGrip ?: return
        val active = tracker.hoverHandle == Handle.Screen
        if (!active && !tracker.screenGripWanted) return
        fillDepthView(eye, grip.distance, sideEyeView)
        Matrix.setRotateM(screenBase, 0, tracker.videoYawDeg, 0f, 1f, 0f)
        val pitch = tracker.screenPitchDeg
        if (pitch != 0f) Matrix.rotateM(screenBase, 0, pitch, 1f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(shapeProgram)
        val alpha = if (active) HANDLE_ACTIVE_ALPHA else HANDLE_IDLE_ALPHA
        val thickness = grip.distance * SideLayout.GRIP_THICKNESS_PER_DISTANCE
        drawShape(zoomed, screenBase, sideEyeView, 0f, grip.y, -grip.distance, grip.width, thickness, alpha)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * Уголки-скобки снаружи углов меню и полоска-ручка под ним. Видны, пока смотрят на меню;
     * то, что под взглядом или в руке, — ярче.
     */
    private fun drawHandles(projection: FloatArray, hovered: Handle) {
        GLES20.glUseProgram(shapeProgram)
        val s = tracker.panelScale
        val fade = ease(appear)
        val panelW = tracker.panelW
        val panelCy = tracker.panelCy
        val halfW = panelW / 2
        val halfH = tracker.panelH / 2
        val out = PanelTracker.CORNER_OFFSET * s
        val arm = PanelTracker.CORNER_ARM * s
        val thick = PanelTracker.HANDLE_THICKNESS * s
        val corners = listOf(
            Handle.TopLeft to (-1f to 1f), Handle.TopRight to (1f to 1f),
            Handle.BottomLeft to (-1f to -1f), Handle.BottomRight to (1f to -1f),
        )
        val z = tracker.panelZ + CURSOR_LIFT * s / 2
        for ((handle, signs) in corners) {
            if (panelLayout != PanelLayout.Menu) break
            val (sx, sy) = signs
            val alpha = (if (hovered == handle) HANDLE_ACTIVE_ALPHA else HANDLE_IDLE_ALPHA) * fade
            val px = sx * (halfW + out)
            val py = panelCy + sy * (halfH + out)
            // Буква «Г» в каждом углу: плечо вдоль края и плечо вниз/вверх.
            drawShape(projection, panelRotation, panelEyeView, px - sx * (arm - thick) / 2, py, z, arm, thick, alpha)
            drawShape(projection, panelRotation, panelEyeView, px, py - sy * (arm - thick) / 2, z, thick, arm, alpha)
        }
        val barAlpha = (if (hovered == Handle.Move) HANDLE_ACTIVE_ALPHA else HANDLE_IDLE_ALPHA) * fade
        drawShape(projection, panelRotation, panelEyeView, 0f, panelCy - halfH - PanelTracker.BAR_GAP * s, z, panelW * PanelTracker.BAR_WIDTH, thick * 1.4f, barAlpha)
    }

    /** Скруглённая белая плашка: [base] — поворот окна, [eyeView] — вид глаза, центр и размер в метрах. */
    private fun drawShape(
        projection: FloatArray,
        base: FloatArray,
        eyeView: FloatArray,
        cx: Float,
        cy: Float,
        z: Float,
        w: Float,
        h: Float,
        alpha: Float,
    ) {
        Matrix.translateM(shapeModel, 0, base, 0, cx, cy, z)
        Matrix.scaleM(shapeModel, 0, w, h, 1f)
        Matrix.multiplyMM(viewModel, 0, eyeView, 0, shapeModel, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0)
        GLES20.glUniformMatrix4fv(shapeMvp, 1, false, mvp, 0)
        GLES20.glUniform2f(shapeSize, w, h)
        GLES20.glUniform4f(shapeColor, 1f, 1f, 1f, alpha)
        drawMesh(quad, shapePosition, shapeUv)
    }

    private fun drawVideo(eye: CardboardView.Eye, projection: FloatArray) {
        if (!video.isReady) return
        val format = format
        val rect = eyeRect(format.stereo, eye.eyeType == CardboardView.Eye.RIGHT)
        GLES20.glUseProgram(textureProgram)
        GLES20.glUniform1f(uAlpha, 1f)
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
        Matrix.setRotateM(environmentModel, 0, tracker.videoYawDeg, 0f, 1f, 0f)
        if (tracker.videoPitchDeg != 0f) Matrix.rotateM(environmentModel, 0, tracker.videoPitchDeg, 1f, 0f, 0f)
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
        Matrix.setRotateM(environmentModel, 0, tracker.panelYawDeg, 0f, 1f, 0f)
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
        drawPrepared(texture, mesh, setUvRect)
    }

    /** Рисует с уже посчитанной матрицей [mvp]. */
    private fun drawPrepared(texture: ExternalTexture, mesh: Mesh, setUvRect: Boolean) {
        GLES20.glUseProgram(textureProgram)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texture.transform, 0)
        if (setUvRect) GLES20.glUniform4f(uUvRect, 0f, 0f, 1f, 1f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture.id)
        drawMesh(mesh, aPosition, aUv)
    }

    /**
     * Вид глаза для предмета на расстоянии [distance]: сдвиг глаз масштабируется так, чтобы сводить их
     * как на видео. Размер предмета на глаз не меняется, а в 2D глаз один и менять нечего.
     */
    private fun fillDepthView(eye: CardboardView.Eye, distance: Float, out: FloatArray) {
        eye.eyeView.copyInto(out)
        val scale = depthScale(eye, distance)
        out[12] *= scale
        out[13] *= scale
        out[14] *= scale
    }

    /** Во сколько раз изменить сдвиг глаз для предмета на расстоянии [distance]. */
    private fun depthScale(eye: CardboardView.Eye, distance: Float): Float {
        val eyeOffset = abs(eye.eyeFromHead[12])
        if (!settings.menuDepthAuto || eyeOffset < 0.0001f) return 1f
        val eyeSeparation = 2 * eyeOffset
        val onVideo = hasVideo && !calibration
        // Окружение и сфера — на бесконечности, плоский экран — на своём расстоянии, стерео добавляет сдвиг из кадра.
        val geometric = if (onVideo && format.projection == Projection.Flat) eyeSeparation / settings.screenDistance else 0f
        val measured = if (onVideo && format.stereo != StereoLayout.Mono) depth.radians else 0f
        val vergence = ((geometric + measured) * zoom).coerceAtLeast(0f)
        return vergence * distance / eyeSeparation
    }

    private fun eyeAspect(format: VideoFormat) = when (format.stereo) {
        StereoLayout.Mono -> videoAspect
        StereoLayout.SideBySide -> videoAspect / 2
        StereoLayout.TopBottom -> videoAspect * 2
    }

    /** Соседние окна есть у плоского экрана в шлеме. Пересчитываются, только когда меняется сам экран. */
    private fun currentSideLayout(): SideLayout? {
        val format = format
        // При приближении положение окон не совпало бы со взглядом, да и экран тогда занимает всё поле зрения.
        if (sideImages == null || !hasVideo || calibration || !stereoView || format.projection != Projection.Flat || zoom != 1f) return null
        val key = listOf(settings.screenHeight * eyeAspect(format), settings.screenDistance, settings.screenCurve)
        if (key != sideLayoutKey) {
            sideLayoutKey = key
            sideLayoutCached = SideLayout.of(key[0], key[1], key[2])
        }
        return sideLayoutCached
    }

    private fun drawSideCards(eye: CardboardView.Eye) {
        val side = tracker.sideLayout ?: return
        val images = sideImages ?: return
        if (sideAlpha <= 0f) return
        if (images !== sideUploaded) {
            uploadSide(sideTextures[0], images.left)
            uploadSide(sideTextures[1], images.right)
            sideUploaded = images
        }
        fillDepthView(eye, side.distance, sideEyeView)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(sideProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        for (slot in SideSlot.entries) {
            val hovered = tracker.hoverSide == slot
            val grow = if (hovered) SIDE_HOVER_SCALE else 1f
            fillSideBase(side, slot)
            Matrix.translateM(sideModel, 0, sideBase, 0, 0f, 0f, -side.distance)
            Matrix.scaleM(sideModel, 0, side.cardWidth * grow, side.cardHeight * grow, 1f)
            Matrix.multiplyMM(viewModel, 0, sideEyeView, 0, sideModel, 0)
            Matrix.multiplyMM(mvp, 0, zoomed, 0, viewModel, 0)
            GLES20.glUniformMatrix4fv(sideMvp, 1, false, mvp, 0)
            GLES20.glUniform1f(sideAlphaUniform, sideAlpha * (if (hovered) 1f else SIDE_IDLE_ALPHA))
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sideTextures[slot.ordinal])
            drawMesh(quad, sidePosition, sideUv)
        }
        // Ручки окон — поверх: за них окна и перетаскиваются.
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(shapeProgram)
        val thickness = side.distance * SideLayout.GRIP_THICKNESS_PER_DISTANCE
        for (slot in SideSlot.entries) {
            val active = tracker.hoverHandle.side == slot
            fillSideBase(side, slot)
            val alpha = sideAlpha * (if (active) HANDLE_ACTIVE_ALPHA else HANDLE_IDLE_ALPHA)
            drawShape(zoomed, sideBase, sideEyeView, 0f, side.gripY, -side.distance, side.gripWidth, thickness, alpha)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Поворот соседнего окна: вместе с экраном, плюс куда его отодвинули. */
    private fun fillSideBase(side: SideLayout, slot: SideSlot) {
        Matrix.setRotateM(sideBase, 0, tracker.videoYawDeg + side.yawOf(slot) + tracker.sideOffsets.yaw(slot), 0f, 1f, 0f)
        val pitch = tracker.screenPitchDeg + tracker.sideOffsets.pitch(slot)
        if (pitch != 0f) Matrix.rotateM(sideBase, 0, pitch, 1f, 0f, 0f)
    }

    private fun uploadSide(texture: Int, bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    private fun createProbe() {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        probeTexture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, probeTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, PROBE_WIDTH * 2, PROBE_HEIGHT, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glGenFramebuffers(1, ids, 0)
        probeFramebuffer = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, probeFramebuffer)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, probeTexture, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        Matrix.perspectiveM(probeProjection, 0, PROBE_FOV_Y_DEG, PROBE_WIDTH.toFloat() / PROBE_HEIGHT, Z_NEAR, Z_FAR)
    }

    /**
     * Стерео: насколько расходятся глаза в кадре рядом с панелью. Видео рисуется для каждого глаза маленькой
     * картинкой из одной точки, поэтому разница между ними — только сдвиг, заложенный в самом видео.
     * Так одинаково работает с любой проекцией. Пока ничего поверх видео нет, не измеряем.
     */
    private fun measureDepth() {
        val format = format
        val needed = settings.menuDepthAuto && stereoView && hasVideo && !calibration && !settling &&
            video.isReady && format.stereo != StereoLayout.Mono && (panelVisible || sideAlpha > 0f)
        if (!needed) {
            depth.reset()
            return
        }
        val now = SystemClock.uptimeMillis()
        val interval = if (depth.wantsQuickSample) PROBE_QUICK_INTERVAL_MS else PROBE_INTERVAL_MS
        if (now - depthProbeAt < interval) return
        depthProbeAt = now

        val framebuffer = IntArray(1)
        val viewport = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, framebuffer, 0)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, probeFramebuffer)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glViewport(0, 0, PROBE_WIDTH * 2, PROBE_HEIGHT)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Смотрим туда, где панель; если её нет, а видны только соседние окна, — на центр экрана.
        Matrix.setRotateM(probeRotation, 0, if (panelVisible) tracker.panelYawDeg else tracker.videoYawDeg, 0f, 1f, 0f)
        Matrix.rotateM(probeRotation, 0, if (panelVisible) tracker.panelPitchDeg else tracker.videoPitchDeg, 1f, 0f, 0f)
        Matrix.transposeM(probeView, 0, probeRotation, 0)
        Matrix.multiplyMM(viewModel, 0, probeView, 0, videoModel, 0)
        Matrix.multiplyMM(mvp, 0, probeProjection, 0, viewModel, 0)
        GLES20.glUseProgram(textureProgram)
        GLES20.glUniform1f(uAlpha, 1f)
        GLES20.glUniform1f(uBrightness, 1f)
        val mesh = meshFor(format)
        for (right in listOf(false, true)) {
            GLES20.glViewport(if (right) PROBE_WIDTH else 0, 0, PROBE_WIDTH, PROBE_HEIGHT)
            val rect = eyeRect(format.stereo, right)
            GLES20.glUniform4f(uUvRect, rect[0], rect[1], rect[2], rect[3])
            drawPrepared(video, mesh, setUvRect = false)
        }
        probePixels.position(0)
        GLES20.glReadPixels(0, 0, PROBE_WIDTH * 2, PROBE_HEIGHT, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, probePixels)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
        GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])

        for (y in 0 until PROBE_HEIGHT) {
            for (x in 0 until PROBE_WIDTH) {
                probeLeft[y * PROBE_WIDTH + x] = probePixels.get((y * PROBE_WIDTH * 2 + x) * 4 + 1)
                probeRight[y * PROBE_WIDTH + x] = probePixels.get((y * PROBE_WIDTH * 2 + PROBE_WIDTH + x) * 4 + 1)
            }
        }
        val shift = StereoDisparity.estimate(probeLeft, probeRight, PROBE_WIDTH, PROBE_HEIGHT, PROBE_MIN_SHIFT, PROBE_MAX_SHIFT)
        val tanPerPixel = 2 * tan(Math.toRadians(PROBE_FOV_Y_DEG / 2.0)).toFloat() / PROBE_HEIGHT
        depth.sample(shift?.let { atan(it * tanPerPixel) })
    }

    private fun drawCursor(eye: CardboardView.Eye, projection: FloatArray) {
        Matrix.multiplyMM(viewModel, 0, panelEyeView, 0, cursorModel, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0)
        GLES20.glUseProgram(cursorProgram)
        GLES20.glUniformMatrix4fv(cursorMvp, 1, false, mvp, 0)
        GLES20.glUniform1f(cursorProgress, dwellProgress)
        drawMesh(quad, cursorPosition, cursorUv)
    }

    private fun drawMesh(mesh: Mesh, positionAttr: Int, uvAttr: Int) = GlUtil.drawMesh(mesh, positionAttr, uvAttr)

    override fun onFinishFrame(viewport: Viewport) = Unit

    override fun onRendererShutdown() = release()

    /** Плоский экран уже в метрах (см. [flatScreen]), здесь только поворот: рецентр, наклон и подъём ручкой. */
    private fun updateVideoModel(format: VideoFormat) {
        Matrix.setRotateM(videoModel, 0, tracker.videoYawDeg, 0f, 1f, 0f)
        val pitch = tracker.videoPitchDeg + if (format.projection == Projection.Flat) tracker.screenLiftDeg else 0f
        if (pitch != 0f) Matrix.rotateM(videoModel, 0, pitch, 1f, 0f, 0f)
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
        private const val CURSOR_SIZE = 0.05f
        private const val CURSOR_LIFT = 0.01f
        private const val APPEAR_S = 0.18f

        private const val HANDLE_IDLE_ALPHA = 0.35f
        private const val HANDLE_ACTIVE_ALPHA = 0.95f

        private const val Z_NEAR = 0.1f
        private const val Z_FAR = 100f
        private const val SPHERE_RADIUS = 10f

        private const val SIDE_FADE_S = 0.25f
        private const val SIDE_IDLE_ALPHA = 0.8f
        private const val SIDE_HOVER_SCALE = 1.05f

        // Замер стерео: область около 48° × 28°, примерно 0,4° на пиксель. Сдвиг от −1° до 7°.
        private const val PROBE_WIDTH = 128
        private const val PROBE_HEIGHT = 72
        private const val PROBE_FOV_Y_DEG = 28f
        private const val PROBE_MIN_SHIFT = -3
        private const val PROBE_MAX_SHIFT = 18
        private const val PROBE_INTERVAL_MS = 700L
        private const val PROBE_QUICK_INTERVAL_MS = 50L

        private const val SIDE_FRAGMENT_SHADER = """
precision mediump float;
uniform sampler2D uTexture;
uniform float uAlpha;
varying vec2 vUv;
void main() {
    // У картинки первая строка сверху, а у прямоугольника v = 0 внизу. Цвет с премультиплицированной альфой.
    gl_FragColor = texture2D(uTexture, vec2(vUv.x, 1.0 - vUv.y)) * uAlpha;
}
"""
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
uniform float uAlpha;
varying vec2 vUv;
void main() {
    vec4 c = texture2D(uTexture, vUv);
    // Альфа премультиплицирована: для проявления умножаем весь цвет.
    gl_FragColor = vec4(c.rgb * uBrightness, c.a) * uAlpha;
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

        // Скруглённый прямоугольник (у тонкого — «пилюля») со сглаженным краем; размер в uSize — для радиуса.
        private const val SHAPE_FRAGMENT_SHADER = """
precision mediump float;
uniform vec2 uSize;
uniform vec4 uColor;
varying vec2 vUv;
void main() {
    vec2 hs = uSize * 0.5;
    vec2 p = (vUv - 0.5) * uSize;
    float r = min(hs.x, hs.y);
    vec2 q = abs(p) - hs + vec2(r);
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
    float a = 1.0 - smoothstep(-r * 0.3, 0.0, d);
    gl_FragColor = vec4(uColor.rgb, uColor.a * a);
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

/** Картинки соседних окон. */
class SideImages(val left: Bitmap, val right: Bitmap)

/** Куда смотреть по отладочной команде. */
sealed interface DebugLook {
    data class Angles(val yawDeg: Float, val pitchDeg: Float) : DebugLook

    /** Точка панели в пикселях дисплея; за краями панели значения отрицательные или больше её размера. */
    data class PanelPoint(val x: Float, val y: Float) : DebugLook
}

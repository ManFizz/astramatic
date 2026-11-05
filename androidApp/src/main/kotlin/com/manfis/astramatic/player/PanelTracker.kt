package com.manfis.astramatic.player

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Размер виртуального дисплея панели. */
const val PANEL_WIDTH_PX = 1280
const val PANEL_HEIGHT_PX = 800
const val PANEL_DENSITY_DPI = 240

const val MIN_MENU_DISTANCE = 1.5f
const val MAX_MENU_DISTANCE = 12f

/** Размер меню, множитель к обычному: столько можно растянуть или сжать за уголок. */
const val MIN_MENU_SIZE = 0.6f
const val MAX_MENU_SIZE = 1.8f

/** Базовое расстояние до панели в метрах: размеры ниже заданы для него, дальше — пропорционально больше. */
const val PANEL_DISTANCE = 2.5f

/**
 * Какую часть виртуального дисплея показывать и где.
 * [visibleHeightPx] — сколько пикселей сверху дисплея видно, [width] — ширина в метрах,
 * [centerY] — высота центра относительно глаз в метрах.
 */
enum class PanelLayout(val visibleHeightPx: Int, val width: Float, val centerY: Float) {
    Menu(PANEL_HEIGHT_PX, 2.2f, 0f),

    /** Полоска управления: верхние 360 px (240 dp при плотности панели), посередине — там, куда смотрели при нажатии. */
    Controls(360, 2.0f, 0f),

    /**
     * Полоска с раскрытой над ней панелью (вид или очередь): 735 px = 240 + 250 dp.
     * Нижний край — там же, где у простой полоски, панель растёт вверх.
     */
    ControlsSheet(735, 2.0f, 0.293f);

    val height: Float get() = width * visibleHeightPx / PANEL_WIDTH_PX
    val visibleFraction: Float get() = visibleHeightPx.toFloat() / PANEL_HEIGHT_PX
}

/** Куда поставить панель. */
enum class PanelPlacement {
    /** Перед глазами; наклон только в режиме «лёжа». */
    InFront,

    /** Точно туда, куда смотрят, в том числе вверх и вниз: так открывается полоска. */
    AtGaze,

    /** На центр видео, а если смотрят далеко в сторону — напротив взгляда, на высоте центра. Так открывается меню. */
    Center,
}

/** Точка взгляда на панели в пикселях виртуального дисплея. */
data class Gaze(val x: Float, val y: Float)

/**
 * За что можно взяться: уголки меню — растянуть; [Move] — ручка под панелью, [Screen] — под плоским экраном,
 * [SideLeft] и [SideRight] — под соседними окнами.
 */
enum class Handle {
    None, TopLeft, TopRight, BottomLeft, BottomRight, Move, Screen, SideLeft, SideRight;

    val resizes get() = this == TopLeft || this == TopRight || this == BottomLeft || this == BottomRight
    val side get() = when (this) {
        SideLeft -> SideSlot.Left
        SideRight -> SideSlot.Right
        else -> null
    }
}

/** Настройки, от которых зависит геометрия; читаются каждый кадр. */
interface PanelPreferences {
    /** Режим «лёжа»: рецентр поворачивает и вверх-вниз, туда же ставится меню. */
    val recenterPitch: Boolean
    val menuDistance: Float
    val menuSize: Float
}

/**
 * Где находятся видео и панель и куда на панели попадает взгляд. Считается каждый кадр на GL-потоке
 * по направлению взгляда; рисование остаётся за [VrRenderer].
 */
class PanelTracker(
    private val prefs: PanelPreferences,
    /** Меню растянули за уголок и отпустили: новый размер — запомнить. */
    private val onMenuSizeChanged: (Float) -> Unit = {},
) {
    private val recenterRequested = AtomicBoolean(true)
    private val centerOnPanelRequested = AtomicBoolean(false)
    private val placeRequested = AtomicReference<PanelPlacement?>(PanelPlacement.InFront)

    /**
     * Первые кадры после запуска трекинг головы ещё не определился с направлением. Пока он устаканивается,
     * панель не показываем (иначе она «тянется» за взглядом), а ставим и центрируем видео один раз — после.
     */
    private val settleFrames = AtomicInteger(SETTLE_FRAMES)

    /** Трекинг ещё устаканивается после запуска: панель не видна. */
    val settling get() = settleFrames.get() > 0

    /** Панель видна (из UI-потока). */
    @Volatile var panelVisible = false

    @Volatile var layout = PanelLayout.Menu

    /** Взялись (из UI-потока): что держат; [Handle.None] — отпустили. */
    @Volatile var grab = Handle.None

    /** Точка взгляда на панели, null — взгляд мимо. Обновляется каждый кадр. */
    @Volatile var gaze: Gaze? = null
        private set

    /** Что из [Handle] сейчас под взглядом. Обновляется каждый кадр. */
    @Volatile var hoverHandle = Handle.None
        private set

    var videoYawDeg = 0f
        private set
    var videoPitchDeg = 0f
        private set

    /** На сколько плоский экран подняли ручкой. Окружение при этом не наклоняется. */
    var screenLiftDeg = 0f
        private set
    var panelYawDeg = 0f
        private set
    var panelPitchDeg = 0f
        private set

    /** Во сколько раз меню дальше базовых [PANEL_DISTANCE] (и во столько же раз больше). */
    var panelScale = 1f
        private set

    /** Панель в метрах этого кадра: ширина, высота, высота центра, плоскость (z < 0). */
    var panelW = 0f
        private set
    var panelH = 0f
        private set
    var panelCy = 0f
        private set
    var panelZ = -PANEL_DISTANCE
        private set

    /** Куда взгляд попадает на плоскости панели, метры от её оси; NaN — мимо плоскости. */
    var hitX = Float.NaN
        private set
    var hitY = Float.NaN
        private set

    /** Что держат прямо сейчас: начинается и заканчивается в кадре после изменения [grab]. */
    var activeHandle = Handle.None
        private set

    private var localX = 0f
    private var localY = 0f
    private var localZ = -1f

    /** Взгляд относительно поворота экрана вокруг вертикали, без наклона: в этой системе стоят соседние окна. */
    private var turnedX = 0f
    private var turnedY = 0f
    private var turnedZ = -1f

    private var grabYawOffset = 0f
    private var grabPitchOffset = 0f
    private var grabStartDistance = 0f
    private var grabStartSize = 1f

    /** Размер меню, пока его растягивают (и пока новый не записан в настройки); иначе NaN. */
    private var liveMenuSize = Float.NaN

    /** Соседние окна плоского экрана этого кадра; null — их нет. */
    @Volatile var sideLayout: SideLayout? = null

    /** Плоский экран этого кадра, если за него можно взяться; иначе null. */
    @Volatile var screenGrip: ScreenGrip? = null

    /** Ручка экрана должна быть видна: на неё почти смотрят или открыта полоска. */
    var screenGripWanted = false
        private set

    /** Куда отодвинули соседние окна. */
    val sideOffsets = SideOffsets()

    /** Соседнее окно под взглядом. Обновляется каждый кадр. */
    @Volatile var hoverSide: SideSlot? = null
        private set

    /** Соседние окна должны быть видны: взгляд ушёл за край экрана или открыта полоска. */
    var sideWanted = false
        private set

    /**
     * Повернуть видео так, чтобы его центр оказался там, куда сейчас смотрит голова:
     * по горизонтали, а в режиме «лёжа» — и по вертикали.
     */
    fun recenter() = recenterRequested.set(true)

    /** Повернуть видео к панели: файл выбран в меню — пусть начнётся там, где меню. */
    fun centerOnPanel() = centerOnPanelRequested.set(true)

    /** Поставить панель в следующем кадре. */
    fun placePanel(placement: PanelPlacement) = placeRequested.set(placement)

    /** Новый кадр: взгляд [x], [y], [z] — единичный вектор в мире, вперёд — −Z. */
    fun update(x: Float, y: Float, z: Float) {
        val lookYawDeg = Math.toDegrees(atan2(-x, -z).toDouble()).toFloat()
        val truePitchDeg = Math.toDegrees(asin(y.coerceIn(-1f, 1f).toDouble())).toFloat()
        val lookPitchDeg = if (prefs.recenterPitch) truePitchDeg else 0f

        if (!lookYawDeg.isNaN()) {
            val grabbed = grab
            if (activeHandle == Handle.None && grabbed != Handle.None && !settling) {
                startGrab(grabbed, lookYawDeg, truePitchDeg)
            } else if (activeHandle != Handle.None && grabbed == Handle.None) {
                endGrab()
            }
            if (settling) {
                settleFrames.decrementAndGet()
                if (recenterRequested.get()) moveVideo(lookYawDeg, lookPitchDeg)
            } else if (recenterRequested.getAndSet(false)) {
                moveVideo(lookYawDeg, lookPitchDeg)
            }
            val placement = if (settling) PanelPlacement.InFront else placeRequested.getAndSet(null)
            if (placement != null) {
                place(placement, lookYawDeg, lookPitchDeg, truePitchDeg)
            } else if (activeHandle == Handle.Move) {
                // Панель едет за взглядом, сохраняя, где за неё взялись.
                panelYawDeg = wrapDegrees(lookYawDeg + grabYawOffset)
                panelPitchDeg = (truePitchDeg + grabPitchOffset).coerceIn(-MAX_MENU_PITCH, MAX_MENU_PITCH)
            } else if (activeHandle == Handle.Screen) {
                videoYawDeg = wrapDegrees(lookYawDeg + grabYawOffset)
                screenLiftDeg = (truePitchDeg + grabPitchOffset - videoPitchDeg).coerceIn(-MAX_MENU_PITCH, MAX_MENU_PITCH)
            }
        }

        if (!settling && centerOnPanelRequested.getAndSet(false)) moveVideo(panelYawDeg, panelPitchDeg)

        val layout = layout
        // Меню дальше — то же по видимому размеру, но глазам меньше сводиться при переходе от видео к меню.
        val scale = prefs.menuDistance.coerceIn(MIN_MENU_DISTANCE, MAX_MENU_DISTANCE) / PANEL_DISTANCE
        panelScale = scale
        val size = if (layout == PanelLayout.Menu) menuSize() else 1f
        panelW = layout.width * scale * size
        panelH = layout.height * scale * size
        panelCy = layout.centerY * scale
        panelZ = -PANEL_DISTANCE * scale

        updateHit(x, y, z)
        if (activeHandle.resizes) resizeMenu()
        val interactive = panelVisible && !settling
        val panelHandle = when {
            activeHandle != Handle.None -> activeHandle
            interactive -> findHandle(layout)
            else -> Handle.None
        }
        gaze = if (interactive && activeHandle == Handle.None) computeGaze(layout) else null
        toVideoFrame(x, y, z)
        dragSide()
        updateSide(layout)
        val sideHandle = findSideHandle()
        val screenHandle = findScreenHandle()
        hoverHandle = when {
            panelHandle != Handle.None || gaze != null -> panelHandle
            sideHandle != Handle.None -> sideHandle
            else -> screenHandle
        }
        if (hoverHandle != Handle.None) hoverSide = null
    }

    /** Новый центр видео: экран снова на его высоте. */
    private fun moveVideo(yawDeg: Float, pitchDeg: Float) {
        videoYawDeg = yawDeg
        videoPitchDeg = pitchDeg
        screenLiftDeg = 0f
    }

    private fun place(placement: PanelPlacement, lookYawDeg: Float, lookPitchDeg: Float, truePitchDeg: Float) {
        when (placement) {
            PanelPlacement.InFront -> {
                panelYawDeg = lookYawDeg
                panelPitchDeg = lookPitchDeg
            }
            PanelPlacement.AtGaze -> {
                panelYawDeg = lookYawDeg
                panelPitchDeg = truePitchDeg.coerceIn(-MAX_MENU_PITCH, MAX_MENU_PITCH)
            }
            PanelPlacement.Center -> {
                val nearCenter = abs(wrapDegrees(lookYawDeg - videoYawDeg)) <= MENU_CENTER_REACH_DEG
                panelYawDeg = if (nearCenter) videoYawDeg else lookYawDeg
                panelPitchDeg = videoPitchDeg + screenLiftDeg
            }
        }
    }

    /** Взгляд в системе экрана: с учётом его поворота и подъёма. */
    private fun toVideoFrame(x: Float, y: Float, z: Float) {
        val yaw = Math.toRadians(videoYawDeg.toDouble())
        val pitch = Math.toRadians((videoPitchDeg + screenLiftDeg).toDouble())
        localX = (cos(yaw) * x - sin(yaw) * z).toFloat()
        val depth = sin(yaw) * x + cos(yaw) * z
        localY = (cos(pitch) * y + sin(pitch) * depth).toFloat()
        localZ = (cos(pitch) * depth - sin(pitch) * y).toFloat()
        turnedX = localX
        turnedY = y
        turnedZ = depth.toFloat()
    }

    private val localYawDeg get() = Math.toDegrees(atan2(-localX, -localZ).toDouble()).toFloat()
    private val turnedYawDeg get() = Math.toDegrees(atan2(-turnedX, -turnedZ).toDouble()).toFloat()
    private val turnedPitchDeg get() = Math.toDegrees(asin(turnedY.coerceIn(-1f, 1f).toDouble())).toFloat()

    /** Наклон экрана: на этой высоте стоят соседние окна. */
    val screenPitchDeg get() = videoPitchDeg + screenLiftDeg

    /** Соседнее окно едет за взглядом, сохраняя, где за него взялись. */
    private fun dragSide() {
        val slot = activeHandle.side ?: return
        val side = sideLayout ?: return
        sideOffsets.set(
            slot,
            wrapDegrees(turnedYawDeg + grabYawOffset - side.yawOf(slot)),
            (turnedPitchDeg + grabPitchOffset - screenPitchDeg).coerceIn(-MAX_MENU_PITCH, MAX_MENU_PITCH),
        )
    }

    /** Видны ли соседние окна и на какое из них смотрят. */
    private fun updateSide(layout: PanelLayout) {
        val side = sideLayout
        if (side == null || settling) {
            hoverSide = null
            sideWanted = false
            return
        }
        val pastEdge = abs(localYawDeg) > side.screenHalfDeg - SIDE_REVEAL_MARGIN_DEG
        val sideDragged = activeHandle.side != null
        sideWanted = pastEdge || sideDragged || (panelVisible && layout != PanelLayout.Menu)
        hoverSide = if (gaze != null || activeHandle != Handle.None) null else side.slotAt(turnedX, turnedY, turnedZ, sideOffsets, screenPitchDeg)
    }

    private fun findSideHandle(): Handle {
        val side = sideLayout ?: return Handle.None
        if (settling) return Handle.None
        return when (side.gripAt(turnedX, turnedY, turnedZ, sideOffsets, screenPitchDeg)) {
            SideSlot.Left -> Handle.SideLeft
            SideSlot.Right -> Handle.SideRight
            null -> Handle.None
        }
    }

    private fun findScreenHandle(): Handle {
        val grip = screenGrip
        if (grip == null || settling || localZ > -0.01f) {
            screenGripWanted = false
            return Handle.None
        }
        val t = -grip.distance / localZ
        val pointX = localX * t
        val pointY = localY * t
        val onGrip = abs(pointX) <= grip.width / 2 + grip.reach && abs(pointY - grip.y) <= grip.reach
        // Проявляется заранее, когда взгляд подходит к нижнему краю экрана или открыта полоска.
        screenGripWanted = onGrip || activeHandle == Handle.Screen ||
            (abs(pointX) <= grip.width * 2 && abs(pointY - grip.y) <= grip.reach * 4) ||
            (panelVisible && layout != PanelLayout.Menu)
        return if (onGrip) Handle.Screen else Handle.None
    }

    /**
     * Направление взгляда в мире, при котором он попадает в точку панели [px], [py] (пиксели дисплея,
     * за краями панели — отрицательные или больше её размера): (рысканье, тангаж) в градусах.
     */
    fun anglesForPanelPoint(px: Float, py: Float): Pair<Float, Float> {
        val localX = (px / PANEL_WIDTH_PX - 0.5f) * panelW
        val localY = panelCy + (0.5f - py / layout.visibleHeightPx) * panelH
        val localZ = panelZ
        val length = sqrt(localX * localX + localY * localY + localZ * localZ)
        // Поворот панели: сначала наклон, потом рысканье.
        val pitch = Math.toRadians(panelPitchDeg.toDouble())
        val yaw = Math.toRadians(panelYawDeg.toDouble())
        val y1 = localY * cos(pitch) - localZ * sin(pitch)
        val z1 = localY * sin(pitch) + localZ * cos(pitch)
        val worldX = localX * cos(yaw) + z1 * sin(yaw)
        val worldZ = -localX * sin(yaw) + z1 * cos(yaw)
        val worldYaw = Math.toDegrees(atan2(-worldX, -worldZ)).toFloat()
        val worldPitch = Math.toDegrees(asin((y1 / length).coerceIn(-1.0, 1.0))).toFloat()
        return worldYaw to worldPitch
    }

    /** Что сейчас под взглядом и где панель: для проверок в эмуляторе. */
    fun describe(): String = "visible=$panelVisible settling=$settling layout=$layout" +
        " gaze=${gaze?.let { "${it.x.toInt()},${it.y.toInt()}" }} handle=$hoverHandle active=$activeHandle" +
        " size=${"%.3f".format(java.util.Locale.ROOT, if (layout == PanelLayout.Menu) panelW / (layout.width * panelScale) else 1f)}" +
        " panelYaw=${"%.1f".format(java.util.Locale.ROOT, panelYawDeg)}" +
        " panelPitch=${"%.1f".format(java.util.Locale.ROOT, panelPitchDeg)}" +
        " videoYaw=${"%.1f".format(java.util.Locale.ROOT, videoYawDeg)}" +
        " screenLift=${"%.1f".format(java.util.Locale.ROOT, screenLiftDeg)}" +
        " sideLeft=${"%.1f".format(java.util.Locale.ROOT, sideOffsets.yaw(SideSlot.Left))}" +
        " sideRight=${"%.1f".format(java.util.Locale.ROOT, sideOffsets.yaw(SideSlot.Right))}"

    /** Размер меню (множитель к обычному): во время растягивания — живой, иначе из настроек. */
    private fun menuSize(): Float {
        val saved = prefs.menuSize.coerceIn(MIN_MENU_SIZE, MAX_MENU_SIZE)
        val live = liveMenuSize
        if (live.isNaN()) return saved
        // Новый размер дошёл до настроек — дальше снова по ним.
        if (activeHandle == Handle.None && abs(saved - live) < 0.0001f) {
            liveMenuSize = Float.NaN
            return saved
        }
        return live
    }

    private fun startGrab(handle: Handle, lookYawDeg: Float, truePitchDeg: Float) {
        activeHandle = handle
        val slot = handle.side
        when {
            handle == Handle.Move -> {
                grabYawOffset = wrapDegrees(panelYawDeg - lookYawDeg)
                grabPitchOffset = panelPitchDeg - truePitchDeg
            }
            handle == Handle.Screen -> {
                grabYawOffset = wrapDegrees(videoYawDeg - lookYawDeg)
                grabPitchOffset = videoPitchDeg + screenLiftDeg - truePitchDeg
            }
            slot != null -> {
                val side = sideLayout
                grabYawOffset = wrapDegrees((side?.yawOf(slot) ?: 0f) + sideOffsets.yaw(slot) - turnedYawDeg)
                grabPitchOffset = sideOffsets.pitch(slot) + screenPitchDeg - turnedPitchDeg
            }
            else -> {
                grabStartSize = menuSize()
                grabStartDistance = if (hitX.isNaN()) 0f else hypot(hitX, hitY - panelCy)
                liveMenuSize = grabStartSize
            }
        }
    }

    private fun endGrab() {
        val wasResize = activeHandle.resizes
        activeHandle = Handle.None
        if (wasResize && !liveMenuSize.isNaN()) onMenuSizeChanged(liveMenuSize)
    }

    /** Уголок тянут от центра — меню растёт, к центру — уменьшается; пропорции те же. */
    private fun resizeMenu() {
        if (hitX.isNaN() || grabStartDistance <= 0f) return
        val distance = hypot(hitX, hitY - panelCy)
        liveMenuSize = (grabStartSize * distance / grabStartDistance).coerceIn(MIN_MENU_SIZE, MAX_MENU_SIZE)
    }

    /** Пересечение взгляда с плоскостью панели (в том числе за её краями) — в [hitX], [hitY]. */
    private fun updateHit(x: Float, y: Float, z: Float) {
        // Поворот панели — чистое вращение, обратное к нему — транспонированное: сначала рысканье назад, потом наклон.
        val yaw = Math.toRadians(panelYawDeg.toDouble())
        val pitch = Math.toRadians(panelPitchDeg.toDouble())
        val cosYaw = cos(yaw).toFloat()
        val sinYaw = sin(yaw).toFloat()
        val cosPitch = cos(pitch).toFloat()
        val sinPitch = sin(pitch).toFloat()
        val localX = cosYaw * x - sinYaw * z
        val turnedZ = sinYaw * x + cosYaw * z
        val localY = cosPitch * y + sinPitch * turnedZ
        val localZ = cosPitch * turnedZ - sinPitch * y
        if (localZ > -0.01f) {
            hitX = Float.NaN
            hitY = Float.NaN
            return
        }
        val t = panelZ / localZ
        hitX = localX * t
        hitY = localY * t
    }

    /** Ручка, а у меню и уголок, под взглядом. Они снаружи панели, чтобы не мешать её кнопкам. */
    private fun findHandle(layout: PanelLayout): Handle {
        if (hitX.isNaN()) return Handle.None
        val s = panelScale
        val halfW = panelW / 2
        val top = panelCy + panelH / 2
        val bottom = panelCy - panelH / 2
        val barY = bottom - BAR_GAP * s
        if (abs(hitX) < panelW * BAR_WIDTH / 2 + BAR_HIT * s && abs(hitY - barY) < BAR_HIT * s) return Handle.Move
        if (layout != PanelLayout.Menu) return Handle.None
        val inset = EDGE_INSET * s
        val inside = abs(hitX) < halfW - inset && hitY < top - inset && hitY > bottom + inset
        if (inside) return Handle.None
        val reach = CORNER_HIT * s
        return when {
            abs(hitX + halfW) < reach && abs(hitY - top) < reach -> Handle.TopLeft
            abs(hitX - halfW) < reach && abs(hitY - top) < reach -> Handle.TopRight
            abs(hitX + halfW) < reach && abs(hitY - bottom) < reach -> Handle.BottomLeft
            abs(hitX - halfW) < reach && abs(hitY - bottom) < reach -> Handle.BottomRight
            else -> Handle.None
        }
    }

    private fun wrapDegrees(deg: Float): Float = ((deg + 540f) % 360f) - 180f

    /** Точка взгляда на панели в пикселях виртуального дисплея; null — мимо панели. */
    private fun computeGaze(layout: PanelLayout): Gaze? {
        if (hitX.isNaN()) return null
        val u = hitX / panelW + 0.5f
        val v = 0.5f - (hitY - panelCy) / panelH
        if (u !in 0f..1f || v !in 0f..1f) return null
        return Gaze(u * PANEL_WIDTH_PX, v * layout.visibleHeightPx)
    }

    companion object {
        /** Сколько кадров трекинг устаканивается после запуска. */
        const val SETTLE_FRAMES = 45

        private const val MAX_MENU_PITCH = 70f

        /** Настолько в сторону от центра видео меню ещё открывается на центре, дальше — напротив взгляда. */
        const val MENU_CENTER_REACH_DEG = 45f

        /** Соседние окна проявляются, когда до края экрана остаётся столько градусов. */
        private const val SIDE_REVEAL_MARGIN_DEG = 3f

        // Уголки и полоска-ручка, метры при базовом расстоянии меню (дальше меню — пропорционально больше).
        const val CORNER_OFFSET = 0.05f
        const val CORNER_ARM = 0.16f
        private const val CORNER_HIT = 0.16f
        const val HANDLE_THICKNESS = 0.024f
        private const val EDGE_INSET = 0.03f
        const val BAR_GAP = 0.08f
        private const val BAR_HIT = 0.07f

        /** Ширина полоски — доля ширины меню. */
        const val BAR_WIDTH = 0.18f
    }
}

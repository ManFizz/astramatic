package com.manfis.astramatic.player

import android.view.KeyEvent
import com.manfis.astramatic.settings.AxisBinding
import com.manfis.astramatic.settings.AxisContext
import com.manfis.astramatic.settings.KeyBindings
import com.manfis.astramatic.settings.RemoteAction
import com.manfis.astramatic.ui.VrMenuActions
import com.manfis.astramatic.ui.VrMenuState
import kotlin.math.abs

/** Ось устройства ввода: номер и нижняя граница хода. */
data class AxisRange(val axis: Int, val min: Float)

/** Что делать с кнопкой дальше. */
sealed interface KeyRoute {
    /** Обработана здесь. */
    data object Consumed : KeyRoute

    /** Отдать интерфейсу панели как кнопку [keyCode]. */
    data class ToPanel(val keyCode: Int) : KeyRoute

    /** Не наша: пусть обрабатывает система. */
    data object System : KeyRoute
}

/**
 * Пульт и геймпад: кнопки, оси и колесо мыши, включая назначение новых.
 * Получает значения, уже извлечённые из событий Android, и решает, какое действие выполнить.
 */
class RemoteInput(
    private val menu: VrMenuState,
    private val bindings: KeyBindings,
    private val host: PanelHost,
    private val panels: PanelController,
    private val actions: VrMenuActions,
    private val ticks: Ticks,
    private val clock: () -> Long,
    /** Системная «Назад»: через неё проходят и обработчики интерфейса. */
    private val systemBack: () -> Unit,
    /** Громкость: true — громче. */
    private val volume: (Boolean) -> Unit,
) {
    /** Последние значения осей. Геймпад присылает события только при движении — поэтому храним. */
    private val axisValues = HashMap<Int, Float>()

    /** Положение покоя на момент начала назначения: снимок до первого движения. */
    private var captureBaseline: Map<Int, Float>? = null

    /** Когда началось назначение. */
    private var captureStartedAt = 0L

    /** Оси, нажатые как кнопки; только что назначенные не срабатывают, пока их не отпустят. */
    private val axisPressed = HashSet<AxisBinding>()
    private val axisSuppressed = HashSet<AxisBinding>()
    private val zoomDetent = ZoomDetent(clock)
    private var recenterAt = Long.MIN_VALUE / 2
    private var clickSuppressed = false
    private var axisTicking = false
    private var seekHeldTicks = 0
    private var navX = 0
    private var navY = 0

    /** Началось или закончилось назначение. */
    fun captureChanged() {
        captureStartedAt = clock()
        captureBaseline = null
    }

    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int, time: Long): KeyRoute {
        // Назначение кнопки: следующая нажатая кнопка (кроме системной «Назад») достаётся выбранному действию.
        val capturing = menu.capturingKey
        if (capturing != null && !capturing.isAxis && keyCode != KeyEvent.KEYCODE_BACK) {
            if (down && repeatCount == 0) {
                bindings.assignKey(capturing, keyCode)
                menu.capturingKey = null
            }
            return KeyRoute.Consumed
        }

        // Назначения пульта важнее всего остального.
        val action = bindings.actionFor(keyCode)
        if (action != null) {
            if (action == RemoteAction.Click) {
                if (down && repeatCount == 0) clickDown(time)
                if (!down) clickUp(time)
            } else if (down && (repeatCount == 0 || action in REPEATABLE)) {
                perform(action, held = repeatCount > 0)
            }
            return KeyRoute.Consumed
        }

        // Не назначенные стрелки и OK ходят по меню в шлеме (на экране — сами, как обычно).
        if (host.place == PanelPlace.Vr) {
            // Пульт может прислать на одну кнопку два кода, например рецентр и OK. Такой OK ничего не нажимает.
            if (keyCode in OK_KEYS && clock() - recenterAt < SAME_PRESS_MS) return KeyRoute.Consumed
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_TAB -> return KeyRoute.ToPanel(keyCode)
                KeyEvent.KEYCODE_BUTTON_A -> return KeyRoute.ToPanel(KeyEvent.KEYCODE_DPAD_CENTER)
            }
        }
        return KeyRoute.System
    }

    /** Колесо мыши (пульты в режиме мыши): прокрутка меню в шлеме. На экране меню прокручивается само. */
    fun onWheel(amount: Float): Boolean {
        if (host.place != PanelPlace.Vr) return false
        panels.scroll(amount)
        return true
    }

    /** Самая отклонённая ось этого события, чтобы показать её на экране пульта; null — ничего заметного. */
    fun strongestAxis(ranges: List<AxisRange>, values: Map<Int, Float>): Pair<Int, Float>? {
        val rest = restOf(ranges)
        val strongest = values.maxByOrNull { abs(it.value - (rest[it.key] ?: 0f)) } ?: return null
        if (abs(strongest.value - (rest[strongest.key] ?: 0f)) <= LABEL_THRESHOLD) return null
        return strongest.key to strongest.value
    }

    /** Событие геймпада: положение осей [values] в момент [time]. */
    fun onAxes(time: Long, ranges: List<AxisRange>, values: Map<Int, Float>) {
        val capturing = menu.capturingKey
        if (capturing != null) {
            captureAxis(capturing, time, ranges, values)
            return
        }
        captureBaseline = null
        axisValues.putAll(values)
        updateAxes()
    }

    /** Покой оси, которая ещё не двигалась: у курков (0..1) — 0, у стиков (−1..1) — середина. */
    private fun restOf(ranges: List<AxisRange>): Map<Int, Float> =
        ranges.associate { it.axis to (axisValues[it.axis] ?: if (it.min >= -0.01f) it.min else 0f) }

    private fun captureAxis(capturing: RemoteAction, time: Long, ranges: List<AxisRange>, values: Map<Int, Float>) {
        // Курок или стик, которым только что нажали «+», ещё возвращается в покой — это не назначение:
        // первые мгновения просто запоминаем, где оси остановились.
        if (time - captureStartedAt < CAPTURE_SETTLE_MS) {
            axisValues.putAll(values)
            captureBaseline = values
            return
        }
        val baseline = captureBaseline ?: restOf(ranges).also { captureBaseline = it }
        axisValues.putAll(values)
        val moved = values.maxByOrNull { abs(it.value - (baseline[it.key] ?: 0f)) } ?: return
        val delta = moved.value - (baseline[moved.key] ?: 0f)
        if (abs(delta) <= CAPTURE_THRESHOLD) return
        val restValue = baseline[moved.key] ?: 0f
        val binding = AxisBinding(
            axis = moved.key,
            inverted = delta < 0,
            rest = if (abs(restValue) > 0.8f) restValue.toInt().coerceIn(-1, 1) else 0,
        )
        bindings.assignAxis(capturing, binding)
        // Ось сейчас отклонена: пусть сначала вернётся в покой, иначе действие сработало бы сразу.
        if (!capturing.isAxis) {
            axisPressed += binding
            axisSuppressed += binding
        }
        menu.capturingKey = null
        captureBaseline = null
    }

    /** Отклонение для осевого действия: самая сильная из назначенных на него осей. */
    private fun axisValue(action: RemoteAction): Float {
        // Ось, назначенная и на меню, и на просмотр: работает то, что сейчас на экране.
        val menuOpen = host.place != PanelPlace.Hidden
        val active = when (action.axisContext) {
            AxisContext.Menu -> menuOpen
            AxisContext.Viewing -> !menuOpen
            AxisContext.Always -> true
        }
        if (!active) return 0f
        return bindings.axisBindings().filter { it.first == action }
            .map { (_, binding) -> binding.valueOf(axisValues[binding.axis] ?: binding.rest.toFloat()) }
            .maxByOrNull { abs(it) } ?: 0f
    }

    private fun updateAxes() {
        // Оси на кнопочных действиях (например, курок на «Нажатии») — как кнопка, с запасом от дребезга.
        bindings.axisBindings().filter { !it.first.isAxis }.forEach { (action, binding) ->
            val value = binding.valueOf(axisValues[binding.axis] ?: binding.rest.toFloat())
            val pressed = binding in axisPressed
            if (!pressed && value > AXIS_PRESS) {
                axisPressed += binding
                if (binding !in axisSuppressed) axisButton(action, down = true)
            } else if (pressed && value < AXIS_RELEASE) {
                axisPressed -= binding
                if (!axisSuppressed.remove(binding)) axisButton(action, down = false)
            }
        }

        // Стрелки меню — по одному шагу на отклонение.
        val x = direction(axisValue(RemoteAction.AxisNavHorizontal))
        if (x != navX) {
            navX = x
            if (x != 0) panels.navKey(if (x < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        val y = direction(axisValue(RemoteAction.AxisNavVertical))
        if (y != navY) {
            navY = y
            if (y != 0) panels.navKey(if (y < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN)
        }
        // Прокрутка и перемотка — плавно, пока ось отклонена. Шаг задаёт таймер, а не события оси:
        // стик, который шлёт их непрерывно, иначе мотал бы быстрее и рвал бы картинку.
        if (!axisTicking && SMOOTH_AXES.any { abs(axisValue(it)) > DEAD_ZONE }) {
            axisTicking = true
            ticks.schedule(axisTick)
        }
    }

    private fun axisButton(action: RemoteAction, down: Boolean) {
        val now = clock()
        if (action == RemoteAction.Click) {
            if (down) clickDown(now) else clickUp(now)
        } else if (down) {
            perform(action)
        }
    }

    private fun clickDown(time: Long) {
        clickSuppressed = time - recenterAt < SAME_PRESS_MS
        if (!clickSuppressed) panels.triggerDown(time)
    }

    private fun clickUp(time: Long) {
        if (clickSuppressed) clickSuppressed = false else panels.triggerUp(time, cancelled = false)
    }

    private fun direction(value: Float) = when {
        value > NAV_THRESHOLD -> 1
        value < -NAV_THRESHOLD -> -1
        else -> 0
    }

    private val axisTick = object : Runnable {
        override fun run() {
            val scroll = axisValue(RemoteAction.AxisScroll)
            val seek = axisValue(RemoteAction.AxisSeek)
            val zoom = axisValue(RemoteAction.AxisZoom)
            // Вверх по стику (назначен «вверх») — приблизить, плавно, примерно вдвое за секунду.
            if (abs(zoom) > DEAD_ZONE) setZoom(menu.zoom * (1f + zoom * AXIS_ZOOM_PER_TICK))
            // Стик вниз (плюс) прокручивает вниз — как колесо от себя.
            if (abs(scroll) > DEAD_ZONE) panels.scroll(-scroll * AXIS_SCROLL_PER_TICK)
            if (abs(seek) > DEAD_ZONE) {
                // Лёгкое отклонение — точно, полное и долгое — быстро: длинный файл так проходится за секунды.
                val speedUp = (1f + (seekHeldTicks - AXIS_SEEK_STEADY_TICKS).coerceAtLeast(0) / AXIS_SEEK_TICKS_TO_DOUBLE)
                    .coerceAtMost(AXIS_SEEK_MAX_SPEED_UP)
                actions.seekBy((seek * abs(seek) * AXIS_SEEK_MS_PER_TICK * speedUp).toLong())
                seekHeldTicks++
            } else {
                seekHeldTicks = 0
            }
            if (abs(scroll) > DEAD_ZONE || abs(seek) > DEAD_ZONE || abs(zoom) > DEAD_ZONE) {
                ticks.schedule(this, AXIS_TICK_MS)
            } else {
                axisTicking = false
            }
        }
    }

    private fun setZoom(wanted: Float) = actions.setZoom(zoomDetent.next(menu.zoom, wanted))

    /** [held] — кнопку держат: шаг мельче, иначе автоповтор проскакивал бы минуты за секунду. */
    private fun perform(action: RemoteAction, held: Boolean = false) {
        val seekStep = if (held) HELD_SEEK_DIVIDER else 1L
        when (action) {
            RemoteAction.Click -> Unit
            RemoteAction.Back -> systemBack()
            RemoteAction.Controls -> panels.cycleControls()
            RemoteAction.PlayPause -> actions.togglePlayback()
            RemoteAction.SeekBack -> actions.seekBy(-SEEK_STEP_MS / seekStep)
            RemoteAction.SeekForward -> actions.seekBy(SEEK_STEP_MS / seekStep)
            RemoteAction.SeekBackLong -> actions.seekBy(-LONG_SEEK_STEP_MS / seekStep)
            RemoteAction.SeekForwardLong -> actions.seekBy(LONG_SEEK_STEP_MS / seekStep)
            RemoteAction.PreviousFile -> actions.previousFile()
            RemoteAction.NextFile -> actions.nextFile()
            RemoteAction.VolumeUp -> volume(true)
            RemoteAction.VolumeDown -> volume(false)
            RemoteAction.Recenter -> {
                // Рецентр никогда не нажимает то, на что смотрят.
                recenterAt = clock()
                panels.cancelPress()
                actions.recenter()
            }
            RemoteAction.ZoomIn -> setZoom(menu.zoom * ZOOM_STEP)
            RemoteAction.ZoomOut -> setZoom(menu.zoom / ZOOM_STEP)
            RemoteAction.NavUp -> panels.navKey(KeyEvent.KEYCODE_DPAD_UP)
            RemoteAction.NavDown -> panels.navKey(KeyEvent.KEYCODE_DPAD_DOWN)
            RemoteAction.NavLeft -> panels.navKey(KeyEvent.KEYCODE_DPAD_LEFT)
            RemoteAction.NavRight -> panels.navKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            RemoteAction.NavSelect -> panels.navKey(KeyEvent.KEYCODE_DPAD_CENTER)
            RemoteAction.ScrollUp -> panels.scroll(SCROLL_STEP)
            RemoteAction.ScrollDown -> panels.scroll(-SCROLL_STEP)
            RemoteAction.AxisScroll, RemoteAction.AxisSeek, RemoteAction.AxisZoom,
            RemoteAction.AxisNavHorizontal, RemoteAction.AxisNavVertical -> Unit
        }
    }

    private companion object {
        const val SEEK_STEP_MS = 10_000L
        const val LONG_SEEK_STEP_MS = 30_000L
        const val SCROLL_STEP = 1f
        const val ZOOM_STEP = 1.15f
        const val DEAD_ZONE = 0.2f
        const val NAV_THRESHOLD = 0.6f
        const val LABEL_THRESHOLD = 0.5f
        const val CAPTURE_THRESHOLD = 0.6f
        const val CAPTURE_SETTLE_MS = 400L
        const val AXIS_PRESS = 0.6f
        const val AXIS_RELEASE = 0.3f
        const val AXIS_TICK_MS = 50L
        const val AXIS_SCROLL_PER_TICK = 0.3f
        const val AXIS_ZOOM_PER_TICK = 0.035f

        /** При полном отклонении — около 20 секунд видео за секунду. */
        const val AXIS_SEEK_MS_PER_TICK = 1_000f

        /** Первую секунду скорость постоянная. */
        const val AXIS_SEEK_STEADY_TICKS = 20

        /** Дальше за это число шагов она вырастает ещё на одну базовую: вдвое за 1,5 с. */
        const val AXIS_SEEK_TICKS_TO_DOUBLE = 30f
        const val AXIS_SEEK_MAX_SPEED_UP = 6f
        const val HELD_SEEK_DIVIDER = 4L

        /** Два кода от одной кнопки приходят почти одновременно. */
        const val SAME_PRESS_MS = 150L
        val OK_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A)

        val SMOOTH_AXES = listOf(RemoteAction.AxisScroll, RemoteAction.AxisSeek, RemoteAction.AxisZoom)

        /** Действия, которые повторяются, пока кнопку держат. */
        val REPEATABLE = setOf(
            RemoteAction.SeekBack, RemoteAction.SeekForward, RemoteAction.SeekBackLong, RemoteAction.SeekForwardLong,
            RemoteAction.VolumeUp, RemoteAction.VolumeDown,
            RemoteAction.NavUp, RemoteAction.NavDown, RemoteAction.NavLeft, RemoteAction.NavRight,
            RemoteAction.ScrollUp, RemoteAction.ScrollDown,
        )
    }
}

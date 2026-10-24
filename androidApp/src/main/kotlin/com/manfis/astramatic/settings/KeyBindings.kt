package com.manfis.astramatic.settings

import android.content.SharedPreferences
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.mutableStateMapOf

/** Группа в настройках пульта. */
enum class ActionGroup(val title: String) {
    Main("Основное"),
    Playback("Воспроизведение"),
    View("Вид"),
    Menu("Меню"),
    Axes("Оси геймпада и стики"),
}

/**
 * Что можно повесить на пульт. Кнопочные действия срабатывают от нажатия кнопки,
 * осевые ([isAxis]) — от стика, курка или крестовины, плавно по силе отклонения.
 * По умолчанию почти всё пусто: пульты слишком разные, назначьте под свой.
 */
enum class RemoteAction(
    val title: String,
    val group: ActionGroup,
    val isAxis: Boolean = false,
    vararg defaults: Int,
) {
    Click("Нажатие — как кнопка Cardboard (удерживать и вести головой — прокрутка)", ActionGroup.Main),
    Back("Назад / закрыть меню", ActionGroup.Main, false, KeyEvent.KEYCODE_BACK),
    Controls("Полоска → меню → скрыть", ActionGroup.Main, false, KeyEvent.KEYCODE_MENU),

    PlayPause("Пауза / воспроизведение", ActionGroup.Playback, false, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
    SeekBack("Назад на 10 с", ActionGroup.Playback),
    SeekForward("Вперёд на 10 с", ActionGroup.Playback),
    SeekBackLong("Назад на 30 с", ActionGroup.Playback),
    SeekForwardLong("Вперёд на 30 с", ActionGroup.Playback),
    PreviousFile("Предыдущее видео", ActionGroup.Playback),
    NextFile("Следующее видео", ActionGroup.Playback),
    VolumeUp("Громче", ActionGroup.Playback),
    VolumeDown("Тише", ActionGroup.Playback),

    Recenter("Рецентр", ActionGroup.View),

    NavUp("Вверх по меню", ActionGroup.Menu),
    NavDown("Вниз по меню", ActionGroup.Menu),
    NavLeft("Влево по меню", ActionGroup.Menu),
    NavRight("Вправо по меню", ActionGroup.Menu),
    NavSelect("Выбрать пункт меню", ActionGroup.Menu),
    ScrollUp("Прокрутить меню вверх", ActionGroup.Menu),
    ScrollDown("Прокрутить меню вниз", ActionGroup.Menu),

    AxisScroll("Прокрутка меню", ActionGroup.Axes, true),
    AxisSeek("Перемотка — чем сильнее отклонение, тем быстрее", ActionGroup.Axes, true),
    AxisNavHorizontal("Влево / вправо по меню", ActionGroup.Axes, true),
    AxisNavVertical("Вверх / вниз по меню", ActionGroup.Axes, true),
    ;

    val defaultKeys: Set<Int> = defaults.toSet()
}

/**
 * Ось устройства ввода. [inverted] — «вперёд» для действия даёт отрицательные значения,
 * [rest] — положение покоя: у стиков 0, у некоторых курков −1 (тогда ход оси вдвое длиннее).
 */
data class AxisBinding(val axis: Int, val inverted: Boolean, val rest: Int = 0) {
    val title: String get() = "${MotionEvent.axisToString(axis).removePrefix("AXIS_")}${if (inverted) " ↺" else ""}"

    /** Отклонение от покоя для действия: −1..1 (у курков 0..1). */
    fun valueOf(raw: Float): Float {
        val centered = if (rest == 0) raw else (raw - rest) / 2
        return if (inverted) -centered else centered
    }

    fun encode() = "$axis:${if (inverted) 1 else 0}:$rest"

    companion object {
        fun decode(text: String): AxisBinding? {
            val parts = text.split(':')
            if (parts.size < 2) return null
            return AxisBinding(parts[0].toIntOrNull() ?: return null, parts[1] == "1", parts.getOrNull(2)?.toIntOrNull() ?: 0)
        }
    }
}

/**
 * Назначения кнопок и осей. Одна кнопка (ось) — одно действие: при назначении она снимается с прежнего.
 * Хранится в SharedPreferences, на экране обновляется само (Compose-состояние).
 */
class KeyBindings(private val prefs: SharedPreferences) {

    private val keys = mutableStateMapOf<RemoteAction, Set<Int>>()
    private val axes = mutableStateMapOf<RemoteAction, Set<AxisBinding>>()

    init {
        // Версии схемы: 2 — старые умолчания сброшены; 3 — оси, назначенные с неверным покоем, сброшены.
        val schema = prefs.getInt(SCHEMA_KEY, 0)
        if (schema < SCHEMA) {
            prefs.edit().apply {
                prefs.all.keys
                    .filter { (schema < 2 && it.startsWith("key_")) || it.startsWith("axis_") }
                    .forEach(::remove)
                putInt(SCHEMA_KEY, SCHEMA)
            }.apply()
        }
        RemoteAction.entries.forEach { action ->
            keys[action] = prefs.getString(keyPref(action), null)
                ?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet()
                ?: action.defaultKeys
            axes[action] = prefs.getString(axisPref(action), null)
                ?.split(',')?.mapNotNull(AxisBinding::decode)?.toSet()
                .orEmpty()
        }
    }

    fun keysOf(action: RemoteAction): Set<Int> = keys[action].orEmpty()

    fun axesOf(action: RemoteAction): Set<AxisBinding> = axes[action].orEmpty()

    fun actionFor(keyCode: Int): RemoteAction? = keys.entries.firstOrNull { keyCode in it.value }?.key

    /** Все осевые назначения: (действие, ось). */
    fun axisBindings(): List<Pair<RemoteAction, AxisBinding>> =
        axes.entries.flatMap { (action, set) -> set.map { action to it } }

    fun assignKey(action: RemoteAction, keyCode: Int) {
        RemoteAction.entries.forEach { other ->
            if (other != action && keyCode in keysOf(other)) saveKeys(other, keysOf(other) - keyCode)
        }
        saveKeys(action, keysOf(action) + keyCode)
    }

    fun assignAxis(action: RemoteAction, binding: AxisBinding) {
        RemoteAction.entries.forEach { other ->
            val same = axesOf(other).filter { it.axis == binding.axis }.toSet()
            if (same.isNotEmpty()) saveAxes(other, axesOf(other) - same)
        }
        saveAxes(action, axesOf(action) + binding)
    }

    fun removeKey(action: RemoteAction, keyCode: Int) = saveKeys(action, keysOf(action) - keyCode)

    fun removeAxis(action: RemoteAction, binding: AxisBinding) = saveAxes(action, axesOf(action) - binding)

    /** Развернуть направление оси, если «вверх» работает как «вниз». */
    fun invertAxis(action: RemoteAction, binding: AxisBinding) =
        saveAxes(action, axesOf(action) - binding + binding.copy(inverted = !binding.inverted))

    fun resetAll() = RemoteAction.entries.forEach {
        saveKeys(it, it.defaultKeys)
        saveAxes(it, emptySet())
    }

    fun clearAll() = RemoteAction.entries.forEach {
        saveKeys(it, emptySet())
        saveAxes(it, emptySet())
    }

    private fun saveKeys(action: RemoteAction, set: Set<Int>) {
        keys[action] = set
        prefs.edit().putString(keyPref(action), set.joinToString(",")).apply()
    }

    private fun saveAxes(action: RemoteAction, set: Set<AxisBinding>) {
        axes[action] = set
        prefs.edit().putString(axisPref(action), set.joinToString(",") { it.encode() }).apply()
    }

    private fun keyPref(action: RemoteAction) = "key_${action.name}"
    private fun axisPref(action: RemoteAction) = "axis_${action.name}"

    companion object {
        private const val SCHEMA_KEY = "bindings_schema"
        private const val SCHEMA = 3

        fun keyName(keyCode: Int): String = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
    }
}

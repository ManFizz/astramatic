package com.manfis.astramatic.settings

import android.content.SharedPreferences
import android.view.KeyEvent
import androidx.compose.runtime.mutableStateMapOf

/** Что можно повесить на кнопку пульта. Пока открыто меню, стрелки и OK ходят по нему и не переназначаются. */
enum class RemoteAction(val title: String, vararg defaults: Int) {
    PlayPause(
        "Пауза / воспроизведение",
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    ),
    SeekBack("Назад на 10 с", KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_BUTTON_L1),
    SeekForward("Вперёд на 10 с", KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_BUTTON_R1),
    SeekBackLong("Назад на 30 с", KeyEvent.KEYCODE_BUTTON_L2),
    SeekForwardLong("Вперёд на 30 с", KeyEvent.KEYCODE_BUTTON_R2),
    PreviousFile("Предыдущий файл", KeyEvent.KEYCODE_MEDIA_PREVIOUS),
    NextFile("Следующий файл", KeyEvent.KEYCODE_MEDIA_NEXT),
    Recenter("Рецентр", KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_BUTTON_Y),
    Controls(
        "Полоска → меню → скрыть",
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
    ),
    ToggleStereo("2D / VR", KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_BUTTON_X);

    val defaultKeys: Set<Int> = defaults.toSet()
}

/** Назначения кнопок. Одна кнопка — одно действие: при назначении она снимается с прежнего. */
class KeyBindings(private val prefs: SharedPreferences) {

    private val bindings = mutableStateMapOf<RemoteAction, Set<Int>>().apply {
        RemoteAction.entries.forEach { action ->
            val saved = prefs.getString(prefKey(action), null)
            put(action, saved?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet() ?: action.defaultKeys)
        }
    }

    fun keysOf(action: RemoteAction): Set<Int> = bindings[action].orEmpty()

    fun actionFor(keyCode: Int): RemoteAction? = bindings.entries.firstOrNull { keyCode in it.value }?.key

    fun assign(action: RemoteAction, keyCode: Int) {
        RemoteAction.entries.forEach { other ->
            if (other != action && keyCode in keysOf(other)) save(other, keysOf(other) - keyCode)
        }
        save(action, keysOf(action) + keyCode)
    }

    fun clear(action: RemoteAction) = save(action, emptySet())

    fun resetAll() = RemoteAction.entries.forEach { save(it, it.defaultKeys) }

    private fun save(action: RemoteAction, keys: Set<Int>) {
        bindings[action] = keys
        prefs.edit().putString(prefKey(action), keys.joinToString(",")).apply()
    }

    private fun prefKey(action: RemoteAction) = "key_${action.name}"

    companion object {
        fun keyName(keyCode: Int): String = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
    }
}

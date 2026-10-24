package com.manfis.astramatic.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Настройки приложения. Значения — Compose-состояние, поэтому экраны обновляются сами,
 * а каждое изменение сразу пишется в SharedPreferences.
 */
class AppSettings private constructor(private val prefs: SharedPreferences) {

    /** Продолжать видео с места остановки. */
    var resumePlayback by pref("resume_playback", false)

    /** После конца видео открывать следующее в папке. */
    var autoplayNext by pref("autoplay_next", true)

    /** Нажатие задержкой взгляда — для шлемов без кнопки. */
    var dwellClick by pref("dwell_click", false)
    var dwellMs by pref("dwell_ms", 1500)

    /** Плоский экран: высота и расстояние в метрах. */
    var screenHeight by pref("screen_height", 2f)
    var screenDistance by pref("screen_distance", 3f)

    /** Комната вокруг плоского экрана подсвечивается цветом кадра. */
    var ambientLight by pref("ambient_light", false)

    val keys = KeyBindings(prefs)

    private fun <T> pref(key: String, default: T) = object : ReadWriteProperty<Any?, T> {
        private val state: MutableState<T> = mutableStateOf(read(key, default))

        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            prefs.edit().apply {
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                    else -> error("Тип настройки не поддержан: $key")
                }
            }.apply()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> read(key: String, default: T): T = when (default) {
        is Boolean -> prefs.getBoolean(key, default)
        is Int -> prefs.getInt(key, default)
        is Float -> prefs.getFloat(key, default)
        is String -> prefs.getString(key, default)
        else -> error("Тип настройки не поддержан: $key")
    } as T

    companion object {
        @Volatile private var instance: AppSettings? = null

        fun get(context: Context): AppSettings = instance ?: synchronized(this) {
            instance ?: AppSettings(
                context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}

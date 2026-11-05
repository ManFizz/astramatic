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
class AppSettings internal constructor(private val prefs: SharedPreferences) {

    /** Продолжать видео с места остановки. */
    var resumePlayback by pref("resume_playback", false)

    /** После конца видео открывать следующее в папке. */
    var autoplayNext by pref("autoplay_next", true)

    /** Запоминать, что смотрели. */
    var keepHistory by pref("keep_history", true)

    /** Нажатие задержкой взгляда — для шлемов без кнопки. */
    var dwellClick by pref("dwell_click", false)
    var dwellMs by pref("dwell_ms", 1500)

    /** Плоский экран: высота и расстояние в метрах. */
    var screenHeight by pref("screen_height", 2f)
    var screenDistance by pref("screen_distance", 3f)

    /** Изгиб плоского экрана: 0 — плоский, 1 — дуга вокруг зрителя. */
    var screenCurve by pref("screen_curve", 0f)

    /** Что вокруг: имя [com.manfis.astramatic.player.Environment]. */
    var environment by pref("environment", "Cinema")

    /** В кинозале стены подсвечиваются цветом кадра. */
    var ambientLight by pref("ambient_light", true)

    /** Яркость видео и фото, 0.5..1.5. */
    var brightness by pref("brightness", 1f)

    /** Расстояние до меню в метрах; видимый размер от него не зависит. */
    var menuDistance by pref("menu_distance", 6f)

    /** Глаза сводятся на меню так же, как на видео: при переводе взгляда картинка не двоится. */
    var menuDepthAuto by pref("menu_depth_auto", true)

    /** Размер меню — множитель к обычному; меняется и уголками меню в шлеме. */
    var menuSize by pref("menu_size", 1f)

    /** Подтягивать поворот головы к компасу: центр не уплывает со временем. */
    var compassCorrection by pref("compass_correction", true)

    /** Режим «лёжа»: рецентр поворачивает картинку и вверх-вниз, туда же ставится меню. */
    var recenterPitch by pref("recenter_pitch", false)

    /** Слайд-шоу фото: включается в плеере, [slideshowSeconds] — секунд на кадр. */
    var slideshow by pref("slideshow", false)
    var slideshowSeconds by pref("slideshow_interval", 5)

    /** Повтор: имя [RepeatMode]. */
    var repeatMode by pref("repeat_mode", RepeatMode.Off.name)

    /** Случайный порядок файлов папки. */
    var shuffle by pref("shuffle", false)

    /** Формат файлов без тегов в имени, [com.manfis.astramatic.model.VideoFormat.encode]. */
    var defaultVideoFormat by pref("default_video_format", "Dome180|SideBySide|180|0")
    var defaultImageFormat by pref("default_image_format", "Flat|Mono|180|0")

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

/** Что делать в конце файла (и у краёв папки). */
enum class RepeatMode(val title: String) {
    Off("Повтор выкл."),
    All("Повтор папки"),
    One("Повтор файла"),
}

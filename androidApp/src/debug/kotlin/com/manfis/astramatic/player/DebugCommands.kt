package com.manfis.astramatic.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.manfis.astramatic.model.VideoFormat
import com.manfis.astramatic.model.VideoFormatDetector
import com.manfis.astramatic.remote.VideoFiles
import com.manfis.astramatic.settings.AppSettings
import com.manfis.astramatic.ui.VrMenuState

/**
 * Команды для проверки в эмуляторе, только в debug-сборке. Описание — в tools/emu.sh.
 * В ответ на любую команду приходит состояние плеера: так проверке не нужно разбирать картинку.
 */
object DebugCommands {
    private const val ACTION = "com.manfis.astramatic.debug.COMMAND"

    /** Очередь из адресов через запятую, начиная с первого. Формат — по имени файла. */
    private fun playlistOf(uris: String) = Playlist(
        uris.split(',').map { text ->
            val uri = Uri.parse(text)
            val name = uri.lastPathSegment.orEmpty()
            PlaylistItem(uri, name, VideoFormatDetector.detect(name) ?: VideoFormat(), isImage = VideoFiles.isImage(name))
        },
        0,
    )

    fun install(
        activity: ComponentActivity,
        renderer: VrRenderer,
        host: PanelHost,
        menu: VrMenuState,
        settings: AppSettings,
        open: (Playlist) -> Unit,
    ) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getStringExtra("cmd")) {
                    "aim" -> renderer.debugLook = DebugLook.PanelPoint(
                        intent.getFloatExtra("px", 0f),
                        intent.getFloatExtra("py", 0f),
                    )
                    "angle" -> renderer.debugLook = DebugLook.Angles(
                        intent.getFloatExtra("yaw", 0f),
                        intent.getFloatExtra("pitch", 0f),
                    )
                    "free" -> renderer.debugLook = null
                    "open" -> open(playlistOf(intent.getStringExtra("uris").orEmpty()))
                    "browse" -> {
                        menu.mode = VrMenuState.Mode.Menu
                        menu.tab = VrMenuState.Tab.Home
                        menu.library.openBrowser(intent.getStringExtra("url"))
                    }
                }
                resultData = buildString {
                    append("place=").append(host.place)
                    append(" mode=").append(menu.mode)
                    append(" tab=").append(menu.tab)
                    append(" sheet=").append(menu.sheet)
                    append(" title=").append(menu.title)
                    append(" parked=").append(menu.parkedTitle)
                    append(" savedSize=").append(settings.menuSize)
                    append(" browser=").append(menu.library.browser.url)
                    append(" webTitle=").append(menu.library.browser.title.replace(' ', '_'))
                    append(" keyboard=").append(menu.keyboard.target?.label?.replace(' ', '_'))
                    append(" typed=").append(menu.keyboard.text.replace(' ', '_'))
                    append(' ').append(renderer.debugState())
                }
            }
        }
        activity.registerReceiver(receiver, IntentFilter(ACTION), Context.RECEIVER_EXPORTED)
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = activity.unregisterReceiver(receiver)
        })
    }
}

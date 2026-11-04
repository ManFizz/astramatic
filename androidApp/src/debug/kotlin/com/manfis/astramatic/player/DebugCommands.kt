package com.manfis.astramatic.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.manfis.astramatic.settings.AppSettings
import com.manfis.astramatic.ui.VrMenuState

/**
 * Команды для проверки в эмуляторе, только в debug-сборке. Описание — в tools/emu.sh.
 * В ответ на любую команду приходит состояние плеера: так проверке не нужно разбирать картинку.
 */
object DebugCommands {
    private const val ACTION = "com.manfis.astramatic.debug.COMMAND"

    fun install(activity: ComponentActivity, renderer: VrRenderer, host: PanelHost, menu: VrMenuState, settings: AppSettings) {
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
                }
                resultData = buildString {
                    append("place=").append(host.place)
                    append(" mode=").append(menu.mode)
                    append(" tab=").append(menu.tab)
                    append(" sheet=").append(menu.sheet)
                    append(" savedSize=").append(settings.menuSize)
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

package com.manfis.astramatic.player

import androidx.activity.ComponentActivity
import com.manfis.astramatic.settings.AppSettings
import com.manfis.astramatic.ui.VrMenuState

/** В release отладочных команд нет. */
object DebugCommands {
    @Suppress("UNUSED_PARAMETER")
    fun install(activity: ComponentActivity, renderer: VrRenderer, host: PanelHost, menu: VrMenuState, settings: AppSettings) = Unit
}

package com.manfis.astramatic

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.manfis.astramatic.desktop.ConfigHolder
import com.manfis.astramatic.desktop.DesktopApp
import com.manfis.astramatic.desktop.MediaServer
import com.manfis.astramatic.desktop.RemoteHub
import com.manfis.astramatic.remote.RemoteCommand
import io.github.vinceglb.filekit.FileKit

fun main() {
    // Системные окна выбора файлов и папок.
    FileKit.init(appId = "AstraMatic")

    val holder = ConfigHolder()
    val hub = RemoteHub()
    val server = MediaServer(holder, hub).apply { start() }

    application {
        Window(
            onCloseRequest = {
                server.stop()
                exitApplication()
            },
            title = "AstraMatic",
            state = rememberWindowState(width = 1180.dp, height = 800.dp),
            // Клавиши пульта работают, когда окно в фокусе.
            onKeyEvent = { event ->
                if (event.type != KeyEventType.KeyDown) return@Window false
                val long = event.isShiftPressed
                val command = when (event.key) {
                    Key.Spacebar -> RemoteCommand.TogglePlayback
                    Key.DirectionLeft -> RemoteCommand.SeekBy(if (long) -30_000 else -10_000)
                    Key.DirectionRight -> RemoteCommand.SeekBy(if (long) 30_000 else 10_000)
                    Key.PageUp -> RemoteCommand.PreviousFile
                    Key.PageDown -> RemoteCommand.NextFile
                    Key.R -> RemoteCommand.Recenter
                    else -> null
                } ?: return@Window false
                hub.send(command)
                true
            },
        ) {
            DesktopApp(holder, server, hub)
        }
    }
}

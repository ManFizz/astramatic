package com.manfis.astramatic.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.manfis.astramatic.headset.HeadsetScreen

/**
 * Главная — файлы, как в Play'a: с неё всё начинается и на неё возвращает «Назад».
 * Настройки (общие, шлем, пульт) — отдельный раздел, формат текущего видео — тоже.
 */
@Composable
internal fun MenuContent(state: VrMenuState, actions: VrMenuActions) {
    Column(Modifier.fillMaxSize()) {
        state.notice?.let { notice ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
            ) {
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(notice, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { state.notice = null }) { Text("✕") }
                }
            }
        }
        val home = state.tab == VrMenuState.Tab.Home
        val hub = state.showingHub
        // Из настроек при открытом видео «Главная» — это тоже выход в хаб.
        val goHome: () -> Unit = {
            if (state.title != null) {
                actions.toHub()
            } else {
                state.tab = VrMenuState.Tab.Home
            }
        }
        if (!hub) Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (home) {
                Text("AstraMatic", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
                Spacer(Modifier.weight(1f))
            } else {
                TextButton(onClick = goHome) { Text("← Главная") }
                // Разделы настроек — вкладками; прокручиваются вбок, если не влезают.
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.tab == VrMenuState.Tab.Video) {
                        Text("Формат видео", style = MaterialTheme.typography.titleMedium)
                    } else {
                        TabChip(state, VrMenuState.Tab.Settings, "⚙ Общие")
                        TabChip(state, VrMenuState.Tab.Headset, "👓 Шлем")
                        TabChip(state, VrMenuState.Tab.Remote, "🎮 Пульт")
                    }
                }
            }
            if (state.title != null) {
                if (home) OutlinedButton(onClick = { state.tab = VrMenuState.Tab.Video }) { Text("Формат") }
                Button(onClick = actions::hide) { Text("▶ К видео") }
            }
            if (home) FilledTonalButton(onClick = { state.tab = VrMenuState.Tab.Settings }) { Text("⚙ Настройки") }
            // Выйти из VR и включить 2D можно только пальцем — крестиком и кнопкой на экране.
            if (!state.stereo) {
                OutlinedButton(onClick = actions::toggleStereo) { Text("VR") }
                TextButton(onClick = actions::exit) { Text("Выход") }
            }
        }
        if (!hub) HorizontalDivider()
        val parked = state.parkedTitle
        if (!hub && home && parked != null) {
            Button(
                onClick = actions::resumeViewing,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("▶ Продолжить: $parked", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (state.tab) {
                VrMenuState.Tab.Home -> LibraryApp(
                    onPlay = actions::play,
                    state = state.library,
                    embedded = true,
                    allowEditing = !state.stereo,
                    hub = if (state.stereo) {
                        HubChrome(
                            parkedTitle = parked,
                            videoOpen = state.title != null,
                            onResume = actions::resumeViewing,
                            onToVideo = actions::hide,
                            onFormat = { state.tab = VrMenuState.Tab.Video },
                            onSettings = { state.tab = VrMenuState.Tab.Settings },
                        )
                    } else {
                        null
                    },
                )
                VrMenuState.Tab.Video -> VideoTab(state, actions)
                VrMenuState.Tab.Headset -> HeadsetScreen(
                    onBack = goHome,
                    onSaved = actions::headsetSaved,
                    closeOnSave = false,
                    // В шлеме — на лету, вокруг калибровочная комната.
                    live = state.stereo,
                )
                VrMenuState.Tab.Remote -> RemoteScreen(state)
                VrMenuState.Tab.Settings -> SettingsScreen()
            }
        }
    }
}

@Composable
private fun TabChip(state: VrMenuState, tab: VrMenuState.Tab, label: String) {
    FilterChip(selected = state.tab == tab, onClick = { state.tab = tab }, label = { Text(label) })
}

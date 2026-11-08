package com.manfis.astramatic.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
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
                Row(Modifier.padding(start = 18.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(notice, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    AppIconButton(Icons.Rounded.Close, "Закрыть", { state.notice = null })
                }
            }
        }
        val home = state.tab == VrMenuState.Tab.Home
        // У хаба и браузера своя верхняя строка: общая только отняла бы место.
        val hub = state.showingHub || (state.tab == VrMenuState.Tab.Home && state.library.inBrowser)
        // Из настроек при открытом видео «Главная» — это тоже выход в хаб.
        val goHome: () -> Unit = {
            if (state.title != null) {
                actions.toHub()
            } else {
                state.tab = VrMenuState.Tab.Home
            }
        }
        if (!hub) Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (home) {
                Text("AstraMatic", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
                Spacer(Modifier.weight(1f))
            } else {
                AppIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Главная", goHome, style = ButtonStyle.Tonal)
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (state.tab == VrMenuState.Tab.Video) {
                        Text("Формат видео", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
                    } else {
                        // Разделы настроек — один выбор из трёх.
                        Segmented(
                            listOf(VrMenuState.Tab.Settings, VrMenuState.Tab.Headset, VrMenuState.Tab.Remote),
                            state.tab,
                            {
                                when (it) {
                                    VrMenuState.Tab.Headset -> "Шлем"
                                    VrMenuState.Tab.Remote -> "Пульт"
                                    else -> "Общие"
                                }
                            },
                            { state.tab = it },
                        )
                    }
                }
            }
            if (state.title != null) {
                if (home) AppButton("Формат", { state.tab = VrMenuState.Tab.Video }, icon = Icons.Rounded.Tune)
                AppButton("К видео", actions::hide, icon = Icons.Rounded.PlayArrow, style = ButtonStyle.Filled)
            }
            if (home) AppButton("Настройки", { state.tab = VrMenuState.Tab.Settings }, icon = Icons.Rounded.Settings)
            // Выйти из VR и включить 2D можно только пальцем — крестиком и кнопкой на экране.
            if (!state.stereo) {
                AppButton("VR", actions::toggleStereo)
                AppButton("Выход", actions::exit, style = ButtonStyle.Ghost)
            }
        }
        if (!hub) Divider()
        val parked = state.parkedTitle
        if (!hub && home && parked != null) {
            AppButton(
                "Продолжить: $parked",
                actions::resumeViewing,
                icon = Icons.Rounded.PlayArrow,
                style = ButtonStyle.Filled,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (state.tab) {
                VrMenuState.Tab.Home -> LibraryApp(
                    onPlay = actions::play,
                    state = state.library,
                    embedded = true,
                    allowEditing = true,
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
                    header = false,
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

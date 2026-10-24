package com.manfis.astramatic.ui

import android.net.Uri
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.manfis.astramatic.headset.HeadsetScreen
import com.manfis.astramatic.model.Projection
import com.manfis.astramatic.model.StereoLayout
import com.manfis.astramatic.model.VideoFormat
import kotlinx.coroutines.delay

/** Состояние меню плеера; живёт в активности, поэтому переживает переключение VR ⇄ 2D. */
class VrMenuState {
    enum class Tab { Player, Files, Headset }

    var tab by mutableStateOf(Tab.Files)

    /** Имя открытого видео, null — видео ещё не выбрано. */
    var title by mutableStateOf<String?>(null)
    var format by mutableStateOf(VideoFormat())
    var stereo by mutableStateOf(true)

    val library = LibraryState()
}

/** Что меню умеет делать с плеером. */
interface VrMenuActions {
    fun isPlaying(): Boolean
    fun positionMs(): Long
    fun durationMs(): Long
    fun togglePlayback()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)
    fun setFormat(format: VideoFormat)
    fun toggleStereo()
    fun recenter()
    fun play(uri: Uri, name: String, format: VideoFormat)
    fun headsetSaved()
    fun closeMenu()
    fun exit()
}

@Composable
fun VrMenu(state: VrMenuState, actions: VrMenuActions) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        // В шлеме панель со скруглёнными углами висит в пространстве, на экране телефона — на весь экран.
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = if (state.stereo) RoundedCornerShape(28.dp) else RoundedCornerShape(0.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        ) {
            Column(Modifier.fillMaxSize()) {
                TopBar(state, actions)
                HorizontalDivider()
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (state.tab) {
                        VrMenuState.Tab.Player -> PlayerTab(state, actions)
                        VrMenuState.Tab.Files -> LibraryApp(
                            onPlay = actions::play,
                            state = state.library,
                            embedded = true,
                            allowEditing = !state.stereo,
                        )
                        VrMenuState.Tab.Headset -> HeadsetScreen(
                            onBack = {
                                state.tab = if (state.title != null) VrMenuState.Tab.Player else VrMenuState.Tab.Files
                            },
                            onSaved = actions::headsetSaved,
                            closeOnSave = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(state: VrMenuState, actions: VrMenuActions) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.title != null) TabChip(state, VrMenuState.Tab.Player, "▶ Плеер")
        TabChip(state, VrMenuState.Tab.Files, "📁 Файлы")
        TabChip(state, VrMenuState.Tab.Headset, "👓 Шлем")
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = actions::toggleStereo) { Text(if (state.stereo) "2D" else "VR") }
        if (state.title != null) Button(onClick = actions::closeMenu) { Text("К видео") }
        TextButton(onClick = actions::exit) { Text("Выход") }
    }
}

@Composable
private fun TabChip(state: VrMenuState, tab: VrMenuState.Tab, label: String) {
    FilterChip(selected = state.tab == tab, onClick = { state.tab = tab }, label = { Text(label) })
}

@Composable
private fun PlayerTab(state: VrMenuState, actions: VrMenuActions) {
    var position by remember { mutableLongStateOf(actions.positionMs()) }
    var duration by remember { mutableLongStateOf(actions.durationMs()) }
    var playing by remember { mutableStateOf(actions.isPlaying()) }
    var dragging by remember { mutableStateOf<Float?>(null) }

    // Плеер не сообщает о каждом сдвиге позиции, проще опрашивать, пока вкладка на экране.
    LaunchedEffect(Unit) {
        while (true) {
            position = actions.positionMs()
            duration = actions.durationMs()
            playing = actions.isPlaying()
            delay(250)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            state.title ?: "",
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Slider(
            value = dragging ?: position.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { actions.seekTo(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(dragging?.toLong() ?: position))
            Spacer(Modifier.weight(1f))
            Text(formatTime(duration))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        ) {
            OutlinedButton(onClick = { actions.seekBy(-30_000) }) { Text("−30 с") }
            OutlinedButton(onClick = { actions.seekBy(-10_000) }) { Text("−10 с") }
            Button(onClick = actions::togglePlayback) { Text(if (playing) "⏸ Пауза" else "▶ Смотреть") }
            OutlinedButton(onClick = { actions.seekBy(10_000) }) { Text("+10 с") }
            OutlinedButton(onClick = { actions.seekBy(30_000) }) { Text("+30 с") }
        }

        Text("Формат видео", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Projection.entries.forEach {
                FilterChip(
                    selected = state.format.projection == it,
                    onClick = { actions.setFormat(state.format.copy(projection = it)) },
                    label = { Text(projectionLabel(it)) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StereoLayout.entries.forEach {
                FilterChip(
                    selected = state.format.stereo == it,
                    onClick = { actions.setFormat(state.format.copy(stereo = it)) },
                    label = { Text(stereoLabel(it)) },
                )
            }
        }

        OutlinedButton(onClick = actions::recenter) { Text("Рецентр — центр видео туда, куда смотрю") }
    }
}

private fun formatTime(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

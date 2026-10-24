package com.manfis.astramatic

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.manfis.astramatic.headset.HeadsetActivity
import com.manfis.astramatic.model.Projection
import com.manfis.astramatic.model.StereoLayout
import com.manfis.astramatic.model.VideoFormat
import com.manfis.astramatic.player.PlayerActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    LibraryScreen(onPlay = { uri, format ->
                        startActivity(PlayerActivity.intent(this, uri, format))
                    })
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(onPlay: (Uri, VideoFormat) -> Unit) {
    val context = LocalContext.current
    var format by remember { mutableStateOf(VideoFormat()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Чтобы файл открывался и после перезапуска (пригодится для истории просмотров).
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onPlay(uri, format)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().safeContentPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AstraMatic", style = MaterialTheme.typography.headlineMedium)

        Text("Проекция", style = MaterialTheme.typography.titleMedium)
        OptionRow(Projection.entries, format.projection, ::projectionLabel) {
            format = format.copy(projection = it)
        }

        Text("Стерео", style = MaterialTheme.typography.titleMedium)
        OptionRow(StereoLayout.entries, format.stereo, ::stereoLabel) {
            format = format.copy(stereo = it)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(arrayOf("video/*")) }) {
                Text("Открыть видео")
            }
            OutlinedButton(onClick = { context.startActivity(Intent(context, HeadsetActivity::class.java)) }) {
                Text("Настроить шлем")
            }
        }

        Text(
            "Тап — пауза, долгое нажатие — рецентр, шестерёнка в плеере — настройка шлема.\n" +
                "Пульт/геймпад: OK или A — пауза, ←/→ — перемотка на 10 с, ↑ или Y — рецентр.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun <T> OptionRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

private fun projectionLabel(projection: Projection) = when (projection) {
    Projection.Flat -> "Плоское"
    Projection.Dome180 -> "180°"
    Projection.Sphere360 -> "360°"
}

private fun stereoLabel(stereo: StereoLayout) = when (stereo) {
    StereoLayout.Mono -> "Моно"
    StereoLayout.SideBySide -> "SBS"
    StereoLayout.TopBottom -> "Над/под"
}

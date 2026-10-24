package com.manfis.astramatic.headset

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun HeadsetScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var params by remember { mutableStateOf(HeadsetParams.load(context)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeContentPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Настройка шлема", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Для шлемов без QR-кода. Меняйте по одному параметру и проверяйте в плеере.",
            style = MaterialTheme.typography.bodySmall,
        )

        ParamSlider(
            title = "Расстояние между линзами, мм",
            hint = "Картинка двоится или глаза напрягаются — подстройте это первым.",
            value = params.interLensMm, range = 50f..75f, decimals = 1,
        ) { params = params.copy(interLensMm = it) }

        ParamSlider(
            title = "Экран — линза, мм",
            hint = "Влияет на масштаб: если всё кажется слишком крупным или мелким.",
            value = params.screenToLensMm, range = 25f..65f, decimals = 1,
        ) { params = params.copy(screenToLensMm = it) }

        ParamSlider(
            title = "Поле зрения линзы, °",
            hint = "Насколько широкой видна картинка; у типичных VR-боксов 45–60°.",
            value = params.fovDeg, range = 30f..70f, decimals = 0,
        ) { params = params.copy(fovDeg = it) }

        ParamSlider(
            title = "Коррекция искажения k1",
            hint = "Прямые линии у краёв выгибаются наружу — увеличьте, внутрь — уменьшите.",
            value = params.k1, range = 0f..1f, decimals = 3,
        ) { params = params.copy(k1 = it) }

        ParamSlider(
            title = "Коррекция искажения k2",
            hint = "Тонкая подстройка самых краёв, обычно меньше k1.",
            value = params.k2, range = 0f..1f, decimals = 3,
        ) { params = params.copy(k2 = it) }

        Text("Положение телефона", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeadsetParams.Alignment.entries.forEach { alignment ->
                FilterChip(
                    selected = params.alignment == alignment,
                    onClick = { params = params.copy(alignment = alignment) },
                    label = {
                        Text(
                            when (alignment) {
                                HeadsetParams.Alignment.Bottom -> "Упор снизу"
                                HeadsetParams.Alignment.Center -> "По центру"
                                HeadsetParams.Alignment.Top -> "Упор сверху"
                            }
                        )
                    },
                )
            }
        }
        Text(
            "Большинство боксов зажимают телефон по центру. Если центры картинок выше или ниже зрачков — выберите упор.",
            style = MaterialTheme.typography.bodySmall,
        )

        if (params.alignment != HeadsetParams.Alignment.Center) {
            ParamSlider(
                title = "Упор — центр линзы, мм",
                hint = "Расстояние от края телефона, на который он опирается, до центра линз.",
                value = params.trayToLensMm, range = 10f..60f, decimals = 1,
            ) { params = params.copy(trayToLensMm = it) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val saved = params.save(context)
                Toast.makeText(context, if (saved) "Сохранено" else "Не удалось сохранить", Toast.LENGTH_SHORT).show()
                if (saved) onBack()
            }) { Text("Сохранить") }
            OutlinedButton(onClick = { params = HeadsetParams.CardboardV1 }) { Text("Как Cardboard v1") }
            OutlinedButton(onClick = onBack) { Text("Назад") }
        }
    }
}

@Composable
private fun ParamSlider(
    title: String,
    hint: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    decimals: Int,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(
            "$title: ${String.format(Locale.US, "%.${decimals}f", value)}",
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
        Text(hint, style = MaterialTheme.typography.bodySmall)
    }
}

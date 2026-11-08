package com.manfis.astramatic.headset

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * [onSaved] — параметры записаны (плееру пора их перечитать).
 * [closeOnSave] = false — остаться на экране после сохранения: в шлеме так удобнее подбирать значения.
 * [live] — в шлеме: каждое изменение сразу применяется (виден результат на калибровочной сетке);
 * «Отменить» или уход с экрана без «Сохранить» возвращают как было.
 */
@Composable
fun HeadsetScreen(onBack: () -> Unit, onSaved: () -> Unit = {}, closeOnSave: Boolean = false, live: Boolean = false, header: Boolean = true) {
    val context = LocalContext.current
    var original by remember { mutableStateOf(HeadsetParams.load(context)) }
    var params by remember { mutableStateOf(original) }
    var status by remember { mutableStateOf<String?>(null) }

    if (live) {
        var applied by remember { mutableStateOf(original) }
        // Ползунок шлёт значения часто — применяем, когда он чуть замер.
        LaunchedEffect(params) {
            delay(LIVE_APPLY_DELAY_MS)
            if (params != applied && params.save(context)) {
                applied = params
                onSaved()
            }
        }
        // Ушли, не сохранив: в шлеме должно остаться то, что было сохранено.
        val inDevice by rememberUpdatedState(applied)
        val saved by rememberUpdatedState(original)
        DisposableEffect(Unit) {
            onDispose {
                if (inDevice != saved && saved.save(context)) onSaved()
            }
        }
    }

    Column(Modifier.fillMaxSize().safeContentPadding()) {
        // В меню шлема заголовок уже есть — вкладка «Шлем».
        if (header) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("Назад") }
                Text("Настройка шлема", style = MaterialTheme.typography.titleLarge)
            }
        }
        HeadsetForm(
            params = params,
            live = live,
            onChange = {
                params = it
                status = null
            },
            status = status,
            onSave = {
                val saved = params.save(context)
                status = if (saved) "Сохранено." else "Не удалось сохранить."
                if (saved) {
                    original = params
                    onSaved()
                    if (closeOnSave) onBack()
                }
            },
            onCancel = {
                params = original
                status = "Вернул как было."
            },
        )
    }
}

@Composable
private fun HeadsetForm(
    params: HeadsetParams,
    live: Boolean,
    onChange: (HeadsetParams) -> Unit,
    status: String?,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        com.manfis.astramatic.ui.Hint(
            if (live) {
                "Изменения видны сразу на сетке вокруг. Линии стен должны быть прямыми до самых краёв, " +
                    "крест в центре — не двоиться. «Сохранить» — оставить, «Отменить» — вернуть как было."
            } else {
                "Для шлемов без QR-кода. Меняйте по одному параметру и проверяйте в плеере."
            },
        )

        ParamSlider(
            title = "Расстояние между линзами, мм",
            hint = "Картинка двоится или глаза напрягаются — подстройте это первым.",
            value = params.interLensMm, range = 50f..75f, decimals = 1,
        ) { onChange(params.copy(interLensMm = it)) }

        ParamSlider(
            title = "Экран — линза, мм",
            hint = "Влияет на масштаб: если всё кажется слишком крупным или мелким.",
            value = params.screenToLensMm, range = 25f..65f, decimals = 1,
        ) { onChange(params.copy(screenToLensMm = it)) }

        ParamSlider(
            title = "Поле зрения линзы, °",
            hint = "Насколько широкой видна картинка; у типичных VR-боксов 45–60°.",
            value = params.fovDeg, range = 30f..70f, decimals = 0,
        ) { onChange(params.copy(fovDeg = it)) }

        ParamSlider(
            title = "Коррекция искажения k1",
            hint = "Прямые линии у краёв выгибаются наружу — увеличьте, внутрь — уменьшите.",
            value = params.k1, range = 0f..1f, decimals = 3,
        ) { onChange(params.copy(k1 = it)) }

        ParamSlider(
            title = "Коррекция искажения k2",
            hint = "Тонкая подстройка самых краёв, обычно меньше k1.",
            value = params.k2, range = 0f..1f, decimals = 3,
        ) { onChange(params.copy(k2 = it)) }

        com.manfis.astramatic.ui.SectionTitle("Положение телефона")
        com.manfis.astramatic.ui.Segmented(
            HeadsetParams.Alignment.entries,
            params.alignment,
            {
                when (it) {
                    HeadsetParams.Alignment.Bottom -> "Упор снизу"
                    HeadsetParams.Alignment.Center -> "По центру"
                    HeadsetParams.Alignment.Top -> "Упор сверху"
                }
            },
            { onChange(params.copy(alignment = it)) },
        )
        com.manfis.astramatic.ui.Hint(
            "Большинство боксов зажимают телефон по центру. Если центры картинок выше или ниже зрачков — выберите упор.",
        )

        if (params.alignment != HeadsetParams.Alignment.Center) {
            ParamSlider(
                title = "Упор — центр линзы, мм",
                hint = "Расстояние от края телефона, на который он опирается, до центра линз.",
                value = params.trayToLensMm, range = 10f..60f, decimals = 1,
            ) { onChange(params.copy(trayToLensMm = it)) }
        }

        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.manfis.astramatic.ui.AppButton("Сохранить", onSave, style = com.manfis.astramatic.ui.ButtonStyle.Filled)
            if (live) com.manfis.astramatic.ui.AppButton("Отменить", onCancel)
            com.manfis.astramatic.ui.AppButton("Как Cardboard v1", { onChange(HeadsetParams.CardboardV1) })
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
    val step = when (decimals) {
        0 -> 1f
        1 -> 0.1f
        else -> 0.001f
    }
    com.manfis.astramatic.ui.LabeledSlider(
        title = "$title: ${String.format(Locale.US, "%.${decimals}f", value)}",
        hint = hint,
        value = value,
        range = range,
        step = step,
        onChange = onChange,
    )
}

private const val LIVE_APPLY_DELAY_MS = 150L

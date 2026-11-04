package com.manfis.astramatic.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlin.math.roundToInt

@Composable
internal fun LabeledSlider(
    title: String,
    hint: String?,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = value.coerceIn(range),
            onValueChange = { onChange((it / step).roundToInt() * step) },
            valueRange = range,
        )
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall)
    }
}

const val MIN_ZOOM = 0.5f
const val MAX_ZOOM = 4f
internal val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

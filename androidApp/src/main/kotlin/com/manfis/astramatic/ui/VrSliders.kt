package com.manfis.astramatic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Ползунок с названием слева и значением справа. [title] вида «Яркость: 100%» делится по двоеточию,
 * чтобы значение стояло отдельно и не прыгало вместе с текстом.
 */
@Composable
internal fun LabeledSlider(
    title: String,
    hint: String?,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit,
) {
    val (name, shown) = splitValue(title)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (shown != null) {
                Text(shown, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AppSlider(
            value = value,
            onValueChange = { onChange((it / step).roundToInt() * step) },
            valueRange = range,
        )
        if (hint != null) Hint(hint)
    }
}

/** «Яркость: 100%» → «Яркость» и «100%»; без двоеточия — последнее слово, если в нём есть цифра. */
internal fun splitValue(title: String): Pair<String, String?> {
    val colon = title.lastIndexOf(':')
    if (colon > 0) return title.substring(0, colon).trim() to title.substring(colon + 1).trim()
    val space = title.lastIndexOf(' ')
    if (space > 0 && title.substring(space).any { it.isDigit() }) {
        // «Экран 2.5 м»: значение — с последнего слова с цифрой.
        val words = title.split(' ')
        val first = words.indexOfFirst { word -> word.any { it.isDigit() } }
        if (first > 0) return words.take(first).joinToString(" ") to words.drop(first).joinToString(" ")
    }
    return title to null
}

const val MIN_ZOOM = 0.5f
const val MAX_ZOOM = 4f
internal val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** «1.25×», «1×». */
internal fun speedLabel(speed: Float) = "${speed}×".replace(".0×", "×").replace('.', ',')

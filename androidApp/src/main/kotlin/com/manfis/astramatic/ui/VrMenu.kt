package com.manfis.astramatic.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Высота полоски управления в шлеме: столько пикселей сверху виртуального дисплея показывает рендерер. */
val ControlsHeightDp = 240.dp

/** Высота панели над полоской (вид, очередь); вместе с полоской рендерер показывает 490 dp сверху. */
val SheetHeightDp = 250.dp

@Composable
fun VrMenu(state: VrMenuState, actions: VrMenuActions) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        when (state.mode) {
            VrMenuState.Mode.Controls -> ControlsLayer(state, actions)
            VrMenuState.Mode.Menu -> Panel(Modifier.fillMaxSize(), shape(state)) { MenuContent(state, actions) }
        }
    }
}

internal fun shape(state: VrMenuState): Shape = RoundedCornerShape(if (state.stereo) 28.dp else 0.dp)

/**
 * Фон панели. Цвет текста задан явно: у полупрозрачного фона Material не находит пару
 * в теме и оставил бы текст чёрным.
 */
@Composable
internal fun Panel(modifier: Modifier, shape: Shape, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        content = content,
    )
}

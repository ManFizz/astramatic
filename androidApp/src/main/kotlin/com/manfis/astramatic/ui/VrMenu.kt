package com.manfis.astramatic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Высота полоски управления в шлеме: столько пикселей сверху виртуального дисплея показывает рендерер. */
val ControlsHeightDp = 240.dp

/** Высота панели над полоской (вид, очередь); вместе с полоской рендерер показывает 490 dp сверху. */
val SheetHeightDp = 250.dp

@Composable
fun VrMenu(state: VrMenuState, actions: VrMenuActions) {
    AppTheme(vr = state.stereo) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            when (state.mode) {
                VrMenuState.Mode.Controls -> ControlsLayer(state, actions)
                VrMenuState.Mode.Menu -> CompositionLocalProvider(LocalVrKeyboard provides state.keyboard.takeIf { state.stereo }) {
                    // Клавиатура снизу, содержимое над ней сжимается: видно, что находит поиск.
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            if (state.showingHub) {
                                // Хаб парит без общего фона: вокруг него видно окружение.
                                MenuContent(state, actions)
                            } else {
                                Panel(Modifier.fillMaxSize(), shape(state)) { MenuContent(state, actions) }
                            }
                        }
                        if (state.stereo && state.keyboard.isOpen) {
                            VrKeyboard(state.keyboard, Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
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
        modifier = modifier.background(GlassBrush, shape).border(1.dp, GlassEdge, shape),
        shape = shape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        content = content,
    )
}

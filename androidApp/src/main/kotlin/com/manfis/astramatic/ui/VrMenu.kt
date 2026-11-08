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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Высота полоски управления в шлеме: столько пикселей сверху виртуального дисплея показывает рендерер. */
val ControlsHeightDp = 240.dp

/** Высота панели над полоской (вид, очередь); вместе с полоской рендерер показывает 490 dp сверху. */
val SheetHeightDp = 250.dp

/** Цвета интерфейса: глубокий фиолетовый, светлый акцент. В шлеме чистый белый слепит, поэтому текст мягче. */
internal val VrColors = darkColorScheme(
    primary = Color(0xFFB9A7FF),
    onPrimary = Color(0xFF1F1147),
    primaryContainer = Color(0xFF3D2E7C),
    onPrimaryContainer = Color(0xFFE7E0FF),
    secondary = Color(0xFF85D8EA),
    onSecondary = Color(0xFF00363F),
    secondaryContainer = Color(0xFF243B52),
    onSecondaryContainer = Color(0xFFD2EEF6),
    tertiary = Color(0xFFFFB68F),
    surface = Color(0xFF14131F),
    onSurface = Color(0xFFEDEBF5),
    surfaceVariant = Color(0xFF2B2940),
    onSurfaceVariant = Color(0xFFC9C6DA),
    surfaceContainer = Color(0xFF1E1C2E),
    surfaceContainerHigh = Color(0xFF28263A),
    surfaceContainerHighest = Color(0xFF322F47),
    outline = Color(0xFF6F6C85),
    outlineVariant = Color(0xFF3F3C55),
)

@Composable
fun VrMenu(state: VrMenuState, actions: VrMenuActions) {
    MaterialTheme(colorScheme = VrColors) {
        when (state.mode) {
            VrMenuState.Mode.Controls -> ControlsLayer(state, actions)
            VrMenuState.Mode.Menu -> CompositionLocalProvider(LocalVrKeyboard provides state.keyboard.takeIf { state.stereo }) {
                // Клавиатура снизу, содержимое над ней сжимается: видно, что находит поиск.
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        if (state.showingHub) {
                            // Хаб парит без общего фона: вокруг него видно окружение.
                            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                                MenuContent(state, actions)
                            }
                        } else {
                            Panel(Modifier.fillMaxSize(), shape(state)) { MenuContent(state, actions) }
                        }
                    }
                    if (state.stereo && state.keyboard.isOpen) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                            VrKeyboard(state.keyboard, Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

internal fun shape(state: VrMenuState): Shape = RoundedCornerShape(if (state.stereo) 28.dp else 0.dp)

/** Фон окна: тёмное стекло с лёгким фиолетовым светом сверху и тонкой светлой кромкой. */
internal val GlassBrush = Brush.verticalGradient(0f to Color(0xF62A2350), 0.5f to Color(0xF61A1830), 1f to Color(0xF6121120))
internal val GlassEdge = Brush.verticalGradient(0f to Color(0x55FFFFFF), 1f to Color(0x14FFFFFF))

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

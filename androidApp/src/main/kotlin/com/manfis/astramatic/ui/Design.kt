package com.manfis.astramatic.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Оформление: тёмный графит, один светлый акцент, монохромные иконки.
 * Правила, по которым сделаны экраны:
 * - два уровня текста: основной (onSurface, контраст ~16:1) и вторичный (onSurfaceVariant, ~9:1); бледнее не бывает;
 * - в шлеме шрифт крупнее, чем на телефоне: экран Cardboard даёт около 15 пикселей на градус,
 *   и подпись мельче 16 sp там уже читается с трудом;
 * - сетка отступов 4/8/12/16/24; кнопки не ниже 48 dp, главные — 56–64 dp;
 * - выбранное — светлой плашкой с тёмным текстом, наведение — лёгкой подсветкой и кромкой.
 */

/** Цвета. В шлеме чистый белый слепит, поэтому «белый» чуть приглушён. */
internal val AppColors = darkColorScheme(
    primary = Color(0xFFEDEFF3),
    onPrimary = Color(0xFF141519),
    primaryContainer = Color(0xFF3A3D45),
    onPrimaryContainer = Color(0xFFEDEFF3),
    secondary = Color(0xFF9DC1FF),
    onSecondary = Color(0xFF0E1A2E),
    secondaryContainer = Color(0xFF2B3240),
    onSecondaryContainer = Color(0xFFDDE7FF),
    tertiary = Color(0xFF9DC1FF),
    onTertiary = Color(0xFF0E1A2E),
    tertiaryContainer = Color(0xFF2B3240),
    onTertiaryContainer = Color(0xFFDDE7FF),
    background = Color(0xFF111215),
    onBackground = Color(0xFFEDEFF3),
    surface = Color(0xFF16171B),
    onSurface = Color(0xFFEDEFF3),
    surfaceVariant = Color(0xFF26282E),
    onSurfaceVariant = Color(0xFFB7BBC4),
    surfaceContainerLowest = Color(0xFF0F1013),
    surfaceContainerLow = Color(0xFF1A1B20),
    surfaceContainer = Color(0xFF1F2025),
    surfaceContainerHigh = Color(0xFF26282E),
    surfaceContainerHighest = Color(0xFF30333A),
    outline = Color(0xFF5A5E68),
    outlineVariant = Color(0xFF33363D),
    error = Color(0xFFFF8F87),
    onError = Color(0xFF2B0A08),
    errorContainer = Color(0xFF4A1F1C),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFEDEFF3),
    inverseOnSurface = Color(0xFF16171B),
)

/** Акцент для активного: поле, в которое печатают, перемотка, выбранная вкладка-индикатор. */
internal val Accent = Color(0xFF9DC1FF)

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, spacing: Float = 0f) =
    TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = spacing.sp)

/** Шрифты шлема: крупнее телефонных, без тонких начертаний. */
private val VrTypography = Typography(
    displaySmall = style(34, 42, FontWeight.Medium),
    headlineMedium = style(30, 38, FontWeight.Medium),
    headlineSmall = style(26, 34, FontWeight.Medium),
    titleLarge = style(24, 30, FontWeight.Medium),
    titleMedium = style(20, 26, FontWeight.Medium),
    titleSmall = style(18, 24, FontWeight.Medium),
    bodyLarge = style(20, 28),
    bodyMedium = style(18, 26),
    bodySmall = style(16, 22),
    labelLarge = style(18, 24, FontWeight.Medium),
    labelMedium = style(16, 20, FontWeight.Medium),
    labelSmall = style(14, 18, FontWeight.Medium, 0.2f),
)

/** Телефон: обычные размеры Material, только вес заголовков ровнее. */
private val PhoneTypography = Typography().let {
    it.copy(
        titleLarge = it.titleLarge.copy(fontWeight = FontWeight.Medium),
        titleMedium = it.titleMedium.copy(fontWeight = FontWeight.Medium),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Тема приложения. [vr] — в шлеме: крупнее шрифты. */
@Composable
fun AppTheme(vr: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        typography = if (vr) VrTypography else PhoneTypography,
        shapes = AppShapes,
        content = content,
    )
}

/** Фон окна: ровное тёмное стекло, почти непрозрачное, без цветных переливов. */
internal val GlassBrush: Brush = Brush.verticalGradient(0f to Color(0xF41D1E23), 1f to Color(0xF4151619))

/** Кромка окна: тонкая, чуть светлее сверху. */
internal val GlassEdge: Brush = Brush.verticalGradient(0f to Color(0x2EFFFFFF), 1f to Color(0x12FFFFFF))

/** Плашка внутри окна: карточка, строка, поле. */
internal val CardColor = Color(0x14FFFFFF)
internal val CardHover = Color(0x24FFFFFF)

/** Подсветка под взглядом: фон и кромка. */
@Composable
internal fun hoverState(interaction: MutableInteractionSource) = interaction.collectIsHoveredAsState()

enum class ButtonStyle { Filled, Tonal, Ghost }

/**
 * Кнопка-«таблетка» с иконкой и подписью. [selected] — включённый режим: светлая плашка.
 * Высота 48 dp, в шлеме этого хватает, чтобы попасть точкой не целясь.
 */
@Composable
internal fun AppButton(
    text: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: ButtonStyle = ButtonStyle.Tonal,
    selected: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 48.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by hoverState(interaction)
    val colors = MaterialTheme.colorScheme
    val filled = style == ButtonStyle.Filled || selected
    val background by animateColorAsState(
        when {
            filled && hovered -> Color.White
            filled -> colors.primary
            hovered -> CardHover
            style == ButtonStyle.Tonal -> CardColor
            else -> Color.Transparent
        },
        label = "button",
    )
    val content = if (filled) colors.onPrimary else colors.onSurface
    Row(
        modifier
            .height(height)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = if (text == null) 0.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = if (text == null) "" else null, tint = content, modifier = Modifier.size(height * 0.46f))
        }
        if (text != null) {
            Text(text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Круглая кнопка-иконка. [label] — её название: читается тестами и озвучкой.
 * [style] Filled — главная кнопка экрана (пауза), остальные прозрачные с подсветкой.
 */
@Composable
internal fun AppIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    style: ButtonStyle = ButtonStyle.Ghost,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by hoverState(interaction)
    val colors = MaterialTheme.colorScheme
    val filled = style == ButtonStyle.Filled || selected
    val background by animateColorAsState(
        when {
            filled && hovered -> Color.White
            filled -> colors.primary
            hovered -> CardHover
            style == ButtonStyle.Tonal -> CardColor
            else -> Color.Transparent
        },
        label = "icon",
    )
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .alpha(if (enabled) 1f else 0.32f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (filled) colors.onPrimary else colors.onSurface, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * Выбор одного из нескольких: сегменты на общей плашке, выбранный — светлый.
 * Заменяет россыпь чипов: видно, что это один выбор, а не набор флажков.
 */
@Composable
internal fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
) {
    Row(
        modifier.height(height).clip(CircleShape).background(CardColor).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val interaction = remember { MutableInteractionSource() }
            val hovered by hoverState(interaction)
            val active = option == selected
            val colors = MaterialTheme.colorScheme
            Box(
                Modifier
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(
                        when {
                            active -> colors.primary
                            hovered -> CardHover
                            else -> Color.Transparent
                        },
                    )
                    .clickable(interactionSource = interaction, indication = null, role = Role.RadioButton) { onSelect(option) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) colors.onPrimary else colors.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Тонкий ползунок: дорожка 4 dp, светлая заливка, круглый бегунок без украшений. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by hoverState(interaction)
    val colors = MaterialTheme.colorScheme
    Slider(
        value = value.coerceIn(valueRange),
        onValueChange = onValueChange,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth().height(36.dp),
        thumb = {
            Box(
                Modifier
                    .size(if (hovered) 24.dp else 20.dp)
                    .background(colors.primary, CircleShape)
                    .border(3.dp, Color(0x33000000), CircleShape),
            )
        },
        track = { state ->
            val span = (state.valueRange.endInclusive - state.valueRange.start).takeIf { it > 0f } ?: 1f
            val fraction = ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color(0x38FFFFFF))) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(colors.primary))
            }
        },
    )
}

/** Подпись раздела: спокойно, вторичным цветом. */
@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 8.dp),
    )
}

/** Пояснение под настройкой. */
@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** Строка настройки: название, пояснение, справа — переключатель или кнопка. */
@Composable
internal fun SettingRow(
    title: String,
    hint: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by hoverState(interaction)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (hovered && onClick != null) CardHover else CardColor)
            .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Hint(hint)
        }
        trailing()
    }
}

/** Переключатель в цветах темы: включённый — светлый, выключенный — контурный. */
@Composable
internal fun AppSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.onPrimary,
            checkedTrackColor = colors.primary,
            checkedBorderColor = colors.primary,
            uncheckedThumbColor = colors.onSurfaceVariant,
            uncheckedTrackColor = Color.Transparent,
            uncheckedBorderColor = colors.outline,
        ),
    )
}

/** Строка настройки с переключателем; нажатие по всей строке переключает. */
@Composable
internal fun SwitchRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(title, hint, onClick = { onChange(!checked) }) { AppSwitch(checked, onChange) }
}

/** Значок в квадратной плашке: источник, сайт, файл без превью. */
@Composable
internal fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Color(0x1FFFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(size * 0.52f))
    }
}

/** Горизонтальный отступ-разделитель между группами в строке. */
@Composable
internal fun Gap(width: Dp = 16.dp) = Spacer(Modifier.width(width))

/** Тонкая линия-разделитель. */
@Composable
internal fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(SolidColor(Color(0x1AFFFFFF))))
}

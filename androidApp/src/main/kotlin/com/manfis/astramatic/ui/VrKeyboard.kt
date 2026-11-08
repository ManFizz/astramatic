package com.manfis.astramatic.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.KeyboardCapslock
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Поле, в которое печатает клавиатура шлема. */
class KeyboardTarget(
    val label: String,
    val secret: Boolean = false,
    val text: () -> String,
    val onTextChange: (String) -> Unit,
    /** «Готово»: например, подключиться. Клавиатура закрывается в любом случае. */
    val onDone: () -> Unit = {},
)

enum class KeyboardLayout { Ru, En, Symbols }

enum class Shift { Off, Once, Locked }

/** Кнопка клавиатуры. [weight] — ширина относительно буквы. */
sealed class Key(val label: String, val weight: Float = 1f) {
    class Char(val char: String) : Key(char)
    data object Backspace : Key("⌫", 1.5f)
    data object ShiftKey : Key("⇧", 1.5f)
    data object Language : Key("🌐", 1.3f)
    data object Symbols : Key("?123", 1.4f)
    data object Space : Key("пробел", 4f)
    data object Done : Key("Готово", 1.8f)
}

/**
 * Клавиатура шлема: системная там не видна, а набирать надо поиск, адреса и пароли.
 * Текст хранится здесь, в поле он уходит после каждой кнопки: так два быстрых нажатия
 * не потеряют букву, даже если поле ещё не успело перерисоваться.
 */
class VrKeyboardState {
    var target by mutableStateOf<KeyboardTarget?>(null)
        private set
    var layout by mutableStateOf(KeyboardLayout.Ru)
        private set
    var shift by mutableStateOf(Shift.Off)
        private set
    var text by mutableStateOf("")
        private set
    var cursor by mutableIntStateOf(0)
        private set

    /** Какие буквы вернуть после символов. */
    private var letters = KeyboardLayout.Ru

    val isOpen get() = target != null

    fun open(target: KeyboardTarget) {
        this.target = target
        text = target.text()
        cursor = text.length
        shift = Shift.Off
        layout = letters
    }

    fun close() {
        target = null
    }

    /** Поле ушло с экрана: если печатали в него, клавиатура закрывается. */
    fun closeIf(target: KeyboardTarget) {
        if (this.target === target) close()
    }

    fun press(key: Key) {
        when (key) {
            is Key.Char -> type(key.char)
            Key.Space -> type(" ")
            Key.Backspace -> backspace()
            Key.ShiftKey -> shift = when (shift) {
                Shift.Off -> Shift.Once
                Shift.Once -> Shift.Locked
                Shift.Locked -> Shift.Off
            }
            Key.Language -> {
                letters = if (letters == KeyboardLayout.Ru) KeyboardLayout.En else KeyboardLayout.Ru
                layout = letters
            }
            Key.Symbols -> layout = if (layout == KeyboardLayout.Symbols) letters else KeyboardLayout.Symbols
            Key.Done -> done()
            is LettersKey -> layout = letters
        }
    }

    fun type(chars: String) {
        if (target == null) return
        val typed = if (shift != Shift.Off) chars.uppercase() else chars
        set(text.substring(0, cursor) + typed + text.substring(cursor), cursor + typed.length)
        if (shift == Shift.Once) shift = Shift.Off
    }

    fun backspace() {
        if (target == null || cursor == 0) return
        set(text.substring(0, cursor - 1) + text.substring(cursor), cursor - 1)
    }

    fun clear() {
        if (target != null) set("", 0)
    }

    fun moveCursor(delta: Int) {
        cursor = (cursor + delta).coerceIn(0, text.length)
    }

    fun done() {
        val current = target ?: return
        close()
        current.onDone()
    }

    private fun set(value: String, at: Int) {
        text = value
        cursor = at
        target?.onTextChange?.invoke(value)
    }

    /** Ряды кнопок текущей раскладки. Цифры сверху всегда: адреса и пароли без переключений. */
    fun rows(): List<List<Key>> {
        val digits = chars("1234567890")
        val bottom = listOf(
            if (layout == KeyboardLayout.Symbols) LettersKey(letters) else Key.Symbols,
            Key.Language,
            Key.Char(","),
            Key.Space,
            Key.Char("."),
            Key.Done,
        )
        val middle = when (layout) {
            KeyboardLayout.Ru -> listOf(
                chars("йцукенгшщзхъ"),
                chars("фывапролджэ"),
                listOf(Key.ShiftKey) + chars("ячсмитьбюё") + Key.Backspace,
            )
            KeyboardLayout.En -> listOf(
                chars("qwertyuiop"),
                chars("asdfghjkl") + Key.Char("@"),
                listOf(Key.ShiftKey) + chars("zxcvbnm") + chars("-_") + Key.Backspace,
            )
            KeyboardLayout.Symbols -> listOf(
                chars("-/:;()&@\"'"),
                chars("\\_?!#%*+=$"),
                chars("[]{}<>|~^") + Key.Backspace,
            )
        }
        return listOf(digits) + middle + listOf(bottom)
    }

    private fun chars(s: String) = s.map { Key.Char(it.toString()) }
}

/** Возврат к буквам из символов: подпись — какие буквы вернутся. */
private class LettersKey(letters: KeyboardLayout) : Key(if (letters == KeyboardLayout.Ru) "АБВ" else "ABC", 1.4f)

/** Клавиатура шлема; null — на телефоне в 2D, там системная. */
val LocalVrKeyboard = staticCompositionLocalOf<VrKeyboardState?> { null }

/** Поиск по словам: все слова запроса есть в имени, без учёта регистра и разницы «е» и «ё». */
object TextSearch {
    private val spaces = Regex("\\s+")

    private fun normalize(s: String) = s.lowercase().replace('ё', 'е')

    fun matches(name: String, query: String): Boolean {
        val words = normalize(query).split(spaces).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val target = normalize(name)
        return words.all { target.contains(it) }
    }
}

/**
 * Поле ввода. На телефоне — обычное, с системной клавиатурой. В шлеме нажатие открывает
 * клавиатуру шлема, а поле подсвечено, пока в него печатают.
 */
@Composable
internal fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    secret: Boolean = false,
    isError: Boolean = false,
    onDone: () -> Unit = {},
) {
    val keyboard = LocalVrKeyboard.current
    val transformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None
    if (keyboard == null) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = true,
            isError = isError,
            visualTransformation = transformation,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = modifier,
        )
        return
    }
    val currentValue by rememberUpdatedState(value)
    val currentChange by rememberUpdatedState(onValueChange)
    val currentDone by rememberUpdatedState(onDone)
    val target = remember(label, secret) {
        KeyboardTarget(label, secret, text = { currentValue }, onTextChange = { currentChange(it) }, onDone = { currentDone() })
    }
    DisposableEffect(keyboard, target) { onDispose { keyboard.closeIf(target) } }
    val active = keyboard.target === target
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = true,
            isError = isError,
            visualTransformation = transformation,
            colors = if (active) {
                OutlinedTextFieldDefaults.colors(unfocusedBorderColor = accent, unfocusedLabelColor = accent)
            } else {
                OutlinedTextFieldDefaults.colors()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // Поверх поля: нажатие открывает клавиатуру, а не ставит фокус, которого в шлеме не видно.
        Box(Modifier.matchParentSize().clickable { keyboard.open(target) })
    }
}

private val KeyShape = RoundedCornerShape(10.dp)

/** Клавиатура под содержимым меню. */
@Composable
internal fun VrKeyboard(state: VrKeyboardState, modifier: Modifier = Modifier) {
    val target = state.target ?: return
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 28.dp, bottomEnd = 28.dp)
    Column(
        modifier
            .fillMaxWidth()
            .testTag("keyboard")
            .background(GlassBrush, shape)
            .border(1.dp, GlassEdge, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                target.label,
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp),
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(KeyShape)
                    .background(Color(0x59000000))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                val shown = if (target.secret) "•".repeat(state.text.length) else state.text
                val at = state.cursor.coerceIn(0, shown.length)
                Text(
                    buildAnnotatedString {
                        append(shown.substring(0, at))
                        withStyle(SpanStyle(color = colors.primary)) { append("│") }
                        append(shown.substring(at))
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
            }
            KeyButton("", Modifier.width(52.dp), icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft) { state.moveCursor(-1) }
            KeyButton("", Modifier.width(52.dp), icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight) { state.moveCursor(1) }
            KeyButton("Стереть", Modifier.width(104.dp)) { state.clear() }
            KeyButton("Скрыть", Modifier.width(104.dp), icon = Icons.Rounded.KeyboardHide) { state.close() }
        }
        state.rows().forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { key -> KeyCell(state, key) }
            }
        }
    }
}

@Composable
private fun RowScope.KeyCell(state: VrKeyboardState, key: Key) {
    val icon = when (key) {
        Key.Backspace -> Icons.AutoMirrored.Rounded.Backspace
        Key.ShiftKey -> if (state.shift == Shift.Locked) Icons.Rounded.KeyboardCapslock else Icons.Rounded.ArrowUpward
        Key.Language -> Icons.Rounded.Language
        else -> null
    }
    val label = when {
        key is Key.Char && state.shift != Shift.Off -> key.char.uppercase()
        key == Key.Language -> if (state.layout == KeyboardLayout.En) "RU" else "EN"
        icon != null -> ""
        else -> key.label
    }
    val accent = key == Key.Done || (key == Key.ShiftKey && state.shift != Shift.Off)
    val service = key !is Key.Char || key.label.length > 1
    KeyButton(
        label,
        Modifier.weight(key.weight).testTag("key:${key.label}"),
        accent = accent,
        icon = icon,
        service = service && key != Key.Space,
    ) { state.press(key) }
}

@Composable
private fun KeyButton(
    label: String,
    modifier: Modifier,
    accent: Boolean = false,
    icon: ImageVector? = null,
    /** Служебная клавиша: чуть темнее букв, чтобы буквы читались группой. */
    service: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = MaterialTheme.colorScheme
    val background = when {
        accent && hovered -> Color.White
        accent -> colors.primary
        hovered -> Color(0x4DFFFFFF)
        service -> Color(0x17FFFFFF)
        else -> Color(0x29FFFFFF)
    }
    Box(
        modifier
            .height(44.dp)
            .clip(KeyShape)
            .background(background)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = if (accent) colors.onPrimary else colors.onSurface, modifier = Modifier.size(22.dp))
            }
            if (label.isNotEmpty()) {
                Text(
                    label,
                    style = if (label.length == 1) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                    color = if (accent) colors.onPrimary else colors.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

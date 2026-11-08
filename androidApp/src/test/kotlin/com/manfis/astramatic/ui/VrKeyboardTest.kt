package com.manfis.astramatic.ui

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Клавиатура шлема и поиск: VR-28, LIB-17, LIB-18. */
class VrKeyboardTest {
    private var field = ""
    private var done = 0
    private val keyboard = VrKeyboardState()
    private val target = KeyboardTarget("Поле", text = { field }, onTextChange = { field = it }, onDone = { done++ })

    private fun press(vararg labels: String) {
        for (label in labels) {
            val key = keyboard.rows().flatten().first { it.label == label }
            keyboard.press(key)
        }
    }

    @Test
    fun `typing goes into the field at the cursor`() {
        field = "кот"
        keyboard.open(target)
        assertEquals(3, keyboard.cursor)

        press("ы")
        assertEquals("коты", field)

        keyboard.moveCursor(-4)
        press("м")
        assertEquals("мкоты", field)
        assertEquals(1, keyboard.cursor)
    }

    @Test
    fun `backspace removes the letter before the cursor`() {
        field = "абв"
        keyboard.open(target)
        keyboard.moveCursor(-1)

        press("⌫")

        assertEquals("ав", field)
        assertEquals(1, keyboard.cursor)

        keyboard.moveCursor(-5)
        press("⌫")
        assertEquals("ав", field)
    }

    @Test
    fun `shift once makes one capital, twice locks`() {
        keyboard.open(target)

        press("⇧", "п", "р")
        assertEquals("Пр", field)

        press("⇧", "⇧", "и", "в")
        assertEquals("ПрИВ", field)
        assertEquals(Shift.Locked, keyboard.shift)
    }

    @Test
    fun `language and symbols switch and come back`() {
        keyboard.open(target)
        assertEquals(KeyboardLayout.Ru, keyboard.layout)

        press("🌐", "p", "c")
        assertEquals(KeyboardLayout.En, keyboard.layout)

        press("?123", "\\", "/")
        assertEquals(KeyboardLayout.Symbols, keyboard.layout)

        // Из символов — к тем буквам, что были.
        press("ABC")
        assertEquals(KeyboardLayout.En, keyboard.layout)
        assertEquals("pc\\/", field)
    }

    @Test
    fun `address of a windows folder can be typed`() {
        keyboard.open(target)
        press("🌐", "?123", "\\", "\\", "ABC", "p", "c", "?123", "\\", "ABC", "v")

        assertEquals("\\\\pc\\v", field)
    }

    @Test
    fun `done closes the keyboard and runs the action`() {
        keyboard.open(target)

        press("Готово")

        assertFalse(keyboard.isOpen)
        assertEquals(1, done)
    }

    @Test
    fun `clear and hide`() {
        field = "что-то"
        keyboard.open(target)

        keyboard.clear()
        assertEquals("", field)

        keyboard.close()
        assertFalse(keyboard.isOpen)
        assertEquals(0, done)
    }

    @Test
    fun `field leaving the screen closes only its own keyboard`() {
        val other = KeyboardTarget("Другое", text = { "" }, onTextChange = {})
        keyboard.open(target)

        keyboard.closeIf(other)
        assertTrue(keyboard.isOpen)

        keyboard.closeIf(target)
        assertFalse(keyboard.isOpen)
    }

    @Test
    fun `typing after the keyboard closed does nothing`() {
        keyboard.open(target)
        keyboard.close()

        keyboard.type("x")

        assertEquals("", field)
    }

    @Test
    fun `every layout has digits, backspace and done`() {
        keyboard.open(target)
        for (switch in listOf("?123", "АБВ", "🌐")) {
            val keys = keyboard.rows().flatten()
            assertTrue(keys.any { it.label == "1" } && keys.any { it.label == "0" }, "цифры: ${keyboard.layout}")
            assertTrue(keys.any { it == Key.Backspace }, "⌫: ${keyboard.layout}")
            assertTrue(keys.any { it == Key.Done }, "Готово: ${keyboard.layout}")
            press(switch)
        }
    }

    @Test
    fun `search finds all words in any case and treats ё as е`() {
        assertTrue(TextSearch.matches("Прогулка_по_набережной_180_LR.mp4", "набережной прогулка"))
        assertTrue(TextSearch.matches("Ёлка.mp4", "елка"))
        assertTrue(TextSearch.matches("Елка.mp4", "ЁЛ"))
        assertTrue(TextSearch.matches("что угодно", "   "))
        assertFalse(TextSearch.matches("Прогулка.mp4", "прогулка закат"))
    }
}

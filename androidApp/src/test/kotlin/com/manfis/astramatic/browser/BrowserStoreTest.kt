package com.manfis.astramatic.browser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Браузер: адресная строка, закладки, недавние сайты, поля страниц. BR-02…BR-06. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BrowserStoreTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("test_browser", Context.MODE_PRIVATE)
        .also { it.edit().clear().commit() }

    @Test
    fun `address goes as is, a host gets https, anything else is a search`() {
        assertEquals("https://example.com/a?b=1", BrowserUrl.resolve(" https://example.com/a?b=1 "))
        assertEquals("http://10.0.2.2:8000/", BrowserUrl.resolve("http://10.0.2.2:8000/"))
        assertEquals("https://ru.wikipedia.org", BrowserUrl.resolve("ru.wikipedia.org"))
        assertEquals("https://192.168.1.5:8080/x", BrowserUrl.resolve("192.168.1.5:8080/x"))
        assertEquals("https://localhost:3000", BrowserUrl.resolve("localhost:3000"))
        assertEquals("https://www.google.com/search?q=%D0%BA%D0%BE%D1%82%D1%8B+%D0%B2+VR", BrowserUrl.resolve("коты в VR"))
        assertEquals("https://www.google.com/search?q=kotlin", BrowserUrl.resolve("kotlin"))
        assertEquals(null, BrowserUrl.resolve("   "))
    }

    @Test
    fun `only web links open inside`() {
        assertTrue(BrowserUrl.opensHere("https://m.youtube.com/watch?v=1"))
        assertFalse(BrowserUrl.opensHere("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(BrowserUrl.opensHere("market://details?id=x"))
    }

    @Test
    fun `first start has default bookmarks, once`() {
        val store = BrowserStore(prefs)
        assertEquals(BrowserStore.DEFAULT_BOOKMARKS, store.bookmarks.toList())

        store.removeBookmark(store.bookmarks.first())
        assertEquals(1, BrowserStore(prefs).bookmarks.size, "удалённая закладка не возвращается")
    }

    @Test
    fun `star toggles a bookmark and it is kept`() {
        val store = BrowserStore(prefs)
        val page = WebPage("Пример", "https://example.com/")

        store.toggleBookmark(page)
        assertTrue(store.isBookmarked("https://example.com"))
        assertEquals(page, BrowserStore(prefs).bookmarks.first())

        store.toggleBookmark(page)
        assertFalse(BrowserStore(prefs).isBookmarked(page.url))
    }

    @Test
    fun `recent sites are newest first, without repeats and limited`() {
        val store = BrowserStore(prefs)
        repeat(BrowserStore.HISTORY_LIMIT + 5) { store.visited(WebPage("p$it", "https://site$it.ru/")) }
        store.visited(WebPage("снова", "https://site40.ru"))
        store.visited(WebPage("файл", "file:///sdcard/a.html"))

        val history = BrowserStore(prefs).history
        assertEquals(BrowserStore.HISTORY_LIMIT, history.size)
        assertEquals("снова", history.first().title)
        assertEquals(1, history.count { it.url.trimEnd('/') == "https://site40.ru" })
        assertTrue(history.none { it.url.startsWith("file:") })

        store.clearHistory()
        assertTrue(BrowserStore(prefs).history.isEmpty())
    }

    @Test
    fun `site name on the tile`() {
        assertEquals("youtube.com", WebPage("", "https://www.youtube.com/watch?v=1").host)
        assertEquals("ru.m.wikipedia.org", WebPage("", "https://ru.m.wikipedia.org/").host)
    }

    @Test
    fun `typed text reaches the page safely quoted`() {
        val script = BrowserScripts.setValue("он сказал \"привет\"\n</script>\\")

        assertTrue(script.contains("\"он сказал \\\"привет\\\"\\n<\\/script>\\\\\""), script)
        assertTrue(BrowserScripts.WATCH_FIELDS.contains("AstraKeyboard.focus("))
    }
}

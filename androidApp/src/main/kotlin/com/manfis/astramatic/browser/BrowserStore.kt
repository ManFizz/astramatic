package com.manfis.astramatic.browser

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/** Страница: закладка или запись истории сайтов. */
data class WebPage(val title: String, val url: String) {
    /** Короткое имя сайта для плитки: без «www.». */
    val host: String get() = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: url
}

/**
 * Закладки и недавние сайты браузера. Хранятся отдельно от истории видео: смешивать их на главной незачем.
 * Списки — состояние Compose, поэтому экран обновляется сам.
 */
class BrowserStore(private val prefs: SharedPreferences) {
    val bookmarks = mutableStateListOf<WebPage>().apply { addAll(read(KEY_BOOKMARKS)) }
    val history = mutableStateListOf<WebPage>().apply { addAll(read(KEY_HISTORY)) }

    init {
        if (!prefs.getBoolean(KEY_SEEDED, false)) {
            bookmarks.addAll(DEFAULT_BOOKMARKS.filter { page -> bookmarks.none { it.url == page.url } })
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()
            write(KEY_BOOKMARKS, bookmarks)
        }
    }

    fun isBookmarked(url: String) = bookmarks.any { same(it.url, url) }

    fun toggleBookmark(page: WebPage) {
        val existing = bookmarks.firstOrNull { same(it.url, page.url) }
        if (existing != null) bookmarks.remove(existing) else bookmarks.add(0, page)
        write(KEY_BOOKMARKS, bookmarks)
    }

    fun removeBookmark(page: WebPage) {
        bookmarks.remove(page)
        write(KEY_BOOKMARKS, bookmarks)
    }

    /** Открыли страницу: она становится первой в истории, повтор убирается. */
    fun visited(page: WebPage) {
        if (!page.url.startsWith("http")) return
        history.removeAll { same(it.url, page.url) }
        history.add(0, page)
        while (history.size > HISTORY_LIMIT) history.removeAt(history.lastIndex)
        write(KEY_HISTORY, history)
    }

    fun clearHistory() {
        history.clear()
        write(KEY_HISTORY, history)
    }

    private fun same(a: String, b: String) = a.trimEnd('/') == b.trimEnd('/')

    private fun read(key: String): List<WebPage> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]"))
        List(array.length()) { array.getJSONObject(it).let { o -> WebPage(o.getString("title"), o.getString("url")) } }
    }.getOrDefault(emptyList())

    private fun write(key: String, pages: List<WebPage>) {
        val array = JSONArray()
        pages.forEach { array.put(JSONObject().put("title", it.title).put("url", it.url)) }
        prefs.edit().putString(key, array.toString()).apply()
    }

    companion object {
        const val HISTORY_LIMIT = 30
        private const val KEY_BOOKMARKS = "bookmarks"
        private const val KEY_HISTORY = "history"
        private const val KEY_SEEDED = "seeded"

        val DEFAULT_BOOKMARKS = listOf(
            WebPage("YouTube", "https://m.youtube.com/"),
            WebPage("Википедия", "https://ru.m.wikipedia.org/"),
        )

        @Volatile private var instance: BrowserStore? = null

        fun get(context: Context): BrowserStore = instance ?: synchronized(this) {
            instance ?: BrowserStore(context.applicationContext.getSharedPreferences("browser", Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}

/** Что открыть по набранному в адресной строке. */
object BrowserUrl {
    private const val SEARCH = "https://www.google.com/search?q="
    private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val host = Regex("^(localhost|[\\w-]+(\\.[\\w-]+)+|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?([/?#].*)?$")

    /** Адрес — как есть или с https://, остальное — поиск. null — набрано пусто. */
    fun resolve(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (scheme.containsMatchIn(text) || text.startsWith("about:")) return text
        if (!text.contains(' ') && host.matches(text)) return "https://$text"
        return SEARCH + URLEncoder.encode(text, "UTF-8")
    }

    /** Ссылки, которые браузер открывает сам. Остальные (intent:, market:, tel:) — нет: в шлеме их некому принять. */
    fun opensHere(url: String) = url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:")
}

/**
 * Скрипты для полей на страницах. Системной клавиатуры в шлеме нет, поэтому страница сообщает,
 * какое поле выбрано, а набранное клавиатурой шлема записывается в него скриптом.
 */
object BrowserScripts {
    const val BRIDGE = "AstraKeyboard"

    /** Следит за выбором поля. Повторный запуск ничего не делает. */
    val WATCH_FIELDS = """
        (function() {
          if (window.__astraKeyboard) return;
          window.__astraKeyboard = true;
          function editable(el) {
            if (!el) return false;
            if (el.isContentEditable) return true;
            if (el.tagName === 'TEXTAREA') return !el.readOnly && !el.disabled;
            if (el.tagName !== 'INPUT' || el.readOnly || el.disabled) return false;
            var type = (el.getAttribute('type') || 'text').toLowerCase();
            return ['text', 'search', 'email', 'url', 'tel', 'password', 'number'].indexOf(type) >= 0;
          }
          function label(el) {
            return el.getAttribute('aria-label') || el.getAttribute('placeholder') || el.getAttribute('name') || el.title || '';
          }
          function report(e) {
            var el = e.target;
            if (!editable(el)) return;
            window.__astraField = el;
            var value = el.isContentEditable ? el.innerText : (el.value || '');
            $BRIDGE.focus(label(el), (el.getAttribute('type') || '').toLowerCase() === 'password', value);
          }
          document.addEventListener('focusin', report, true);
          document.addEventListener('click', report, true);
        })();
    """.trimIndent()

    /**
     * Записать текст в выбранное поле. Значение ставится через сеттер прототипа и сопровождается
     * событием input: иначе страницы на React и подобном его не заметят.
     */
    fun setValue(text: String): String = """
        (function(v) {
          var el = window.__astraField || document.activeElement;
          if (!el) return;
          if (el.isContentEditable) {
            el.innerText = v;
          } else {
            var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
            Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v);
          }
          el.dispatchEvent(new Event('input', { bubbles: true }));
        })(${JSONObject.quote(text)});
    """.trimIndent()

    /** «Готово»: отправить форму, а без формы — нажать Enter. */
    val SUBMIT = """
        (function() {
          var el = window.__astraField || document.activeElement;
          if (!el) return;
          el.dispatchEvent(new Event('change', { bubbles: true }));
          if (el.form && el.tagName === 'INPUT') {
            if (el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit();
            return;
          }
          ['keydown', 'keypress', 'keyup'].forEach(function(t) {
            el.dispatchEvent(new KeyboardEvent(t, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
          });
        })();
    """.trimIndent()
}

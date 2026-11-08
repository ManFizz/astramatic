package com.manfis.astramatic.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.manfis.astramatic.browser.BrowserScripts
import com.manfis.astramatic.browser.BrowserStore
import com.manfis.astramatic.browser.BrowserUrl
import com.manfis.astramatic.browser.WebPage
import kotlin.math.roundToInt

/** Что «Назад» может сделать внутри браузера, прежде чем закрыть его. */
fun interface BrowserBack {
    /** true — «Назад» отработал здесь: вышел из полноэкранного видео или вернулся на прошлую страницу. */
    fun back(): Boolean
}

/** Браузер в библиотеке: открытая страница переживает уход в хаб и возвращение. */
class BrowserState {
    /** Открытый адрес; null — стартовая страница. */
    var url by mutableStateOf<String?>(null)
        internal set
    var title by mutableStateOf("")
        internal set

    /** История переходов WebView, пока браузер не на экране. */
    internal var saved: Bundle? = null

    /** Адрес, который надо открыть в уже открытой странице. */
    internal var request by mutableStateOf<String?>(null)

    /** Пока браузер на экране — его «Назад». */
    var back: BrowserBack? by mutableStateOf(null)
        internal set

    /** Попробовать «Назад» внутри браузера. */
    fun goBack(): Boolean = back?.back() == true

    /** Открыть адрес: в открытой странице — переходом, иначе со стартовой. */
    fun open(url: String) {
        if (this.url == null) saved = null
        this.url = url
        request = url
    }
}

/** Браузер: страница во всё окно панели, сверху навигация и адресная строка. */
@Composable
internal fun BrowserScreen(state: BrowserState, embedded: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { BrowserStore.get(context) }
    var address by remember { mutableStateOf(state.url.orEmpty()) }
    var progress by remember { mutableFloatStateOf(1f) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf<View?>(null) }
    var fullscreenDone by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    val web = remember { WebHolder() }

    fun open(input: String) {
        val url = BrowserUrl.resolve(input) ?: return
        address = url
        state.open(url)
    }

    val back = BrowserBack {
        val view = web.view
        when {
            fullscreen != null -> {
                fullscreenDone?.onCustomViewHidden()
                true
            }
            view != null && view.canGoBack() -> {
                view.goBack()
                true
            }
            else -> false
        }
    }
    DisposableEffect(state) {
        state.back = back
        onDispose { state.back = null }
    }
    // Вне плеера «Назад» системы; в плеере его разбирает сам плеер.
    BackHandler(enabled = !embedded && (fullscreen != null || canGoBack)) { back.back() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TextButton(onClick = onClose) { Text("✕") }
                TextButton(onClick = { web.view?.goBack() }, enabled = canGoBack) { Text("◀") }
                TextButton(onClick = { web.view?.goForward() }, enabled = canGoForward) { Text("▶") }
                TextButton(onClick = { web.view?.reload() }, enabled = state.url != null) { Text("⟳") }
                AppTextField(
                    address,
                    { address = it },
                    label = "Адрес или поиск",
                    modifier = Modifier.weight(1f),
                    onDone = { open(address) },
                )
                val url = state.url
                if (url != null) {
                    val marked = store.isBookmarked(url)
                    TextButton(onClick = { store.toggleBookmark(WebPage(state.title.ifBlank { url }, url)) }) {
                        Text(if (marked) "★" else "☆")
                    }
                }
                TextButton(onClick = {
                    state.url = null
                    state.saved = null
                    address = ""
                }) { Text("⌂") }
            }
            if (progress < 1f && state.url != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp))
            }
            val url = state.url
            if (url == null) {
                StartPage(store, onOpen = { open(it) })
            } else {
                WebPageView(
                    state = state,
                    initialUrl = url,
                    holder = web,
                    onAddress = { address = it },
                    onProgress = { progress = it },
                    onHistory = { back, forward ->
                        canGoBack = back
                        canGoForward = forward
                    },
                    onFullscreen = { view, done ->
                        fullscreen = view
                        fullscreenDone = done
                    },
                    onVisited = { store.visited(it) },
                )
            }
        }
        // Полноэкранное видео со страницы — поверх всего окна.
        fullscreen?.let { view ->
            AndroidView(
                factory = { view.also { (it.parent as? ViewGroup)?.removeView(it) } },
                modifier = Modifier.fillMaxSize().background(Color.Black),
            )
        }
    }
}

/**
 * Контекст активности, но с дисплеем и плотностью того дисплея, где показано окно.
 * Chromium берёт масштаб страницы у дисплея контекста, а не у окна: без этого в шлеме
 * страница считала бы, что она на экране телефона.
 */
private class PanelContext(base: Context, private val panelDisplay: Display) : ContextWrapper(base) {
    private val displayContext = base.createDisplayContext(panelDisplay)

    override fun getResources(): Resources = displayContext.resources

    override fun getDisplay(): Display = panelDisplay

    override fun getSystemService(name: String): Any? = when (name) {
        WINDOW_SERVICE, DISPLAY_SERVICE -> displayContext.getSystemService(name)
        else -> super.getSystemService(name)
    }
}

/** Ссылка на WebView для кнопок навигации. */
internal class WebHolder {
    var view: WebView? = null
}

/**
 * WebView со своей плотностью: в шлеме панель 240 dpi, и страница должна это знать,
 * иначе получила бы плотность телефона и стала бы крупной и узкой.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebPageView(
    state: BrowserState,
    initialUrl: String,
    holder: WebHolder,
    onAddress: (String) -> Unit,
    onProgress: (Float) -> Unit,
    onHistory: (back: Boolean, forward: Boolean) -> Unit,
    onFullscreen: (View?, WebChromeClient.CustomViewCallback?) -> Unit,
    onVisited: (WebPage) -> Unit,
) {
    val context = LocalContext.current
    val dpi = (LocalDensity.current.density * 160).roundToInt()
    // Дисплей, где сейчас окно: в шлеме — виртуальный дисплей панели.
    val display = LocalView.current.display
    val keyboard = LocalVrKeyboard.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val visited by rememberUpdatedState(onVisited)
    key(dpi, display?.displayId, keyboard) {
        val webView = remember {
            WebView(if (display != null) PanelContext(context, display) else context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.setSupportMultipleWindows(false)
                setBackgroundColor(android.graphics.Color.WHITE)
                fun updateHistory() = onHistory(canGoBack(), canGoForward())
                fun watchFields() {
                    if (keyboard != null) evaluateJavascript(BrowserScripts.WATCH_FIELDS, null)
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !BrowserUrl.opensHere(request.url.toString())

                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        state.url = url
                        onAddress(url)
                        updateHistory()
                    }

                    override fun onPageCommitVisible(view: WebView, url: String) = watchFields()

                    override fun onPageFinished(view: WebView, url: String) {
                        watchFields()
                        updateHistory()
                        state.title = view.title.orEmpty()
                        visited(WebPage(view.title?.ifBlank { null } ?: url, url))
                    }

                    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                        state.url = url
                        onAddress(url)
                        updateHistory()
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, newProgress: Int) = onProgress(newProgress / 100f)

                    override fun onReceivedTitle(view: WebView, title: String?) {
                        state.title = title.orEmpty()
                    }

                    override fun onShowCustomView(view: View, callback: CustomViewCallback) = onFullscreen(view, callback)

                    override fun onHideCustomView() = onFullscreen(null, null)

                    // Камера и микрофон закрыты шлемом, а местоположение сайтам не нужно.
                    override fun onPermissionRequest(request: PermissionRequest) = request.deny()

                    override fun onGeolocationPermissionsShowPrompt(
                        origin: String,
                        callback: android.webkit.GeolocationPermissions.Callback,
                    ) = callback.invoke(origin, false, false)
                }
                if (keyboard != null) addJavascriptInterface(FieldBridge(this, keyboard), BrowserScripts.BRIDGE)
                val saved = state.saved
                if (saved != null) restoreState(saved) else if (state.request == null) loadUrl(initialUrl)
            }
        }
        LaunchedEffect(webView, state.request) {
            val url = state.request ?: return@LaunchedEffect
            state.request = null
            webView.loadUrl(url)
        }
        DisposableEffect(webView) {
            holder.view = webView
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> webView.onPause()
                    Lifecycle.Event.ON_RESUME -> webView.onResume()
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                // Ушли из браузера или сменился режим: история переходов сохраняется, страница освобождается.
                if (state.url != null) state.saved = Bundle().also { webView.saveState(it) }
                keyboard?.close()
                if (holder.view === webView) holder.view = null
                webView.stopLoading()
                webView.destroy()
            }
        }
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
    }
}

/** Страница сообщает о выбранном поле, клавиатура шлема печатает в него. */
private class FieldBridge(private val view: WebView, private val keyboard: VrKeyboardState) {
    @JavascriptInterface
    fun focus(label: String, secret: Boolean, value: String) {
        view.post {
            keyboard.open(
                KeyboardTarget(
                    label = label.ifBlank { "Поле на странице" },
                    secret = secret,
                    text = { value },
                    onTextChange = { view.evaluateJavascript(BrowserScripts.setValue(it), null) },
                    onDone = { view.evaluateJavascript(BrowserScripts.SUBMIT, null) },
                ),
            )
        }
    }
}

/** Стартовая страница: адрес или поиск, закладки и недавние сайты. */
@Composable
private fun StartPage(store: BrowserStore, onOpen: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Закладки", style = MaterialTheme.typography.titleMedium)
        }
        if (store.bookmarks.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Здесь будут закладки: ☆ рядом с адресом добавляет страницу.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(store.bookmarks, key = { "b" + it.url }) { page ->
            SiteTile(page, onOpen = { onOpen(page.url) }, onRemove = { store.removeBookmark(page) })
        }
        if (store.history.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Недавние сайты", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = store::clearHistory) { Text("Очистить") }
                }
            }
            items(store.history.take(12), key = { "h" + it.url }) { page ->
                SiteTile(page, onOpen = { onOpen(page.url) }, onRemove = null)
            }
        }
    }
}

@Composable
private fun SiteTile(page: WebPage, onOpen: () -> Unit, onRemove: (() -> Unit)?) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onOpen)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(page.title.ifBlank { page.host }.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(page.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                page.host,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onRemove != null) TextButton(onClick = onRemove) { Text("✕") }
    }
}

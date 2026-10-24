package com.manfis.astramatic.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.google.cardboard.sdk.CardboardView
import com.manfis.astramatic.model.Projection
import com.manfis.astramatic.model.StereoLayout
import com.manfis.astramatic.model.VideoFormat
import com.manfis.astramatic.ui.VrMenu
import com.manfis.astramatic.ui.VrMenuActions
import com.manfis.astramatic.ui.VrMenuState

/**
 * VR-оболочка: видео, меню (плеер, файлы, шлем) и переключение VR ⇄ 2D.
 * Можно открыть сразу с видео (intent.data) или без него — тогда начинается с файлов.
 */
class PlayerActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var renderer: VrRenderer
    private lateinit var cardboardView: CardboardView
    private lateinit var uiHost: VrUiHost
    private lateinit var modeButton: TextView

    private val menu = VrMenuState()
    private val handler = Handler(Looper.getMainLooper())

    private val prefs by lazy { getSharedPreferences("player", MODE_PRIVATE) }

    /** true — VR (два глаза, линзы), false — обычный плоский показ на экране телефона. */
    private var stereoMode: Boolean
        get() = prefs.getBoolean(KEY_STEREO, true)
        set(value) = prefs.edit().putBoolean(KEY_STEREO, value).apply()

    @OptIn(UnstableApi::class)
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        player = ExoPlayer.Builder(this)
            // Умеет и обычные файлы, и smb:// с компьютера.
            .setMediaSourceFactory(DefaultMediaSourceFactory(AppDataSource.Factory(this)))
            .build()
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) {
                    renderer.videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "Ошибка воспроизведения: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
                showMenu(VrMenuState.Tab.Files)
            }
        })

        renderer = VrRenderer(
            onVideoSurfaceReady = { surface -> runOnUiThread { player.setVideoSurface(surface) } },
            onPanelSurfaceReady = { surface -> runOnUiThread { uiHost.setSurface(surface) } },
        )

        // Как в примере paperscope: GL-вид SDK умеет выполнить код завершения на GL-потоке при отсоединении.
        CardboardView.setUseCardboardGlSurfaceView(true)
        cardboardView = CardboardView(this).apply {
            setRenderer(renderer)
            setOnViewDetachedRunnable {
                renderer.onRendererShutdown()
                cardboardView.onDestroy()
            }
            setStereoRenderMode(stereoMode)
            // Крестик SDK — выход из VR, как в других Cardboard-приложениях.
            setOnBackButtonClick { finish() }
            setOnSettingsButtonClick { showMenu(VrMenuState.Tab.Headset) }
        }
        cardboardView.setOnTouchListener { _, event ->
            onScreenTouch(event)
            true
        }

        val screenOverlay = FrameLayout(this).apply { visibility = View.GONE }
        modeButton = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            val padH = dp(16)
            val padV = dp(8)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0x99000000.toInt())
            }
            setOnClickListener { toggleStereoMode() }
        }
        // Кнопка сверху по центру: в режиме VR это стык двух половинок экрана, в линзах её почти не видно.
        setContentView(FrameLayout(this).apply {
            addView(cardboardView)
            addView(screenOverlay)
            addView(
                modeButton,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                ).apply { topMargin = dp(12) },
            )
        })

        menu.stereo = stereoMode
        uiHost = VrUiHost(this, screenOverlay) { VrMenu(menu, menuActions) }
        updateModeButton()

        onBackPressedDispatcher.addCallback(this) {
            when {
                menu.title == null -> finish()
                uiHost.place == VrUiHost.Place.Hidden -> showControls()
                else -> hideMenu()
            }
        }

        val uri = intent.data
        if (uri != null) {
            openVideo(uri, intent.getStringExtra(EXTRA_TITLE) ?: uri.lastPathSegment ?: "", formatFrom(intent))
        } else {
            showMenu(VrMenuState.Tab.Files)
        }
    }

    private val menuActions = object : VrMenuActions {
        override fun isPlaying() = player.isPlaying
        override fun positionMs() = player.currentPosition
        override fun durationMs() = player.duration.coerceAtLeast(0)
        override fun togglePlayback() = this@PlayerActivity.togglePlayback()
        override fun seekTo(positionMs: Long) = player.seekTo(positionMs)

        override fun seekBy(deltaMs: Long) {
            val end = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            player.seekTo((player.currentPosition + deltaMs).coerceIn(0, end))
        }

        override fun setFormat(format: VideoFormat) {
            menu.format = format
            renderer.format = format
        }

        override fun toggleStereo() = toggleStereoMode()
        override fun recenter() = renderer.recenter()
        override fun play(uri: Uri, name: String, format: VideoFormat) = openVideo(uri, name, format)
        override fun headsetSaved() = cardboardView.loadDeviceParams()
        override fun openMenu(tab: VrMenuState.Tab) = showMenu(tab)
        override fun hide() = hideMenu()
        override fun exit() = finish()
    }

    private fun openVideo(uri: Uri, title: String, format: VideoFormat) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.play()
        menu.title = title
        menuActions.setFormat(format)
        renderer.hasVideo = true
        renderer.recenter()
        menu.tab = VrMenuState.Tab.Video
        hideMenu()
    }

    // --- Меню -------------------------------------------------------------------------------

    /** Большое меню: видео, файлы, шлем. */
    private fun showMenu(tab: VrMenuState.Tab? = null) {
        if (tab != null) menu.tab = tab
        showPanel(VrMenuState.Mode.Menu)
    }

    /** Полоска управления воспроизведением. Без видео управлять нечем — тогда меню. */
    private fun showControls() {
        if (menu.title == null) showMenu(VrMenuState.Tab.Files) else showPanel(VrMenuState.Mode.Controls)
    }

    private fun showPanel(mode: VrMenuState.Mode) {
        menu.mode = mode
        if (stereoMode) {
            renderer.panelLayout = when (mode) {
                VrMenuState.Mode.Menu -> VrRenderer.PanelLayout.Menu
                VrMenuState.Mode.Controls -> VrRenderer.PanelLayout.Controls
            }
            renderer.placePanelInFront()
            renderer.panelVisible = true
            uiHost.showInVr()
            handler.removeCallbacks(hoverTick)
            handler.post(hoverTick)
        } else {
            uiHost.showOnScreen()
        }
        updateModeButton()
    }

    private fun hideMenu() {
        handler.removeCallbacks(hoverTick)
        uiHost.hover(null, null)
        renderer.panelVisible = false
        uiHost.hide()
        updateModeButton()
    }

    /** Подсветка под взглядом, пока панель в шлеме. */
    private val hoverTick = object : Runnable {
        override fun run() {
            if (uiHost.place != VrUiHost.Place.Vr) return
            val gaze = renderer.gaze
            uiHost.hover(gaze?.x, gaze?.y)
            handler.postDelayed(this, HOVER_INTERVAL_MS)
        }
    }

    // --- Касания экрана (кнопка Cardboard) --------------------------------------------------

    private var pressStart = 0L
    private var pressedOnPanel = false
    private var lastGaze: VrRenderer.Gaze? = null

    /** Пока кнопка зажата на панели, точка нажатия следует за взглядом: так прокручиваются списки и двигаются ползунки. */
    private val dragTick = object : Runnable {
        override fun run() {
            if (!pressedOnPanel) return
            renderer.gaze?.let {
                lastGaze = it
                uiHost.touch(MotionEvent.ACTION_MOVE, it.x, it.y)
            }
            handler.postDelayed(this, DRAG_INTERVAL_MS)
        }
    }

    private fun onScreenTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressStart = event.eventTime
                val gaze = renderer.gaze
                if (uiHost.place == VrUiHost.Place.Vr && gaze != null) {
                    pressedOnPanel = true
                    lastGaze = gaze
                    uiHost.touch(MotionEvent.ACTION_DOWN, gaze.x, gaze.y)
                    handler.postDelayed(dragTick, DRAG_INTERVAL_MS)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pressedOnPanel) {
                    pressedOnPanel = false
                    handler.removeCallbacks(dragTick)
                    val point = renderer.gaze ?: lastGaze ?: return
                    val action = if (event.actionMasked == MotionEvent.ACTION_UP) MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL
                    uiHost.touch(action, point.x, point.y)
                    return
                }
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) return
                val longPress = event.eventTime - pressStart >= LONG_PRESS_MS
                when {
                    // Нажатие мимо панели закрывает меню, если есть что смотреть, иначе возвращает панель перед глаза.
                    uiHost.place == VrUiHost.Place.Vr -> if (menu.title != null) hideMenu() else renderer.placePanelInFront()
                    longPress -> renderer.recenter()
                    else -> showControls()
                }
            }
        }
    }

    // --- Пульт и геймпад --------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // В шлеме меню живёт на виртуальном дисплее: стрелки и OK отправляем туда.
        if (uiHost.place == VrUiHost.Place.Vr) {
            val forwarded = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_TAB -> event
                KeyEvent.KEYCODE_BUTTON_A -> KeyEvent(
                    event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER, event.repeatCount,
                )
                else -> null
            }
            if (forwarded != null) {
                uiHost.key(forwarded)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (uiHost.place != VrUiHost.Place.Hidden) {
            // Menu/Start: полоска → меню → скрыть.
            if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BUTTON_START) {
                when {
                    menu.mode == VrMenuState.Mode.Controls -> showMenu(VrMenuState.Tab.Video)
                    menu.title != null -> hideMenu()
                }
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> togglePlayback()
            KeyEvent.KEYCODE_MEDIA_PLAY -> player.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> player.pause()
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_BUTTON_L1 -> menuActions.seekBy(-SEEK_STEP_MS)
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_BUTTON_R1 -> menuActions.seekBy(SEEK_STEP_MS)
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_BUTTON_Y -> renderer.recenter()
            KeyEvent.KEYCODE_V,
            KeyEvent.KEYCODE_BUTTON_X -> toggleStereoMode()
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT -> showControls()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    // --- Режимы -----------------------------------------------------------------------------

    private fun toggleStereoMode() {
        stereoMode = !stereoMode
        menu.stereo = stereoMode
        cardboardView.setStereoRenderMode(stereoMode)
        if (uiHost.place != VrUiHost.Place.Hidden) {
            if (!stereoMode) {
                renderer.panelVisible = false
                handler.removeCallbacks(hoverTick)
            }
            showPanel(menu.mode)
        }
        updateModeButton()
    }

    /** Подпись — режим, в который кнопка переключит. На плоском меню у неё есть своя кнопка. */
    private fun updateModeButton() {
        modeButton.text = if (stereoMode) "2D" else "VR"
        modeButton.visibility = if (uiHost.place == VrUiHost.Place.Screen) View.GONE else View.VISIBLE
    }

    private fun togglePlayback() {
        if (player.isPlaying) player.pause() else player.play()
    }

    override fun onResume() {
        super.onResume()
        cardboardView.onResume()
    }

    override fun onPause() {
        cardboardView.onPause()
        super.onPause()
    }

    override fun onStop() {
        player.pause()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::uiHost.isInitialized) uiHost.release()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SEEK_STEP_MS = 10_000L
        private const val LONG_PRESS_MS = 500L
        private const val DRAG_INTERVAL_MS = 16L
        private const val HOVER_INTERVAL_MS = 50L
        private const val KEY_STEREO = "stereo"
        private const val EXTRA_PROJECTION = "projection"
        private const val EXTRA_STEREO = "stereo"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, uri: Uri, title: String, format: VideoFormat): Intent =
            Intent(context, PlayerActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_PROJECTION, format.projection.name)
                .putExtra(EXTRA_STEREO, format.stereo.name)

        private fun formatFrom(intent: Intent): VideoFormat {
            val default = VideoFormat()
            return VideoFormat(
                projection = intent.getStringExtra(EXTRA_PROJECTION)
                    ?.let { name -> Projection.entries.find { it.name == name } } ?: default.projection,
                stereo = intent.getStringExtra(EXTRA_STEREO)
                    ?.let { name -> StereoLayout.entries.find { it.name == name } } ?: default.stereo,
            )
        }
    }
}

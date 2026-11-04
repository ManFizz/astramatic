package com.manfis.astramatic.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.compose.runtime.snapshotFlow
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.cardboard.sdk.CardboardView
import com.manfis.astramatic.remote.PlayerStatus
import com.manfis.astramatic.settings.AppSettings
import com.manfis.astramatic.settings.KeyBindings
import com.manfis.astramatic.ui.VrMenu
import com.manfis.astramatic.ui.VrMenuState
import java.util.concurrent.Executors
import kotlinx.coroutines.launch

/**
 * Связка плеера с Android: окно, Cardboard, события ввода и жизненный цикл.
 * Вся логика — в [PanelController], [PlayerSession] и [RemoteInput].
 * Можно открыть сразу с видео ([PendingPlaylist]) или без него — тогда начинается с файлов.
 */
class PlayerActivity : ComponentActivity() {

    private lateinit var media: PlayerMedia
    private lateinit var renderer: VrRenderer
    private lateinit var cardboardView: CardboardView
    private lateinit var uiHost: VrUiHost
    private lateinit var modeButton: TextView
    private lateinit var panels: PanelController
    private lateinit var session: PlayerSession
    private lateinit var input: RemoteInput

    private val menu = VrMenuState()
    private val handler = Handler(Looper.getMainLooper())
    private val ticks = HandlerTicks(handler)
    private val settings by lazy { AppSettings.get(this) }
    private val compass by lazy { CompassDrift(SensorRotationSource.of(this), SystemClock::uptimeMillis) }

    /** Сеть (позиции в Darkframe, списки с ПК) — не на главном потоке, по одному запросу за раз. */
    private val io = Executors.newSingleThreadExecutor()

    private val remote by lazy {
        val commands = DesktopCommands(
            session,
            DesktopPlaylists(this, io, handler, onOpen = { session.open(it) }, onNotice = { panels.showNotice(it) }),
        )
        RemoteLink(
            context = this,
            status = {
                PlayerStatus(
                    device = Build.MODEL,
                    title = menu.title,
                    positionMs = session.positionMs(),
                    durationMs = session.durationMs(),
                    playing = session.isPlaying(),
                    stereo = stereoMode,
                    format = menu.title?.let { menu.format.encode() },
                )
            },
            onCommand = commands::handle,
        )
    }

    private val prefs by lazy { getSharedPreferences("player", MODE_PRIVATE) }

    /** true — VR (два глаза, линзы), false — обычный плоский показ на экране телефона. */
    private var stereoMode: Boolean
        get() = prefs.getBoolean(KEY_STEREO, true)
        set(value) = prefs.edit().putBoolean(KEY_STEREO, value).apply()

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        renderer = VrRenderer(
            settings = settings,
            environmentFile = environmentFile(this),
            onVideoSurfaceReady = { surface -> runOnUiThread { media.onVideoSurface(surface) } },
            onPanelSurfaceReady = { surface -> runOnUiThread { uiHost.setSurface(surface) } },
            compass = compass.takeIf { it.available },
            onMenuSizeChanged = { size -> runOnUiThread { settings.menuSize = size } },
        )
        media = PlayerMedia(
            this, renderer, settings,
            onEnded = { session.onVideoEnded() },
            onError = { session.onPlaybackError(it) },
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
            setOnSettingsButtonClick { panels.showMenu(VrMenuState.Tab.Headset) }
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
        uiHost = VrUiHost(this, screenOverlay) { VrMenu(menu, session) }
        panels = PanelController(
            menu, settings, uiHost, renderer, ticks,
            clock = SystemClock::uptimeMillis,
            stereo = { stereoMode },
            videoPlaying = { session.videoPlaying },
            exit = ::finish,
            onShownChanged = ::updateModeButton,
        )
        session = PlayerSession(
            menu, settings, media,
            memory = PlaybackStore(this, settings, io, handler),
            scene = renderer,
            panels = panels,
            ticks = ticks,
            switchStereo = ::toggleStereoMode,
            // Только на потоке отрисовки: SDK пользуется параметрами шлема там же, и перечитывание
            // с главного потока роняло приложение посреди кадра.
            applyHeadset = { cardboardView.queueEvent { cardboardView.loadDeviceParams() } },
            closePlayer = ::finish,
        )
        input = RemoteInput(
            menu, settings.keys, uiHost, panels, session, ticks,
            clock = SystemClock::uptimeMillis,
            systemBack = { onBackPressedDispatcher.onBackPressed() },
            volume = ::changeVolume,
        )
        updateModeButton()

        lifecycleScope.launch {
            snapshotFlow { menu.tab to menu.mode }.collect { panels.updateCalibration() }
        }
        lifecycleScope.launch {
            snapshotFlow { menu.sheet }.collect { panels.updateLayout() }
        }
        lifecycleScope.launch {
            snapshotFlow { menu.capturingKey }.collect { input.captureChanged() }
        }

        // «Назад» ведёт к главной (файлам) и дальше неё не уходит: из VR выходят только крестиком, пальцем.
        // В 2D на главной «Назад» выходит, как в обычном приложении.
        onBackPressedDispatcher.addCallback(this) { panels.goBack() }

        val pending = PendingPlaylist.take()
        if (pending != null) session.open(pending) else panels.showMenu(VrMenuState.Tab.Home)
    }

    private fun onScreenTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> panels.triggerDown(event.eventTime)
            MotionEvent.ACTION_UP -> panels.triggerUp(event.eventTime, cancelled = false)
            MotionEvent.ACTION_CANCEL -> panels.triggerUp(event.eventTime, cancelled = true)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down && event.repeatCount == 0) {
            menu.lastKey = "${KeyBindings.keyName(event.keyCode)} · ${event.device?.name ?: "?"}"
        }

        return when (val route = input.onKey(event.keyCode, down, event.repeatCount, event.eventTime)) {
            KeyRoute.Consumed -> true
            is KeyRoute.ToPanel -> {
                val forwarded = if (route.keyCode == event.keyCode) {
                    event
                } else {
                    KeyEvent(event.downTime, event.eventTime, event.action, route.keyCode, event.repeatCount)
                }
                uiHost.key(forwarded)
                true
            }
            KeyRoute.System -> super.dispatchKeyEvent(event)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        // Отдельные «играть» и «пауза» некоторых пультов работают всегда.
        KeyEvent.KEYCODE_MEDIA_PLAY -> true.also { media.play() }
        KeyEvent.KEYCODE_MEDIA_PAUSE -> true.also { media.pause() }
        else -> super.onKeyDown(keyCode, event)
    }

    /** Громкость без системной плашки: в шлеме её всё равно не видно. */
    private fun changeVolume(louder: Boolean) {
        val direction = if (louder) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL && input.onWheel(event.getAxisValue(MotionEvent.AXIS_VSCROLL))) {
            return true
        }
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.action != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event)
        }
        val motion = event.device?.motionRanges.orEmpty().filter { it.isFromSource(InputDevice.SOURCE_JOYSTICK) }
        val ranges = motion.map { AxisRange(it.axis, it.min) }
        val values = motion.associate { it.axis to event.getAxisValue(it.axis) }
        input.strongestAxis(ranges, values)?.let { (axis, value) ->
            menu.lastKey = "${MotionEvent.axisToString(axis).removePrefix("AXIS_")} " +
                "${"%+.1f".format(value)} · ${event.device?.name ?: "?"}"
        }
        input.onAxes(event.eventTime, ranges, values)
        return true
    }

    private fun toggleStereoMode() {
        stereoMode = !stereoMode
        menu.stereo = stereoMode
        cardboardView.setStereoRenderMode(stereoMode)
        panels.stereoChanged()
    }

    /** Подпись — режим, в который кнопка переключит. На плоском меню у неё есть своя кнопка. */
    private fun updateModeButton() {
        modeButton.text = if (stereoMode) "2D" else "VR"
        modeButton.visibility = if (uiHost.place == PanelPlace.Screen) View.GONE else View.VISIBLE
    }

    override fun onStart() {
        super.onStart()
        remote.start()
    }

    override fun onResume() {
        super.onResume()
        cardboardView.onResume()
        compass.start()
    }

    override fun onPause() {
        compass.stop()
        cardboardView.onPause()
        super.onPause()
    }

    override fun onStop() {
        remote.stop()
        session.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::uiHost.isInitialized) uiHost.release()
        if (::media.isInitialized) media.release()
        io.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_STEREO = "stereo"

        /** Своя панорама окружения: копия, уменьшенная до [EnvironmentLayer.CUSTOM_MAX_WIDTH]. */
        fun environmentFile(context: Context) = java.io.File(context.filesDir, "environment.jpg")
    }
}

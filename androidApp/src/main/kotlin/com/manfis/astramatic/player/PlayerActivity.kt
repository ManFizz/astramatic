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
import com.manfis.astramatic.ui.MAX_ZOOM
import com.manfis.astramatic.ui.MIN_ZOOM
import android.graphics.Bitmap
import android.view.Surface
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.snapshotFlow
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.ErrorResult
import com.manfis.astramatic.ui.describe
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.manfis.astramatic.settings.AppSettings
import com.manfis.astramatic.settings.RemoteAction
import com.manfis.astramatic.settings.AxisBinding
import com.manfis.astramatic.settings.AxisContext
import com.manfis.astramatic.settings.KeyBindings
import android.media.AudioManager
import kotlin.math.hypot
import com.manfis.astramatic.library.DarkframeClient
import com.manfis.astramatic.library.DarkframeStore
import java.util.concurrent.Executors
import android.os.Build
import com.manfis.astramatic.library.DesktopClient
import com.manfis.astramatic.library.DesktopServer
import com.manfis.astramatic.library.MediaLibrary
import com.manfis.astramatic.library.SmbThumbnailFetcher
import com.manfis.astramatic.library.MediaSourceRef
import com.manfis.astramatic.model.VideoFormatDetector
import com.manfis.astramatic.remote.PlayerStatus
import com.manfis.astramatic.remote.VideoFiles
import com.manfis.astramatic.remote.RemoteCommand
import com.manfis.astramatic.ui.DefaultFormat
import kotlin.math.abs
import kotlin.random.Random
import com.manfis.astramatic.settings.RepeatMode

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
    private val settings by lazy { AppSettings.get(this) }
    private val positions by lazy { ResumePositions(this) }
    private val formats by lazy { FormatMemory(this) }
    private val history by lazy { History.get(this) }
    private val darkframes by lazy { DarkframeStore(this) }
    private val compass by lazy { CompassDrift(SensorRotationSource.of(this), SystemClock::uptimeMillis) }

    /** Сеть (позиции в Darkframe, списки с ПК) — не на главном потоке, по одному запросу за раз. */
    private val io = Executors.newSingleThreadExecutor()

    private val remote by lazy {
        RemoteLink(
            context = this,
            status = {
                PlayerStatus(
                    device = Build.MODEL,
                    title = menu.title,
                    positionMs = player.currentPosition,
                    durationMs = player.duration.coerceAtLeast(0),
                    playing = menuActions.isPlaying(),
                    stereo = stereoMode,
                    format = menu.title?.let { menu.format.encode() },
                )
            },
            onCommand = ::onRemoteCommand,
        )
    }
    private var playlist: Playlist? = null

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

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_ENDED || playlist?.current?.isImage == true) return
                savePosition()
                if (repeatMode == RepeatMode.One) {
                    player.seekTo(0)
                    player.play()
                    return
                }
                val next = playlist?.let { step(it, 1) }
                val auto = settings.autoplayNext || repeatMode == RepeatMode.All || settings.shuffle
                if (auto && next != null) openPlaylist(next) else showControls()
            }

            override fun onPlayerError(error: PlaybackException) {
                showNotice(error.describe(), VrMenuState.Tab.Home)
            }
        })

        renderer = VrRenderer(
            settings = settings,
            environmentFile = environmentFile(this),
            onVideoSurfaceReady = { surface -> runOnUiThread { onVideoSurface(surface) } },
            onPanelSurfaceReady = { surface -> runOnUiThread { uiHost.setSurface(surface) } },
            compass = compass.takeIf { it.available },
            onMenuSizeChanged = { size -> runOnUiThread { settings.menuSize = size } },
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

        lifecycleScope.launch {
            snapshotFlow { menu.tab to menu.mode }.collect { updateCalibration() }
        }
        lifecycleScope.launch {
            snapshotFlow { menu.sheet }.collect { updatePanelLayout() }
        }
        lifecycleScope.launch {
            snapshotFlow { menu.capturingKey }.collect {
                captureStartedAt = SystemClock.uptimeMillis()
                captureBaseline = null
            }
        }

        // «Назад» ведёт к главной (файлам) и дальше неё не уходит: из VR выходят только крестиком, пальцем.
        // В 2D на главной «Назад» выходит, как в обычном приложении.
        onBackPressedDispatcher.addCallback(this) { goBack() }

        val pending = PendingPlaylist.take()
        if (pending != null) openPlaylist(pending) else showMenu(VrMenuState.Tab.Home)
    }

    private val menuActions = object : VrMenuActions {
        // У фото «играет» — значит идёт слайд-шоу.
        override fun isPlaying() = if (playlist?.current?.isImage == true) slideshowRunning() else player.isPlaying
        override fun positionMs() = player.currentPosition
        override fun durationMs() = player.duration.coerceAtLeast(0)
        override fun togglePlayback() = this@PlayerActivity.togglePlayback()
        override fun seekTo(positionMs: Long) = player.seekTo(positionMs)

        override fun seekBy(deltaMs: Long) {
            val end = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            player.seekTo((player.currentPosition + deltaMs).coerceIn(0, end))
        }

        override fun setFormat(format: VideoFormat) {
            applyFormat(format)
            // Выбран вручную — запоминаем для этого файла.
            playlist?.current?.let { formats.save(it.uri, format) }
        }

        override fun resetFormat() {
            val item = playlist?.current ?: return
            formats.clear(item.uri)
            applyFormat(item.format)
        }

        override fun toggleStereo() = toggleStereoMode()
        override fun recenter() = renderer.recenter()

        override fun setZoom(zoom: Float) {
            val value = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
            menu.zoom = value
            renderer.zoom = value
        }

        override fun setSpeed(speed: Float) {
            menu.speed = speed
            player.setPlaybackSpeed(speed)
        }

        override fun setAsEnvironment(): String = saveEnvironment()
        // Выбрано в меню — видео встаёт там, где меню.
        override fun play(playlist: Playlist) = openPlaylist(playlist, fromMenu = true)
        override fun previousFile() { playlist?.let { step(it, -1) }?.let(::openPlaylist) }
        override fun nextFile() { playlist?.let { step(it, 1) }?.let(::openPlaylist) }

        override fun cycleRepeat() {
            settings.repeatMode = RepeatMode.entries[(repeatMode.ordinal + 1) % RepeatMode.entries.size].name
            updateNavigation()
        }

        override fun toggleShuffle() {
            settings.shuffle = !settings.shuffle
            updateNavigation()
        }

        override fun playQueueItem(index: Int) {
            val list = playlist ?: return
            if (index in list.items.indices && index != list.index) openPlaylist(list.copy(index = index))
        }

        override fun toggleSlideshow() {
            settings.slideshow = !settings.slideshow
            slideshowPaused = false
            scheduleSlideshow()
        }
        // Только на потоке отрисовки: SDK пользуется параметрами шлема там же, и перечитывание
        // с главного потока роняло приложение посреди кадра.
        override fun headsetSaved() = cardboardView.queueEvent { cardboardView.loadDeviceParams() }
        override fun openMenu(tab: VrMenuState.Tab) = showMenu(tab)
        override fun hide() = hideMenu()
        override fun exit() = finish()
    }

    private val repeatMode get() = RepeatMode.entries.find { it.name == settings.repeatMode } ?: RepeatMode.Off

    /**
     * Соседний файл с учётом режимов: случайный — любой другой, «повтор папки» — по кругу.
     * null — дальше некуда.
     */
    private fun step(list: Playlist, delta: Int): Playlist? {
        val size = list.items.size
        if (settings.shuffle && size > 1) {
            val other = Random.nextInt(size - 1)
            return list.copy(index = if (other >= list.index) other + 1 else other)
        }
        list.moved(delta)?.let { return it }
        if (repeatMode != RepeatMode.All || size < 2) return null
        return list.copy(index = if (delta > 0) 0 else list.items.lastIndex)
    }

    private fun updateNavigation() {
        val list = playlist ?: return
        menu.hasPrevious = step(list, -1) != null
        menu.hasNext = step(list, 1) != null
    }

    /**
     * Видео открывается в том же направлении, что и предыдущее: смотрели вбок — центр не съезжает.
     * [fromMenu] — выбрано в меню: тогда видео встаёт там, где меню.
     */
    private fun openPlaylist(next: Playlist, fromMenu: Boolean = false) {
        savePosition()
        val wasImage = playlist?.current?.isImage == true
        playlist = next
        val item = next.current
        handler.removeCallbacks(slideshowTick)
        imageJob?.cancel()
        menuActions.setZoom(1f)
        if (item.isImage) {
            showImage(item)
        } else {
            image = null
            // После фото поверхность занята холстом — декодеру нужна свежая.
            if (wasImage) renderer.newVideoSurface()?.let { videoSurface = it }
            videoSurface?.let(player::setVideoSurface)
            player.setMediaItem(MediaItem.fromUri(item.uri))
            player.prepare()
            if (settings.resumePlayback) resume(item)
            menuActions.setSpeed(1f)
            player.play()
        }
        if (settings.keepHistory) history.opened(item)
        menu.isImage = item.isImage
        menu.title = item.title
        updateNavigation()
        menu.queue = next.items
        menu.queueIndex = next.index
        applyFormat(formats.get(item.uri) ?: item.format)
        handler.removeCallbacks(saveTick)
        handler.postDelayed(saveTick, SAVE_INTERVAL_MS)
        renderer.hasVideo = true
        if (fromMenu) renderer.centerOnPanel()
        menu.tab = VrMenuState.Tab.Home
        hideMenu()
    }

    private fun applyFormat(format: VideoFormat) {
        menu.format = format
        renderer.format = format
    }

    /** У видео из Darkframe позиция общая с Darkframe, у остальных — своя, на телефоне. */
    private fun resume(item: PlaylistItem) {
        val ref = item.darkframe
        if (ref == null) {
            positions.get(item.uri)?.let(player::seekTo)
            return
        }
        val server = darkframes.get(ref.serverId) ?: return
        io.execute {
            val position = runCatching { DarkframeClient.position(server, ref.itemId) }.getOrNull() ?: return@execute
            runOnUiThread { if (playlist?.current == item) player.seekTo(position) }
        }
    }

    // --- Фото ------------------------------------------------------------------------------

    private var videoSurface: Surface? = null
    private var image: Bitmap? = null
    private var imageJob: Job? = null
    private var slideshowPaused = false

    /** Поверхность кадра от рендерера; после пересоздания GL-контекста — новая. */
    private fun onVideoSurface(surface: Surface) {
        videoSurface = surface
        if (playlist?.current?.isImage == true) drawImage() else player.setVideoSurface(surface)
    }

    /**
     * Фото рисуется холстом в ту же поверхность, что и видео: дальше всё как у видео —
     * проекции, стерео, приближение, окружение.
     */
    private fun showImage(item: PlaylistItem) {
        player.stop()
        player.clearVideoSurface()
        image = null
        imageJob = lifecycleScope.launch {
            val request = ImageRequest.Builder(this@PlayerActivity)
                .data(item.uri.toString())
                .size(MAX_IMAGE_SIDE)
                .allowHardware(false)
                .build()
            val result = SingletonImageLoader.get(this@PlayerActivity).execute(request)
            val bitmap = (result as? SuccessResult)?.image?.toBitmap()
            if (bitmap == null) {
                val reason = (result as? ErrorResult)?.throwable?.describe()
                showNotice("Не удалось открыть фото${reason?.let { ": $it" } ?: "."}")
                return@launch
            }
            if (playlist?.current != item) return@launch
            image = bitmap
            renderer.newVideoSurface()?.let { videoSurface = it }
            drawImage()
            scheduleSlideshow()
        }
    }

    private fun drawImage() {
        val bitmap = image ?: return
        val surface = videoSurface ?: return
        renderer.setVideoBufferSize(bitmap.width, bitmap.height)
        renderer.videoAspect = bitmap.width.toFloat() / bitmap.height
        runCatching {
            val canvas = surface.lockHardwareCanvas()
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun slideshowRunning() = settings.slideshow && !slideshowPaused

    private fun scheduleSlideshow() {
        handler.removeCallbacks(slideshowTick)
        if (slideshowRunning()) handler.postDelayed(slideshowTick, settings.slideshowSeconds.coerceAtLeast(2) * 1000L)
    }

    private val slideshowTick = Runnable {
        if (playlist?.current?.isImage == true && slideshowRunning()) playlist?.let { step(it, 1) }?.let(::openPlaylist)
    }

    /**
     * Фото — в окружение: один глаз у стерео, не шире [EnvironmentLayer.CUSTOM_MAX_WIDTH].
     * Ожидается панорама 360° (2:1); другие пропорции растянутся по сфере.
     */
    private fun saveEnvironment(): String {
        val bitmap = image ?: return "Сначала откройте фото"
        val eye = when (menu.format.stereo) {
            StereoLayout.SideBySide -> Bitmap.createBitmap(bitmap, 0, 0, bitmap.width / 2, bitmap.height)
            StereoLayout.TopBottom -> Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height / 2)
            StereoLayout.Mono -> bitmap
        }
        val width = eye.width.coerceAtMost(EnvironmentLayer.CUSTOM_MAX_WIDTH)
        val scaled = Bitmap.createScaledBitmap(eye, width, (eye.height * width.toFloat() / eye.width).toInt(), true)
        environmentFile(this).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        settings.environment = Environment.Custom.name
        renderer.environmentChanged()
        val ratio = eye.width.toFloat() / eye.height
        return if (ratio in 1.8f..2.2f) "Готово: фото теперь вокруг вас." else "Готово, но это не панорама 2:1 — по краям будет растянуто."
    }

    /** Позиция пишется всегда: включить «продолжать с места» можно и позже. */
    private fun savePosition() {
        val item = playlist?.current?.takeIf { !it.isImage } ?: return
        val position = player.currentPosition
        val duration = player.duration
        positions.save(item.uri, position, duration)
        if (settings.keepHistory && duration > 0) history.progress(item.uri, position, duration)
        val ref = item.darkframe ?: return
        val server = darkframes.get(ref.serverId) ?: return
        if (duration <= 0) return
        io.execute { runCatching { DarkframeClient.savePosition(server, ref.itemId, position, duration) } }
    }

    private val saveTick = object : Runnable {
        override fun run() {
            if (player.isPlaying) savePosition()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    // --- Пульт с ПК ------------------------------------------------------------------------

    private fun onRemoteCommand(server: DesktopServer, command: RemoteCommand) {
        when (command) {
            RemoteCommand.TogglePlayback -> togglePlayback()
            is RemoteCommand.SeekBy -> menuActions.seekBy(command.deltaMs)
            is RemoteCommand.SeekTo -> player.seekTo(command.positionMs)
            RemoteCommand.PreviousFile -> menuActions.previousFile()
            RemoteCommand.NextFile -> menuActions.nextFile()
            RemoteCommand.Recenter -> renderer.recenter()
            // 2D включается только пальцем на телефоне.
            RemoteCommand.ToggleStereo -> Unit
            is RemoteCommand.SetFormat -> VideoFormat.decode(command.format)?.let(menuActions::setFormat)
            is RemoteCommand.Open -> openFromDesktop(server, command)
            is RemoteCommand.OpenDarkframe -> openFromDarkframe(server, command)
        }
    }

    /**
     * Видео из Darkframe, выбранное в окне ПК. Телефон открывает его через своё подключение к Darkframe —
     * того, что работает на том же компьютере (или единственного).
     */
    private fun openFromDarkframe(desktop: DesktopServer, command: RemoteCommand.OpenDarkframe) {
        val all = darkframes.all()
        val host = Uri.parse(desktop.baseUrl).host
        val server = all.find { Uri.parse(it.baseUrl).host == host } ?: all.singleOrNull()
        if (server == null) {
            showNotice("Подключите телефон к Darkframe: «+ Darkframe» на главном экране.")
            return
        }
        val settingsRef = this
        val library = MediaLibrary(this)
        io.execute {
            val videos = runCatching {
                kotlinx.coroutines.runBlocking { library.list(MediaSourceRef.Darkframe(server.id), command.collection) }
            }.getOrNull()?.filter { !it.isDirectory && it.playUri != null } ?: return@execute
            val index = videos.indexOfFirst { it.path == command.itemId }.takeIf { it >= 0 } ?: return@execute
            val items = videos.map {
                PlaylistItem(
                    it.playUri!!, it.name, VideoFormatDetector.detect(it.formatHint) ?: DefaultFormat.forFile(settingsRef, it.isImage),
                    it.darkframe, isImage = it.isImage, thumbnail = it.thumbnail,
                )
            }
            runOnUiThread { openPlaylist(Playlist(items, index)) }
        }
    }

    /** Видео, выбранное в окне AstraMatic на ПК, — вместе с остальными видео его папки. */
    private fun openFromDesktop(server: DesktopServer, command: RemoteCommand.Open) {
        val settingsRef = this
        io.execute {
            val parent = command.path.substringBeforeLast('/', "")
            val videos = runCatching { DesktopClient.list(server, command.folderId, parent) }.getOrNull()
                ?.filter { !it.isDirectory }
                ?: return@execute
            val index = videos.indexOfFirst { it.path == command.path }.takeIf { it >= 0 } ?: return@execute
            val items = videos.map {
                val image = VideoFiles.isImage(it.name)
                val uri = server.fileUri(command.folderId, it.path)
                PlaylistItem(
                    uri, it.name,
                    VideoFormatDetector.detect(it.name) ?: DefaultFormat.forFile(settingsRef, image), isImage = image,
                    thumbnail = if (image) uri.toString() else "${SmbThumbnailFetcher.HTTP_VIDEO_SCHEME}:$uri",
                )
            }
            runOnUiThread { openPlaylist(Playlist(items, index)) }
        }
    }

    // --- Меню -------------------------------------------------------------------------------

    /** Большое меню: видео, файлы, шлем. */
    private fun showMenu(tab: VrMenuState.Tab? = null) {
        if (tab != null) menu.tab = tab
        showPanel(VrMenuState.Mode.Menu)
    }

    /** Полоска управления воспроизведением. Без видео управлять нечем — тогда меню. */
    private fun showControls() {
        if (menu.title == null) showMenu(VrMenuState.Tab.Home) else showPanel(VrMenuState.Mode.Controls)
    }

    /** Какую часть виртуального дисплея и где показывает рендерер: меню, полоску или полоску с панелью. */
    private fun updatePanelLayout() {
        renderer.panelLayout = when {
            menu.mode == VrMenuState.Mode.Menu -> PanelLayout.Menu
            menu.sheet != null -> PanelLayout.ControlsSheet
            else -> PanelLayout.Controls
        }
    }

    private fun showPanel(mode: VrMenuState.Mode) {
        menu.mode = mode
        if (mode == VrMenuState.Mode.Menu) menu.sheet = null
        lastLookAtControls = SystemClock.uptimeMillis()
        if (stereoMode) {
            updatePanelLayout()
            renderer.placePanelInFront()
            renderer.panelVisible = true
            uiHost.showInVr()
            handler.removeCallbacks(hoverTick)
            handler.post(hoverTick)
        } else {
            uiHost.showOnScreen()
        }
        updateCalibration()
        updateModeButton()
    }

    /**
     * «Назад» — шаг к главной: отмена назначения кнопки → из видео на главную → из настроек на главную →
     * вверх по папкам. На главной в VR ничего не делает, в 2D выходит.
     */
    private fun goBack() {
        val library = menu.library.backStack
        when {
            menu.capturingKey != null -> menu.capturingKey = null
            uiHost.place == VrUiHost.Place.Hidden || menu.mode == VrMenuState.Mode.Controls -> showMenu(VrMenuState.Tab.Home)
            menu.tab != VrMenuState.Tab.Home -> menu.tab = VrMenuState.Tab.Home
            library.size > 1 -> library.removeAt(library.lastIndex)
            !stereoMode -> finish()
        }
    }

    /** Калибровочная комната вокруг — пока в шлеме открыта настройка шлема. */
    private fun updateCalibration() {
        renderer.calibration = stereoMode && uiHost.place == VrUiHost.Place.Vr &&
            menu.mode == VrMenuState.Mode.Menu && menu.tab == VrMenuState.Tab.Headset
    }

    /** Сообщение приложения: в меню, где его видно и в шлеме, и на экране. */
    private fun showNotice(text: String, tab: VrMenuState.Tab? = null) {
        menu.notice = text
        showMenu(tab)
    }

    private fun hideMenu() {
        menu.notice = null
        menu.sheet = null
        grabbing = false
        renderer.grab = Handle.None
        handler.removeCallbacks(hoverTick)
        uiHost.hover(null, null)
        renderer.panelVisible = false
        uiHost.hide()
        updateCalibration()
        updateModeButton()
    }

    /** Подсветка под взглядом, пока панель в шлеме. */
    private val hoverTick = object : Runnable {
        override fun run() {
            if (uiHost.place != VrUiHost.Place.Vr) {
                renderer.dwellProgress = 0f
                return
            }
            val gaze = renderer.gaze
            uiHost.hover(gaze?.x, gaze?.y)
            updateDwell(gaze)
            if (autoHideControls(gaze)) return
            handler.postDelayed(this, HOVER_INTERVAL_MS)
        }
    }

    /** Когда последний раз смотрели на полоску плеера (или трогали её). */
    private var lastLookAtControls = 0L

    /**
     * Полоска сама уходит, если идёт видео, а смотрят не на неё: панель не раскрыта, ничего не держат.
     * true — спрятали.
     */
    private fun autoHideControls(gaze: Gaze?): Boolean {
        val now = SystemClock.uptimeMillis()
        val watching = menu.mode == VrMenuState.Mode.Controls && menu.sheet == null && !pressedOnPanel &&
            !grabbing && gaze == null && player.isPlaying && playlist?.current?.isImage != true
        if (!watching) {
            lastLookAtControls = now
            return false
        }
        if (now - lastLookAtControls < CONTROLS_AUTO_HIDE_MS) return false
        hideMenu()
        return true
    }

    // Нажатие взглядом: точка простояла на месте dwellMs — нажимаем. Следующее — только после сдвига взгляда.
    private var dwellAnchor: Gaze? = null
    private var dwellStart = 0L
    private var dwellFired = false

    private fun updateDwell(gaze: Gaze?) {
        if (!settings.dwellClick || gaze == null || pressedOnPanel) {
            dwellAnchor = null
            renderer.dwellProgress = 0f
            return
        }
        val now = SystemClock.uptimeMillis()
        val anchor = dwellAnchor
        if (anchor == null || hypot(gaze.x - anchor.x, gaze.y - anchor.y) > DWELL_RADIUS_PX) {
            dwellAnchor = gaze
            dwellStart = now
            dwellFired = false
        }
        if (dwellFired) {
            renderer.dwellProgress = 0f
            return
        }
        val progress = (now - dwellStart).toFloat() / settings.dwellMs
        renderer.dwellProgress = progress.coerceAtMost(1f)
        if (progress >= 1f) {
            dwellFired = true
            renderer.dwellProgress = 0f
            val point = dwellAnchor ?: gaze
            uiHost.touch(MotionEvent.ACTION_DOWN, point.x, point.y)
            uiHost.touch(MotionEvent.ACTION_UP, point.x, point.y)
        }
    }

    // --- Нажатие: кнопка Cardboard или действие «Нажатие» на пульте ------------------------

    private var pressStart = 0L
    private var pressedOnPanel = false

    /** Держат уголок или полоску меню: нажатие не уходит в интерфейс. */
    private var grabbing = false
    private var lastGaze: Gaze? = null

    /** Пока нажатие держат на панели, точка следует за взглядом: так прокручиваются списки и двигаются ползунки. */
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
            MotionEvent.ACTION_DOWN -> triggerDown(event.eventTime)
            MotionEvent.ACTION_UP -> triggerUp(event.eventTime, cancelled = false)
            MotionEvent.ACTION_CANCEL -> triggerUp(event.eventTime, cancelled = true)
        }
    }

    private fun triggerDown(time: Long) {
        pressStart = time
        val handle = renderer.hoverHandle
        if (uiHost.place == VrUiHost.Place.Vr && handle != Handle.None) {
            grabbing = true
            renderer.grab = handle
            return
        }
        val gaze = renderer.gaze
        if (uiHost.place == VrUiHost.Place.Vr && gaze != null) {
            pressedOnPanel = true
            lastGaze = gaze
            uiHost.touch(MotionEvent.ACTION_DOWN, gaze.x, gaze.y)
            handler.postDelayed(dragTick, DRAG_INTERVAL_MS)
        }
    }

    private fun triggerUp(time: Long, cancelled: Boolean) {
        if (grabbing) {
            grabbing = false
            renderer.grab = Handle.None
            return
        }
        if (pressedOnPanel) {
            pressedOnPanel = false
            handler.removeCallbacks(dragTick)
            val point = renderer.gaze ?: lastGaze ?: return
            uiHost.touch(if (cancelled) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, point.x, point.y)
            return
        }
        if (cancelled) return
        val longPress = time - pressStart >= LONG_PRESS_MS
        when {
            // Нажатие мимо панели закрывает меню, если есть что смотреть, иначе возвращает панель перед глаза.
            uiHost.place == VrUiHost.Place.Vr -> if (menu.title != null) hideMenu() else renderer.placePanelInFront()
            uiHost.place == VrUiHost.Place.Screen -> Unit
            longPress -> renderer.recenter()
            else -> showControls()
        }
    }

    // --- Пульт и геймпад: кнопки ------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down && event.repeatCount == 0) {
            menu.lastKey = "${KeyBindings.keyName(event.keyCode)} · ${event.device?.name ?: "?"}"
        }

        // Назначение кнопки: следующая нажатая кнопка (кроме системной «Назад») достаётся выбранному действию.
        val capturing = menu.capturingKey
        if (capturing != null && !capturing.isAxis && event.keyCode != KeyEvent.KEYCODE_BACK) {
            if (down && event.repeatCount == 0) {
                settings.keys.assignKey(capturing, event.keyCode)
                menu.capturingKey = null
            }
            return true
        }

        // Назначения пульта важнее всего остального.
        val action = settings.keys.actionFor(event.keyCode)
        if (action != null) {
            if (action == RemoteAction.Click) {
                if (down && event.repeatCount == 0) triggerDown(event.eventTime)
                if (!down) triggerUp(event.eventTime, cancelled = false)
            } else if (down && (event.repeatCount == 0 || action in REPEATABLE)) {
                perform(action)
            }
            return true
        }

        // Не назначенные стрелки и OK ходят по меню в шлеме (на экране — сами, как обычно).
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        // Отдельные «играть» и «пауза» некоторых пультов работают всегда.
        KeyEvent.KEYCODE_MEDIA_PLAY -> true.also { player.play() }
        KeyEvent.KEYCODE_MEDIA_PAUSE -> true.also { player.pause() }
        else -> super.onKeyDown(keyCode, event)
    }

    private fun perform(action: RemoteAction) {
        when (action) {
            RemoteAction.Click -> Unit
            RemoteAction.Back -> onBackPressedDispatcher.onBackPressed()
            RemoteAction.Controls -> when {
                uiHost.place == VrUiHost.Place.Hidden -> showControls()
                menu.mode == VrMenuState.Mode.Controls -> showMenu(VrMenuState.Tab.Home)
                menu.title != null -> hideMenu()
            }
            RemoteAction.PlayPause -> togglePlayback()
            RemoteAction.SeekBack -> menuActions.seekBy(-SEEK_STEP_MS)
            RemoteAction.SeekForward -> menuActions.seekBy(SEEK_STEP_MS)
            RemoteAction.SeekBackLong -> menuActions.seekBy(-LONG_SEEK_STEP_MS)
            RemoteAction.SeekForwardLong -> menuActions.seekBy(LONG_SEEK_STEP_MS)
            RemoteAction.PreviousFile -> menuActions.previousFile()
            RemoteAction.NextFile -> menuActions.nextFile()
            RemoteAction.VolumeUp -> volume(AudioManager.ADJUST_RAISE)
            RemoteAction.VolumeDown -> volume(AudioManager.ADJUST_LOWER)
            RemoteAction.Recenter -> renderer.recenter()
            RemoteAction.ZoomIn -> menuActions.setZoom(menu.zoom * 1.15f)
            RemoteAction.ZoomOut -> menuActions.setZoom(menu.zoom / 1.15f)
            RemoteAction.NavUp -> navKey(KeyEvent.KEYCODE_DPAD_UP)
            RemoteAction.NavDown -> navKey(KeyEvent.KEYCODE_DPAD_DOWN)
            RemoteAction.NavLeft -> navKey(KeyEvent.KEYCODE_DPAD_LEFT)
            RemoteAction.NavRight -> navKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            RemoteAction.NavSelect -> navKey(KeyEvent.KEYCODE_DPAD_CENTER)
            RemoteAction.ScrollUp -> scrollMenu(SCROLL_STEP)
            RemoteAction.ScrollDown -> scrollMenu(-SCROLL_STEP)
            RemoteAction.AxisScroll, RemoteAction.AxisSeek, RemoteAction.AxisZoom,
            RemoteAction.AxisNavHorizontal, RemoteAction.AxisNavVertical -> Unit
        }
    }

    /** Громкость без системной плашки: в шлеме её всё равно не видно. */
    private fun volume(direction: Int) {
        getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
    }

    /** Стрелка или OK — в интерфейс меню, мимо назначений пульта. */
    private fun navKey(keyCode: Int) {
        if (uiHost.place == VrUiHost.Place.Hidden) return
        val now = SystemClock.uptimeMillis()
        uiHost.key(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        uiHost.key(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    /** [amount] > 0 — вверх. В шлеме — там, куда смотрят, на экране — в центре. */
    private fun scrollMenu(amount: Float) {
        if (uiHost.place == VrUiHost.Place.Hidden) return
        val gaze = if (uiHost.place == VrUiHost.Place.Vr) renderer.gaze else null
        uiHost.scroll(gaze?.x, gaze?.y, amount)
    }

    // --- Пульт и геймпад: оси и колесо мыши ------------------------------------------------

    /** Последние значения осей. Геймпад присылает события только при движении — поэтому храним. */
    private val axisValues = HashMap<Int, Float>()

    /** Положение покоя на момент начала назначения: снимок до первого движения. */
    private var captureBaseline: Map<Int, Float>? = null

    /** Когда началось назначение (uptime, мс). */
    private var captureStartedAt = 0L

    /** Оси, нажатые как кнопки; только что назначенные не срабатывают, пока их не отпустят. */
    private val axisPressed = HashSet<AxisBinding>()
    private val axisSuppressed = HashSet<AxisBinding>()
    private var navX = 0
    private var navY = 0

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // Колесо мыши (пульты в режиме мыши): прокрутка меню в шлеме. На экране меню прокручивается само.
        if (event.actionMasked == MotionEvent.ACTION_SCROLL && uiHost.place == VrUiHost.Place.Vr) {
            scrollMenu(event.getAxisValue(MotionEvent.AXIS_VSCROLL))
            return true
        }
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.action != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event)
        }

        val ranges = event.device?.motionRanges.orEmpty().filter { it.isFromSource(InputDevice.SOURCE_JOYSTICK) }
        // Покой оси, которая ещё не двигалась: у курков (0..1) — 0, у стиков (−1..1) — середина.
        val rest = ranges.associate { it.axis to (axisValues[it.axis] ?: if (it.min >= -0.01f) it.min else 0f) }
        val values = ranges.associate { it.axis to event.getAxisValue(it.axis) }

        val strongest = values.maxByOrNull { abs(it.value - (rest[it.key] ?: 0f)) }
        if (strongest != null && abs(strongest.value - (rest[strongest.key] ?: 0f)) > 0.5f) {
            menu.lastKey = "${MotionEvent.axisToString(strongest.key).removePrefix("AXIS_")} " +
                "${"%+.1f".format(strongest.value)} · ${event.device?.name ?: "?"}"
        }

        val capturing = menu.capturingKey
        if (capturing != null) {
            // Курок или стик, которым только что нажали «+», ещё возвращается в покой — это не назначение:
            // первые мгновения просто запоминаем, где оси остановились.
            if (event.eventTime - captureStartedAt < CAPTURE_SETTLE_MS) {
                axisValues.putAll(values)
                captureBaseline = values
                return true
            }
            val baseline = captureBaseline ?: rest.also { captureBaseline = it }
            axisValues.putAll(values)
            val moved = values.maxByOrNull { abs(it.value - (baseline[it.key] ?: 0f)) } ?: return true
            val delta = moved.value - (baseline[moved.key] ?: 0f)
            if (abs(delta) > CAPTURE_THRESHOLD) {
                val restValue = baseline[moved.key] ?: 0f
                val binding = AxisBinding(
                    axis = moved.key,
                    inverted = delta < 0,
                    rest = if (abs(restValue) > 0.8f) restValue.toInt().coerceIn(-1, 1) else 0,
                )
                settings.keys.assignAxis(capturing, binding)
                // Ось сейчас отклонена: пусть сначала вернётся в покой, иначе действие сработало бы сразу.
                if (!capturing.isAxis) {
                    axisPressed += binding
                    axisSuppressed += binding
                }
                menu.capturingKey = null
                captureBaseline = null
            }
            return true
        }
        captureBaseline = null

        axisValues.putAll(values)
        updateAxes()
        return true
    }

    /** Отклонение для осевого действия: самая сильная из назначенных на него осей. */
    private fun axisValue(action: RemoteAction): Float {
        // Ось, назначенная и на меню, и на просмотр: работает то, что сейчас на экране.
        val menuOpen = uiHost.place != VrUiHost.Place.Hidden
        val active = when (action.axisContext) {
            AxisContext.Menu -> menuOpen
            AxisContext.Viewing -> !menuOpen
            AxisContext.Always -> true
        }
        if (!active) return 0f
        return axisBindingValue(action)
    }

    private fun axisBindingValue(action: RemoteAction): Float =
        settings.keys.axisBindings().filter { it.first == action }
            .map { (_, binding) -> binding.valueOf(axisValues[binding.axis] ?: binding.rest.toFloat()) }
            .maxByOrNull { abs(it) } ?: 0f

    private fun updateAxes() {
        // Оси на кнопочных действиях (например, курок на «Нажатии») — как кнопка, с запасом от дребезга.
        settings.keys.axisBindings().filter { !it.first.isAxis }.forEach { (action, binding) ->
            val value = binding.valueOf(axisValues[binding.axis] ?: binding.rest.toFloat())
            val pressed = binding in axisPressed
            if (!pressed && value > AXIS_PRESS) {
                axisPressed += binding
                if (binding !in axisSuppressed) axisButton(action, down = true)
            } else if (pressed && value < AXIS_RELEASE) {
                axisPressed -= binding
                if (!axisSuppressed.remove(binding)) axisButton(action, down = false)
            }
        }

        // Стрелки меню — по одному шагу на отклонение.
        val x = direction(axisValue(RemoteAction.AxisNavHorizontal))
        if (x != navX) {
            navX = x
            if (x != 0) navKey(if (x < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        val y = direction(axisValue(RemoteAction.AxisNavVertical))
        if (y != navY) {
            navY = y
            if (y != 0) navKey(if (y < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN)
        }
        // Прокрутка и перемотка — плавно, пока ось отклонена.
        handler.removeCallbacks(axisTick)
        if (listOf(RemoteAction.AxisScroll, RemoteAction.AxisSeek, RemoteAction.AxisZoom).any { abs(axisValue(it)) > DEAD_ZONE }) {
            handler.post(axisTick)
        }
    }

    private fun axisButton(action: RemoteAction, down: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (action == RemoteAction.Click) {
            if (down) triggerDown(now) else triggerUp(now, cancelled = false)
        } else if (down) {
            perform(action)
        }
    }

    private fun direction(value: Float) = when {
        value > NAV_THRESHOLD -> 1
        value < -NAV_THRESHOLD -> -1
        else -> 0
    }

    private val axisTick = object : Runnable {
        override fun run() {
            val scroll = axisValue(RemoteAction.AxisScroll)
            val seek = axisValue(RemoteAction.AxisSeek)
            val zoom = axisValue(RemoteAction.AxisZoom)
            // Вверх по стику (назначен «вверх») — приблизить, плавно, примерно вдвое за секунду.
            if (abs(zoom) > DEAD_ZONE) menuActions.setZoom(menu.zoom * (1f + zoom * AXIS_ZOOM_PER_TICK))
            // Стик вниз (плюс) прокручивает вниз — как колесо от себя.
            if (abs(scroll) > DEAD_ZONE) scrollMenu(-scroll * AXIS_SCROLL_PER_TICK)
            if (abs(seek) > DEAD_ZONE) menuActions.seekBy((seek * AXIS_SEEK_MS_PER_TICK).toLong())
            if (abs(scroll) > DEAD_ZONE || abs(seek) > DEAD_ZONE || abs(zoom) > DEAD_ZONE) handler.postDelayed(this, AXIS_TICK_MS)
        }
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
        if (playlist?.current?.isImage == true) {
            // ⏯ у фото: включает слайд-шоу, если оно выключено, иначе ставит на паузу.
            if (!settings.slideshow) {
                settings.slideshow = true
                slideshowPaused = false
            } else {
                slideshowPaused = !slideshowPaused
            }
            scheduleSlideshow()
            return
        }
        if (player.isPlaying) player.pause() else player.play()
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
        savePosition()
        player.pause()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::uiHost.isInitialized) uiHost.release()
        if (::player.isInitialized) player.release()
        io.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CONTROLS_AUTO_HIDE_MS = 4000L
        /** Своя панорама окружения: копия, уменьшенная до [EnvironmentLayer.CUSTOM_MAX_WIDTH]. */
        fun environmentFile(context: Context) = java.io.File(context.filesDir, "environment.jpg")

        private const val SEEK_STEP_MS = 10_000L
        private const val LONG_SEEK_STEP_MS = 30_000L
        private const val LONG_PRESS_MS = 500L
        private const val DRAG_INTERVAL_MS = 16L
        private const val HOVER_INTERVAL_MS = 50L
        private const val KEY_STEREO = "stereo"
        private const val SCROLL_STEP = 1f
        private const val DEAD_ZONE = 0.2f
        private const val NAV_THRESHOLD = 0.6f
        private const val CAPTURE_THRESHOLD = 0.6f
        private const val CAPTURE_SETTLE_MS = 400L
        private const val AXIS_PRESS = 0.6f
        private const val AXIS_RELEASE = 0.3f
        private const val AXIS_TICK_MS = 50L
        private const val AXIS_SCROLL_PER_TICK = 0.3f
        private const val AXIS_ZOOM_PER_TICK = 0.035f

        /** Сторона фото не больше: 8K-панорама в памяти — уже около 130 МБ. */
        private const val MAX_IMAGE_SIDE = 6144
        /** При полном отклонении — около 20 секунд видео за секунду. */
        private const val AXIS_SEEK_MS_PER_TICK = 1_000f

        /** Действия, которые повторяются, пока кнопку держат. */
        private val REPEATABLE = setOf(
            RemoteAction.SeekBack, RemoteAction.SeekForward, RemoteAction.SeekBackLong, RemoteAction.SeekForwardLong,
            RemoteAction.VolumeUp, RemoteAction.VolumeDown,
            RemoteAction.NavUp, RemoteAction.NavDown, RemoteAction.NavLeft, RemoteAction.NavRight,
            RemoteAction.ScrollUp, RemoteAction.ScrollDown,
        )
        private const val SAVE_INTERVAL_MS = 5_000L
        private const val DWELL_RADIUS_PX = 18f
    }
}

package com.manfis.astramatic.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import coil3.SingletonImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.manfis.astramatic.model.StereoLayout
import com.manfis.astramatic.settings.AppSettings
import com.manfis.astramatic.ui.describe
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ExoPlayer и показ фото в поверхность кадра рендерера. */
@OptIn(UnstableApi::class)
class PlayerMedia(
    private val activity: ComponentActivity,
    private val renderer: VrRenderer,
    private val settings: AppSettings,
    private val onEnded: () -> Unit,
    private val onError: (String) -> Unit,
) : MediaEngine {

    private val player: ExoPlayer = ExoPlayer.Builder(activity)
        // Умеет и обычные файлы, и smb:// с компьютера.
        .setMediaSourceFactory(DefaultMediaSourceFactory(AppDataSource.Factory(activity)))
        .build()
        .apply {
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.height > 0) {
                        renderer.videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) onEnded()
                }

                override fun onPlayerError(error: PlaybackException) = onError(error.describe())
            })
        }

    private var videoSurface: Surface? = null
    private var image: Bitmap? = null
    private var imageJob: Job? = null
    private var showingImage = false
    private var animation: ImageAnimation? = null
    private val dataSources = AppDataSource.Factory(activity)

    override val isPlaying get() = player.isPlaying
    override val positionMs get() = player.currentPosition
    override val durationMs get() = player.duration

    override fun play() {
        animation?.start()
        if (!showingImage) player.play()
    }

    override fun pause() {
        animation?.stop()
        player.pause()
    }

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    override fun setSpeed(speed: Float) = player.setPlaybackSpeed(speed)

    override fun setScrubbing(on: Boolean) {
        player.setSeekParameters(if (on) SeekParameters.CLOSEST_SYNC else SeekParameters.DEFAULT)
        player.volume = if (on) 0f else 1f
    }

    /** Поверхность кадра от рендерера; после пересоздания GL-контекста — новая. */
    fun onVideoSurface(surface: Surface) {
        videoSurface = surface
        if (showingImage) drawImage() else player.setVideoSurface(surface)
    }

    override fun openVideo(item: PlaylistItem, afterImage: Boolean) {
        stopAnimation()
        showingImage = false
        image = null
        // После фото поверхность занята холстом — декодеру нужна свежая.
        if (afterImage) renderer.newVideoSurface()?.let { videoSurface = it }
        videoSurface?.let(player::setVideoSurface)
        player.setMediaItem(MediaItem.fromUri(item.uri))
        player.prepare()
    }

    /**
     * Фото рисуется холстом в ту же поверхность, что и видео: дальше всё как у видео —
     * проекции, стерео, приближение, окружение.
     */
    override fun openImage(item: PlaylistItem, isCurrent: () -> Boolean, onShown: () -> Unit, onFailed: (String?) -> Unit) {
        showingImage = true
        player.stop()
        player.clearVideoSurface()
        image = null
        imageJob = activity.lifecycleScope.launch {
            val animated = if (AnimatedImages.mayAnimate(item.title)) decodeAnimated(item.uri) else null
            if (animated != null) {
                if (!isCurrent()) return@launch
                renderer.newVideoSurface()?.let { videoSurface = it }
                animation = ImageAnimation(animated).also { it.start() }
                onShown()
                return@launch
            }
            val request = ImageRequest.Builder(activity)
                .data(item.uri.toString())
                .size(MAX_IMAGE_SIDE)
                .allowHardware(false)
                .build()
            val result = SingletonImageLoader.get(activity).execute(request)
            val bitmap = (result as? SuccessResult)?.image?.toBitmap()
            if (bitmap == null) {
                onFailed((result as? ErrorResult)?.throwable?.describe())
                return@launch
            }
            if (!isCurrent()) return@launch
            image = bitmap
            renderer.newVideoSurface()?.let { videoSurface = it }
            drawImage()
            onShown()
        }
    }

    override fun cancelImage() {
        imageJob?.cancel()
        stopAnimation()
    }

    private fun stopAnimation() {
        animation?.stop()
        animation = null
    }

    /** Анимация из файла с любого источника, что и у видео. null — не анимация или прочитать не вышло: тогда как обычное фото. */
    private suspend fun decodeAnimated(uri: Uri): AnimatedImageDrawable? = withContext(Dispatchers.IO) {
        runCatching {
            val source = dataSources.createDataSource()
            val bytes = try {
                source.open(DataSpec(uri))
                DataSourceUtil.readToEnd(source)
            } finally {
                source.close()
            }
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) as? AnimatedImageDrawable
        }.getOrNull()
    }

    private fun drawImage() {
        val bitmap = image ?: return
        val surface = videoSurface ?: return
        renderer.setVideoBufferSize(bitmap.width, bitmap.height)
        renderer.videoAspect = bitmap.width.toFloat() / bitmap.height
        runCatching {
            val canvas = surface.lockHardwareCanvas()
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            surface.unlockCanvasAndPost(canvas)
        }
    }

    /**
     * Фото — в окружение: один глаз у стерео, не шире [EnvironmentLayer.CUSTOM_MAX_WIDTH].
     * Ожидается панорама 360° (2:1); другие пропорции растянутся по сфере.
     */
    override fun saveImageAsEnvironment(stereo: StereoLayout): String {
        val bitmap = image ?: return "Сначала откройте фото"
        val eye = when (stereo) {
            StereoLayout.SideBySide -> Bitmap.createBitmap(bitmap, 0, 0, bitmap.width / 2, bitmap.height)
            StereoLayout.TopBottom -> Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height / 2)
            StereoLayout.Mono -> bitmap
        }
        val width = eye.width.coerceAtMost(EnvironmentLayer.CUSTOM_MAX_WIDTH)
        val scaled = Bitmap.createScaledBitmap(eye, width, (eye.height * width.toFloat() / eye.width).toInt(), true)
        PlayerActivity.environmentFile(activity).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        settings.environment = Environment.Custom.name
        renderer.environmentChanged()
        val ratio = eye.width.toFloat() / eye.height
        return if (ratio in 1.8f..2.2f) "Готово: фото теперь вокруг вас." else "Готово, но это не панорама 2:1 — по краям будет растянуто."
    }

    fun release() {
        stopAnimation()
        player.release()
    }

    /**
     * Гифка и анимированный WebP: кадры рисуются программно в свой холст, а уже он — в поверхность кадра,
     * дальше всё как у фото. На аппаратном холсте анимацией управляет система, и без окна она не идёт.
     * Следующий кадр drawable выбирает сам по времени, поэтому достаточно перерисовывать его по таймеру.
     */
    private inner class ImageAnimation(private val drawable: AnimatedImageDrawable) {
        private val handler = Handler(Looper.getMainLooper())
        private val frame = Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1), drawable.intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888,
        )
        private val canvas = Canvas(frame)
        private val tick = object : Runnable {
            override fun run() {
                frame.eraseColor(Color.TRANSPARENT)
                drawable.draw(canvas)
                image = frame
                drawImage()
                handler.postDelayed(this, FRAME_INTERVAL_MS)
            }
        }

        init {
            drawable.setBounds(0, 0, frame.width, frame.height)
            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        }

        fun start() {
            drawable.start()
            handler.removeCallbacks(tick)
            tick.run()
        }

        fun stop() {
            drawable.stop()
            handler.removeCallbacks(tick)
        }
    }

    private companion object {
        /** Сторона фото не больше: 8K-панорама в памяти — уже около 130 МБ. */
        const val MAX_IMAGE_SIDE = 6144

        /** Гифки редко быстрее 30 кадров в секунду. */
        const val FRAME_INTERVAL_MS = 33L
    }
}

object AnimatedImages {
    private val extensions = setOf("gif", "webp", "avif", "heic", "heif")

    /** Может ли файл оказаться анимацией. Окончательно это видно только по содержимому. */
    fun mayAnimate(name: String) = name.substringAfterLast('.', "").lowercase() in extensions
}

package com.manfis.astramatic.player

import android.graphics.Bitmap
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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

    override val isPlaying get() = player.isPlaying
    override val positionMs get() = player.currentPosition
    override val durationMs get() = player.duration

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    override fun setSpeed(speed: Float) = player.setPlaybackSpeed(speed)

    /** Поверхность кадра от рендерера; после пересоздания GL-контекста — новая. */
    fun onVideoSurface(surface: Surface) {
        videoSurface = surface
        if (showingImage) drawImage() else player.setVideoSurface(surface)
    }

    override fun openVideo(item: PlaylistItem, afterImage: Boolean) {
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

    fun release() = player.release()

    private companion object {
        /** Сторона фото не больше: 8K-панорама в памяти — уже около 130 МБ. */
        const val MAX_IMAGE_SIDE = 6144
    }
}

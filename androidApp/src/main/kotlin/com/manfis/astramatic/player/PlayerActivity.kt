package com.manfis.astramatic.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.google.cardboard.sdk.CardboardView
import com.manfis.astramatic.headset.HeadsetActivity
import com.manfis.astramatic.model.Projection
import com.manfis.astramatic.model.StereoLayout
import com.manfis.astramatic.model.VideoFormat

class PlayerActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var renderer: VrRenderer
    private lateinit var cardboardView: CardboardView

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: return finish()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()

        renderer = VrRenderer { surface -> runOnUiThread { player.setVideoSurface(surface) } }
        renderer.format = formatFrom(intent)

        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) {
                    renderer.videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "Ошибка воспроизведения: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
            }
        })
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true

        // Как в примере paperscope: GL-вид SDK умеет выполнить код завершения на GL-потоке при отсоединении.
        CardboardView.setUseCardboardGlSurfaceView(true)
        cardboardView = CardboardView(this).apply {
            setRenderer(renderer)
            setOnViewDetachedRunnable {
                renderer.onRendererShutdown()
                cardboardView.onDestroy()
            }
            setStereoRenderMode(true)
            setOnBackButtonClick { finish() }
            // Шестерёнка: ручная настройка шлема. CardboardView перечитывает параметры в onResume.
            setOnSettingsButtonClick {
                startActivity(Intent(this@PlayerActivity, HeadsetActivity::class.java))
            }
        }
        setContentView(cardboardView)

        // Кнопка Cardboard — это касание экрана. GL-вид касания не обрабатывает, они доходят до CardboardView.
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                togglePlayback()
                return true
            }

            override fun onLongPress(e: MotionEvent) = renderer.recenter()
        })
        cardboardView.setOnTouchListener { _, event -> gestures.onTouchEvent(event) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
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
            KeyEvent.KEYCODE_BUTTON_L1 -> player.seekBack()
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_BUTTON_R1 -> player.seekForward()
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_BUTTON_Y -> renderer.recenter()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
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
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    companion object {
        private const val SEEK_STEP_MS = 10_000L
        private const val EXTRA_PROJECTION = "projection"
        private const val EXTRA_STEREO = "stereo"

        fun intent(context: Context, uri: Uri, format: VideoFormat): Intent =
            Intent(context, PlayerActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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

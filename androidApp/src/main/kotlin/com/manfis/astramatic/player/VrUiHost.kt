package com.manfis.astramatic.player

import android.app.Presentation
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Один и тот же Compose-интерфейс показывается в двух местах:
 * - в VR — на виртуальном дисплее, который рисует в текстуру панели рендерера;
 * - в 2D — обычным слоем поверх видео.
 * Композиция переживает переезд между ними, поэтому открытая папка и вкладка не сбрасываются.
 */
class VrUiHost(
    private val activity: ComponentActivity,
    private val screenOverlay: FrameLayout,
    content: @Composable () -> Unit,
) : PanelHost {
    /** Масштаб берётся из контекста активности (экран телефона), а на панели свой — подменяем его. */
    private var inVr by mutableStateOf(false)

    private val composeView = ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(activity))
        // На виртуальном дисплее нет окна активности, владельцев приходится задать явно.
        setViewTreeLifecycleOwner(activity)
        setViewTreeSavedStateRegistryOwner(activity)
        setViewTreeViewModelStoreOwner(activity)
        setViewTreeOnBackPressedDispatcherOwner(activity)
        setContent {
            val density = if (inVr) PANEL_DENSITY else LocalDensity.current
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides activity,
                LocalOnBackPressedDispatcherOwner provides activity,
                LocalDensity provides density,
            ) { content() }
        }
    }

    private var surface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null

    private val pointer = PanelPointer { presentation?.window?.decorView }

    override var place = PanelPlace.Hidden
        private set

    /** Поверхность панели от рендерера; приходит заново после пересоздания GL-контекста. */
    fun setSurface(surface: Surface) {
        this.surface = surface
        val display = virtualDisplay
        if (display == null) {
            virtualDisplay = activity.getSystemService(DisplayManager::class.java).createVirtualDisplay(
                "AstraMatic VR UI",
                PANEL_WIDTH_PX,
                PANEL_HEIGHT_PX,
                PANEL_DENSITY_DPI,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
        } else {
            display.surface = surface
        }
        if (place == PanelPlace.Vr) showInVr()
    }

    override fun showInVr() {
        place = PanelPlace.Vr
        inVr = true
        val display = virtualDisplay?.display ?: return
        if (presentation?.display?.displayId == display.displayId && presentation?.isShowing == true) return
        detach()
        presentation = Presentation(activity, display).apply {
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setFormat(PixelFormat.TRANSLUCENT)
            // Системная клавиатура в шлеме не видна и всплыла бы на экране телефона: печатает клавиатура шлема.
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setContentView(composeView)
            show()
        }
    }

    override fun showOnScreen() {
        place = PanelPlace.Screen
        inVr = false
        if (composeView.parent === screenOverlay) return
        detach()
        screenOverlay.addView(composeView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        screenOverlay.visibility = android.view.View.VISIBLE
    }

    override fun hide() {
        place = PanelPlace.Hidden
        detach()
    }

    private fun detach() {
        (composeView.parent as? ViewGroup)?.removeView(composeView)
        screenOverlay.visibility = android.view.View.GONE
        presentation?.dismiss()
        presentation = null
    }

    override fun touch(phase: TouchPhase, x: Float, y: Float) = pointer.touch(phase, x, y)

    /** Как наведение мыши. */
    override fun hover(x: Float?, y: Float?) = pointer.hover(x, y)

    /** Где сейчас живёт интерфейс: окно виртуального дисплея или слой на экране. */
    private fun root(): android.view.View? = when (place) {
        PanelPlace.Vr -> presentation?.window?.decorView
        PanelPlace.Screen -> composeView
        PanelPlace.Hidden -> null
    }

    /** Кнопки пульта: стрелки и OK двигают фокус по интерфейсу. */
    fun key(event: KeyEvent): Boolean = root()?.dispatchKeyEvent(event) ?: false

    override fun pressKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        key(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        key(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    override fun scroll(x: Float?, y: Float?, amount: Float) {
        val root = root() ?: return
        val now = SystemClock.uptimeMillis()
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x ?: root.width / 2f
            this.y = y ?: root.height / 2f
            setAxisValue(MotionEvent.AXIS_VSCROLL, amount)
        }
        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(properties), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        root.dispatchGenericMotionEvent(event)
        event.recycle()
    }

    private companion object {
        /** Плотность виртуального дисплея; размер шрифта не увеличиваем — панель и так крупная. */
        val PANEL_DENSITY = Density(PANEL_DENSITY_DPI / 160f, fontScale = 1f)
    }

    fun release() {
        detach()
        virtualDisplay?.release()
        virtualDisplay = null
    }
}

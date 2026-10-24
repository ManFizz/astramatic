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
) {
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

    private var touchDownTime = 0L
    private var hovering = false

    enum class Place { Hidden, Vr, Screen }

    var place = Place.Hidden
        private set

    /** Поверхность панели от рендерера; приходит заново после пересоздания GL-контекста. */
    fun setSurface(surface: Surface) {
        this.surface = surface
        val display = virtualDisplay
        if (display == null) {
            virtualDisplay = activity.getSystemService(DisplayManager::class.java).createVirtualDisplay(
                "AstraMatic VR UI",
                VrRenderer.PANEL_WIDTH_PX,
                VrRenderer.PANEL_HEIGHT_PX,
                VrRenderer.PANEL_DENSITY_DPI,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
        } else {
            display.surface = surface
        }
        if (place == Place.Vr) showInVr()
    }

    fun showInVr() {
        place = Place.Vr
        inVr = true
        val display = virtualDisplay?.display ?: return
        if (presentation?.display?.displayId == display.displayId && presentation?.isShowing == true) return
        detach()
        presentation = Presentation(activity, display).apply {
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setFormat(PixelFormat.TRANSLUCENT)
            setContentView(composeView)
            show()
        }
    }

    fun showOnScreen() {
        place = Place.Screen
        inVr = false
        if (composeView.parent === screenOverlay) return
        detach()
        screenOverlay.addView(composeView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        screenOverlay.visibility = android.view.View.VISIBLE
    }

    fun hide() {
        place = Place.Hidden
        detach()
    }

    private fun detach() {
        (composeView.parent as? ViewGroup)?.removeView(composeView)
        screenOverlay.visibility = android.view.View.GONE
        presentation?.dismiss()
        presentation = null
    }

    /** Нажатие, перетаскивание и отпускание по точке взгляда (x, y в пикселях панели). */
    fun touch(action: Int, x: Float, y: Float) {
        val root = presentation?.window?.decorView ?: return
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) touchDownTime = now
        val event = MotionEvent.obtain(touchDownTime, now, action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        root.dispatchTouchEvent(event)
        event.recycle()
    }

    /** Подсветка элемента под взглядом, как наведение мыши. null — взгляд ушёл с панели. */
    fun hover(x: Float?, y: Float?) {
        val root = presentation?.window?.decorView ?: return
        val action = when {
            x == null || y == null -> if (hovering) MotionEvent.ACTION_HOVER_EXIT else return
            !hovering -> MotionEvent.ACTION_HOVER_ENTER
            else -> MotionEvent.ACTION_HOVER_MOVE
        }
        hovering = action != MotionEvent.ACTION_HOVER_EXIT
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x ?: 0f, y ?: 0f, 0).apply {
            source = InputDevice.SOURCE_MOUSE
        }
        root.dispatchGenericMotionEvent(event)
        event.recycle()
    }

    /** Кнопки пульта: стрелки и OK двигают фокус по интерфейсу на панели. */
    fun key(event: KeyEvent): Boolean = presentation?.window?.decorView?.dispatchKeyEvent(event) ?: false

    private companion object {
        /** Плотность виртуального дисплея; размер шрифта не увеличиваем — панель и так крупная. */
        val PANEL_DENSITY = Density(VrRenderer.PANEL_DENSITY_DPI / 160f, fontScale = 1f)
    }

    fun release() {
        detach()
        virtualDisplay?.release()
        virtualDisplay = null
    }
}

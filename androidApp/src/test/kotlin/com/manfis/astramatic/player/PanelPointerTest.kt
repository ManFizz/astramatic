package com.manfis.astramatic.player

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Настоящая кнопка Compose под взглядом, VR-03: наведение не должно мешать нажатию. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PanelPointerTest {

    @get:Rule
    val compose = createComposeRule()

    private var clicks by mutableIntStateOf(0)
    private lateinit var pointer: PanelPointer

    @Before
    fun setUp() {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                Button(onClick = { clicks++ }, modifier = Modifier.size(200.dp, 100.dp)) { Text("Кнопка") }
            }
        }
        compose.waitForIdle()
        val root: View = (compose.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        pointer = PanelPointer { root }
    }

    private fun onUi(block: () -> Unit) {
        compose.runOnIdle(block)
        compose.waitForIdle()
    }

    private fun hover() = onUi { pointer.hover(X, Y) }

    private fun press() {
        onUi { pointer.touch(TouchPhase.Down, X, Y) }
        onUi { pointer.touch(TouchPhase.Move, X, Y) }
        onUi { pointer.touch(TouchPhase.Up, X, Y) }
    }

    @Test
    fun `press works while the gaze rests on the button`() {
        repeat(3) { hover() }

        press()

        assertEquals(1, clicks)
    }

    @Test
    fun `next press works too, after hovering starts again`() {
        repeat(3) {
            hover()
            hover()
            press()
        }

        assertEquals(3, clicks)
    }

    @Test
    fun `press works without any hover before it`() {
        press()

        assertEquals(1, clicks)
    }

    @Test
    fun `cancelled press does not click`() {
        hover()

        onUi { pointer.touch(TouchPhase.Down, X, Y) }
        onUi { pointer.touch(TouchPhase.Cancel, X, Y) }

        assertEquals(0, clicks)
    }

    private companion object {
        const val X = 50f
        const val Y = 50f
    }
}

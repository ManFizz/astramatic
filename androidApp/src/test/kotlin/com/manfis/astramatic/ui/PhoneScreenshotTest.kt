package com.manfis.astramatic.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Главная на обычном экране телефона, вне шлема. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-port-xxhdpi")
class PhoneScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun home() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    CompositionLocalProvider(LocalVersionText provides "v1.0 · тест") { LibraryApp(onPlay = {}) }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/phone_home.png")
    }
}

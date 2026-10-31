package com.ljyh.mei.thor.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.thor.model.ThorAppearance
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.thor.model.ThorThemePack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** One composition exercises motion re-enabling; no account, audio service, or network. */
@RunWith(AndroidJUnit4::class)
class ThorThemeMotionInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun reEnablingMotionCompletesTheThemeTransitionWithAVirtualFrameClock() {
        val preferences = mutableStateOf(ThorPreferences(
            theme = ThorThemePack.Ceramic,
            appearance = ThorAppearance.Night,
            reduceMotion = true,
        ))
        lateinit var visible: ThorThemeTokens
        compose.setContent {
            ThorTheme(preferences.value) {
                val theme = LocalThorTheme.current
                SideEffect { visible = theme }
                Box(Modifier.size(120.dp).background(theme.background).testTag("theme-motion")) {
                    Text("主题切换", color = theme.text)
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(ThorThemePack.Ceramic, visible.pack)
            assertTrue(visible.isDark)
            assertTrue(visible.reduceMotion)
            preferences.value = preferences.value.copy(
                theme = ThorThemePack.Glass,
                appearance = ThorAppearance.Day,
                reduceMotion = false,
            )
        }

        // Auto-advanced Compose frames must eventually finish a transition begun with nanoTime.
        // Mixing the virtual frame timestamp with that monotonic origin never reaches idle.
        compose.waitForIdle()
        val expected = thorThemeTokens(ThorThemePack.Glass, isDark = false)
        compose.runOnIdle {
            assertEquals(ThorThemePack.Glass, visible.pack)
            assertFalse(visible.isDark)
            assertFalse(visible.reduceMotion)
            assertEquals(expected.background, visible.background)
        }
        val pixels = compose.onNodeWithTag("theme-motion").assertIsDisplayed().captureToImage().toPixelMap()
        assertEquals(expected.background.toArgb(), pixels[pixels.width / 2, pixels.height - 2].toArgb())
    }
}

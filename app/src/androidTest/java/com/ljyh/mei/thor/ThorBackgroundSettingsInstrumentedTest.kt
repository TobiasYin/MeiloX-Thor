package com.ljyh.mei.thor

import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.thor.model.ThorAppearance
import com.ljyh.mei.thor.model.ThorBackgroundIntensity
import com.ljyh.mei.thor.model.ThorBackgroundMode
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.thor.model.ThorThemePack
import com.ljyh.mei.thor.state.ThorOverlay
import com.ljyh.mei.thor.state.ThorPage
import com.ljyh.mei.thor.state.ThorTab
import com.ljyh.mei.thor.test.ThorLiveEnvironment
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Activity/Presentation UI; only three non-account visual preferences are temporarily edited. */
@RunWith(AndroidJUnit4::class)
class ThorBackgroundSettingsInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: ThorActivity
    private lateinit var model: ThorShellViewModel

    @Test(timeout = 90_000)
    fun realAppearanceButtonsPersistBackgroundChoicesAndCleanRemovesIntensityFromControllerScope() {
        ThorLiveEnvironment.requireRequestedDevice(instrumentation.targetContext)
        val context = instrumentation.targetContext
        val original = AtomicReference<ThorPreferences?>(null)
        var scenario: ActivityScenario<ThorActivity>? = null
        try {
            scenario = ActivityScenario.launch(Intent(context, ThorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            scenario.onActivity { owner ->
                activity = owner
                model = ViewModelProvider(owner)[ThorShellViewModel::class.java]
            }
            assertTrue("A secondary presentation display is required",
                context.getSystemService(DisplayManager::class.java).displays.any {
                    it.displayId != activity.display?.displayId && it.flags and Display.FLAG_PRESENTATION != 0
                })
            // Window inspection does not wait for Compose idle while the original artwork may
            // still be animating. Read the persisted value inside the production edit itself.
            compose.waitUntil(10_000) { lowerWindowIsAttached() }
            instrumentation.runOnMainSync {
                model.updatePreferences {
                    original.compareAndSet(null, it)
                    it.copy(reduceMotion = true)
                }
            }
            compose.waitUntil(10_000) { original.get() != null && model.preferences.value.reduceMotion }
            compose.waitForIdle()

            touch("我的", scroll = false)
            awaitPage(ThorPage.Root(ThorTab.My))
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("主题与外观") and hasClickAction())
            touch("主题与外观")
            awaitPage(ThorPage.Appearance)
            assertNoRootSidebar()
            compose.onNodeWithTag(BACKDROP_TAG).assertExists()

            touch("封面流光")
            awaitBackground(ThorBackgroundMode.ArtworkFlow)
            for (intensity in ThorBackgroundIntensity.entries) {
                touch(intensity.title)
                awaitBackground(ThorBackgroundMode.ArtworkFlow, intensity)
            }
            touch("柔焦渐层")
            awaitBackground(ThorBackgroundMode.SoftGradient, ThorBackgroundIntensity.High)
            for (intensity in ThorBackgroundIntensity.entries) button(intensity.title).assertIsEnabled()

            touch("纯净")
            awaitBackground(ThorBackgroundMode.Clean, ThorBackgroundIntensity.High)
            for (intensity in ThorBackgroundIntensity.entries) button(intensity.title).assertIsNotEnabled()
            assertCleanControllerScope()
            assertEquals(ThorBackgroundIntensity.High, model.preferences.value.backgroundIntensity)

            touch("封面流光")
            awaitBackground(ThorBackgroundMode.ArtworkFlow, ThorBackgroundIntensity.High)
            for (intensity in ThorBackgroundIntensity.entries) button(intensity.title).assertIsEnabled()

            press(KeyEvent.KEYCODE_BUTTON_START)
            compose.waitUntil(5_000) { model.navigation.value.overlay == ThorOverlay.Player }
            compose.waitForIdle()
            press(KeyEvent.KEYCODE_BUTTON_START)
            awaitPage(ThorPage.Appearance)
            assertNoRootSidebar()
            compose.onNodeWithTag(BACKDROP_TAG).assertExists()
            awaitBackground(ThorBackgroundMode.ArtworkFlow, ThorBackgroundIntensity.High)

            press(KeyEvent.KEYCODE_BUTTON_B)
            awaitPage(ThorPage.Root(ThorTab.My))
            compose.onNodeWithTag(BACKDROP_TAG).assertDoesNotExist()
        } finally {
            try {
                original.get()?.let { saved ->
                    if (::model.isInitialized && !activity.isDestroyed) {
                        val restorationAccepted = AtomicBoolean(false)
                        instrumentation.runOnMainSync {
                            model.updatePreferences { current ->
                                restorationAccepted.set(true)
                                current.copy(
                                    reduceMotion = saved.reduceMotion,
                                    backgroundMode = saved.backgroundMode,
                                    backgroundIntensity = saved.backgroundIntensity,
                                )
                            }
                        }
                        // Other visual preferences, audio settings and account data are untouched.
                        // Avoid waitForIdle after restoring a possibly animated original mode.
                        compose.waitUntil(5_000) {
                            val current = model.preferences.value
                            restorationAccepted.get() && current.reduceMotion == saved.reduceMotion &&
                                current.backgroundMode == saved.backgroundMode && current.backgroundIntensity == saved.backgroundIntensity
                        }
                    }
                }
            } finally { scenario?.close() }
        }
    }

    private fun button(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun touch(label: String, scroll: Boolean = true) {
        val node = button(label).assertIsEnabled()
        if (scroll) node.performScrollTo()
        node.performTouchInput { click() }
        compose.waitForIdle()
    }

    private fun awaitPage(page: ThorPage) {
        compose.waitUntil(10_000) { model.navigation.value.page == page && model.navigation.value.overlay == null }
        compose.waitForIdle()
    }

    private fun awaitBackground(mode: ThorBackgroundMode, intensity: ThorBackgroundIntensity? = null) {
        compose.waitUntil(5_000) {
            model.preferences.value.let { it.backgroundMode == mode && (intensity == null || it.backgroundIntensity == intensity) }
        }
        compose.waitForIdle()
    }

    private fun assertCleanControllerScope() {
        val allowed = ThorThemePack.entries.map { "theme:${it.name}" } +
            ThorAppearance.entries.map { "appearance:${it.name}" } +
            ThorBackgroundMode.entries.map { "background:mode:${it.name}" } +
            listOf("appearance:motion", "appearance:transparency")
        for (code in listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT)) {
            press(code)
            compose.waitForIdle()
            var focused: String? = null
            instrumentation.runOnMainSync { focused = model.rememberedFocus(model.navigation.value.sceneKey) }
            assertNotNull("An appearance option must retain the controller cursor", focused)
            assertTrue("Clean must keep the cursor in its enabled option scope: $focused", focused in allowed)
        }
    }

    private fun press(code: Int) {
        instrumentation.runOnMainSync {
            val now = SystemClock.uptimeMillis()
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val event = KeyEvent(now, now, action, code, 0, 0, 99, 0, 0, InputDevice.SOURCE_GAMEPAD)
                assertTrue("Thor's real shared controller router must handle key $code", activity.dispatchKeyEvent(event))
            }
        }
    }

    private fun assertNoRootSidebar() {
        for (tab in ThorTab.entries) compose.onAllNodes(hasText(tab.title) and hasClickAction()).assertCountEquals(0)
    }

    private fun lowerWindowIsAttached(): Boolean {
        var attached = false
        instrumentation.runOnMainSync {
            val upperDisplayId = activity.window.decorView.display?.displayId
            attached = WindowInspector.getGlobalWindowViews().any {
                it.isAttachedToWindow && it.isShown && it.display?.displayId != upperDisplayId && containsCompose(it)
            }
        }
        return attached
    }

    private fun containsCompose(view: View): Boolean = view.javaClass.name == "androidx.compose.ui.platform.ComposeView" ||
        view is ViewGroup && (0 until view.childCount).any { containsCompose(view.getChildAt(it)) }

    private companion object { const val BACKDROP_TAG = "thor-playback-backdrop" }
}

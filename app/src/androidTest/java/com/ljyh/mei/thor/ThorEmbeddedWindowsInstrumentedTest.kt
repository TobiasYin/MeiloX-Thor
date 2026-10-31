package com.ljyh.mei.thor

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.MainActivity
import com.ljyh.mei.data.network.CaptchaUiHost
import com.ljyh.mei.data.network.NeteaseCaptchaDialog
import com.ljyh.mei.data.network.createNeteaseCaptchaDialog
import com.ljyh.mei.thor.account.ThorAccountRoute
import com.ljyh.mei.thor.state.ThorOverlay
import com.ljyh.mei.thor.state.ThorPage
import com.ljyh.mei.thor.state.ThorTab
import com.ljyh.mei.ui.screen.account.OfficialNeteasePage
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real Hilt Activity and its secondary-display Presentation. A dedicated Thor/Duo
 * emulator is required; ordinary single-display CI skips these window-level checks.
 * No credentials, verification request, auth submit, audio value, or account state is changed.
 */
@RunWith(AndroidJUnit4::class)
class ThorEmbeddedWindowsInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: ThorActivity
    private lateinit var model: ThorShellViewModel

    @Test(timeout = 60_000)
    fun audioEqualizerAndQuickPlayerKeepBothOriginalWindowsAlive() = withThorWindows { baseline, lifecycle ->
        openMy()
        assertStable(baseline, lifecycle)
        click("播放设置")
        awaitPage(ThorPage.AudioSettings)
        click("十段均衡器")
        awaitPage(ThorPage.Equalizer)
        assertNoRootSidebar()
        assertStable(baseline, lifecycle)

        press(KeyEvent.KEYCODE_BUTTON_START)
        compose.waitUntil(5_000) { model.navigation.value.overlay == ThorOverlay.Player }
        compose.waitForIdle()
        assertStable(baseline, lifecycle)
        press(KeyEvent.KEYCODE_BUTTON_START)
        compose.waitUntil(5_000) { model.navigation.value.overlay == null && model.navigation.value.page == ThorPage.Equalizer }
        compose.waitForIdle()
        assertStable(baseline, lifecycle)

        press(KeyEvent.KEYCODE_BUTTON_B)
        awaitPage(ThorPage.AudioSettings)
        press(KeyEvent.KEYCODE_BUTTON_B)
        awaitPage(ThorPage.Root(ThorTab.My))
        assertStable(baseline, lifecycle)
    }

    @Test(timeout = 90_000)
    fun nativeAccountFormsAndEmbeddedWebCreationNeverLaunchALegacyActivity() = withThorWindows { baseline, lifecycle ->
        openMy()
        // The native My account row has a stable public subtitle in either session state.
        compose.onNode((hasText("连接网易云音乐") or hasText("网易云音乐 ·", substring = true)) and hasClickAction())
            .performClick()
        compose.waitUntil(10_000) {
            (model.navigation.value.page as? ThorPage.Account)?.route in setOf(ThorAccountRoute.Login, ThorAccountRoute.Home)
        }
        compose.waitForIdle()
        if ((model.navigation.value.page as? ThorPage.Account)?.route == ThorAccountRoute.Home) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("切换账号"))
            click("切换账号")
        }
        awaitPage(ThorPage.Account(ThorAccountRoute.Login))
        assertStable(baseline, lifecycle)

        for ((label, route) in listOf("手机号登录" to ThorAccountRoute.MobileLogin, "Web Cookie 登录" to ThorAccountRoute.CookieLogin)) {
            click(label)
            awaitPage(ThorPage.Account(route))
            assertNoRootSidebar()
            assertStable(baseline, lifecycle)
            // First B may leave an automatically focused native field; the next leaves the form.
            backUntil(ThorPage.Account(ThorAccountRoute.Login), maximumPresses = 3)
            assertStable(baseline, lifecycle)
        }

        click("网页登录")
        awaitPage(ThorPage.Account(ThorAccountRoute.WebLogin))
        awaitWebView()
        assertStable(baseline, lifecycle)
        // Official login can have its own redirect history. B consumes that history first.
        backUntil(ThorPage.Account(ThorAccountRoute.Login), maximumPresses = 8)
        assertStable(baseline, lifecycle)

        // The official feature requires authentication to load its account data. Creating and
        // leaving its real native wrapper is still safe when this emulator is signed out.
        val beforeOfficial = model.navigation.value.page
        instrumentation.runOnMainSync {
            model.push(ThorPage.Account(ThorAccountRoute.Web(OfficialNeteasePage.ThirdPartyBinding)))
        }
        awaitPage(ThorPage.Account(ThorAccountRoute.Web(OfficialNeteasePage.ThirdPartyBinding)))
        awaitWebView()
        assertStable(baseline, lifecycle)
        backUntil(beforeOfficial, maximumPresses = 8)
        assertStable(baseline, lifecycle)
    }

    @Test(timeout = 45_000)
    fun syntheticCaptchaUsesTheLowerParentWindowAndRealAAndBInput() = withThorWindows { baseline, lifecycle ->
        instrumentation.runOnMainSync { model.push(ThorPage.Account(ThorAccountRoute.MobileLogin)) }
        awaitPage(ThorPage.Account(ThorAccountRoute.MobileLogin))
        val origin = model.navigation.value
        val fixture = showSyntheticCaptcha(baseline)
        try {
            awaitSyntheticCaptcha(fixture)
            assertCaptchaWindow(baseline, fixture)
            assertStable(baseline, lifecycle)

            dialogPress(fixture.dialog, KeyEvent.KEYCODE_BUTTON_A)
            compose.waitUntil(5_000) { fixture.confirmations.get() == 1 }
            assertEquals("A must activate the focused real WebView button exactly once", 1, fixture.confirmations.get())
            assertEquals(0, fixture.cancellations.get())
            assertEquals(origin, model.navigation.value)
            assertStable(baseline, lifecycle)

            dialogPress(fixture.dialog, KeyEvent.KEYCODE_BUTTON_B)
            compose.waitUntil(5_000) { !fixture.dialog.isShowing }
            assertEquals("B must cancel the modal exactly once", 1, fixture.cancellations.get())
            assertEquals(0, fixture.departures.get())
            assertEquals("Cancelling a captcha must leave its native login page in place", origin, model.navigation.value)
            assertStable(baseline, lifecycle)
        } finally { destroySyntheticCaptcha(fixture) }
    }

    @Test(timeout = 45_000)
    fun startInsideTheAttachedCaptchaReturnsToTheGlobalPlayerAndCancelsItsRequest() = withThorWindows { baseline, lifecycle ->
        instrumentation.runOnMainSync { model.push(ThorPage.Account(ThorAccountRoute.MobileLogin)) }
        awaitPage(ThorPage.Account(ThorAccountRoute.MobileLogin))
        val fixture = showSyntheticCaptcha(baseline)
        try {
            awaitSyntheticCaptcha(fixture)
            assertCaptchaWindow(baseline, fixture)
            dialogPress(fixture.dialog, KeyEvent.KEYCODE_BUTTON_START)
            compose.waitUntil(5_000) { !fixture.dialog.isShowing && model.navigation.value.overlay == ThorOverlay.Player }
            assertEquals("Start must report global departure once, not a local B cancellation", 1, fixture.departures.get())
            assertEquals(0, fixture.cancellations.get())
            assertEquals(0, fixture.confirmations.get())
            assertStable(baseline, lifecycle)

            press(KeyEvent.KEYCODE_BUTTON_START)
            awaitPage(ThorPage.Account(ThorAccountRoute.MobileLogin))
            assertStable(baseline, lifecycle)
        } finally { destroySyntheticCaptcha(fixture) }
    }

    private fun showSyntheticCaptcha(baseline: WindowSnapshot): SyntheticCaptcha {
        lateinit var fixture: SyntheticCaptcha
        instrumentation.runOnMainSync {
            val lower = checkNotNull(baseline.lower)
            val parent = lower.composeViews.first { it.javaClass.name == "androidx.compose.ui.platform.ComposeView" }
            val context = parent.context
            val confirmations = AtomicInteger(0)
            val cancellations = AtomicInteger(0)
            val departures = AtomicInteger(0)
            val ready = AtomicBoolean(false)
            val closed = AtomicBoolean(false)
            val web = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                addJavascriptInterface(ThorSyntheticCaptchaBridge(confirmations), "ThorCaptchaFixture")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (!closed.get()) view.evaluateJavascript(
                            "document.getElementById('qa-confirm').focus(); document.activeElement.id",
                        ) { value -> if (!closed.get()) ready.set(value == "\"qa-confirm\"") }
                    }
                }
            }
            val host = CaptchaUiHost(context, checkNotNull(parent.windowToken)) { event ->
                // This is the same production host boundary: Start belongs to Thor's shared
                // router, while the captcha controller owns its local A/B/directional input.
                if (event.keyCode == KeyEvent.KEYCODE_BUTTON_START) {
                    activity.dispatchKeyEvent(event)
                    true
                } else false
            }
            lateinit var dialog: NeteaseCaptchaDialog
            dialog = createNeteaseCaptchaDialog(activity, host, web,
                onCancel = { cancellations.incrementAndGet(); dialog.dismiss() },
                onGlobalDeparture = { departures.incrementAndGet(); dialog.dismiss() })
            fixture = SyntheticCaptcha(dialog, web, confirmations, cancellations, departures, ready, closed)
            dialog.show()
            web.requestFocus()
            web.loadDataWithBaseURL("https://captcha-fixture.invalid/", """
                <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:24px;background:#fff;color:#111;font:20px sans-serif">
                <p>Synthetic captcha input fixture</p>
                <button id="qa-confirm" autofocus style="font-size:20px;padding:16px"
                    onclick="ThorCaptchaFixture.confirm()">Confirm fixture</button>
                </body></html>
            """.trimIndent(), "text/html", "UTF-8", null)
        }
        return fixture
    }

    private fun awaitSyntheticCaptcha(fixture: SyntheticCaptcha) {
        compose.waitUntil(10_000) {
            var laidOut = false
            instrumentation.runOnMainSync { laidOut = fixture.dialog.isShowing && fixture.web.width > 0 && fixture.web.height > 0 }
            fixture.ready.get() && laidOut
        }
    }

    private fun assertCaptchaWindow(baseline: WindowSnapshot, fixture: SyntheticCaptcha) {
        instrumentation.runOnMainSync {
            val lower = checkNotNull(baseline.lower)
            val window = checkNotNull(fixture.dialog.window)
            assertEquals("The real captcha Dialog must be displayed on the original lower display",
                lower.displayId, window.decorView.display.displayId)
            assertEquals(lower.displayId, fixture.web.display.displayId)
            assertSame("The captcha must attach to the original lower parent token", lower.token, window.attributes.token)
            assertNotSame("An attached captcha is a child window, not a replacement base window",
                lower.token, window.decorView.windowToken)
            assertTrue("The actual WebView must fit the lower screen's measured width", fixture.web.width <= lower.root.width)
            assertTrue(fixture.web.height > 0)
        }
    }

    private fun dialogPress(dialog: NeteaseCaptchaDialog, code: Int) {
        instrumentation.runOnMainSync {
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, 99, 0, 0, InputDevice.SOURCE_GAMEPAD)
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0, 99, 0, 0, InputDevice.SOURCE_GAMEPAD)
            assertTrue("The real modal controller must consume the pressed button", dialog.dispatchKeyEvent(down))
            // Once the modal leaves, Android delivers the physical release to the base window.
            // Forward it there too so Start's held state cannot leak into the next interaction.
            if (dialog.isShowing) assertTrue(dialog.dispatchKeyEvent(up)) else activity.dispatchKeyEvent(up)
        }
    }

    private fun destroySyntheticCaptcha(fixture: SyntheticCaptcha) {
        instrumentation.runOnMainSync {
            fixture.closed.set(true)
            fixture.dialog.dismiss()
            fixture.web.stopLoading()
            fixture.web.removeJavascriptInterface("ThorCaptchaFixture")
            fixture.web.webViewClient = WebViewClient()
            fixture.web.destroy()
        }
    }

    private data class SyntheticCaptcha(
        val dialog: NeteaseCaptchaDialog,
        val web: WebView,
        val confirmations: AtomicInteger,
        val cancellations: AtomicInteger,
        val departures: AtomicInteger,
        val ready: AtomicBoolean,
        val closed: AtomicBoolean,
    )

    private fun withThorWindows(check: (WindowSnapshot, LifecycleRecorder) -> Unit) {
        val context = instrumentation.targetContext
        val application = context.applicationContext as Application
        val lifecycle = LifecycleRecorder()
        application.registerActivityLifecycleCallbacks(lifecycle)
        val intent = Intent(context, ThorActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        var scenario: ActivityScenario<ThorActivity>? = null
        val originalReduceMotion = AtomicReference<Boolean?>(null)
        try {
            scenario = ActivityScenario.launch(intent)
            scenario.onActivity { owner ->
                activity = owner
                model = ViewModelProvider(owner)[ThorShellViewModel::class.java]
            }
            val hasSecondary = context.getSystemService(DisplayManager::class.java).displays.any {
                it.displayId != activity.display?.displayId && it.flags and android.view.Display.FLAG_PRESENTATION != 0
            }
            assumeTrue("This class requires a secondary presentation display", hasSecondary)
            // Stop the real artwork's perpetual decorative frames before Compose idleness is
            // used. Restore this one general preference in finally; no audio/account keys change.
            compose.waitUntil(10_000) { snapshotOrNull()?.lower != null }
            instrumentation.runOnMainSync {
                model.updatePreferences {
                    // Capture the persisted value inside the production edit, rather than the
                    // StateFlow's temporary default while preferences are still loading.
                    originalReduceMotion.compareAndSet(null, it.reduceMotion)
                    it.copy(reduceMotion = true)
                }
            }
            compose.waitUntil(10_000) { originalReduceMotion.get() != null && model.preferences.value.reduceMotion }
            compose.waitForIdle()
            val baseline = checkNotNull(snapshotOrNull())
            assumeTrue("This class requires an active secondary-display Presentation", baseline.lower != null)
            assertTrue("Both real windows must contain their original Compose roots", baseline.upper.composeViews.isNotEmpty())
            assertTrue(checkNotNull(baseline.lower).composeViews.isNotEmpty())
            lifecycle.baseline()
            check(baseline, lifecycle)
        } finally {
            try {
                originalReduceMotion.get()?.let { original ->
                    if (::model.isInitialized && !activity.isDestroyed) {
                        instrumentation.runOnMainSync { model.updatePreferences { it.copy(reduceMotion = original) } }
                        // Do not destroy the ViewModel while its restoring DataStore edit is pending.
                        compose.waitUntil(5_000) { model.preferences.value.reduceMotion == original }
                    }
                }
            } finally {
                scenario?.close()
                application.unregisterActivityLifecycleCallbacks(lifecycle)
            }
        }
    }

    private fun openMy() {
        compose.onNode(hasText("我的") and hasClickAction()).performClick()
        awaitPage(ThorPage.Root(ThorTab.My))
    }

    private fun click(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(text) and hasClickAction() and isEnabled()).fetchSemanticsNodes().size == 1
        }
        compose.onNode(hasText(text) and hasClickAction()).assertIsEnabled().performClick()
        compose.waitForIdle()
    }

    private fun awaitPage(page: ThorPage) {
        compose.waitUntil(10_000) { model.navigation.value.page == page && model.navigation.value.overlay == null }
        compose.waitForIdle()
    }

    private fun backUntil(page: ThorPage, maximumPresses: Int) {
        for (attempt in 0 until maximumPresses) {
            if (model.navigation.value.page == page && model.navigation.value.overlay == null) break
            val before = backState()
            press(KeyEvent.KEYCODE_BUTTON_B)
            // Chromium history changes asynchronously. Wait for this one local transition,
            // not an arbitrary sleep or an unlimited stream of repeated B presses.
            compose.waitUntil(5_000) { backState() != before }
            compose.waitForIdle()
        }
        assertEquals("Local field/Web history B handling must eventually return to the native caller", page, model.navigation.value.page)
        assertNull(model.navigation.value.overlay)
    }

    private fun backState(): BackState {
        var historyIndex: Int? = null
        instrumentation.runOnMainSync {
            val web = WindowInspector.getGlobalWindowViews().asSequence().filter { it.isShown && it.isAttachedToWindow }
                .flatMap { descendants(it).asSequence() }
                .filterIsInstance<android.webkit.WebView>().firstOrNull()
            historyIndex = web?.copyBackForwardList()?.currentIndex
        }
        return BackState(model.navigation.value.page, model.textEditing.value, historyIndex)
    }

    private fun press(code: Int) {
        instrumentation.runOnMainSync {
            val now = SystemClock.uptimeMillis()
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val event = KeyEvent(now, now, action, code, 0, 0, 99, 0, 0, InputDevice.SOURCE_GAMEPAD)
                assertTrue("The real shared controller router must handle key $code", activity.dispatchKeyEvent(event))
            }
        }
    }

    private fun assertNoRootSidebar() {
        for (tab in ThorTab.entries) {
            compose.onAllNodes(hasText(tab.title) and hasClickAction()).assertCountEquals(0)
        }
    }

    private fun awaitWebView() {
        compose.waitUntil(10_000) {
            var found = false
            instrumentation.runOnMainSync {
                found = WindowInspector.getGlobalWindowViews().any { root ->
                    root.isShown && root.isAttachedToWindow && descendants(root).any { it is android.webkit.WebView && it.isShown }
                }
            }
            found
        }
    }

    private fun snapshotOrNull(): WindowSnapshot? {
        if (!::activity.isInitialized) return null
        var result: WindowSnapshot? = null
        instrumentation.runOnMainSync {
            val upper = activity.window.decorView
            if (upper.windowToken == null || !upper.isAttachedToWindow) return@runOnMainSync
            val presentations = WindowInspector.getGlobalWindowViews().filter { root ->
                root.isAttachedToWindow && root.isShown && root.display?.displayId != upper.display?.displayId &&
                    descendants(root).any { it.javaClass.name == "androidx.compose.ui.platform.ComposeView" }
            }
            assertTrue("Only one application lower Presentation may own the secondary display", presentations.size <= 1)
            result = WindowSnapshot(activity, activity.taskId, identity(upper), presentations.singleOrNull()?.let(::identity))
        }
        return result
    }

    private fun assertStable(expected: WindowSnapshot, lifecycle: LifecycleRecorder) {
        compose.waitForIdle()
        val current = checkNotNull(snapshotOrNull())
        assertSame("Internal navigation must retain the original ThorActivity", expected.activity, current.activity)
        assertEquals(expected.taskId, current.taskId)
        assertSameWindow(expected.upper, current.upper)
        assertSameWindow(checkNotNull(expected.lower), checkNotNull(current.lower))
        instrumentation.runOnMainSync { lifecycle.assertUnchanged() }
    }

    private fun assertSameWindow(expected: WindowIdentity, current: WindowIdentity) {
        assertEquals(expected.displayId, current.displayId)
        assertSame("Internal navigation must not replace a base DecorView", expected.root, current.root)
        assertSame("Internal navigation must not recreate a base window token", expected.token, current.token)
        assertEquals(expected.composeViews.size, current.composeViews.size)
        expected.composeViews.zip(current.composeViews).forEach { (before, after) ->
            assertSame("The base Compose host must survive every embedded page", before, after)
        }
    }

    private fun identity(root: View) = WindowIdentity(root, checkNotNull(root.windowToken), root.display.displayId,
        descendants(root).filter { it.javaClass.name.contains("ComposeView") })

    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(descendants(root.getChildAt(index)))
    }

    private data class WindowIdentity(val root: View, val token: IBinder, val displayId: Int, val composeViews: List<View>)
    private data class WindowSnapshot(val activity: ThorActivity, val taskId: Int, val upper: WindowIdentity, val lower: WindowIdentity?)
    private data class BackState(val page: ThorPage, val editing: Boolean, val historyIndex: Int?)

    private class LifecycleRecorder : Application.ActivityLifecycleCallbacks {
        private var created = 0
        private var paused = 0
        private var stopped = 0
        private var destroyed = 0
        private var legacyCreated = 0
        private var baseline = emptyList<Int>()
        fun baseline() { baseline = values() }
        fun assertUnchanged() {
            assertEquals("Embedded navigation must not create, pause, stop or destroy an Activity", baseline, values())
            assertEquals("Embedded navigation must never instantiate MainActivity", 0, legacyCreated)
        }
        private fun values() = listOf(created, paused, stopped, destroyed, legacyCreated)
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity is ThorActivity) created++
            if (activity is MainActivity) legacyCreated++
        }
        override fun onActivityPaused(activity: Activity) { if (activity is ThorActivity) paused++ }
        override fun onActivityStopped(activity: Activity) { if (activity is ThorActivity) stopped++ }
        override fun onActivityDestroyed(activity: Activity) { if (activity is ThorActivity) destroyed++ }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}

/** A synthetic DOM click witness; it never authenticates or sends a request. */
internal class ThorSyntheticCaptchaBridge(private val confirmations: AtomicInteger) {
    @JavascriptInterface fun confirm() { confirmations.incrementAndGet() }
}

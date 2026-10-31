package com.ljyh.mei.thor.ui

import android.app.Presentation
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.thor.ThorNotice
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.thor.theme.ThorTheme
import com.ljyh.mei.thor.input.ThorInputRouter
import com.ljyh.mei.thor.test.ThorLiveEnvironment
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production search/notice UI in a real secondary-display window; no ViewModel or network. */
@RunWith(AndroidJUnit4::class)
class ThorSearchAndNoticeInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var activeLower: Presentation? = null

    @Test(timeout = 45_000)
    fun theFirstTouchOnSearchSubmitsTheVisibleEditorEvenBeforeItsProjectionCatchesUp() {
        val fixture = SearchFixture(freezeProjection = true, compactNotice = true)
        withLowerWindow({ fixture.Content() }, fixture.router) {
            val input = compose.onNodeWithTag("thor-search-input")
            val submitBounds = compose.onNodeWithTag("thor-search-submit").fetchSemanticsNode().boundsInRoot
            val oldNoticeClose = compose.onNodeWithTag("thor-notice-close").fetchSemanticsNode().boundsInRoot
            assertTrue("The compact fixture must reproduce the old notice action covering the submit center",
                oldNoticeClose.contains(submitBounds.center))
            input.performTouchInput { click() }
            input.performTextInput("  QA first touch  ")
            input.assertIsFocused()
            assertTrue(fixture.editing.get())
            compose.onNodeWithTag("thor-notice").assertDoesNotExist()
            // The fixture deliberately leaves the parent query empty, reproducing a lagging
            // projection. The production editor must submit its visible buffer, not that value.
            assertEquals("", fixture.query.value)
            compose.onNodeWithTag("thor-search-submit").assertIsEnabled().performTouchInput { click() }
            compose.waitUntil(5_000) { fixture.submitted.size == 1 }
            assertEquals(listOf("QA first touch"), fixture.submitted.toList())
            assertEquals("The first submit touch must not activate an old toast action", 0, fixture.noticeActions.get())
            input.assertIsNotFocused()
            assertFalse(fixture.editing.get())
            compose.onNodeWithTag("thor-search-submit").assertIsNotEnabled().assertTextContains("搜索中")
                .performTouchInput { click() }
            compose.waitForIdle()
            assertEquals("A disabled busy button must not submit a second request", 1, fixture.submitted.size)
        }
    }

    @Test(timeout = 45_000)
    fun imeAndControllerBindingsUseTheSameLatestQueryAndClosingEditingDoesNotSubmit() {
        val fixture = SearchFixture()
        withLowerWindow({ fixture.Content() }, fixture.router) {
            val input = compose.onNodeWithTag("thor-search-input")
            instrumentation.runOnMainSync { assertTrue(fixture.actions.invoke("search:edit")) }
            input.performTextReplacement("  QA IME  ")
            assertTrue(fixture.router.isTextEditing)
            input.performImeAction()
            compose.waitUntil(5_000) { fixture.submitted.size == 1 }
            assertEquals("QA IME", fixture.submitted[0])
            input.assertIsNotFocused()

            instrumentation.runOnMainSync {
                fixture.loading.value = false
                assertTrue(fixture.actions.invoke("search:edit"))
            }
            input.performTextReplacement("  QA controller  ")
            assertTrue(fixture.router.isTextEditing)
            press(KeyEvent.KEYCODE_BUTTON_A)
            compose.waitUntil(5_000) { fixture.submitted.size == 2 }
            assertEquals("QA controller", fixture.submitted[1])
            input.assertIsNotFocused()
            assertFalse(fixture.editing.get())
            assertFalse(fixture.router.isTextEditing)

            instrumentation.runOnMainSync { fixture.loading.value = false; fixture.actions.invoke("search:edit") }
            input.performTextReplacement("QA retained draft")
            assertTrue(fixture.router.isTextEditing)
            press(KeyEvent.KEYCODE_BUTTON_B)
            input.assertIsNotFocused().assertTextContains("QA retained draft")
            assertFalse(fixture.editing.get())
            assertEquals(2, fixture.submitted.size)
        }
    }

    @Test(timeout = 45_000)
    fun repeatNoticesRestartTheirTimerUndoExpiresAndErrorsRemain() {
        val notice = mutableStateOf<ThorNotice?>(null)
        val expired = CopyOnWriteArrayList<Long>()
        withLowerWindow({
            ThorNoticeAutoDismiss(notice.value) { id ->
                expired += id
                if (notice.value?.id == id) notice.value = null
            }
            Text(notice.value?.message ?: "QA notice absent", Modifier.testTag("qa-notice"))
        }) {
            fun accept(next: ThorNotice) {
                compose.mainClock.autoAdvance = true
                instrumentation.runOnMainSync { notice.value = next }
                compose.waitForIdle()
                compose.mainClock.autoAdvance = false
            }
            val first = ThorNotice("QA 已喜欢")
            val repeated = ThorNotice("QA 已喜欢")
            accept(first)
            compose.mainClock.advanceTimeBy(2_000)
            accept(repeated)
            compose.mainClock.advanceTimeBy(1_600)
            compose.onNodeWithTag("qa-notice").assertTextEquals("QA 已喜欢")
            assertTrue("The superseded timer must never expire the replacement", expired.isEmpty())
            compose.mainClock.advanceTimeBy(1_800)
            compose.onNodeWithTag("qa-notice").assertTextEquals("QA 已喜欢")
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithTag("qa-notice").assertTextEquals("QA notice absent")
            assertEquals(listOf(repeated.id), expired.toList())

            val undo = ThorNotice("QA 可撤销", undoToken = "qa-unused-local-token")
            accept(undo)
            compose.mainClock.advanceTimeBy(5_900)
            compose.onNodeWithTag("qa-notice").assertTextEquals("QA 可撤销")
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithTag("qa-notice").assertTextEquals("QA notice absent")
            assertEquals(listOf(repeated.id, undo.id), expired.toList())

            accept(ThorNotice("QA error remains", success = false))
            compose.mainClock.advanceTimeBy(20_000)
            compose.onNodeWithTag("qa-notice").assertTextEquals("QA error remains")
        }
    }

    private class SearchFixture(private val freezeProjection: Boolean = false, private val compactNotice: Boolean = false) {
        val query = mutableStateOf("")
        val loading = mutableStateOf(false)
        val editing = AtomicBoolean(false)
        val editingState = mutableStateOf(false)
        val noticeActions = AtomicInteger()
        private val oldNotice = ThorNotice("QA retained undo", undoToken = "qa-unused-local-token")
        val submitted = CopyOnWriteArrayList<String>()
        val actions = ThorPageActionBindings()
        val router = ThorInputRouter(onNonBackInput = {}, onCommand = {
            handleThorSearchEditingCommand(it, editing.get(), actions)
        })
        // Production search buttons remain outside the closed list focus scope. That must not
        // disable touch or the explicit page-action submission path.
        val registry = ThorFocusRegistry().apply { installScope(Any(), ThorFocusScope("qa-empty-results", emptyList())) }

        @Composable fun Content() {
            Box(Modifier.fillMaxWidth().then(if (compactNotice) Modifier.height(216.dp) else Modifier.fillMaxHeight())) {
                Column {
                    Text("QA search header", Modifier.height(56.dp))
                    ThorSearchInput(query.value, loading.value, registry, actions,
                        onQueryChange = { if (!freezeProjection) query.value = it },
                        onSubmit = { submitted += it; query.value = it; loading.value = true },
                        onEditingChange = { editing.set(it); editingState.value = it; router.setTextEditing(it) })
                    Text("QA results region")
                }
                if (compactNotice) ThorNoticeBanner(oldNotice, registry, editingState.value,
                    onUndo = { noticeActions.incrementAndGet() }, onClose = { noticeActions.incrementAndGet() },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 96.dp))
            }
        }
    }

    private fun withLowerWindow(content: @Composable () -> Unit, router: ThorInputRouter? = null, check: () -> Unit) {
        ThorLiveEnvironment.requireRequestedDevice(instrumentation.targetContext)
        lateinit var owner: ComponentActivity
        compose.setContent {
            val activity = checkNotNull(LocalActivity.current) as ComponentActivity
            SideEffect { owner = activity }
        }
        compose.waitForIdle()
        var upperDisplayId: Int? = null
        compose.waitUntil(10_000) {
            instrumentation.runOnMainSync {
                val decor = owner.window.decorView
                upperDisplayId = decor.display?.displayId.takeIf { decor.isAttachedToWindow }
            }
            upperDisplayId != null
        }
        val display = instrumentation.targetContext.getSystemService(DisplayManager::class.java).displays.firstOrNull {
            it.displayId != upperDisplayId && it.flags and Display.FLAG_PRESENTATION != 0
        }
        assertNotNull("A real secondary presentation display is required", display)
        val expectedLowerDisplayId = checkNotNull(display).displayId
        var lower: Presentation? = null
        var lowerContent: ComposeView? = null
        try {
            instrumentation.runOnMainSync {
                lower = object : Presentation(owner, checkNotNull(display)) {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                        router?.onKeyEvent(event) == true || super.dispatchKeyEvent(event)
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setCancelable(false)
                        window!!.apply {
                            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                            WindowCompat.setDecorFitsSystemWindows(this, false)
                            decorView.setViewTreeLifecycleOwner(owner)
                            decorView.setViewTreeViewModelStoreOwner(owner)
                            decorView.setViewTreeSavedStateRegistryOwner(owner)
                            decorView.setViewTreeOnBackPressedDispatcherOwner(owner)
                        }
                        setContentView(ComposeView(context).apply {
                            lowerContent = this
                            id = View.generateViewId()
                            setViewTreeLifecycleOwner(owner)
                            setViewTreeViewModelStoreOwner(owner)
                            setViewTreeSavedStateRegistryOwner(owner)
                            setViewTreeOnBackPressedDispatcherOwner(owner)
                            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                            setContent {
                                CompositionLocalProvider(LocalActivity provides owner) {
                                    ThorTheme(ThorPreferences(reduceMotion = true)) {
                                        Column(Modifier.fillMaxSize()) { content() }
                                    }
                                }
                            }
                        })
                    }
                }.also {
                    activeLower = it
                    it.show()
                    it.window!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                }
            }
            // show() adds the window but does not guarantee ViewRoot attachment or its first
            // traversal has finished. In particular View.display is null until attachment.
            compose.waitUntil(10_000) {
                var ready = false
                instrumentation.runOnMainSync {
                    val decor = lower?.window?.decorView
                    val contentView = lowerContent
                    ready = lower?.isShowing == true && decor != null && decor.isAttachedToWindow &&
                        decor.isShown && decor.windowToken != null && decor.display != null &&
                        decor.width > 0 && decor.height > 0 && contentView != null &&
                        contentView.isAttachedToWindow && contentView.display != null &&
                        contentView.width > 0 && contentView.height > 0
                }
                ready
            }
            instrumentation.runOnMainSync {
                val decor = checkNotNull(lower?.window?.decorView)
                val contentView = checkNotNull(lowerContent)
                assertTrue("The lower window must still be showing after attachment", lower!!.isShowing)
                assertTrue("The lower decor must remain attached", decor.isAttachedToWindow)
                assertTrue("The actual Compose host must remain attached", contentView.isAttachedToWindow)
                assertEquals("The decor must be attached to the selected secondary display",
                    expectedLowerDisplayId, decor.display?.displayId)
                assertEquals("The actual Compose host must use that same secondary display",
                    expectedLowerDisplayId, contentView.display?.displayId)
                assertNotEquals("The lower fixture must not silently fall back to the Activity display",
                    upperDisplayId, decor.display?.displayId)
                assertSame("The Compose host must belong to the attached lower window",
                    decor.windowToken, contentView.windowToken)
            }
            compose.waitForIdle()
            check()
        } finally {
            compose.mainClock.autoAdvance = true
            instrumentation.runOnMainSync { lower?.dismiss(); router?.close(); activeLower = null }
        }
    }

    private fun press(code: Int) {
        instrumentation.runOnMainSync {
            val now = SystemClock.uptimeMillis()
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val event = KeyEvent(now, now, action, code, 0, 0, 99, 0, 0, InputDevice.SOURCE_GAMEPAD)
                assertTrue("The actual lower window and production router must handle gamepad $code",
                    checkNotNull(activeLower).dispatchKeyEvent(event))
            }
        }
    }

}

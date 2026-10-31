package com.ljyh.mei.thor.upper

import android.app.Dialog
import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.thor.artwork.CaseMotionPhase
import com.ljyh.mei.thor.artwork.LocalThorCaseDiagnostics
import com.ljyh.mei.thor.artwork.ThorCaseDiagnostics
import com.ljyh.mei.thor.artwork.ThorCaseFrame
import com.ljyh.mei.thor.model.ThorAppearance
import com.ljyh.mei.thor.model.ThorBackgroundMode
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil

/** Input focus stays out of the real upper tree; touch and accessibility actions remain usable. */
@RunWith(AndroidJUnit4::class)
class ThorUpperDisplayOnlyInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val preferences = mutableStateOf(ThorPreferences(appearance = ThorAppearance.Day,
        reduceMotion = true, backgroundMode = ThorBackgroundMode.Clean))
    private val visible = mutableStateOf(true)
    private val position = mutableLongStateOf(2_500L)
    private val selected = mutableListOf<Long>()
    private val diagnostics = ThorCaseDiagnostics()
    private lateinit var inputView: View
    private lateinit var focusManager: FocusManager

    @Test(timeout = 30_000)
    fun touchingThenRequestingDpadOrSystemKeysNeverSelectsAnUpperNodeOrAddsACaseBorder() = withUpper {
        turnCaseByTouch()
        assertNoUpperInputFocus()
        val before = casePixels()
        val requestable = compose.onAllNodes(upperDescendant and
            SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus), useUnmergedTree = true)
        repeat(requestable.fetchSemanticsNodes().size) { index ->
            requestable[index].performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            assertNoUpperInputFocus()
        }
        compose.runOnUiThread {
            listOf(FocusDirection.Down, FocusDirection.Up, FocusDirection.Left, FocusDirection.Right,
                FocusDirection.Next, FocusDirection.Previous, FocusDirection.Enter).forEach { focusManager.moveFocus(it) }
            // Dispatch to the View, not Activity/AudioManager: exercise volume-key focus handling
            // without changing the device's real volume or launching a system panel.
            listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_VOLUME_UP).forEach { code ->
                inputView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                inputView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
        }
        advance(64)
        assertNoUpperInputFocus()
        assertTrue("Unrelated input must not turn the cover or add a focus border", before.contentEquals(casePixels()))
        compose.onNodeWithText(lineText(2)).assertIsSelected()
        // Retain the existing accessibility action, without making it a keyboard focus target.
        val previousYaw = requireNotNull(diagnostics.latest).pose.yawDegrees
        compose.onNodeWithTag(CASE).performSemanticsAction(SemanticsActions.OnClick) { it() }
        advance(64)
        assertEquals(180.0, abs(requireNotNull(diagnostics.latest).pose.yawDegrees - previousYaw), .1)
        assertNoUpperInputFocus()
    }

    @Test(timeout = 30_000)
    fun realWindowFocusLossAndRestorationAfterTouchDoesNotRestoreAnUpperSelection() = withUpper {
        val activity = compose.activity
        compose.runOnUiThread {
            // A preceding editable-field test may still be dismissing the real IME. The focus
            // roundtrip must not resize the scene while comparing its otherwise static pixels.
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            WindowInsetsControllerCompat(activity.window, inputView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(5_000) {
            var imeHidden = false
            compose.runOnUiThread {
                imeHidden = ViewCompat.getRootWindowInsets(inputView)?.isVisible(WindowInsetsCompat.Type.ime()) != true
            }
            imeHidden
        }
        advance(64)
        turnCaseByTouch()
        val before = focusEvidence("before-window-focus-loss")
        lateinit var dialog: Dialog
        compose.runOnUiThread {
            dialog = Dialog(activity).apply {
                // Test actual input-window ownership, excluding unrelated WM dim/exit animation.
                window?.apply {
                    setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                    clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    setWindowAnimations(0)
                }
                setTitle("Synthetic focus owner")
                val field = TextView(context).apply {
                    text = "QA window focus"
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
                setContentView(field)
                show()
                field.requestFocus()
            }
        }
        try {
            awaitWindowFocus(false)
        } finally { compose.runOnUiThread { dialog.dismiss() } }
        awaitWindowFocus(true)
        advance(64)
        assertNoUpperInputFocus()
        val after = focusEvidence("after-window-focus-restoration")
        assertEquals("A focus-only roundtrip must not pause or replace the activity", Lifecycle.State.RESUMED,
            activity.lifecycle.currentState)
        assertEquals("Reduced-motion touch must already be settled before showing the window",
            CaseMotionPhase.Idle, before.frame.phase)
        assertEquals("Window focus must not alter the actual case pose or motion policy", before.frame, after.frame)
        assertEquals("The native case screenshot width must stay fixed", before.width, after.width)
        assertEquals("The native case screenshot height must stay fixed", before.height, after.height)
        assertEquals("Window focus must not resize or relocate the case canvas", before.bounds, after.bounds)
        assertNoNewFocusBorder(before, after)
    }

    @Test(timeout = 30_000)
    fun caseDraggingAndLyricTouchBrowsingAndSeekContinueWithoutInputFocus() = withUpper {
        compose.runOnUiThread { preferences.value = preferences.value.copy(reduceMotion = false) }
        advance(640)
        val beforeYaw = requireNotNull(diagnostics.latest).pose.yawDegrees
        val case = compose.onNodeWithTag(CASE)
        case.performTouchInput {
            down(center)
            moveTo(center + Offset(width * .24f, -height * .08f), 180)
        }
        try {
            advance(100)
            val dragged = requireNotNull(diagnostics.latest)
            assertEquals(CaseMotionPhase.Dragging, dragged.phase)
            assertTrue("Removing focus must preserve real model dragging", abs(dragged.pose.yawDegrees - beforeYaw) > 45)
            assertNoUpperInputFocus()
        } finally { case.performTouchInput { up() } }
        advance(4_000)
        assertEquals(CaseMotionPhase.Idle, requireNotNull(diagnostics.latest).phase)

        val viewport = compose.onNodeWithTag(LYRICS)
        viewport.performTouchInput {
            swipe(Offset(width * .5f, height * .80f), Offset(width * .5f, height * .22f), 500)
        }
        advance(1_000)
        viewport.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "浏览歌词"))
        assertTrue("Dragging lyrics must not seek", selected.isEmpty())
        assertNoUpperInputFocus()
        val viewportBounds = viewport.fetchSemanticsNode().boundsInRoot
        val target = compose.onAllNodes(hasClickAction() and hasText("Display lyric", substring = true))
            .fetchSemanticsNodes().filter {
                it.boundsInRoot.top > viewportBounds.top + viewportBounds.height * .12f &&
                    it.boundsInRoot.bottom < viewportBounds.bottom - viewportBounds.height * .12f
            }.minByOrNull { abs(it.boundsInRoot.center.y - viewportBounds.center.y) }
        val text = requireNotNull(target) { "A real browsed lyric must remain touchable" }
            .config[SemanticsProperties.Text].first { it.text.startsWith("Display lyric") }.text
        val index = text.substringAfter("Display lyric ").toInt()
        compose.onNodeWithText(text).performTouchInput { click() }
        advance(400)
        assertEquals("Touching a real lyric still invokes seek exactly once", listOf(index * 1_000L), selected)
        viewport.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "跟随播放"))
        assertNoUpperInputFocus()
    }

    @Test(timeout = 30_000)
    fun upperFocusExclusionDoesNotDisableASiblingNativeTextInput() = withUpper(includeSiblingInput = true) {
        compose.runOnUiThread { siblingRequester.requestFocus() }
        advance(64)
        compose.onNodeWithTag(SIBLING).assertIsFocused()
        assertNoUpperInputFocus()
        compose.onNodeWithTag(SIBLING).performTextReplacement("QA editable")
        assertEquals("QA editable", siblingValue.value)
    }

    private val siblingRequester = FocusRequester()
    private val siblingValue = mutableStateOf("QA")
    private val upperDescendant get() = hasTestTag(UPPER) or hasAnyAncestor(hasTestTag(UPPER))

    private fun withUpper(includeSiblingInput: Boolean = false, test: () -> Unit) {
        val lyrics = LyricData(source = LyricSource.NetEaseCloudMusic,
            lyricLine = SyncedLyrics(lines = List(30) { index ->
                SyncedLine(lineText(index), "Synthetic translation", index * 1_000, (index + 1) * 1_000 - 1)
            }))
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                val view = LocalView.current
                val manager = LocalFocusManager.current
                SideEffect { inputView = view; focusManager = manager }
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth().testTag(UPPER)) {
                        CompositionLocalProvider(LocalThorCaseDiagnostics provides diagnostics) {
                            ThorUpperScreen(ThorItem(ThorItemKey.Album(9_128L, local = true),
                                "Display-only QA", "Synthetic album"), false, preferences.value, 0f,
                                lyricData = lyrics, positionProvider = { position.longValue },
                                playbackScene = true, visible = visible.value,
                                onLyricSelected = { selected += it; position.longValue = it },
                                modifier = Modifier.fillMaxSize())
                        }
                    }
                    if (includeSiblingInput) BasicTextField(siblingValue.value, { siblingValue.value = it },
                        Modifier.fillMaxWidth().height(48.dp).testTag(SIBLING).focusRequester(siblingRequester))
                }
            }
            advance(640)
            test()
        } finally {
            compose.runOnUiThread {
                visible.value = false
                preferences.value = preferences.value.copy(reduceMotion = true)
                if (::focusManager.isInitialized) focusManager.clearFocus(force = true)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.mainClock.autoAdvance = true
        }
    }

    private fun turnCaseByTouch() {
        val yaw = requireNotNull(diagnostics.latest).pose.yawDegrees
        compose.onNodeWithTag(CASE).performTouchInput {
            down(center); moveTo(center + Offset(width * .2f, 0f), 180); up()
        }
        advance(64)
        assertEquals("A display-only cover must still respond to touch", 180.0,
            abs(requireNotNull(diagnostics.latest).pose.yawDegrees - yaw), .1)
    }

    private fun assertNoUpperInputFocus() {
        compose.onAllNodes(upperDescendant and isFocused(), useUnmergedTree = true).assertCountEquals(0)
    }

    private fun awaitWindowFocus(expected: Boolean) {
        compose.waitUntil(5_000) {
            var focused = false
            compose.runOnUiThread { focused = inputView.hasWindowFocus() }
            focused == expected
        }
    }

    private fun advance(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
    }

    private fun casePixels(): IntArray {
        val bitmap = compose.onNodeWithTag(CASE).captureToImage().asAndroidBitmap()
        return IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
    }

    private data class FocusEvidence(val bounds: Rect, val frame: ThorCaseFrame,
        val width: Int, val height: Int, val pixels: IntArray)

    private fun assertNoNewFocusBorder(before: FocusEvidence, after: FocusEvidence) {
        val perimeterPx = ceil(12f * inputView.resources.displayMetrics.density).toInt()
        var interiorChanges = 0
        for (index in before.pixels.indices) {
            val first = before.pixels[index]
            val second = after.pixels[index]
            if (first == second) continue
            val x = index % before.width
            val y = index / before.width
            val perimeter = x < perimeterPx || y < perimeterPx ||
                x >= before.width - perimeterPx || y >= before.height - perimeterPx
            assertTrue("Window focus must leave the entire 12dp selection-border band pixel-exact; changed at ($x,$y)",
                !perimeter)
            interiorChanges++
            // Evidence from two actual GPU renders: identical pose/bounds, four projected-edge
            // antialias pixels changed by at most 29 channel levels out of 734,400 pixels.
            // Keep a tiny absolute allowance, never a whole-image percentage or similarity score.
            val channelDelta = listOf(0, 8, 16, 24).maxOf { shift ->
                abs(((first ushr shift) and 255) - ((second ushr shift) and 255))
            }
            assertTrue("Interior raster variation must remain small at ($x,$y), channelDelta=$channelDelta",
                channelDelta <= 32)
        }
        assertTrue("Only isolated projected-edge antialias pixels may vary; interiorChangedPixels=$interiorChanges",
            interiorChanges <= 32)
    }

    private fun focusEvidence(name: String): FocusEvidence {
        val node = compose.onNodeWithTag(CASE)
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val bitmap = node.captureToImage().asAndroidBitmap()
        val directory = File(inputView.context.cacheDir, "thor-upper-focus-evidence").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        val pixels = IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        return FocusEvidence(bounds, requireNotNull(diagnostics.latest), bitmap.width, bitmap.height, pixels)
    }

    private companion object {
        const val UPPER = "display-only-upper"
        const val CASE = "thor-cd-case"
        const val LYRICS = "thor-upper-lyrics-viewport"
        const val SIBLING = "non-upper-input"
        fun lineText(index: Int) = "Display lyric ${index.toString().padStart(2, '0')}"
    }
}

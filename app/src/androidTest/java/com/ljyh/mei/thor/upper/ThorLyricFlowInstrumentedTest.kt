package com.ljyh.mei.thor.upper

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.thor.model.ThorAppearance
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.thor.model.ThorThemePack
import com.ljyh.mei.thor.theme.LocalThorTheme
import com.ljyh.mei.thor.theme.ThorTheme
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real native text pixels and production renderer; synthetic timings/text, no account/network. */
@RunWith(AndroidJUnit4::class)
class ThorLyricFlowInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val preferences = mutableStateOf(ThorPreferences(reduceMotion = true, appearance = ThorAppearance.Day))
    private val playing = mutableStateOf(false)
    private val active = mutableStateOf(true)
    private val position = mutableLongStateOf(1_000)
    private val diagnostics = ThorLyricFlowDiagnostics()

    @After fun stopDecorativeClock() {
        compose.runOnUiThread { playing.value = false; active.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test fun sixMaterialAppearancesKeepProgressWithoutMotionAndKeepTranslationBoundsStable() {
        show(wordTimed = true)
        for (pack in ThorThemePack.entries) for (appearance in listOf(ThorAppearance.Day, ThorAppearance.Night)) {
            compose.runOnIdle {
                preferences.value = preferences.value.copy(theme = pack, appearance = appearance)
                position.longValue = 1_000
            }
            compose.waitForIdle()
            val before = pixels()
            val translated = compose.onNodeWithText(TRANSLATION, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val bounds = primary().fetchSemanticsNode().boundsInRoot
            val layouts = diagnostics.layouts.get()
            compose.runOnIdle { position.longValue = 5_000 }
            compose.waitForIdle()
            assertTrue("$pack/$appearance must visibly advance real timed words even with reduced motion",
                changed(before, pixels()) > 30)
            assertEquals("A word tick must not measure/layout the line again", layouts, diagnostics.layouts.get())
            assertEquals(bounds, primary().fetchSemanticsNode().boundsInRoot)
            assertEquals(translated, compose.onNodeWithText(TRANSLATION, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
            assertEquals(ThorLyricTiming.WordTimed, diagnostics.timing)
            assertFalse(diagnostics.decorated)
            assertEquals(0, diagnostics.frameTicks.get())
        }
    }

    @Test fun playbackFramesOnlyRedrawWordsAndPauseHiddenAndReducedMotionStopTheDecorativeClock() {
        compose.mainClock.autoAdvance = false
        preferences.value = preferences.value.copy(reduceMotion = false, appearance = ThorAppearance.Night)
        playing.value = true
        show(wordTimed = true)
        advance(160)
        assertTrue("The production playing renderer must request actual draw frames", diagnostics.frameTicks.get() > 0)
        assertTrue(diagnostics.decorated)
        val layouts = diagnostics.layouts.get()
        val before = pixels()
        compose.runOnUiThread { position.longValue = 5_000 }
        advance(160)
        assertTrue("Timed progress must change the native text image", changed(before, pixels()) > 30)
        assertEquals("Frame/time updates may not rebuild the text layout", layouts, diagnostics.layouts.get())
        compose.runOnUiThread { playing.value = false }
        advance(64)
        val pausedTicks = diagnostics.frameTicks.get()
        advance(1_000)
        assertEquals("Paused playback must not keep a shimmer clock alive", pausedTicks, diagnostics.frameTicks.get())
        assertFalse(diagnostics.decorated)

        compose.runOnUiThread { playing.value = true; active.value = false }
        advance(64)
        val hiddenTicks = diagnostics.frameTicks.get()
        advance(1_000)
        assertEquals("A hidden upper lyric view must stop frame requests", hiddenTicks, diagnostics.frameTicks.get())

        compose.runOnUiThread {
            active.value = true
            preferences.value = preferences.value.copy(reduceMotion = true)
            position.longValue = 1_000
        }
        advance(64)
        val reducedTicks = diagnostics.frameTicks.get()
        val reducedBefore = pixels()
        compose.runOnUiThread { position.longValue = 5_000 }
        advance(500)
        assertEquals(reducedTicks, diagnostics.frameTicks.get())
        assertFalse(diagnostics.decorated)
        assertTrue("Reduced motion retains seek/progress ink without decorative movement", changed(reducedBefore, pixels()) > 30)
    }

    @Test fun actualMediaClockCrossesTheLineBoundaryWithoutWaitingForAnObservableBridgeTick() {
        compose.mainClock.autoAdvance = false
        preferences.value = preferences.value.copy(reduceMotion = false)
        playing.value = true
        val clock = AtomicLong(500L)
        val first = "First rendering clock line"
        val second = "Second rendering clock line"
        val data = LyricData(source = LyricSource.NetEaseCloudMusic,
            lyricLine = SyncedLyrics(listOf(SyncedLine(first, null, 0, 1_999),
                SyncedLine(second, null, 2_000, 5_000))))
        compose.setContent {
            ThorTheme(preferences.value) {
                ThorUpperLyrics(data, clock::get, active.value,
                    Modifier.width(320.dp).height(360.dp), playing = playing.value)
            }
        }
        fun fontSize(text: String): Float {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.style.fontSize.value
        }
        advance(96)
        assertTrue(fontSize(first) > fontSize(second))
        // AtomicLong is deliberately not Compose state: only the real draw clock can observe it.
        clock.set(2_500L)
        advance(400)
        assertTrue("The next line must activate on time without a 250ms bridge event",
            fontSize(second) > fontSize(first))
    }

    @Test fun plainLrcUsesContinuousLineProgressAndNeverReportsInventedWordTiming() {
        show(wordTimed = false)
        val before = pixels()
        val layout = layout()
        val layouts = diagnostics.layouts.get()
        compose.runOnIdle { position.longValue = 6_500 }
        compose.waitForIdle()
        assertTrue(changed(before, pixels()) > 30)
        assertEquals(ThorLyricTiming.LineProgress, diagnostics.timing)
        assertEquals(SENTENCE, layout.layoutInput.text.text)
        assertEquals(layout.size, layout().size)
        assertEquals(layouts, diagnostics.layouts.get())
        assertEquals(0, diagnostics.frameTicks.get())
    }

    private fun show(wordTimed: Boolean) {
        val first = if (wordTimed) KaraokeLine.MainKaraokeLine(
            listOf(KaraokeSyllable("Moonlight 🌙 ", 0, 2_000),
                KaraokeSyllable("越过海岸 ", 3_000, 6_000), KaraokeSyllable("e\u0301toile", 6_000, 8_000)),
            TRANSLATION, KaraokeAlignment.Start, 0, 8_000,
        ) else SyncedLine(SENTENCE, TRANSLATION, 0, 8_000)
        val data = LyricData(isVerbatim = wordTimed, source = LyricSource.NetEaseCloudMusic,
            lyricLine = SyncedLyrics(listOf(first, SyncedLine("A quiet following line", null, 9_000, 14_000))))
        compose.setContent {
            ThorTheme(preferences.value) {
                CompositionLocalProvider(LocalThorLyricFlowDiagnostics provides diagnostics) {
                    ThorUpperLyrics(data, { position.longValue }, active.value,
                        Modifier.width(320.dp).height(360.dp).background(LocalThorTheme.current.background).testTag("flow-viewport"),
                        playing = playing.value)
                }
            }
        }
        if (!compose.mainClock.autoAdvance) advance(64) else compose.waitForIdle()
    }
    private fun primary() = compose.onNodeWithText(SENTENCE, useUnmergedTree = true)
    private fun pixels(): IntArray {
        val map = primary().captureToImage().toPixelMap()
        return IntArray(map.width * map.height) { index -> map[index % map.width, index / map.width].toArgb() }
    }
    private fun changed(before: IntArray, after: IntArray): Int {
        assertEquals(before.size, after.size)
        return before.indices.count { before[it] != after[it] }
    }
    private fun layout(): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        primary().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single()
    }
    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }
    private companion object {
        const val SENTENCE = "Moonlight 🌙 越过海岸 e\u0301toile"
        const val TRANSLATION = "月光掠过海岸，译文始终保留自己的位置。"
    }
}

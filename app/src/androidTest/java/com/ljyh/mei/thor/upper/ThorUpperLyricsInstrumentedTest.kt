package com.ljyh.mei.thor.upper

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.thor.model.ThorAppearance
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.thor.theme.LocalThorTheme
import com.ljyh.mei.thor.theme.ThorTheme
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Artificial timed text only; touch gestures never access a player, account, or network. */
@RunWith(AndroidJUnit4::class)
class ThorUpperLyricsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val position = mutableLongStateOf(2_500L)
    private val active = mutableStateOf(true)
    private val selected = mutableListOf<Long>()
    private val browse = ThorLyricBrowseState()
    private lateinit var list: LazyListState
    private lateinit var inputView: View
    private val lyrics = LyricData(
        source = LyricSource.NetEaseCloudMusic,
        lyricLine = SyncedLyrics(lines = List(30) { index ->
            SyncedLine(lineText(index), null, index * 1_000, (index + 1) * 1_000 - 1)
        }),
    )

    @Test
    fun draggingAndFlingingBrowseWithoutSeeking() {
        showLyrics()
        slowDragUp()
        awaitBrowsingAtRest()
        compose.runOnIdle {
            assertTrue(list.firstVisibleItemIndex > 0)
            assertTrue(selected.isEmpty())
        }
        compose.onNodeWithTag(TAG).performTouchInput {
            swipe(Offset(width * .5f, height * .9f), Offset(width * .5f, height * .12f), 90)
        }
        awaitBrowsingAtRest()
        assertBrowsing()
        compose.runOnIdle { assertTrue("Drag/fling must not invoke line selection", selected.isEmpty()) }
    }

    @Test
    fun playbackClockAdvancesWithoutMovingTheBrowsedViewportAndIdleCanResumeFollowing() {
        showLyrics()
        slowDragUp()
        awaitBrowsingAtRest()
        val first = compose.runOnIdle { list.firstVisibleItemIndex }
        compose.runOnIdle { position.longValue = 24_500L }
        compose.waitForIdle()
        assertBrowsing()
        compose.runOnIdle {
            assertEquals("Playback must not take over a browsed viewport", first, list.firstVisibleItemIndex)
            assertTrue(selected.isEmpty())
            assertTrue(browse.resumeIfIdle(checkNotNull(browse.returnAtMs)))
        }
        assertFollowing()
        compose.onNodeWithText(lineText(24)).assertIsDisplayed()
        compose.runOnIdle { assertTrue("Automatic return must not seek", selected.isEmpty()) }
    }

    @Test(timeout = 30_000)
    fun productionFifteenSecondIdleTimerReturnsToTheCurrentLineWithoutSeeking() {
        showLyrics()
        slowDragUp()
        awaitBrowsingAtRest()
        val deadline = compose.runOnIdle {
            val value = checkNotNull(browse.returnAtMs)
            assertTrue("The real product timer must still be pending", value > SystemClock.uptimeMillis())
            position.longValue = 24_500L
            value
        }
        assertBrowsing()
        // This one test deliberately waits for the production monotonic-clock + delay timer.
        // Other cases advance the injected state instead of shortening the 15s product policy.
        compose.waitUntil(timeoutMillis = 18_000) { browse.canFollow }
        val returnedAt = SystemClock.uptimeMillis()
        compose.waitForIdle()
        assertTrue("Must not resume ahead of its real deadline", returnedAt >= deadline)
        assertTrue("Allow up to 800ms for idle callback scheduling", returnedAt - deadline <= 800)
        assertFollowing()
        compose.onNodeWithText(lineText(24)).assertIsDisplayed()
        compose.runOnIdle { assertTrue(selected.isEmpty()) }
    }

    @Test
    fun anotherDragReplacesTheDeadlineAndAnOldTimerCannotResumeFollowing() {
        showLyrics()
        slowDragUp()
        awaitBrowsingAtRest()
        val oldDeadline = compose.runOnIdle { checkNotNull(browse.returnAtMs) }
        compose.onNodeWithTag(TAG).performTouchInput {
            swipe(Offset(width * .5f, height * .25f), Offset(width * .5f, height * .72f), 900)
        }
        awaitBrowsingAtRest()
        compose.runOnIdle {
            val newDeadline = checkNotNull(browse.returnAtMs)
            assertTrue("A new gesture must restart the idle interval", newDeadline > oldDeadline)
            assertFalse(browse.resumeIfIdle(oldDeadline))
            assertTrue(browse.isBrowsing)
            assertTrue(selected.isEmpty())
            position.longValue = 24_500L
        }
        assertBrowsing()
        compose.runOnIdle { assertTrue(browse.resumeIfIdle(checkNotNull(browse.returnAtMs))) }
        assertFollowing()
        compose.onNodeWithText(lineText(24)).assertIsDisplayed()
    }

    @Test
    fun aSingleTapOnABrowsedLineSelectsThatTimeOnceAndResumesFollowing() {
        showLyrics()
        slowDragUp()
        awaitBrowsingAtRest()
        val target = compose.runOnIdle {
            val info = list.layoutInfo
            info.visibleItemsInfo.first {
                it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset
            }.index
        }
        compose.onNodeWithText(lineText(target)).performTouchInput { click(center) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(target * 1_000L), selected)
            assertTrue(browse.canFollow)
            assertEquals(null, browse.returnAtMs)
        }
        assertFollowing()
    }

    @Test
    fun inactiveLyricsIgnoreTouchSelectionAndScrolling() {
        active.value = false
        showLyrics()
        val first = compose.runOnIdle { list.firstVisibleItemIndex }
        compose.onNodeWithText(lineText(first)).performTouchInput { click(center) }
        slowDragUp()
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(selected.isEmpty())
            assertEquals(first, list.firstVisibleItemIndex)
            assertFalse(browse.isBrowsing)
        }
    }

    @Test
    fun aRealDragInterruptsFollowAnimationAndLaterPlaybackUpdatesStillFollow() {
        showLyrics(reduceMotion = false)
        // Cache geometry before freezing. Semantics lookup and performTouchInput synchronize
        // with Compose idleness, which cannot finish a frozen follow animation.
        val bounds = compose.onNodeWithTag(TAG).fetchSemanticsNode().boundsInRoot
        val x = bounds.left + bounds.width * .5f
        val startY = bounds.top + bounds.height * .8f
        val endY = bounds.top + bounds.height * .3f
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        var pointerDown = false
        fun dispatch(action: Int, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { instrumentation.runOnMainSync { inputView.dispatchTouchEvent(event) } }
            finally { event.recycle() }
        }
        compose.mainClock.autoAdvance = false
        try {
            instrumentation.runOnMainSync { position.longValue = 24_500L }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(32)
            instrumentation.runOnMainSync { assertTrue("Following must actually be animating", list.isScrollInProgress) }
            dispatch(MotionEvent.ACTION_DOWN, startY)
            pointerDown = true
            SystemClock.sleep(40)
            dispatch(MotionEvent.ACTION_MOVE, (startY + endY) / 2)
            compose.mainClock.advanceTimeByFrame()
            SystemClock.sleep(40)
            dispatch(MotionEvent.ACTION_MOVE, endY)
            compose.mainClock.advanceTimeByFrame()
            instrumentation.runOnMainSync {
                assertTrue("The real drag must win over programmatic scrolling", browse.isDragging)
                assertTrue(browse.isBrowsing)
                assertTrue(selected.isEmpty())
            }
            dispatch(MotionEvent.ACTION_UP, endY)
            pointerDown = false
        } finally {
            try { if (pointerDown) dispatch(MotionEvent.ACTION_CANCEL, endY) }
            finally { compose.mainClock.autoAdvance = true }
        }
        awaitBrowsingAtRest()
        compose.runOnIdle { assertTrue(browse.resumeIfIdle(checkNotNull(browse.returnAtMs))) }
        assertFollowing()
        compose.onNodeWithText(lineText(24)).assertIsDisplayed()
        compose.onNodeWithText(lineText(29)).assertIsNotDisplayed()
        compose.runOnIdle { position.longValue = 29_500L }
        compose.onNodeWithText(lineText(29)).assertIsDisplayed()
        compose.runOnIdle {
            assertTrue("Follow must survive the earlier scroll cancellation", browse.canFollow)
            assertTrue(selected.isEmpty())
        }
    }

    @Test
    fun dayAndNightLongTextTranslationSpacingAndPaddedClickTargetsAreRealLayouts() {
        val sentence = "沿着海岸缓缓前行，让晚风带走今天的喧嚣。"
        val translation = "Follow the coast and let the evening breeze carry the day away."
        val data = lyrics.copy(lyricLine = SyncedLyrics(lines = List(30) { index ->
            when (index) {
                1 -> SyncedLine("把今日留在路上", null, 1_000, 1_999)
                2 -> SyncedLine(sentence, translation, 2_000, 2_999)
                3 -> SyncedLine("等一束微光越过海面", null, 3_000, 3_999)
                else -> SyncedLine(lineText(index), null, index * 1_000, (index + 1) * 1_000 - 1)
            }
        }))
        val appearance = mutableStateOf(ThorAppearance.Day)
        compose.mainClock.autoAdvance = true
        compose.setContent {
            ThorTheme(ThorPreferences(appearance = appearance.value, reduceMotion = true)) {
                val state = rememberLazyListState()
                SideEffect { list = state }
                ThorUpperLyrics(data, { position.longValue }, true,
                    Modifier.width(320.dp).height(360.dp).background(LocalThorTheme.current.background).testTag(TAG),
                    onLineSelected = { time -> selected += time; position.longValue = time },
                    listState = state, browseState = browse)
            }
        }
        for (mode in listOf(ThorAppearance.Day, ThorAppearance.Night)) {
            compose.runOnIdle { appearance.value = mode; position.longValue = 2_500; selected.clear() }
            compose.waitForIdle()
            val primary = compose.onNodeWithText(sentence, useUnmergedTree = true).assertIsDisplayed()
            val translated = compose.onNodeWithText(translation, useUnmergedTree = true).assertIsDisplayed()
            val row = compose.onNode(hasText(sentence) and hasClickAction())
            val primaryBounds = primary.fetchSemanticsNode().boundsInRoot
            val translatedBounds = translated.fetchSemanticsNode().boundsInRoot
            val rowBounds = row.fetchSemanticsNode().boundsInRoot
            val density = compose.density.density
            assertTrue("The artificial primary sentence must wrap", primaryBounds.height > 60 * density)
            assertTrue("Translation must remain below primary text", translatedBounds.top - primaryBounds.bottom >= 4 * density)
            assertTrue("The row must include padding below its translation", rowBounds.bottom - translatedBounds.bottom >= 7 * density)
            compose.runOnIdle {
                val info = list.layoutInfo.visibleItemsInfo
                val current = info.single { it.index == 2 }
                val next = info.firstOrNull { it.index == 3 }
                if (next != null) assertTrue("Neighbour rows need the designed breathing room",
                    next.offset - current.offset - current.size >= 15 * density)
            }
            val image = compose.onNodeWithTag(TAG).captureToImage().asAndroidBitmap()
            val name = if (mode == ThorAppearance.Day) "thor-lyric-layout-day.png" else "thor-lyric-layout-night.png"
            val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
            file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            // Tap the bottom padding, beyond both Text nodes, to verify the full row hit area.
            row.performTouchInput { click(Offset(width * .5f, height - 2f)) }
            compose.runOnIdle {
                assertEquals(listOf(2_000L), selected)
                assertTrue(browse.canFollow)
            }
        }
    }

    private fun showLyrics(reduceMotion: Boolean = true) {
        compose.mainClock.autoAdvance = true
        compose.setContent {
            ThorTheme(ThorPreferences(reduceMotion = reduceMotion)) {
                val state = rememberLazyListState()
                val view = LocalView.current
                SideEffect { list = state; inputView = view }
                ThorUpperLyrics(
                    data = lyrics,
                    positionProvider = { position.longValue },
                    active = active.value,
                    modifier = Modifier.width(360.dp).height(320.dp).testTag(TAG),
                    onLineSelected = { time -> selected += time; position.longValue = time },
                    listState = state,
                    browseState = browse,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun slowDragUp() {
        compose.onNodeWithTag(TAG).performTouchInput {
            swipe(Offset(width * .5f, height * .8f), Offset(width * .5f, height * .25f), 900)
        }
    }

    private fun awaitBrowsingAtRest() {
        compose.waitUntil(timeoutMillis = 5_000) {
            browse.isBrowsing && !browse.isTouching && !browse.isDragging &&
                !browse.isScrolling && browse.returnAtMs != null
        }
        compose.waitForIdle()
    }

    private fun assertBrowsing() {
        compose.onNodeWithTag(TAG).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "浏览歌词"))
    }

    private fun assertFollowing() {
        compose.onNodeWithTag(TAG).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "跟随播放"))
    }

    private fun lineText(index: Int) = "QA line " + index.toString().padStart(2, '0')

    private companion object { const val TAG = "thor-upper-lyrics-test" }
}

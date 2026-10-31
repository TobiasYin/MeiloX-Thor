package com.ljyh.mei.thor.upper

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.thor.model.ThorAppearance
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.thor.theme.LocalThorTheme
import com.ljyh.mei.thor.theme.ThorTheme
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
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
    private lateinit var scrollScope: CoroutineScope
    private val lyrics = LyricData(
        source = LyricSource.NetEaseCloudMusic,
        lyricLine = SyncedLyrics(lines = List(30) { index ->
            SyncedLine(lineText(index), null, index * 1_000, (index + 1) * 1_000 - 1)
        }),
    )
    private val variableLyrics = lyrics.copy(lyricLine = SyncedLyrics(lines = List(30) { index ->
        when (index) {
            1 -> SyncedLine("海面上的风让故事继续", "The story continues.", 1_000, 1_999)
            2 -> SyncedLine(VARIABLE_MAIN, "Follow the coast and let the evening breeze carry the day away.", 2_000, 2_999)
            3 -> SyncedLine("另一束光落在海面", VARIABLE_TRANSLATION, 3_000, 3_999)
            4 -> SyncedLine("We keep going", "我们继续向前。", 4_000, 4_999)
            else -> SyncedLine(lineText(index), null, index * 1_000, (index + 1) * 1_000 - 1)
        }
    }))

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
            // A realized neighbour animates; a distant line intentionally snaps into view.
            instrumentation.runOnMainSync { position.longValue = 4_500L }
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
        compose.runOnIdle {
            position.longValue = 24_500L
            assertTrue(browse.resumeIfIdle(checkNotNull(browse.returnAtMs)))
        }
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
                    listState = state, browseState = browse, playing = false)
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
            val currentStyle = textLayout(primary).layoutInput.style
            val neighbourStyle = textLayout(compose.onNodeWithText("把今日留在路上", useUnmergedTree = true)).layoutInput.style
            assertEquals("Focused lyrics must retain their weight emphasis", FontWeight.Medium, currentStyle.fontWeight)
            assertEquals("Adjacent lyrics must not acquire the focused weight", FontWeight.Normal, neighbourStyle.fontWeight)
            assertTrue("Focused lyrics must retain a distinct readable color", currentStyle.color != neighbourStyle.color)
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

    @Test(timeout = 25_000)
    fun differentlySizedBilingualLinesFollowWithoutOvershootingAndCorrectingBack() {
        showFixture(variableLyrics, reduceMotion = false)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.mainClock.autoAdvance = false
        try {
            // Each destination is already realized. This observes actual native list geometry,
            // rather than reproducing the production target-offset formula in the test.
            for (target in listOf(3, 2, 3, 4, 3)) {
                val offsets = mutableListOf<Int>()
                instrumentation.runOnMainSync {
                    val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
                    assertTrue("Adjacent target $target must already be realized", item != null)
                    offsets += checkNotNull(item).offset
                    position.longValue = target * 1_000L + 500L
                }
                var animated = false
                var idleFrames = 0
                for (frame in 0 until 100) {
                    compose.mainClock.advanceTimeByFrame()
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync {
                        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
                        assertTrue("Following must keep its adjacent target realized", item != null)
                        offsets += checkNotNull(item).offset
                        animated = animated || list.isScrollInProgress
                        idleFrames = if (!list.isScrollInProgress) idleFrames + 1 else 0
                    }
                    if (frame >= 4 && idleFrames >= 3) break
                }
                assertTrue("The regression must exercise a real follow animation", animated)
                assertTrue("The follow animation must settle", idleFrames >= 3)
                val first = offsets.first()
                val last = offsets.last()
                assertTrue("The test must actually move target $target", abs(last - first) > 4)
                for ((before, after) in offsets.zipWithNext()) {
                    if (last < first) {
                        assertTrue("Target $target reversed downwards: $offsets", after <= before + 2)
                        assertTrue("Target $target overshot its final anchor: $offsets", after >= last - 2)
                    } else {
                        assertTrue("Target $target reversed upwards: $offsets", after >= before - 2)
                        assertTrue("Target $target overshot its final anchor: $offsets", after <= last + 2)
                    }
                }
                instrumentation.runOnMainSync {
                    val info = list.layoutInfo
                    val item = info.visibleItemsInfo.single { it.index == target }
                    assertTrue("The focused row must remain inside the visible viewport",
                        item.offset < info.viewportEndOffset && item.offset + item.size > info.viewportStartOffset)
                    assertTrue(selected.isEmpty())
                    assertTrue(browse.canFollow)
                }
            }
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForIdle()
    }

    @Test
    fun upcomingTranslationIsAlreadyVisibleAndFocusDoesNotResizeItsRow() {
        showFixture(variableLyrics, reduceMotion = true, heightDp = 400)
        val primary = "另一束光落在海面"
        val row = compose.onNode(hasText(primary) and hasClickAction())
        val translated = compose.onNodeWithText(VARIABLE_TRANSLATION, useUnmergedTree = true)
        translated.assertIsDisplayed()
        val beforeRow = row.fetchSemanticsNode().boundsInRoot
        val beforeTranslation = translated.fetchSemanticsNode().boundsInRoot
        val beforeSize = compose.runOnIdle { list.layoutInfo.visibleItemsInfo.single { it.index == 3 }.size }
        assertTrue("Upcoming translation must occupy layout space before its timestamp", beforeTranslation.height > 0f)
        val parent = compose.onNodeWithTag(TAG)
        val bitmap = parent.captureToImage().asAndroidBitmap()
        val origin = parent.fetchSemanticsNode().boundsInRoot
        assertTrue("The upcoming translation must already draw actual ink",
            maximumInkContrast(bitmap, beforeTranslation.translate(-origin.left, -origin.top)) > .1f)

        compose.runOnIdle { position.longValue = 3_500L }
        compose.waitForIdle()
        val afterRow = row.fetchSemanticsNode().boundsInRoot
        val afterTranslation = translated.fetchSemanticsNode().boundsInRoot
        val afterSize = compose.runOnIdle { list.layoutInfo.visibleItemsInfo.single { it.index == 3 }.size }
        assertEquals("Highlight must not resize a translated lazy-list item", beforeSize, afterSize)
        assertEquals("Translation must not be inserted or displaced when its line becomes current",
            beforeTranslation.top - beforeRow.top, afterTranslation.top - afterRow.top, 1f)
        assertEquals("Wrapping must be reserved before following begins",
            beforeTranslation.height, afterTranslation.height, 1f)
        compose.runOnIdle { assertTrue(selected.isEmpty()) }
    }

    @Test
    fun upperAndLowerEdgesActuallyFadeTheSameRenderedLineInDayAndNight() {
        val data = lyrics.copy(lyricLine = SyncedLyrics(lines = List(40) { index ->
            SyncedLine("MMMM $index", null, index * 1_000, (index + 1) * 1_000 - 1)
        }))
        val appearance = mutableStateOf(ThorAppearance.Day)
        // Keep the clock emphasis outside the viewport. Every sampled glyph therefore has the
        // same inactive style; a focus color or font change cannot masquerade as edge fading.
        position.longValue = 35_500L
        browse.onDragStarted(SystemClock.uptimeMillis())
        browse.onTouchStarted(SystemClock.uptimeMillis())
        browse.onDragStopped(SystemClock.uptimeMillis())
        compose.setContent {
            ThorTheme(ThorPreferences(appearance = appearance.value, reduceMotion = true)) {
                val state = rememberLazyListState()
                val scope = rememberCoroutineScope()
                SideEffect { list = state; scrollScope = scope }
                // Isolate the actual production fade from palette contrast. Black outside the
                // composited list also makes an erased glyph distinguishable from a white one.
                CompositionLocalProvider(LocalThorTheme provides LocalThorTheme.current.copy(
                    background = Color.Black, text = Color.White, secondary = Color.White)) {
                    Box(Modifier.width(360.dp).height(320.dp).background(Color.Black).testTag(TAG)) {
                        ThorUpperLyrics(data, { position.longValue }, true, Modifier.fillMaxSize(),
                            listState = state, browseState = browse, playing = false)
                    }
                }
            }
        }
        compose.waitForIdle()
        try {
            for (mode in listOf(ThorAppearance.Day, ThorAppearance.Night)) {
                compose.runOnIdle {
                    appearance.value = mode
                    scrollScope.launch { list.scrollToItem(10) }
                }
                compose.waitForIdle()
                val viewport = compose.onNodeWithTag(TAG)
                val density = compose.density.density
                val glyph = compose.onNodeWithText("MMMM 10", useUnmergedTree = true)
                val height = viewport.fetchSemanticsNode().boundsInRoot.height
                val textHeight = glyph.fetchSemanticsNode().boundsInRoot.height
                val desiredTops = listOf((height - textHeight) / 2f, 2 * density, height - textHeight - 2 * density)
                val brightness = desiredTops.map { desiredTop ->
                    val viewportBounds = viewport.fetchSemanticsNode().boundsInRoot
                    val textBounds = glyph.fetchSemanticsNode().boundsInRoot
                    compose.runOnIdle {
                        scrollScope.launch { list.scrollBy(textBounds.top - viewportBounds.top - desiredTop) }
                    }
                    compose.waitForIdle()
                    val actualViewport = viewport.fetchSemanticsNode().boundsInRoot
                    val actualText = glyph.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    assertEquals("The sampled text must be placed completely inside the viewport",
                        desiredTop, actualText.top - actualViewport.top, 2f)
                    assertTrue(actualText.bottom <= actualViewport.bottom + 1f)
                    meanRenderedInk(viewport.captureToImage().asAndroidBitmap(),
                        actualText.translate(-actualViewport.left, -actualViewport.top))
                }
                val center = brightness[0]
                assertTrue("The same central line must have readable solid ink", center > .3f)
                assertTrue("The upper edge must actually reduce rendered opacity: $brightness", brightness[1] < center * .9f)
                assertTrue("The lower edge must actually reduce rendered opacity: $brightness", brightness[2] < center * .9f)
                compose.runOnIdle { assertTrue(selected.isEmpty()) }
            }
        } finally { compose.runOnIdle { browse.onHidden() } }
    }

    @Test
    fun fullUpperScreenUsesTheExtraHeightForRealBilingualText() {
        val data = lyrics.copy(lyricLine = SyncedLyrics(lines = List(30) { index ->
            SyncedLine("海风 $index", "Evening breeze $index", index * 1_000, (index + 1) * 1_000 - 1)
        }))
        compose.setContent {
            ThorUpperScreen(
                item = ThorItem(ThorItemKey.Song(1, local = true), "QA coast", "QA artist"),
                playing = true,
                preferences = ThorPreferences(appearance = ThorAppearance.Day, reduceMotion = true),
                airflow = 0f,
                lyricData = data,
                positionProvider = { position.longValue },
                playbackScene = true,
                modifier = Modifier.width(800.dp).height(450.dp).testTag("full-upper-lyrics"),
            )
        }
        compose.waitForIdle()
        val upper = compose.onNodeWithTag("full-upper-lyrics").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("thor-upper-lyrics-viewport").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue("The real upper-screen lyrics must use at least two thirds of its height",
            viewport.height >= upper.height * .67f)
        val textBounds = compose.onAllNodes(hasText("海风", substring = true) or
            hasText("Evening breeze", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.boundsInRoot }
        val fullyVisible = textBounds.filter { it.top >= viewport.top && it.bottom <= viewport.bottom }
        assertTrue("The larger viewport must show at least three real bilingual pairs, not empty row slots",
            fullyVisible.size >= 6)
        // The former upper layout left only about 58% of the screen height for lyrics.
        // Count actual text beyond that vertical budget, without copying today's layout math.
        val oldHeightBudget = upper.height * .59f
        assertTrue("At least one additional actual text line must fit beyond the former viewport budget",
            fullyVisible.any { it.bottom > viewport.top + oldHeightBudget })
    }

    private fun showFixture(data: LyricData, reduceMotion: Boolean, heightDp: Int = 360) {
        compose.mainClock.autoAdvance = true
        compose.setContent {
            ThorTheme(ThorPreferences(appearance = ThorAppearance.Day, reduceMotion = reduceMotion)) {
                val state = rememberLazyListState()
                val scope = rememberCoroutineScope()
                val view = LocalView.current
                SideEffect { list = state; scrollScope = scope; inputView = view }
                CompositionLocalProvider(LocalThorTheme provides LocalThorTheme.current.copy(
                    background = Color.Black, text = Color.White, secondary = Color.White)) {
                    Box(Modifier.width(320.dp).height(heightDp.dp).background(Color.Black).testTag(TAG)) {
                        ThorUpperLyrics(data, { position.longValue }, true, Modifier.fillMaxSize(),
                            onLineSelected = { time -> selected += time; position.longValue = time },
                            listState = state, browseState = browse, playing = false)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun maximumInkContrast(bitmap: Bitmap, bounds: Rect): Float {
        val background = bitmap.getPixel(0, 0)
        val red = android.graphics.Color.red(background)
        val green = android.graphics.Color.green(background)
        val blue = android.graphics.Color.blue(background)
        var maximum = 0
        for (y in floor(bounds.top).toInt().coerceAtLeast(0) until ceil(bounds.bottom).toInt().coerceAtMost(bitmap.height)) {
            for (x in floor(bounds.left).toInt().coerceAtLeast(0) until ceil(bounds.right).toInt().coerceAtMost(bitmap.width)) {
                val pixel = bitmap.getPixel(x, y)
                maximum = maxOf(maximum, abs(android.graphics.Color.red(pixel) - red),
                    abs(android.graphics.Color.green(pixel) - green), abs(android.graphics.Color.blue(pixel) - blue))
            }
        }
        return maximum / 255f
    }

    private fun meanRenderedInk(bitmap: Bitmap, bounds: Rect): Float {
        var total = 0L
        var inkPixels = 0
        for (y in floor(bounds.top).toInt().coerceAtLeast(0) until ceil(bounds.bottom).toInt().coerceAtMost(bitmap.height)) {
            for (x in floor(bounds.left).toInt().coerceAtLeast(0) until ceil(bounds.right).toInt().coerceAtMost(bitmap.width)) {
                val pixel = bitmap.getPixel(x, y)
                val value = maxOf(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel))
                if (value > 5) { total += value; inkPixels++ }
            }
        }
        assertTrue("The sample must contain real glyph pixels", inkPixels > 10)
        return total.toFloat() / inkPixels / 255f
    }

    private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        return layouts.single()
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
                    playing = false, // This fixture tests scroll ownership, not decorative flow frames.
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

    private companion object {
        const val TAG = "thor-upper-lyrics-test"
        const val VARIABLE_MAIN = "沿着海岸缓缓前行，让晚风带走今天的喧嚣。"
        const val VARIABLE_TRANSLATION = "Another light moves across the sea, quietly marking the way home."
    }
}

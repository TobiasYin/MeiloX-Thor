package com.ljyh.mei.thor.upper

import android.graphics.Bitmap
import android.graphics.Rect as AndroidRect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.thor.artwork.CaseAmbientPalette
import com.ljyh.mei.thor.model.ThorAppearance
import com.ljyh.mei.thor.model.ThorBackgroundIntensity
import com.ljyh.mei.thor.model.ThorBackgroundMode
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.thor.model.ThorThemePack
import com.ljyh.mei.thor.theme.LocalThorTheme
import com.ljyh.mei.thor.theme.ThorTheme
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual hardware-window pixels; synthetic colors/text, no account, network, or player. */
@RunWith(AndroidJUnit4::class)
class ThorPlaybackBackdropInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val preferences = mutableStateOf(ThorPreferences(
        appearance = ThorAppearance.Day,
        reduceMotion = true,
        backgroundIntensity = ThorBackgroundIntensity.High,
    ))
    private val active = mutableStateOf(true)
    private val playing = mutableStateOf(false)
    private val diagnostics = ThorBackdropDiagnostics()
    private lateinit var contentView: View
    private var captureBounds = Rect.Zero
    private var textArgb = 0
    private var secondaryArgb = 0
    private var backgroundArgb = 0

    @Test(timeout = 120_000)
    fun allSixMaterialAppearancesActuallyPaintAndKeepTheLyricZoneReadable() {
        showBackdrop()
        for (pack in ThorThemePack.entries) {
            for (appearance in listOf(ThorAppearance.Day, ThorAppearance.Night)) {
                for (mode in ThorBackgroundMode.entries) {
                    compose.runOnIdle {
                        preferences.value = preferences.value.copy(theme = pack, appearance = appearance,
                            backgroundMode = mode, reduceTransparency = false)
                    }
                    compose.waitForIdle()
                    val image = copyWindowPixels()
                    try {
                        val label = "${pack.name}-${appearance.name}-${mode.name}-high"
                        assertEquals(label, if (mode == ThorBackgroundMode.Clean) "clean" else "agsl",
                            diagnostics.backend)
                        assertTrue("The actual Canvas must draw", diagnostics.draws.get() > 0)
                        assertLyricZoneContrast(image, label)
                        if (mode == ThorBackgroundMode.Clean) {
                            assertTrue("Clean must paint the theme background, including its corners",
                                distanceFromColor(image, backgroundArgb, .01f, .99f) <= 1.0 / 255)
                        } else {
                            assertTrue("$label must contain a visible optical field",
                                luminanceRange(image, .04f, .49f) > .005)
                        }
                        save(image, "background-$label")
                    } finally { image.recycle() }
                }
            }
        }
        assertEquals("Reduced motion must not start a decorative clock", 0L, diagnostics.frameTicks.get())
    }

    @Test(timeout = 90_000)
    fun flowChangesRealPixelsThenPauseVisibilityAndReducedMotionStopDecorativeFrames() {
        preferences.value = preferences.value.copy(theme = ThorThemePack.Glass,
            appearance = ThorAppearance.Night)
        showBackdrop()
        copyWindowPixels().recycle()
        compose.mainClock.autoAdvance = false
        try {
            mutate { preferences.value = preferences.value.copy(reduceMotion = false); playing.value = true }
            advance(1_000)
            val first = copyWindowPixels()
            advance(4_000)
            val flowing = copyWindowPixels()
            try {
                assertEquals("agsl", diagnostics.backend)
                assertTrue("Playing flow must receive actual frame callbacks", diagnostics.frameTicks.get() > 20)
                assertTrue("Flow must alter actual left-field pixels, not just a counter",
                    imageDifference(first, flowing, .04f, .49f) > .001)
                assertLyricZoneContrast(flowing, "flowing-night-high")
                save(first, "flow-phase-a"); save(flowing, "flow-phase-b")
            } finally { first.recycle(); flowing.recycle() }

            mutate { playing.value = false }
            advance(1_000) // Product coasting is approximately 480 ms, never an endless paused clock.
            val paused = copyWindowPixels()
            assertClockAndDrawsStayStopped("paused", 1_000)
            val pausedAgain = copyWindowPixels()
            try {
                assertEquals("A settled paused field must be pixel-stable", 0.0,
                    imageDifference(paused, pausedAgain), 0.0)
                save(pausedAgain, "flow-paused")
            } finally { paused.recycle(); pausedAgain.recycle() }

            mutate { active.value = false; playing.value = true }
            advance(64)
            val hiddenPhase = copyWindowPixels()
            assertClockAndDrawsStayStopped("inactive", 20_000)
            mutate { active.value = true }
            advance(16)
            val firstResumedFrame = copyWindowPixels()
            try {
                assertEquals("Resuming must not integrate twenty hidden seconds", 0.0,
                    imageDifference(hiddenPhase, firstResumedFrame), 0.0)
            } finally { hiddenPhase.recycle(); firstResumedFrame.recycle() }
            advance(1_000)

            mutate { preferences.value = preferences.value.copy(reduceMotion = true) }
            advance(64)
            copyWindowPixels().recycle()
            assertClockAndDrawsStayStopped("reduced motion", 1_000)

            // Static modes stay static even when the user has not enabled reduced motion.
            for (mode in listOf(ThorBackgroundMode.SoftGradient, ThorBackgroundMode.Clean)) {
                mutate { preferences.value = preferences.value.copy(backgroundMode = mode, reduceMotion = false) }
                advance(1_200)
                copyWindowPixels().recycle()
                assertClockAndDrawsStayStopped(mode.name, 1_000)
            }
        } finally {
            mutate { active.value = false; playing.value = false
                preferences.value = preferences.value.copy(reduceMotion = true) }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
    }

    @Test(timeout = 120_000)
    fun modesIntensityAndAllSixThemesKeepTheRealCoverAndLyricLayoutInPlace() {
        val lyrics = LyricData(source = LyricSource.NetEaseCloudMusic,
            lyricLine = SyncedLyrics(lines = List(12) { index ->
                SyncedLine("QA lyric ${index.toString().padStart(2, '0')} · 海风让故事继续",
                    "Synthetic translation, no account information.", index * 1_000, (index + 1) * 1_000 - 1)
            }))
        compose.setContent {
            val view = LocalView.current
            SideEffect { contentView = view }
            ThorUpperScreen(item = ThorItem(ThorItemKey.Destination("backdrop-qa"),
                "Synthetic album", "QA performer"), playing = false, preferences = preferences.value,
                airflow = 0f, lyricData = lyrics, positionProvider = { 2_500L }, playbackScene = true,
                modifier = Modifier.fillMaxSize().onGloballyPositioned { captureBounds = it.boundsInWindow() })
        }
        compose.waitForIdle()
        val cover = bounds("thor-cd-case")
        val lyric = bounds("thor-upper-lyrics-viewport")
        for (pack in ThorThemePack.entries) {
            for (appearance in listOf(ThorAppearance.Day, ThorAppearance.Night)) {
                for (mode in ThorBackgroundMode.entries) {
                    compose.runOnIdle { preferences.value = preferences.value.copy(theme = pack,
                        appearance = appearance, backgroundMode = mode,
                        backgroundIntensity = if (mode == ThorBackgroundMode.SoftGradient)
                            ThorBackgroundIntensity.Low else ThorBackgroundIntensity.High) }
                    compose.waitForIdle()
                    assertSameBounds("cover: $pack $appearance $mode", cover, bounds("thor-cd-case"))
                    assertSameBounds("lyrics: $pack $appearance $mode", lyric, bounds("thor-upper-lyrics-viewport"))
                    if (mode == ThorBackgroundMode.ArtworkFlow) {
                        val image = copyWindowPixels()
                        try { save(image, "upper-${pack.name}-${appearance.name}-synthetic") }
                        finally { image.recycle() }
                    }
                }
            }
        }
        // The scene uses a real lyric list rather than substituting geometry-only placeholders.
        compose.onNodeWithText("QA lyric 02 · 海风让故事继续").assertIsDisplayed()
    }

    private fun showBackdrop() {
        compose.setContent {
            ThorTheme(preferences.value) {
                val theme = LocalThorTheme.current
                val view = LocalView.current
                SideEffect {
                    contentView = view
                    textArgb = theme.text.toArgb(); secondaryArgb = theme.secondary.toArgb()
                    backgroundArgb = theme.background.toArgb()
                }
                ThorPlaybackBackdrop(SYNTHETIC_PALETTE, active.value, playing.value, preferences.value,
                    Modifier.fillMaxSize().testTag("backdrop").onGloballyPositioned {
                        captureBounds = it.boundsInWindow()
                    }, diagnostics)
            }
        }
        compose.waitForIdle()
    }

    private fun mutate(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun advance(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds)
        instrumentation.waitForIdleSync()
    }

    private fun assertClockAndDrawsStayStopped(reason: String, duration: Long) {
        val ticks = diagnostics.frameTicks.get()
        val draws = diagnostics.draws.get()
        advance(duration)
        assertEquals("$reason must stop its withFrameNanos loop", ticks, diagnostics.frameTicks.get())
        assertEquals("$reason must stop decorative invalidations", draws, diagnostics.draws.get())
    }

    /** PixelCopy reads the hardware shader result; software BitmapCanvas cannot verify AGSL. */
    private fun copyWindowPixels(): Bitmap {
        val committed = CountDownLatch(1)
        lateinit var rect: AndroidRect
        instrumentation.runOnMainSync {
            rect = AndroidRect(captureBounds.left.roundToInt(), captureBounds.top.roundToInt(),
                captureBounds.right.roundToInt(), captureBounds.bottom.roundToInt())
            assertTrue("The native fixture must have real area", rect.width() > 0 && rect.height() > 0)
            assertTrue("The fixture must use a hardware window", contentView.isHardwareAccelerated)
            contentView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            contentView.invalidate()
        }
        assertTrue("A native frame must reach the window surface", committed.await(5, TimeUnit.SECONDS))
        val image = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(compose.activity.window, rect, image, {
            result = it; copied.countDown()
        }, Handler(Looper.getMainLooper()))
        assertTrue("PixelCopy must complete", copied.await(5, TimeUnit.SECONDS))
        assertEquals("The actual window must be readable", PixelCopy.SUCCESS, result)
        return image
    }

    private fun assertLyricZoneContrast(image: Bitmap, label: String) {
        var minimum = Double.POSITIVE_INFINITY
        // The lyric column starts near 52%, before the quiet mask is fully opaque at 57%.
        sample(image, .52f, .97f) { pixel ->
            assertEquals("$label must draw an opaque backing", 255, android.graphics.Color.alpha(pixel))
            minimum = minOf(minimum, ColorUtils.calculateContrast(textArgb, pixel),
                ColorUtils.calculateContrast(secondaryArgb, pixel))
        }
        assertTrue("$label actual lyric-zone text contrast was $minimum, expected >= 4.5", minimum >= 4.5)
    }

    private fun sample(image: Bitmap, left: Float, right: Float, block: (Int) -> Unit) {
        for (column in 0..12) for (row in 0..10) {
            val x = ((left + (right - left) * column / 12) * (image.width - 1)).roundToInt()
            val y = ((.08f + .84f * row / 10) * (image.height - 1)).roundToInt()
            block(image.getPixel(x, y))
        }
    }

    private fun luminanceRange(image: Bitmap, left: Float, right: Float): Double {
        var low = 1.0; var high = 0.0
        sample(image, left, right) { val y = ColorUtils.calculateLuminance(it); low = minOf(low, y); high = maxOf(high, y) }
        return high - low
    }

    private fun distanceFromColor(image: Bitmap, color: Int, left: Float, right: Float): Double {
        var sum = 0.0; var count = 0
        sample(image, left, right) { sum += pixelDifference(it, color); count++ }
        return sum / count
    }

    private fun imageDifference(first: Bitmap, second: Bitmap, left: Float = .01f, right: Float = .99f): Double {
        assertEquals(first.width, second.width); assertEquals(first.height, second.height)
        var sum = 0.0; var count = 0
        for (column in 0..40) for (row in 0..24) {
            val x = ((left + (right - left) * column / 40) * (first.width - 1)).roundToInt()
            val y = ((.08f + .84f * row / 24) * (first.height - 1)).roundToInt()
            sum += pixelDifference(first.getPixel(x, y), second.getPixel(x, y)); count++
        }
        return sum / count
    }

    private fun pixelDifference(first: Int, second: Int): Double =
        (abs(android.graphics.Color.red(first) - android.graphics.Color.red(second)) +
            abs(android.graphics.Color.green(first) - android.graphics.Color.green(second)) +
            abs(android.graphics.Color.blue(first) - android.graphics.Color.blue(second))) / (3.0 * 255)

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun assertSameBounds(label: String, expected: Rect, actual: Rect) {
        assertEquals("$label left", expected.left.toDouble(), actual.left.toDouble(), 1.0)
        assertEquals("$label top", expected.top.toDouble(), actual.top.toDouble(), 1.0)
        assertEquals("$label right", expected.right.toDouble(), actual.right.toDouble(), 1.0)
        assertEquals("$label bottom", expected.bottom.toDouble(), actual.bottom.toDouble(), 1.0)
    }

    private fun save(image: Bitmap, name: String) {
        val directory = File(instrumentation.targetContext.cacheDir, "thor-backdrop-evidence").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private companion object {
        val SYNTHETIC_PALETTE = CaseAmbientPalette(Color(0xFFD6475B), Color(0xFF34A9B5), Color(0xFFE9B868))
    }
}

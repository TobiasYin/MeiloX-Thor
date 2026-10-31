package com.ljyh.mei.thor.artwork

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.thor.audio.AIRFLOW_PCM_UNIT
import com.ljyh.mei.thor.audio.ThorAirflowDetector
import com.ljyh.mei.thor.audio.airflowDefaultCalibration
import com.ljyh.mei.thor.model.ThorArtworkSpec
import com.ljyh.mei.thor.model.ThorThemePack
import com.ljyh.mei.thor.theme.LocalThorTheme
import com.ljyh.mei.thor.theme.thorThemeTokens
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic PCM -> production detector -> production Canvas; no AudioRecord, URI or account. */
@RunWith(AndroidJUnit4::class)
class ThorAirflowCaseInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private class Fixture(val reduceMotion: Boolean) {
        val strength = mutableFloatStateOf(0f)
        val visible = mutableStateOf(true)
        val tokens = thorThemeTokens(ThorThemePack.Ceramic, isDark = false, reduceMotion = reduceMotion)
        val detector = ThorAirflowDetector().apply {
            // AudioSource.MIC = 1: use exactly the production verified Thor defaults.
            check(applyCalibration(airflowDefaultCalibration(source = 1, isThor = true)))
        }
    }

    private data class Frame(val width: Int, val height: Int, val pixels: IntArray)

    private fun withCase(reduceMotion: Boolean, test: (Fixture) -> Unit) {
        val fixture = Fixture(reduceMotion)
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                // Resolved real tokens remove the shared theme transition as a source of pixels.
                CompositionLocalProvider(LocalThorTheme provides fixture.tokens) {
                    Box(Modifier.width(320.dp).height(280.dp).background(Color(0xFF14201B)).testTag(TAG)) {
                        ThorCdCase(
                            artwork = ThorArtworkSpec("pcm-case-fixture", null, null, "PCM", "Fixture"),
                            airflow = fixture.strength.floatValue,
                            visible = fixture.visible.value,
                            floating = false,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            // The only initial motion is the finite entrance; it must finish before baseline.
            advance(640)
            test(fixture)
        } finally {
            // Also stop the real frame loop when an assertion fails during positive input.
            compose.runOnUiThread { fixture.strength.floatValue = 0f; fixture.visible.value = false }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.mainClock.autoAdvance = true
        }
    }

    private fun advance(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
    }

    /** Balanced PCM16 pairs retain sub-milliscale RMS without a DC offset or isolated clicks. */
    private fun pcmAtRms(rms: Float): ShortArray {
        val codes = rms / AIRFLOW_PCM_UNIT
        val low = codes.toInt()
        val fraction = (codes * codes - low * low) / ((low + 1) * (low + 1) - low * low)
        val highPairs = (fraction * 320).roundToInt()
        return ShortArray(640) { index ->
            val magnitude = low + if (index / 2 < highPairs) 1 else 0
            (if (index % 2 == 0) magnitude else -magnitude).toShort()
        }
    }

    private fun feed(fixture: Fixture, pcm: ShortArray, blocks: Int): Float {
        var strength = 0f
        repeat(blocks) {
            strength = fixture.detector.process(pcm, pcm.size, compose.mainClock.currentTime).strength
            compose.runOnUiThread { fixture.strength.floatValue = strength }
            advance(40)
        }
        return strength
    }

    private fun capture(): Frame {
        val bitmap = compose.onNodeWithTag(TAG).captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Frame(bitmap.width, bitmap.height, pixels)
    }

    private fun assertStill(message: String, first: Frame, second: Frame) {
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        assertArrayEquals(message, first.pixels, second.pixels)
    }

    private fun assertMoved(message: String, first: Frame, second: Frame) {
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        val changed = first.pixels.indices.count { index ->
            val a = first.pixels[index]
            val b = second.pixels[index]
            max(abs(((a shr 16) and 255) - ((b shr 16) and 255)),
                max(abs(((a shr 8) and 255) - ((b shr 8) and 255)), abs((a and 255) - (b and 255)))) >= 8
        }
        val minimum = max(64, first.pixels.size / 1_000)
        assertTrue("$message: changed=$changed, required=$minimum", changed >= minimum)
    }

    @Test
    fun thorMicWeakAndStrongPcmMoveTheRenderedCaseAndSilenceEventuallyLeavesItStill() = withCase(false) { fixture ->
        val baseline = capture()
        advance(64)
        assertStill("No hover or unfinished entrance may contaminate baseline", baseline, capture())

        val weak = feed(fixture, pcmAtRms(.0002f), blocks = 16)
        assertTrue("Sustained low-gain Thor MIC input must cross the detector gate", weak > 0f)
        val weakFrame = capture()
        assertMoved("Weak detector output must rotate the actual case", baseline, weakFrame)

        val strong = feed(fixture, pcmAtRms(.052114f), blocks = 7)
        assertTrue("Stronger PCM must produce stronger detector output", strong > weak)
        val strongFrame = capture()
        assertMoved("Strong input must also change the rendered pose", weakFrame, strongFrame)
        advance(64)
        assertMoved("Constant positive strength must keep rendering new poses", strongFrame, capture())

        assertEquals(0f, feed(fixture, ShortArray(640), blocks = 1), 0f)
        advance(5_000)
        val settled = capture()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertStill("After silence, damping and face settling must finish", settled, capture())
    }

    @Test
    fun reducedMotionKeepsTheRenderedCaseStillEvenWhenTheDetectorReportsStrongAirflow() = withCase(true) { fixture ->
        val baseline = capture()
        val strong = feed(fixture, pcmAtRms(.052114f), blocks = 12)
        assertTrue("This assertion must exercise real positive detector output", strong > .9f)
        advance(640)
        assertStill("Reduced motion must suppress input-driven rotation", baseline, capture())
    }

    private companion object { const val TAG = "thor-airflow-case-render" }
}

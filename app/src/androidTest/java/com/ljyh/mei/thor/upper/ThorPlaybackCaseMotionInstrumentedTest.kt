package com.ljyh.mei.thor.upper

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.thor.artwork.CaseMotionPhase
import com.ljyh.mei.thor.artwork.CasePose
import com.ljyh.mei.thor.artwork.LocalThorCaseDiagnostics
import com.ljyh.mei.thor.artwork.ThorCaseDiagnostics
import com.ljyh.mei.thor.artwork.ThorCaseFrame
import com.ljyh.mei.thor.model.ThorAppearance
import com.ljyh.mei.thor.model.ThorBackgroundMode
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.model.ThorPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max

/** Production Upper -> CD integration; synthetic airflow, no microphone, URI, player, or account. */
@RunWith(AndroidJUnit4::class)
class ThorPlaybackCaseMotionInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private class Fixture(initialPlaying: Boolean) {
        val playing = mutableStateOf(initialPlaying)
        val airflow = mutableFloatStateOf(0f)
        val visible = mutableStateOf(true)
        val playbackScene = mutableStateOf(true)
        val preferences = mutableStateOf(ThorPreferences(appearance = ThorAppearance.Night,
            airflowEnabled = true, backgroundMode = ThorBackgroundMode.Clean))
        val diagnostics = ThorCaseDiagnostics()
    }

    @Test(timeout = 30_000)
    fun playingRejectsFullRawAirflowAndRendersOnlySmallSlowBreathingInPlayerAndBrowse() =
        withUpper(initialPlaying = true) { fixture ->
            compose.runOnUiThread { fixture.airflow.floatValue = 1f }
            advance(640)
            val trace = List(32) { advance(250); frame(fixture) }
            trace.forEach {
                assertFalse("Real Upper must disable airflow while music plays", it.airflowAllowed)
                assertTrue("Playing must enable the actual CD breathing", it.playbackBreathing)
                assertTrue("Raw airflow=1 must never enter spinning", it.phase != CaseMotionPhase.Airflow)
            }
            val yawRange = range(trace.map { it.pose.yawDegrees })
            val pitchRange = range(trace.map { it.pose.pitchDegrees })
            val rollRange = range(trace.map { it.pose.rollDegrees })
            assertTrue("The rendered CD must have visible vertical breathing", range(trace.map { it.bobDp }) > 1.0)
            assertTrue("Breathing stays a small vertical offset", trace.all { abs(it.bobDp) < 10.0 })
            assertTrue("Breathing includes a real angular change", max(yawRange, max(pitchRange, rollRange)) > .2)
            assertTrue("Breathing must not accumulate revolutions", yawRange < 12 && pitchRange < 8 && rollRange < 8)
            val first = capture()
            advance(1_100)
            assertPixelsMoved("The actual Canvas must move with breathing", first, capture())

            // Album browsing is also an upper music scene while the same player remains active.
            compose.runOnUiThread { fixture.playbackScene.value = false }
            repeat(6) {
                advance(200)
                val current = frame(fixture)
                assertFalse("Browse must retain the playing airflow gate", current.airflowAllowed)
                assertTrue("Browse keeps the same playback breathing", current.playbackBreathing)
                assertTrue(current.phase != CaseMotionPhase.Airflow)
            }
        }

    @Test(timeout = 30_000)
    fun startingPlaybackInterruptsAnAlreadySpinningBlownCaseWithoutResidualRevolutions() =
        withUpper(initialPlaying = false) { fixture ->
            val initial = frame(fixture).pose.yawDegrees
            compose.runOnUiThread { fixture.airflow.floatValue = 1f }
            advance(2_000)
            val spinning = frame(fixture)
            assertTrue("Precondition: actual paused Upper accepts blowing", spinning.airflowAllowed)
            assertEquals(CaseMotionPhase.Airflow, spinning.phase)
            assertTrue("Precondition: the real case has completed a rotation",
                spinning.pose.yawDegrees - initial > 360)

            compose.runOnUiThread { fixture.playing.value = true }
            val trace = List(30) { advance(100); frame(fixture) }
            trace.forEach {
                assertFalse(it.airflowAllowed)
                assertTrue("Playback must cancel the existing airflow phase", it.phase != CaseMotionPhase.Airflow)
                assertTrue("No retained high-speed microphone revolution may survive playback",
                    abs(it.pose.yawDegrees - spinning.pose.yawDegrees) < 120)
            }
            assertEquals("Only breathing remains after face settling", CaseMotionPhase.Idle, trace.last().phase)
            assertTrue(trace.last().playbackBreathing)
            assertEquals("This must exercise a continuously held raw source", 1f, fixture.airflow.floatValue, 0f)
        }

    @Test(timeout = 30_000)
    fun pausingEasesTheRenderedPoseBackThenStopsItsFrameLoop() =
        withUpper(initialPlaying = false) { fixture ->
            val resting = frame(fixture).pose
            compose.runOnUiThread { fixture.playing.value = true }
            var peak: ThorCaseFrame? = null
            repeat(30) {
                if (peak == null) {
                    advance(100)
                    frame(fixture).takeIf { abs(it.bobDp) > 1.0 }?.let { peak = it }
                }
            }
            val before = requireNotNull(peak) { "Playing must first produce a measurable vertical offset" }
            compose.runOnUiThread { fixture.playing.value = false }
            advance(32)
            val releasing = frame(fixture)
            assertFalse(releasing.playbackBreathing)
            assertTrue("Pause must ease away from its existing offset, not snap it to zero",
                abs(releasing.bobDp) > abs(before.bobDp) * .5)
            assertTrue("The first paused frame must remain continuous", abs(releasing.bobDp - before.bobDp) < .6)
            advance(5_000)
            val stopped = frame(fixture)
            assertEquals(0.0, stopped.bobDp, .01)
            assertPoseNear(resting, stopped.pose)
            val still = capture()
            val ticks = fixture.diagnostics.frameTicks.get()
            advance(1_200)
            assertEquals("Paused, settled CD must stop withFrameNanos", ticks, fixture.diagnostics.frameTicks.get())
            assertPixelsStill("Paused CD must be pixel-stable", still, capture())
        }

    @Test(timeout = 30_000)
    fun hiddenAndReducedMotionStopFramesWhileReducedMotionStillAllowsDirectFaceAccess() =
        withUpper(initialPlaying = true) { fixture ->
            compose.runOnUiThread { fixture.airflow.floatValue = 1f }
            advance(1_200)
            assertTrue(fixture.diagnostics.frameTicks.get() > 0)
            compose.runOnUiThread { fixture.visible.value = false }
            advance(64)
            assertFalse(frame(fixture).active)
            val hiddenTicks = fixture.diagnostics.frameTicks.get()
            advance(1_200)
            assertEquals("Hidden upper must stop its CD frame loop", hiddenTicks, fixture.diagnostics.frameTicks.get())

            compose.runOnUiThread {
                fixture.preferences.value = fixture.preferences.value.copy(reduceMotion = true)
                fixture.visible.value = true
            }
            advance(64)
            val reduced = frame(fixture)
            assertTrue(reduced.active)
            assertTrue(reduced.reducedMotion)
            assertEquals(0.0, reduced.bobDp, 0.0)
            val reducedPixels = capture()
            val reducedTicks = fixture.diagnostics.frameTicks.get()
            advance(1_200)
            assertEquals("Reduced motion must stop breathing and airflow clocks", reducedTicks,
                fixture.diagnostics.frameTicks.get())
            assertPixelsStill("Reduced motion must produce a stable image", reducedPixels, capture())

            compose.onNodeWithTag(CASE).performTouchInput {
                down(center)
                moveTo(center + Offset(width * .2f, 0f), 180)
                up()
            }
            advance(64)
            val afterTouch = frame(fixture)
            assertEquals("Reduced-motion drag preserves discrete access to the reverse face", 180.0,
                abs(afterTouch.pose.yawDegrees - reduced.pose.yawDegrees), .1)
            assertEquals("Direct reduced-motion interaction must not start an automatic frame clock",
                reducedTicks, fixture.diagnostics.frameTicks.get())
            assertTrue(afterTouch.phase != CaseMotionPhase.Airflow)
        }

    @Test(timeout = 30_000)
    fun touchTakesOwnershipWhilePlayingAndReleaseDoesNotResumeTheHeldRawAirflow() =
        withUpper(initialPlaying = true) { fixture ->
            compose.runOnUiThread { fixture.airflow.floatValue = 1f }
            advance(800)
            val before = frame(fixture)
            val case = compose.onNodeWithTag(CASE)
            case.performTouchInput {
                down(center)
                moveTo(center + Offset(width * .24f, -height * .08f), 180)
            }
            try {
                advance(100)
                val dragged = frame(fixture)
                assertEquals(CaseMotionPhase.Dragging, dragged.phase)
                assertTrue("Playing must preserve real horizontal direct manipulation",
                    abs(dragged.pose.yawDegrees - before.pose.yawDegrees) > 45)
                assertTrue("The direct touch must take the lifted model pose", dragged.pose.lift > .2)
                assertFalse(dragged.airflowAllowed)
            } finally { case.performTouchInput { up() } }
            repeat(20) {
                advance(200)
                val current = frame(fixture)
                assertFalse(current.airflowAllowed)
                assertTrue("Held raw airflow must not recapture the model after touch release",
                    current.phase != CaseMotionPhase.Airflow)
            }
            assertEquals(CaseMotionPhase.Idle, frame(fixture).phase)
            assertTrue(frame(fixture).playbackBreathing)
        }

    private fun withUpper(initialPlaying: Boolean, test: (Fixture) -> Unit) {
        val fixture = Fixture(initialPlaying)
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                CompositionLocalProvider(LocalThorCaseDiagnostics provides fixture.diagnostics) {
                    ThorUpperScreen(item = ThorItem(ThorItemKey.Album(9_127L, local = true),
                        "Motion QA", "Synthetic album"), playing = fixture.playing.value,
                        preferences = fixture.preferences.value, airflow = fixture.airflow.floatValue,
                        playbackScene = fixture.playbackScene.value, visible = fixture.visible.value,
                        modifier = Modifier.fillMaxSize())
                }
            }
            advance(640) // Finish the actual, finite case entrance before observing the physics.
            test(fixture)
        } finally {
            compose.runOnUiThread {
                fixture.airflow.floatValue = 0f
                fixture.playing.value = false
                fixture.visible.value = false
                fixture.preferences.value = fixture.preferences.value.copy(reduceMotion = true)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.mainClock.autoAdvance = true
        }
    }

    private fun advance(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
    }

    private fun frame(fixture: Fixture): ThorCaseFrame =
        requireNotNull(fixture.diagnostics.latest) { "The production CD must publish its rendered pose" }

    private fun range(values: List<Double>): Double = values.max() - values.min()

    private fun assertPoseNear(expected: CasePose, actual: CasePose) {
        assertEquals(expected.yawDegrees, actual.yawDegrees, .05)
        assertEquals(expected.pitchDegrees, actual.pitchDegrees, .05)
        assertEquals(expected.rollDegrees, actual.rollDegrees, .05)
        assertEquals(expected.lift, actual.lift, .001)
    }

    private data class Pixels(val width: Int, val height: Int, val argb: IntArray)

    private fun capture(): Pixels {
        val image = compose.onNodeWithTag(CASE).captureToImage().asAndroidBitmap()
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        return Pixels(image.width, image.height, pixels)
    }

    private fun assertPixelsStill(message: String, first: Pixels, second: Pixels) {
        assertEquals(first.width, second.width); assertEquals(first.height, second.height)
        assertTrue(message, first.argb.contentEquals(second.argb))
    }

    private fun assertPixelsMoved(message: String, first: Pixels, second: Pixels) {
        assertEquals(first.width, second.width); assertEquals(first.height, second.height)
        val changed = first.argb.indices.count { index ->
            val a = first.argb[index]; val b = second.argb[index]
            max(abs(((a shr 16) and 255) - ((b shr 16) and 255)),
                max(abs(((a shr 8) and 255) - ((b shr 8) and 255)), abs((a and 255) - (b and 255)))) >= 8
        }
        assertTrue("$message: changed=$changed", changed >= max(64, first.argb.size / 1_000))
    }

    private companion object { const val CASE = "thor-cd-case" }
}

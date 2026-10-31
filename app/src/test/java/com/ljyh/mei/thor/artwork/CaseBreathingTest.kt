package com.ljyh.mei.thor.artwork

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseBreathingTest {
    @Test fun playbackBreathesInBothDirectionsWithinSmallPresentationOffsets() {
        val breathing = CaseBreathing()
        val samples = (0 until 1_200).map { breathing.advance(DT, enabled = true) }.drop(120)
        assertTrue(samples.all { abs(it.bobDp) <= 6.0 && abs(it.yawDegrees) <= 2.5 && abs(it.pitchDegrees) <= 1.0 && abs(it.rollDegrees) <= .6 })
        assertTrue(samples.maxOf { it.bobDp } > 5.8)
        assertTrue(samples.minOf { it.bobDp } < -5.8)
        assertTrue(samples.maxOf { it.yawDegrees } > 2.4)
        assertTrue(samples.minOf { it.yawDegrees } < -2.4)
        assertTrue(samples.maxOf { it.pitchDegrees } > .9)
        assertTrue(samples.minOf { it.pitchDegrees } < -.9)
        assertTrue(samples.maxOf { it.rollDegrees } > .55)
        assertTrue(samples.minOf { it.rollDegrees } < -.55)
    }

    @Test fun onsetAndOngoingMotionHaveNoAbruptFrameToFrameRotation() {
        val breathing = CaseBreathing()
        var previous = breathing.pose
        repeat(1_200) {
            val pose = breathing.advance(DT, enabled = true)
            assertTrue(abs(pose.yawDegrees - previous.yawDegrees) < .07)
            assertTrue(abs(pose.bobDp - previous.bobDp) < .2)
            assertTrue(abs(pose.pitchDegrees - previous.pitchDegrees) < .04)
            assertTrue(abs(pose.rollDegrees - previous.rollDegrees) < .025)
            previous = pose
        }
    }

    @Test fun pausingEasesTheOffsetsAwayAndEventuallyNeedsNoFrames() {
        val breathing = CaseBreathing()
        repeat(470) { breathing.advance(DT, enabled = true) }
        val before = breathing.pose
        assertTrue(breathing.needsFrame)
        val first = breathing.advance(DT, enabled = false)
        assertTrue(abs(first.bobDp - before.bobDp) < .4)
        assertTrue(abs(first.yawDegrees - before.yawDegrees) < .15)
        assertTrue(first != CaseBreathingPose())
        repeat(300) { breathing.advance(DT, enabled = false) }
        assertEquals(CaseBreathingPose(), breathing.pose)
        assertFalse(breathing.needsFrame)
    }

    @Test fun draggingSuppressesThePoseAndReleaseContinuesTheSameOscillatorPhase() {
        val dragged = CaseBreathing()
        val frozen = CaseBreathing()
        repeat(180) { dragged.advance(DT, true); frozen.advance(DT, true) }
        frozen.pause()
        repeat(200) { dragged.advance(DT, enabled = true, suppressed = true) }
        assertEquals(CaseBreathingPose(), dragged.pose)
        assertFalse(dragged.needsFrame)
        assertEquals(frozen.advance(DT, enabled = true), dragged.advance(DT, enabled = true))
    }

    @Test fun hiddenOrReducedMotionCleanupImmediatelyDropsResidualWorkWithoutResettingPhase() {
        val cleaned = CaseBreathing()
        val control = CaseBreathing()
        repeat(200) { cleaned.advance(DT, true); control.advance(DT, true) }
        cleaned.pause(); control.pause()
        repeat(500) { cleaned.advance(DT, enabled = false) }
        assertEquals(CaseBreathingPose(), cleaned.pose)
        assertFalse(cleaned.needsFrame)
        assertEquals(control.advance(DT, true), cleaned.advance(DT, true))
    }

    @Test fun decorativePoseDoesNotReplaceTheRealFaceOrDirectTouchLift() {
        val offset = CaseBreathingPose(bobDp = 6.0, yawDegrees = 2.5, pitchDegrees = 1.0, rollDegrees = .6)
        val back = CasePose(yawDegrees = 164.0, pitchDegrees = -8.0, rollDegrees = -5.0, lift = .5)
        val decorated = offset.applyTo(back)
        assertEquals(165.25, decorated.yawDegrees, .000001)
        assertEquals(-7.5, decorated.pitchDegrees, .000001)
        assertEquals(-4.7, decorated.rollDegrees, .000001)
        assertEquals(.5, decorated.lift, 0.0)
        val held = back.copy(lift = 1.0)
        assertEquals(held, offset.applyTo(held))
        assertEquals(back, CaseBreathingPose().applyTo(back))
    }

    @Test fun invalidAndLongFramesCannotJumpOrPoisonTheOscillator() {
        val breathing = CaseBreathing()
        for (seconds in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(CaseBreathingPose(), breathing.advance(seconds, enabled = true))
        }
        val bounded = CaseBreathing()
        assertEquals(bounded.advance(.05, true), breathing.advance(40.0, true))
        assertTrue(listOf(breathing.pose.bobDp, breathing.pose.yawDegrees, breathing.pose.pitchDegrees, breathing.pose.rollDegrees).all { it.isFinite() })
    }

    private companion object { const val DT = 1.0 / 60.0 }
}

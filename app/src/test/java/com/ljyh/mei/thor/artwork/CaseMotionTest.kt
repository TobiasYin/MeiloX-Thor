package com.ljyh.mei.thor.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

class CaseMotionTest {
    @Test fun airflowIsPositiveAndStrengthControlsSpeed() {
        val light = CaseMotion().apply { setAirflow(.2f) }
        val strong = CaseMotion().apply { setAirflow(.9f) }
        var previous = strong.angle
        repeat(360) {
            light.advance(1.0 / 60); strong.advance(1.0 / 60)
            assertTrue(strong.angle >= previous)
            assertTrue(strong.velocity in 0.0..600.00001)
            previous = strong.angle
        }
        assertTrue(strong.velocity > light.velocity * 3)
    }

    @Test fun releasingAirflowSettlesOnARealFrontOrBackDisplayAngle() {
        val motion = CaseMotion().apply { setAirflow(.8f) }
        repeat(130) { motion.advance(1.0 / 60) }
        motion.setAirflow(0f)
        repeat(900) { motion.advance(1.0 / 60) }
        assertEquals(CaseMotionPhase.Idle, motion.phase)
        val turns = (motion.angle + 16.0) / 180.0
        assertEquals(round(turns), turns, .000001)
        assertEquals(0.0, motion.velocity, .000001)
        assertEquals(6.0, motion.pitch, .000001)
    }

    @Test fun draggingPreemptsAirflowAndReleaseRetainsItsDirectionAfterBlowingStops() {
        val motion = CaseMotion().apply { setAirflow(1f) }
        repeat(90) { motion.advance(1.0 / 60) }
        motion.beginDrag()
        assertEquals(0.0, motion.velocity, 0.0)
        motion.dragBy(-.1, 2.0, .016)
        val dragged = motion.angle
        assertTrue(motion.velocity < 0)
        assertEquals(-18.0, motion.pitch, 0.0)
        motion.setAirflow(1f)
        motion.advance(.016)
        assertEquals(CaseMotionPhase.Dragging, motion.phase)
        assertEquals(dragged, motion.angle, 0.0)
        motion.setAirflow(0f)
        motion.endDrag()
        assertTrue(motion.velocity < 0)
        repeat(900) { motion.advance(1.0 / 60) }
        assertEquals(CaseMotionPhase.Idle, motion.phase)
        assertTrue(motion.angle.isFinite())
    }

    @Test fun constantAirflowResumesAfterDraggingWithoutAnotherSourceSample() {
        val motion = CaseMotion().apply { setAirflow(1f) }
        repeat(90) { motion.advance(1.0 / 60) }
        motion.beginDrag()
        motion.dragBy(-.1, 2.0, .016)
        val heldAngle = motion.angle
        repeat(60) { motion.advance(1.0 / 60) }
        assertEquals(CaseMotionPhase.Dragging, motion.phase)
        assertEquals(heldAngle, motion.angle, 0.0)
        motion.endDrag()
        assertEquals(CaseMotionPhase.Airflow, motion.phase)
        var previous = motion.angle
        repeat(360) {
            motion.advance(1.0 / 60)
            assertTrue(motion.angle >= previous)
            assertTrue(motion.velocity in 0.0..600.0)
            previous = motion.angle
        }
        assertTrue(motion.velocity > 599.9)
        assertEquals(6.0, motion.pitch, .000001)
    }

    @Test fun theLatestStrengthReceivedDuringTouchControlsResumedSpeed() {
        val motion = CaseMotion().apply { setAirflow(1f) }
        motion.beginDrag()
        motion.dragBy(-.1, 0.0, .016)
        val heldAngle = motion.angle
        val heldVelocity = motion.velocity
        motion.setAirflow(.9f)
        motion.setAirflow(.25f)
        motion.advance(.05)
        assertEquals(CaseMotionPhase.Dragging, motion.phase)
        assertEquals(heldAngle, motion.angle, 0.0)
        assertEquals(heldVelocity, motion.velocity, 0.0)
        motion.endDrag()
        repeat(600) { motion.advance(1.0 / 60) }
        assertEquals(CaseMotionPhase.Airflow, motion.phase)
        assertEquals(600.0 * 0.25.pow(1.45), motion.velocity, .000001)
    }

    @Test fun endedOrInvalidAirflowDuringTouchCannotRestoreAnOlderRequest() {
        for (strength in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val motion = CaseMotion().apply { setAirflow(1f) }
            motion.beginDrag()
            motion.dragBy(-.1, 0.0, .016)
            motion.setAirflow(strength)
            motion.endDrag()
            assertEquals(CaseMotionPhase.Settling, motion.phase)
            repeat(900) { motion.advance(1.0 / 60) }
            assertEquals(CaseMotionPhase.Idle, motion.phase)
            assertTrue(!motion.needsFrame)
        }
    }

    @Test fun canceledTouchResumesTheLatestRequestedAirflowAndRepeatedCleanupIsHarmless() {
        val motion = CaseMotion().apply { setAirflow(1f) }
        motion.beginDrag()
        motion.dragBy(-.1, 0.0, .016)
        motion.setAirflow(.6f)
        // ThorCdCase uses this stale-movement release for pointer cancellation cleanup.
        motion.endDrag(.1)
        assertEquals(CaseMotionPhase.Airflow, motion.phase)
        assertEquals(0.0, motion.velocity, 0.0)
        motion.endDrag(.1)
        repeat(360) { motion.advance(1.0 / 60) }
        assertEquals(CaseMotionPhase.Airflow, motion.phase)
        assertTrue(motion.velocity > 0)
        assertTrue(motion.velocity <= 600)
    }

    @Test fun pausingDuringTouchDiscardsAirflowAcrossCleanupAndLaterGestures() {
        val motion = CaseMotion().apply { setAirflow(1f) }
        motion.beginDrag()
        motion.dragBy(.1, 0.0, .016)
        motion.pause()
        val paused = motion.pose
        motion.endDrag()
        repeat(120) { motion.advance(1.0 / 60) }
        assertEquals(paused, motion.pose)
        assertTrue(!motion.needsFrame)
        motion.beginDrag()
        motion.dragBy(.1, 0.0, .016)
        motion.endDrag()
        assertEquals(CaseMotionPhase.Settling, motion.phase)
        repeat(900) { motion.advance(1.0 / 60) }
        assertEquals(CaseMotionPhase.Idle, motion.phase)
    }

    @Test fun reducingMotionDiscardsBothExistingAndIgnoredTouchAirflowRequests() {
        val motion = CaseMotion().apply { setAirflow(1f) }
        motion.beginDrag()
        motion.setReducedMotion(true)
        motion.endDrag()
        motion.beginDrag()
        motion.setAirflow(1f)
        motion.dragBy(.13, 0.0, .016)
        motion.endDrag()
        motion.setReducedMotion(false)
        repeat(120) { motion.advance(1.0 / 60) }
        assertEquals(CaseMotionPhase.Idle, motion.phase)
        assertTrue(!motion.needsFrame)
        motion.beginDrag()
        motion.endDrag()
        assertEquals(CaseMotionPhase.Settling, motion.phase)
        repeat(900) { motion.advance(1.0 / 60) }
        assertEquals(CaseMotionPhase.Idle, motion.phase)
        motion.setAirflow(.5f)
        assertEquals(CaseMotionPhase.Airflow, motion.phase)
    }

    @Test fun aFastPositiveDragCannotResumeAboveTheAirflowSpeedLimit() {
        val motion = CaseMotion().apply { setAirflow(2f) }
        motion.beginDrag()
        repeat(3) { motion.dragBy(.5, 0.0, .008) }
        assertTrue(motion.velocity > 600)
        motion.endDrag()
        assertEquals(CaseMotionPhase.Airflow, motion.phase)
        repeat(360) {
            assertTrue(motion.velocity in 0.0..600.0)
            motion.advance(1.0 / 60)
        }
        assertEquals(600.0, motion.velocity, .001)
    }

    @Test fun pausingClearsHiddenWorkAndRequiresFreshAirflowToResume() {
        val motion = CaseMotion().apply { setAirflow(1f) }
        repeat(90) { motion.advance(1.0 / 60) }
        motion.pause()
        val paused = motion.pose
        repeat(120) { motion.advance(1.0 / 60) }
        assertEquals(paused, motion.pose)
        assertTrue(!motion.needsFrame)
        motion.setAirflow(.5f)
        repeat(20) { motion.advance(1.0 / 60) }
        assertTrue(motion.angle > paused.yawDegrees)
    }

    @Test fun reducedMotionHasDiscreteTurnsWithoutInertiaOrContinuousFrames() {
        val motion = CaseMotion()
        motion.setReducedMotion(true)
        motion.setAirflow(1f)
        motion.turn(-1)
        assertEquals(-196.0, motion.angle, .000001)
        assertEquals(CaseMotionPhase.Idle, motion.phase)
        assertTrue(!motion.needsFrame)
        motion.beginDrag()
        repeat(13) { motion.dragBy(.01, .2, .016) }
        motion.endDrag()
        assertEquals(-16.0, motion.angle, .000001)
        assertEquals(0.0, motion.velocity, 0.0)
    }

    @Test fun invalidSamplesAndLongFrameGapsCannotPoisonThePose() {
        val motion = CaseMotion()
        motion.setAirflow(Float.NaN)
        motion.advance(Double.NaN)
        motion.beginDrag()
        motion.dragBy(Double.POSITIVE_INFINITY, .5, .016)
        motion.endDrag(.5)
        repeat(600) { motion.advance(30.0) }
        assertTrue(listOf(motion.angle, motion.pitch, motion.velocity, motion.target).all { it.isFinite() })
        assertTrue(abs(motion.velocity) < .001)
    }
}

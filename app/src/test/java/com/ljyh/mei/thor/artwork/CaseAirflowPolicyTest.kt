package com.ljyh.mei.thor.artwork

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseAirflowPolicyTest {
    @Test fun disablingFullSpeedAirflowPreservesTheVisibleAngleAndSettlesWithinHalfAFace() {
        val motion = CaseMotion().apply { setAirflow(1f) }
        repeat(180) { motion.advance(DT) }
        assertTrue(motion.velocity > 599.0)
        val origin = motion.angle
        motion.setAirflowAllowed(false)
        assertEquals(origin, motion.angle, 0.0)
        assertEquals(0.0, motion.velocity, 0.0)
        assertTrue(abs(motion.target - origin) <= 90.0)
        repeat(600) {
            motion.setAirflow(1f)
            motion.advance(DT)
            assertTrue(abs(motion.angle - origin) <= 90.001)
            assertTrue(motion.phase != CaseMotionPhase.Airflow)
        }
        assertEquals(CaseMotionPhase.Idle, motion.phase)
        assertFalse(motion.needsFrame)
    }

    @Test fun disablingAlsoClearsTheMomentumOfAnAlreadyReleasedMicrophoneStroke() {
        val motion = CaseMotion().apply { setAirflow(1f) }
        repeat(150) { motion.advance(DT) }
        motion.setAirflow(0f)
        repeat(120) { if (motion.phase == CaseMotionPhase.Airflow) motion.advance(DT) }
        assertEquals(CaseMotionPhase.Settling, motion.phase)
        assertTrue(motion.velocity > 0.0)
        val origin = motion.angle
        motion.setAirflowAllowed(false)
        assertEquals(0.0, motion.velocity, 0.0)
        assertTrue(abs(motion.target - origin) <= 90.0)
        repeat(600) {
            motion.advance(DT)
            assertTrue(abs(motion.angle - origin) <= 90.001)
        }
        assertEquals(CaseMotionPhase.Idle, motion.phase)
    }

    @Test fun disablingDuringTouchKeepsTheDragAndItsManualVelocityButDiscardsTheSourceRequest() {
        val motion = CaseMotion().apply { setAirflow(1f) }
        repeat(100) { motion.advance(DT) }
        motion.beginDrag()
        motion.dragBy(-.12, .1, DT)
        val dragging = motion.pose
        val velocity = motion.velocity
        motion.setAirflowAllowed(false)
        assertEquals(CaseMotionPhase.Dragging, motion.phase)
        assertEquals(dragging, motion.pose)
        assertEquals(velocity, motion.velocity, 0.0)
        motion.setAirflow(1f)
        motion.dragBy(-.08, 0.0, DT)
        assertTrue(motion.angle < dragging.yawDegrees)
        motion.endDrag()
        assertEquals(CaseMotionPhase.Settling, motion.phase)
        assertTrue(motion.velocity < 0.0)
        repeat(600) { motion.advance(DT) }
        assertEquals(CaseMotionPhase.Idle, motion.phase)
    }

    @Test fun aManualTurnAlreadyInFlightIsNotMistakenForMicrophoneMomentum() {
        for (direction in listOf(-1, 1)) {
            val motion = CaseMotion()
            motion.turn(direction)
            repeat(3) { motion.advance(DT) }
            val pose = motion.pose
            val velocity = motion.velocity
            val target = motion.target
            motion.setAirflowAllowed(false)
            assertEquals(pose, motion.pose)
            assertEquals(velocity, motion.velocity, 0.0)
            assertEquals(target, motion.target, 0.0)
            repeat(600) { motion.advance(DT) }
            assertEquals(-16.0 + direction * 180.0, motion.angle, .000001)
            motion.turn(-direction)
            repeat(600) { motion.advance(DT) }
            assertEquals(-16.0, motion.angle, .000001)
        }
    }

    @Test fun reallowingAirflowDoesNotReplayInputsIgnoredWhileItWasDisabled() {
        val motion = CaseMotion()
        motion.setAirflowAllowed(false)
        for (sample in listOf(1f, .5f, Float.NaN, Float.POSITIVE_INFINITY)) motion.setAirflow(sample)
        motion.setAirflowAllowed(true)
        repeat(180) { motion.advance(DT) }
        assertEquals(CaseMotionPhase.Idle, motion.phase)
        assertEquals(-16.0, motion.angle, 0.0)
        motion.setAirflow(.5f)
        repeat(120) { motion.advance(DT) }
        assertEquals(CaseMotionPhase.Airflow, motion.phase)
        assertTrue(motion.velocity > 0.0 && motion.velocity <= 600.0)
    }

    @Test fun canceledTouchWhileDisabledCannotRestoreEarlierContinuousAirflow() {
        val motion = CaseMotion().apply { setAirflow(1f) }
        motion.beginDrag()
        motion.setAirflowAllowed(false)
        motion.setAirflow(1f)
        motion.dragBy(.2, 0.0, DT)
        motion.endDrag(.1)
        assertEquals(CaseMotionPhase.Settling, motion.phase)
        assertEquals(0.0, motion.velocity, 0.0)
        repeat(600) { motion.advance(DT) }
        assertEquals(CaseMotionPhase.Idle, motion.phase)
        motion.setAirflowAllowed(true)
        motion.beginDrag()
        motion.endDrag(.1)
        assertTrue(motion.phase != CaseMotionPhase.Airflow)
    }

    private companion object { const val DT = 1.0 / 60.0 }
}

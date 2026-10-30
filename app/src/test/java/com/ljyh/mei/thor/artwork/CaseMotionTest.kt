package com.ljyh.mei.thor.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
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

    @Test fun draggingPreemptsAirflowAndReleaseRetainsItsOwnDirection() {
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
        motion.endDrag()
        repeat(900) { motion.advance(1.0 / 60) }
        assertEquals(CaseMotionPhase.Idle, motion.phase)
        assertTrue(motion.angle.isFinite())
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

package com.ljyh.mei.thor.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ThorAirflowDetectorTest {
    private fun sound(amplitude: Float) = ShortArray(640) { (sin(2 * PI * it / 53) * amplitude * 32767).toInt().toShort() }

    @Test fun silenceDcAndIsolatedClicksDoNotProduceAirflow() {
        val detector = ThorAirflowDetector()
        assertEquals(0f, detector.process(ShortArray(640), 640, 0).strength, 0f)
        assertEquals(0f, detector.process(ShortArray(640) { 8_000 }, 640, 100).strength, 0f)
        val click = ShortArray(640).apply { this[5] = 30_000 }
        assertEquals(0f, detector.process(click, 640, 200).strength, 0f)
        assertEquals(0f, detector.process(ShortArray(640), 640, 280).strength, 0f)
    }

    @Test fun sustainedInputIsBoundedMonotonicAndStopsImmediatelyAtNoiseGate() {
        val detector = ThorAirflowDetector()
        detector.process(sound(.06f), 640, 0)
        val gentle = detector.process(sound(.06f), 640, 80).strength
        val strong = detector.process(sound(.6f), 640, 120).strength
        assertTrue(gentle > 0f)
        assertTrue(strong >= gentle)
        assertEquals(1f, strong, 0f)
        assertEquals(0f, detector.process(sound(.001f), 640, 160).strength, 0f)
        assertEquals(0f, detector.process(sound(.6f), 640, 200).strength, 0f)
    }

    @Test fun ambientCalibrationRaisesNoiseFloorAndNeverEmitsRotationWhileCalibrating() {
        val detector = ThorAirflowDetector()
        detector.calibrate(ThorAirflowCalibrationStep.Quiet, 0, 500)
        for (time in 0L..500L step 50L) {
            assertEquals(0f, detector.process(sound(.04f), 640, time).strength, 0f)
        }
        assertTrue(detector.calibration.noiseFloorRms > .02f)
        assertTrue(detector.calibration.isValid)
        detector.process(sound(.04f), 640, 600)
        assertEquals(0f, detector.process(sound(.04f), 640, 800).strength, 0f)
    }

    @Test fun gentleAndStrongCalibrationStayOrderedAndRejectInvalidSavedValues() {
        val detector = ThorAirflowDetector()
        detector.calibrate(ThorAirflowCalibrationStep.Gentle, 0, 500)
        detector.process(sound(.08f), 640, 0)
        detector.process(sound(.08f), 640, 500)
        detector.calibrate(ThorAirflowCalibrationStep.Strong, 600, 500)
        detector.process(sound(.4f), 640, 600)
        detector.process(sound(.4f), 640, 1_100)
        val calibrated = detector.calibration
        assertTrue(calibrated.isValid)
        assertTrue(calibrated.strongRms > calibrated.gentleRms)
        assertFalse(detector.applyCalibration(ThorAirflowCalibration(Float.NaN, .1f, .2f)))
        assertFalse(detector.applyCalibration(ThorAirflowCalibration(.01f, .2f, .1f)))
        assertEquals(calibrated, detector.calibration)
    }

    @Test fun stoppingCalibrationClearsItsPendingSamplesAndCandidate() {
        val detector = ThorAirflowDetector()
        detector.calibrate(ThorAirflowCalibrationStep.Strong, 0)
        detector.process(sound(.5f), 640, 100)
        detector.cancel()
        val reading = detector.process(sound(.1f), 640, 200)
        assertNull(reading.step)
        assertEquals(0f, reading.strength, 0f)
        assertEquals(ThorAirflowCalibration(), reading.calibration)
    }

    @Test fun savedCalibrationRequiresACompleteFiniteOrderedTuple() {
        assertNull(storedAirflowCalibration(null, .04f, .2f))
        assertNull(storedAirflowCalibration(.005f, null, .2f))
        assertNull(storedAirflowCalibration(.005f, .04f, null))
        assertNull(storedAirflowCalibration(Float.NaN, .04f, .2f))
        assertNull(storedAirflowCalibration(.005f, .04f, Float.POSITIVE_INFINITY))
        assertNull(storedAirflowCalibration(.05f, .04f, .2f))
        assertNull(storedAirflowCalibration(.005f, .3f, .2f))
        assertNull(storedAirflowCalibration(.005f, .04f, 1.1f))
    }

    @Test fun aValidSavedCalibrationIsAppliedWithoutRecalibratingOrEmittingMotion() {
        val saved = storedAirflowCalibration(.008f, .06f, .3f)
        assertEquals(ThorAirflowCalibration(.008f, .06f, .3f), saved)
        val detector = ThorAirflowDetector()
        assertTrue(detector.applyCalibration(requireNotNull(saved)))
        val restored = detector.process(sound(.001f), 640, 100)
        assertEquals(saved, restored.calibration)
        assertNull(restored.step)
        assertEquals(0f, restored.strength, 0f)
    }
}

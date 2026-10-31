package com.ljyh.mei.thor.audio

import com.ljyh.mei.thor.artwork.CaseMotion
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class ThorAirflowDetectorTest {
    private fun sound(amplitude: Float) = ShortArray(640) { (sin(2 * PI * it / 53) * amplitude * 32767).toInt().toShort() }

    /** Balanced integer samples preserve the requested sub-milliscale RMS after PCM16 quantization. */
    private fun pcmRms(rms: Float): ShortArray {
        val codes = rms / AIRFLOW_PCM_UNIT
        val low = codes.toInt()
        val highFraction = (codes * codes - low * low) / ((low + 1) * (low + 1) - low * low)
        val highPairs = (highFraction * 320).roundToInt()
        return ShortArray(640) { index ->
            val code = low + if (index / 2 < highPairs) 1 else 0
            (if (index % 2 == 0) code else -code).toShort()
        }
    }

    private fun calibrate(
        detector: ThorAirflowDetector,
        step: ThorAirflowCalibrationStep,
        rms: Float,
        start: Long,
    ): AirflowReading {
        detector.calibrate(step, start, 500)
        var reading = detector.process(pcmRms(rms), 640, start)
        for (elapsed in 40L..520L step 40L) reading = detector.process(pcmRms(rms), 640, start + elapsed)
        return reading
    }

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
            assertEquals(0f, detector.process(sound(.006f), 640, time).strength, 0f)
        }
        assertTrue(detector.calibration.noiseFloorRms > .004f)
        assertTrue(detector.calibration.isValid)
        detector.process(sound(.006f), 640, 600)
        assertEquals(0f, detector.process(sound(.006f), 640, 800).strength, 0f)
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

    @Test fun unreachableNoiseGateIsNotAValidStoredCalibration() {
        assertNull(storedAirflowCalibration(.4f, .9f, .99f))
        assertNull(storedAirflowCalibration(.03f, .05f, .2f))
    }

    @Test fun anOverloadedQuietSampleKeepsThePreviousCalibrationAndCanBeRetried() {
        val detector = ThorAirflowDetector()
        val previous = detector.calibration
        detector.calibrate(ThorAirflowCalibrationStep.Quiet, 0, 500)
        detector.process(sound(.95f), 640, 0)
        val rejected = detector.process(sound(.95f), 640, 500)
        assertEquals(false, rejected.calibrationAccepted)
        assertNotNull(rejected.calibrationError)
        assertEquals(previous, rejected.calibration)
        assertNull(rejected.step)
        detector.calibrate(ThorAirflowCalibrationStep.Quiet, 600, 500)
        detector.process(sound(.0002f), 640, 600)
        val accepted = detector.process(sound(.0002f), 640, 1_100)
        assertEquals(true, accepted.calibrationAccepted)
        assertTrue(accepted.calibration.isValid)
    }

    @Test fun aCalibratedLowGainMicrophoneCanReachFullStrength() {
        val detector = ThorAirflowDetector()
        assertTrue(detector.applyCalibration(ThorAirflowCalibration(.0001f, .002f, .006f)))
        detector.process(sound(.01f), 640, 0)
        val blow = detector.process(sound(.01f), 640, 80)
        assertEquals(1f, blow.strength, .001f)
        assertTrue(blow.rms > blow.gate)
        assertEquals(0f, detector.process(ShortArray(640), 640, 120).strength, 0f)
    }

    @Test fun missingGentleOrStrongInputDoesNotInventCalibrationValues() {
        val detector = ThorAirflowDetector()
        val before = detector.calibration
        for ((index, step) in listOf(ThorAirflowCalibrationStep.Gentle, ThorAirflowCalibrationStep.Strong).withIndex()) {
            val start = index * 600L
            detector.calibrate(step, start, 500)
            detector.process(ShortArray(640), 640, start)
            val result = detector.process(ShortArray(640), 640, start + 500)
            assertEquals(false, result.calibrationAccepted)
            assertEquals(before, result.calibration)
            assertNotNull(result.calibrationError)
        }
    }

    @Test fun onlyTheVerifiedThorMicGetsTheLowGainDefaultProfile() {
        val mic = airflowDefaultCalibration(source = 1, isThor = true)
        assertTrue(mic.isValid)
        assertTrue(airflowNoiseGate(mic.noiseFloorRms) < .0002f)
        for ((source, isThor) in listOf(1 to false, 6 to true, 9 to true, 9 to false)) {
            val ordinary = airflowDefaultCalibration(source, isThor)
            assertEquals(ThorAirflowCalibration(), ordinary)
            assertEquals(.01f, airflowNoiseGate(ordinary.noiseFloorRms), .000000001f)
        }
    }

    @Test fun lowGainGateTransitionsContinuouslyAndNeverDropsAsTheNoiseFloorRises() {
        var previous = airflowNoiseGate(0f)
        for (codeTenths in 1..100) {
            val gate = airflowNoiseGate(codeTenths / 10f * AIRFLOW_PCM_UNIT)
            assertTrue(gate >= previous)
            previous = gate
        }
        for (boundary in listOf(4f, 8f)) {
            val before = airflowNoiseGate((boundary - .0001f) * AIRFLOW_PCM_UNIT)
            val after = airflowNoiseGate((boundary + .0001f) * AIRFLOW_PCM_UNIT)
            assertTrue(after - before < AIRFLOW_PCM_UNIT * .001f)
        }
    }

    @Test fun thorMicCanRespondToTheFirstGentleBlowWithoutLearningItAsQuiet() {
        val detector = ThorAirflowDetector()
        detector.applyCalibration(airflowDefaultCalibration(1, true))
        val initial = detector.calibration
        assertEquals(0f, detector.process(pcmRms(.0002f), 640, 0).strength, 0f)
        assertEquals(0f, detector.process(pcmRms(.0002f), 640, 59).strength, 0f)
        val sustained = detector.process(pcmRms(.0002f), 640, 60)
        assertTrue(sustained.strength >= .1f)
        assertEquals(initial, detector.calibration)
        assertEquals(0f, detector.process(pcmRms(.00008f), 640, 100).strength, 0f)
    }

    @Test fun measuredQuietCalibrationStillAllowsTwoHundredMicroRmsGentleBlowing() {
        val detector = ThorAirflowDetector()
        detector.applyCalibration(airflowDefaultCalibration(1, true))
        val quiet = calibrate(detector, ThorAirflowCalibrationStep.Quiet, .0000808f, 0)
        assertEquals(true, quiet.calibrationAccepted)
        assertTrue(quiet.calibration.noiseFloorRms < .0001f)
        assertTrue(quiet.gate < .0002f)
        for (time in 600L..800L step 40L) {
            assertEquals(0f, detector.process(pcmRms(.0000808f), 640, time).strength, 0f)
        }
        assertEquals(0f, detector.process(pcmRms(.0002f), 640, 840).strength, 0f)
        val weak = detector.process(pcmRms(.0002f), 640, 920)
        val gentle = detector.process(pcmRms(.0008f), 640, 960)
        val strong = detector.process(pcmRms(.052114f), 640, 1_000)
        assertTrue(weak.strength >= .1f)
        assertTrue(gentle.strength > weak.strength)
        assertTrue(strong.strength > gentle.strength)
        assertEquals(1f, strong.strength, .001f)
    }

    @Test fun lowGainGentleAndStrongCalibrationDoNotRequireMilliscaleGaps() {
        val detector = ThorAirflowDetector()
        detector.applyCalibration(airflowDefaultCalibration(1, true))
        assertEquals(true, calibrate(detector, ThorAirflowCalibrationStep.Quiet, .0000808f, 0).calibrationAccepted)
        val gentle = calibrate(detector, ThorAirflowCalibrationStep.Gentle, .0002193f, 600)
        assertEquals(true, gentle.calibrationAccepted)
        val strong = calibrate(detector, ThorAirflowCalibrationStep.Strong, .0008f, 1_200)
        assertEquals(true, strong.calibrationAccepted)
        assertTrue(strong.calibration.isValid)
        assertTrue(strong.calibration.strongRms < .001f)
    }

    @Test fun anObviouslyPollutedQuietStepCannotSilentlyRaiseTheBlowAnchors() {
        val detector = ThorAirflowDetector()
        val previous = detector.calibration
        val reading = calibrate(detector, ThorAirflowCalibrationStep.Quiet, .03f, 0)
        assertEquals(false, reading.calibrationAccepted)
        assertNotNull(reading.calibrationError)
        assertEquals(previous, reading.calibration)
        assertNull(reading.step)
        detector.applyCalibration(airflowDefaultCalibration(1, true))
        val micPrevious = detector.calibration
        val micRejected = calibrate(detector, ThorAirflowCalibrationStep.Quiet, .001f, 600)
        assertEquals(false, micRejected.calibrationAccepted)
        assertEquals(micPrevious, micRejected.calibration)
    }

    @Test fun lowGainQuietClicksDcAndShortBurstsStillCannotStartRotation() {
        val detector = ThorAirflowDetector()
        detector.applyCalibration(airflowDefaultCalibration(1, true))
        val click = ShortArray(640).apply { this[5] = 2_000 }
        for (time in 0L..240L step 40L) {
            assertEquals(0f, detector.process(click, 640, time).strength, 0f)
        }
        assertEquals(0f, detector.process(ShortArray(640) { 8_000 }, 640, 280).strength, 0f)
        assertEquals(0f, detector.process(pcmRms(.0008f), 640, 320).strength, 0f)
        assertEquals(0f, detector.process(pcmRms(.0008f), 640, 360).strength, 0f)
        assertEquals(0f, detector.process(pcmRms(.0000808f), 640, 400).strength, 0f)
        assertEquals(0f, detector.process(pcmRms(.0008f), 640, 440).strength, 0f)
    }

    @Test fun measuredGentleBlowingActuallyMovesTheArtworkAndStrongBlowingMovesItFaster() {
        fun simulate(rms: Float): CaseMotion {
            val detector = ThorAirflowDetector()
            detector.applyCalibration(airflowDefaultCalibration(1, true))
            assertEquals(true, calibrate(detector, ThorAirflowCalibrationStep.Quiet, .0000808f, 0).calibrationAccepted)
            val motion = CaseMotion()
            for (elapsed in 0L..1_000L step 40L) {
                motion.setAirflow(detector.process(pcmRms(rms), 640, 600 + elapsed).strength)
                repeat(4) { motion.advance(.01) }
            }
            return motion
        }
        val quiet = simulate(.0000808f)
        val weak = simulate(.0002f)
        val gentle = simulate(.0008f)
        val strong = simulate(.052114f)
        assertEquals(quiet.config.restDegrees, quiet.angle, 0.0)
        assertTrue("The lowest verified gentle input must visibly turn the case", weak.angle - weak.config.restDegrees > 10.0)
        assertTrue(weak.velocity > 20.0)
        assertTrue(gentle.angle > weak.angle)
        assertTrue(strong.angle > gentle.angle)
        assertTrue(strong.velocity > gentle.velocity)
        assertTrue(strong.velocity <= strong.config.maximumSpeed)
    }
}

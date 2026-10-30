package com.ljyh.mei.thor.audio

import kotlin.math.abs
import kotlin.math.sqrt

data class ThorAirflowCalibration(
    val noiseFloorRms: Float = .004f,
    val gentleRms: Float = .035f,
    val strongRms: Float = .16f,
) {
    val isValid: Boolean get() = noiseFloorRms.isFinite() && gentleRms.isFinite() && strongRms.isFinite() &&
        noiseFloorRms in 0f..0.8f && gentleRms > noiseFloorRms && strongRms > gentleRms && strongRms <= 1f
}

enum class ThorAirflowCalibrationStep { Quiet, Gentle, Strong }
enum class ThorAirflowStatus { Stopped, Listening, Calibrating, PermissionRequired, Unavailable }

internal data class AirflowReading(
    val strength: Float,
    val calibration: ThorAirflowCalibration,
    val step: ThorAirflowCalibrationStep?,
    val progress: Float,
)

/** Bounded, local energy/noise gate, not a claim to classify breath independently of every sound. */
internal class ThorAirflowDetector {
    var calibration = ThorAirflowCalibration()
        private set
    private var step: ThorAirflowCalibrationStep? = null
    private var startedAt = 0L
    private var calibrationDurationMs = 1_500L
    private val calibrationSamples = mutableListOf<Float>()
    private var candidateAt: Long? = null

    fun applyCalibration(value: ThorAirflowCalibration): Boolean {
        if (!value.isValid) return false
        calibration = value
        cancel()
        return true
    }

    fun calibrate(value: ThorAirflowCalibrationStep, nowMs: Long, durationMs: Long = 1_500) {
        step = value
        startedAt = nowMs
        calibrationDurationMs = durationMs.coerceIn(500, 5_000)
        calibrationSamples.clear()
        candidateAt = null
    }

    fun cancel() {
        step = null
        calibrationSamples.clear()
        candidateAt = null
    }

    fun process(samples: ShortArray, count: Int, nowMs: Long): AirflowReading {
        val size = count.coerceIn(0, samples.size)
        if (size == 0) return AirflowReading(0f, calibration, step, 0f)
        var mean = 0.0
        for (i in 0 until size) mean += samples[i].toDouble() / 32768.0
        mean /= size
        var energy = 0.0
        var peak = 0.0
        for (i in 0 until size) {
            val sample = samples[i].toDouble() / 32768.0 - mean
            energy += sample * sample
            peak = maxOf(peak, abs(sample))
        }
        val rms = sqrt(energy / size).toFloat()
        val activeStep = step
        if (activeStep != null) {
            // Cap storage even if a device delivers unusually small read buffers.
            if (calibrationSamples.size < 512) calibrationSamples += rms
            val progress = ((nowMs - startedAt).toFloat() / calibrationDurationMs).coerceIn(0f, 1f)
            if (progress >= 1f) finishCalibration(activeStep)
            return AirflowReading(0f, calibration, step, progress)
        }
        val gate = maxOf(calibration.noiseFloorRms * 2.5f, calibration.noiseFloorRms + .006f)
        // Isolated clicks and an unchanging DC offset cannot rotate the artwork.
        val sustainedCandidate = rms > gate && peak / maxOf(rms.toDouble(), .00001) < 12.0
        if (!sustainedCandidate) candidateAt = null else if (candidateAt == null) candidateAt = nowMs
        val sustained = candidateAt?.let { nowMs - it >= 60L } == true
        val gentle = maxOf(calibration.gentleRms, gate + .008f)
        val strong = maxOf(calibration.strongRms, gentle + .015f)
        val intensity = if (!sustained) 0f else if (rms <= gentle) {
            .35f * (rms - gate) / (gentle - gate)
        } else .35f + .65f * (rms - gentle) / (strong - gentle)
        return AirflowReading(intensity.coerceIn(0f, 1f), calibration, null, 0f)
    }

    private fun finishCalibration(finishedStep: ThorAirflowCalibrationStep) {
        val sorted = calibrationSamples.sorted()
        val measured = sorted.getOrNull(((sorted.size - 1) * .85f).toInt().coerceAtLeast(0)) ?: 0f
        val noise = when (finishedStep) {
            ThorAirflowCalibrationStep.Quiet -> (measured * 1.12f).coerceIn(.0001f, .45f)
            else -> calibration.noiseFloorRms
        }
        val gate = maxOf(noise * 2.5f, noise + .006f)
        val gentle = (if (finishedStep == ThorAirflowCalibrationStep.Gentle) measured else calibration.gentleRms)
            .coerceIn((gate + .008f).coerceAtMost(.9f), .92f)
        val strong = (if (finishedStep == ThorAirflowCalibrationStep.Strong) measured else calibration.strongRms)
            .coerceIn((gentle + .02f).coerceAtMost(.96f), 1f)
        calibration = ThorAirflowCalibration(noise, gentle, strong)
        cancel()
    }
}

package com.ljyh.mei.thor.audio

import kotlin.math.abs
import kotlin.math.sqrt

/** One signed PCM16 code, expressed in the same full-scale units as RMS. */
internal const val AIRFLOW_PCM_UNIT: Float = 1f / 32768f

data class ThorAirflowCalibration(
    val noiseFloorRms: Float = .004f,
    val gentleRms: Float = .035f,
    val strongRms: Float = .16f,
) {
    val isValid: Boolean get() = noiseFloorRms.isFinite() && gentleRms.isFinite() && strongRms.isFinite() &&
        noiseFloorRms in 0f..0.8f && gentleRms > airflowNoiseGate(noiseFloorRms) &&
        strongRms > gentleRms && strongRms <= 1f
}

/**
 * Preserve the ordinary-gain 2.5x gate. At a floor of only a few PCM codes, a fixed
 * .0005 margin is louder than a real gentle Thor blow. Two codes of headroom and
 * a continuous 2x -> 2.5x transition keep that input usable without amplifying DC.
 */
internal fun airflowNoiseGate(noise: Float): Float {
    val ordinaryGain = ((noise / AIRFLOW_PCM_UNIT - 4f) / 4f).coerceIn(0f, 1f)
    return maxOf(noise * (2f + .5f * ordinaryGain), noise + 2f * AIRFLOW_PCM_UNIT)
}

/** Only the verified Thor built-in MIC path uses this low-gain profile. */
internal fun airflowDefaultCalibration(source: Int, isThor: Boolean): ThorAirflowCalibration =
    if (isThor && source == 1) ThorAirflowCalibration(.00006f, .0008f, .05f)
    else ThorAirflowCalibration()

// RMS is an average across a frame, so its resolution can be below one PCM code.
private fun airflowCalibrationGap(reference: Float): Float =
    maxOf(AIRFLOW_PCM_UNIT * .5f, reference * .025f)

enum class ThorAirflowCalibrationStep { Quiet, Gentle, Strong }
enum class ThorAirflowStatus { Stopped, Listening, Calibrating, PermissionRequired, Unavailable }

internal data class AirflowReading(
    val strength: Float,
    val calibration: ThorAirflowCalibration,
    val step: ThorAirflowCalibrationStep?,
    val progress: Float,
    val rms: Float = 0f,
    val gate: Float = 0f,
    val crest: Float = 0f,
    val calibrationAccepted: Boolean? = null,
    val calibrationError: String? = null,
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
        val crest = (peak / maxOf(rms.toDouble(), .00001)).toFloat()
        val activeStep = step
        if (activeStep != null) {
            // Cap storage even if a device delivers unusually small read buffers.
            if (calibrationSamples.size < 512) calibrationSamples += rms
            val progress = ((nowMs - startedAt).toFloat() / calibrationDurationMs).coerceIn(0f, 1f)
            val error = if (progress >= 1f) finishCalibration(activeStep) else null
            return AirflowReading(0f, calibration, step, progress, rms, airflowNoiseGate(calibration.noiseFloorRms),
                crest, if (progress >= 1f) error == null else null, error)
        }
        val gate = airflowNoiseGate(calibration.noiseFloorRms)
        // Isolated clicks and an unchanging DC offset cannot rotate the artwork.
        val sustainedCandidate = rms > gate && crest < 12f
        if (!sustainedCandidate) candidateAt = null else if (candidateAt == null) candidateAt = nowMs
        val sustained = candidateAt?.let { nowMs - it >= 60L } == true
        val gentle = maxOf(calibration.gentleRms, gate + airflowCalibrationGap(gate))
        val strong = maxOf(calibration.strongRms, gentle + airflowCalibrationGap(gentle))
        val intensity = if (!sustained) 0f else if (rms <= gentle) {
            // Crossing the gate must be visible after the artwork's strength^1.45 mapping.
            .1f + .25f * (rms - gate) / (gentle - gate)
        } else .35f + .65f * (rms - gentle) / (strong - gentle)
        return AirflowReading(intensity.coerceIn(0f, 1f), calibration, null, 0f, rms, gate, crest)
    }

    private fun finishCalibration(finishedStep: ThorAirflowCalibrationStep): String? {
        val sorted = calibrationSamples.sorted()
        val measured = sorted.getOrNull(((sorted.size - 1) * .85f).toInt().coerceAtLeast(0)) ?: 0f
        val noise = when (finishedStep) {
            ThorAirflowCalibrationStep.Quiet -> (measured * 1.12f).coerceIn(AIRFLOW_PCM_UNIT, .45f)
            else -> calibration.noiseFloorRms
        }
        val gate = airflowNoiseGate(noise)
        if (finishedStep == ThorAirflowCalibrationStep.Quiet &&
            gate + airflowCalibrationGap(gate) >= calibration.gentleRms) {
            // Do not silently manufacture new blow anchors from an obviously polluted quiet step.
            cancel()
            return "环境声音过大，请保持安静后重试"
        }
        if (finishedStep == ThorAirflowCalibrationStep.Gentle && measured <= gate + airflowCalibrationGap(gate)) {
            cancel()
            return "未检测到轻吹，请对准麦克风重试"
        }
        if (finishedStep == ThorAirflowCalibrationStep.Strong &&
            measured <= calibration.gentleRms + airflowCalibrationGap(calibration.gentleRms)) {
            cancel()
            return "力度不足，请用力吹后重试"
        }
        val gentle = (if (finishedStep == ThorAirflowCalibrationStep.Gentle) measured else calibration.gentleRms)
            .coerceIn((gate + airflowCalibrationGap(gate)).coerceAtMost(.9f), .92f)
        val strong = (if (finishedStep == ThorAirflowCalibrationStep.Strong) measured else calibration.strongRms)
            .coerceIn((gentle + airflowCalibrationGap(gentle)).coerceAtMost(.96f), 1f)
        val candidate = ThorAirflowCalibration(noise, gentle, strong)
        cancel()
        if (!candidate.isValid) return "环境声音过大，请保持安静后重试"
        calibration = candidate
        return null
    }
}

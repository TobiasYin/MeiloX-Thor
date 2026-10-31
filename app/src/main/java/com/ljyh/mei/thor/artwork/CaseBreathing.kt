package com.ljyh.mei.thor.artwork

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Small presentation offsets; the physics model and its real front/back targets stay unchanged. */
internal data class CaseBreathingPose(
    val bobDp: Double = 0.0,
    val yawDegrees: Double = 0.0,
    val pitchDegrees: Double = 0.0,
    val rollDegrees: Double = 0.0,
) {
    fun applyTo(base: CasePose): CasePose {
        val weight = (1.0 - base.lift).coerceIn(0.0, 1.0)
        return base.copy(
            yawDegrees = base.yawDegrees + yawDegrees * weight,
            pitchDegrees = base.pitchDegrees + pitchDegrees * weight,
            rollDegrees = base.rollDegrees + rollDegrees * weight,
        )
    }
}

/** Deterministic, phase-continuous playback breathing. It never owns touch or microphone input. */
internal class CaseBreathing {
    private var amplitude = 0.0
    private var bobPhase = 0.0
    private var yawPhase = PI / 3.0
    private var pitchPhase = PI * .85
    private var rollPhase = -PI * .2
    var pose = CaseBreathingPose()
        private set
    val needsFrame get() = amplitude > 0.0

    fun advance(seconds: Double, enabled: Boolean, suppressed: Boolean = false): CaseBreathingPose {
        val dt = if (seconds.isFinite()) seconds.coerceIn(0.0, .05) else 0.0
        if (dt == 0.0) return pose
        val target = if (enabled && !suppressed) 1.0 else 0.0
        val tau = when {
            suppressed -> .10
            target > amplitude -> .70
            else -> .45
        }
        amplitude += (target - amplitude) * (1.0 - exp(-dt / tau))
        if (target == 0.0 && amplitude < .001) amplitude = 0.0
        // Touch freezes the oscillator while its weight eases away. Releasing does not restart
        // the cycle or jump to an unrelated wall-clock phase.
        if (!suppressed && (enabled || amplitude > 0.0)) {
            bobPhase = advancePhase(bobPhase, dt, 5.8)
            yawPhase = advancePhase(yawPhase, dt, 7.0)
            pitchPhase = advancePhase(pitchPhase, dt, 8.6)
            rollPhase = advancePhase(rollPhase, dt, 9.4)
        }
        pose = if (amplitude == 0.0) CaseBreathingPose() else CaseBreathingPose(
            bobDp = sin(bobPhase) * 6.0 * amplitude,
            yawDegrees = sin(yawPhase) * 2.5 * amplitude,
            pitchDegrees = sin(pitchPhase) * amplitude,
            rollDegrees = sin(rollPhase) * .6 * amplitude,
        )
        return pose
    }

    /** Hidden/reduced-motion content has no residual frame work; its oscillator phase is retained. */
    fun pause() {
        amplitude = 0.0
        pose = CaseBreathingPose()
    }

    private fun advancePhase(phase: Double, dt: Double, period: Double) = (phase + dt * PI * 2.0 / period) % (PI * 2.0)
}

package com.ljyh.mei.thor.artwork

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.round

/** Angles are actual model angles in degrees, not offsets added to a hidden base rotation. */
data class CaseMotionConfig(
    val restDegrees: Double = -16.0,
    val maximumSpeed: Double = 600.0,
    val strengthExponent: Double = 1.45,
    val inputAttackSeconds: Double = .08,
    val inputReleaseSeconds: Double = .18,
    val accelerationSeconds: Double = .20,
    val decelerationSeconds: Double = .42,
    val settleOmega: Double = 8.0,
    val settleDamping: Double = 16.0,
    val momentumProjectionSeconds: Double = .18,
    val dragDegreesPerWidth: Double = 360.0,
    val dragMaximumSpeed: Double = 900.0,
)

enum class CaseMotionPhase { Idle, Airflow, Dragging, Settling }

data class CasePose(
    val yawDegrees: Double = -16.0,
    val pitchDegrees: Double = 6.0,
    val rollDegrees: Double = -5.0,
    val lift: Double = 0.0,
)

/** Pure, deterministic physics shared by microphone input, direct manipulation and accessible turns. */
class CaseMotion(val config: CaseMotionConfig = CaseMotionConfig()) {
    var phase: CaseMotionPhase = CaseMotionPhase.Idle
        private set
    var angle: Double = config.restDegrees
        private set
    var velocity: Double = 0.0
        private set
    var target: Double = config.restDegrees
        private set
    var pitch: Double = 6.0
        private set
    var lift: Double = 0.0
        private set
    var reducedMotion: Boolean = false
        private set
    // Source intent survives touch ownership; pause/reduced motion explicitly discard it.
    private var requestedAirflow = 0.0
    private var filtered = 0.0
    private var reducedDragTravel = 0.0

    val pose get() = CasePose(angle, pitch, lift = lift)
    val needsFrame get() = !reducedMotion && (phase != CaseMotionPhase.Idle || lift > .001)

    fun setReducedMotion(reduced: Boolean) {
        if (reducedMotion == reduced) return
        reducedMotion = reduced
        if (reduced) pause()
    }

    fun setAirflow(strength: Float) {
        if (reducedMotion) return
        requestedAirflow = if (strength.isFinite()) strength.toDouble().coerceIn(0.0, 1.0) else 0.0
        if (phase == CaseMotionPhase.Dragging) return
        if (requestedAirflow > 0.0) resumeAirflow()
    }

    fun beginDrag() {
        filtered = 0.0
        velocity = 0.0
        reducedDragTravel = 0.0
        phase = CaseMotionPhase.Dragging
    }

    /** Fractions are relative to the complete local touch region, independent of display density. */
    fun dragBy(horizontalFraction: Double, verticalFraction: Double, seconds: Double) {
        if (phase != CaseMotionPhase.Dragging) return
        if (!horizontalFraction.isFinite() || !verticalFraction.isFinite()) return
        val delta = horizontalFraction * config.dragDegreesPerWidth
        if (reducedMotion) {
            // Reduced motion still has discrete front/back access; it has no continuous spin.
            reducedDragTravel += horizontalFraction
            if (abs(reducedDragTravel) >= .12) {
                angle += if (reducedDragTravel > 0) 180.0 else -180.0
                reducedDragTravel = 0.0
            }
            angle = nearestFace(angle)
            velocity = 0.0
            return
        }
        angle += delta
        pitch = (pitch - verticalFraction * 36.0).coerceIn(-18.0, 18.0)
        val dt = if (seconds.isFinite()) seconds.coerceAtLeast(.008) else .008
        val sampled = (delta / dt).coerceIn(-config.dragMaximumSpeed, config.dragMaximumSpeed)
        velocity = .65 * velocity + .35 * sampled
    }

    fun endDrag(secondsSinceLastMove: Double = 0.0) {
        if (phase != CaseMotionPhase.Dragging) return
        if (secondsSinceLastMove > .09) velocity = 0.0
        if (!reducedMotion && requestedAirflow > 0.0) resumeAirflow() else settle()
    }

    fun turn(direction: Int) {
        if (direction == 0) return
        requestedAirflow = 0.0
        filtered = 0.0
        target = nearestFace(angle) + if (direction > 0) 180.0 else -180.0
        velocity = if (direction > 0) 150.0 else -150.0
        phase = CaseMotionPhase.Settling
        if (reducedMotion) snap()
    }

    /** Stop hidden/background work without retaining microphone input or drag ownership. */
    fun pause() {
        requestedAirflow = 0.0
        filtered = 0.0
        target = nearestFace(angle)
        snap()
    }

    fun advance(seconds: Double): CasePose {
        if (reducedMotion) return pose
        val dt = if (seconds.isFinite()) seconds.coerceIn(0.0, .05) else 0.0
        if (dt == 0.0) return pose
        lift += ((if (phase == CaseMotionPhase.Dragging) 1.0 else 0.0) - lift) * response(dt, .08)
        if (lift < .001 && phase != CaseMotionPhase.Dragging) lift = 0.0
        when (phase) {
            CaseMotionPhase.Airflow -> {
                filtered += (requestedAirflow - filtered) * response(dt, if (requestedAirflow > filtered) config.inputAttackSeconds else config.inputReleaseSeconds)
                val requestedSpeed = config.maximumSpeed * filtered.pow(config.strengthExponent)
                velocity += (requestedSpeed - velocity) * response(dt, if (requestedSpeed > velocity) config.accelerationSeconds else config.decelerationSeconds)
                // The airflow source can never reverse the model or select a music item.
                velocity = velocity.coerceAtLeast(0.0)
                angle += velocity * dt
                pitch += (6.0 - pitch) * response(dt, .15)
                if (requestedAirflow == 0.0 && filtered < .03) settle()
            }
            CaseMotionPhase.Settling -> {
                velocity += (config.settleOmega * config.settleOmega * (target - angle) - config.settleDamping * velocity) * dt
                angle += velocity * dt
                pitch += (6.0 - pitch) * response(dt, .15)
                if (abs(target - angle) < .025 && abs(velocity) < .15 && abs(pitch - 6.0) < .02) snap()
            }
            CaseMotionPhase.Dragging, CaseMotionPhase.Idle -> Unit
        }
        return pose
    }

    private fun settle() {
        requestedAirflow = 0.0
        filtered = 0.0
        target = nearestFace(angle + velocity * config.momentumProjectionSeconds)
        phase = CaseMotionPhase.Settling
        if (reducedMotion) snap()
    }

    private fun resumeAirflow() {
        phase = CaseMotionPhase.Airflow
        // A quick touch drag can reach 900°/s; airflow always resumes within its own 600°/s cap.
        velocity = velocity.coerceIn(0.0, config.maximumSpeed)
    }

    private fun nearestFace(value: Double) = round((value - config.restDegrees) / 180.0) * 180.0 + config.restDegrees
    private fun response(dt: Double, tau: Double) = 1.0 - exp(-dt / tau.coerceAtLeast(.001))
    private fun snap() {
        angle = target
        velocity = 0.0
        pitch = 6.0
        lift = 0.0
        phase = CaseMotionPhase.Idle
    }
}

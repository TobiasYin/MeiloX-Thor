package com.ljyh.mei.thor.upper

import kotlin.math.cos
import kotlin.math.sin

// These exact hundredth-frequency harmonics also appear in the runtime shader. Together with
// the existing harmonics they close at 200π, rather than jumping at a short one-rotation wrap.
internal const val ThorBackdropAdvectXTime = 3.10f
internal const val ThorBackdropAdvectYTime = 2.70f
internal const val ThorBackdropInnerXTime = 2.10f
internal const val ThorBackdropInnerYTime = 1.70f
internal const val ThorBackdropFoldTime = 3.30f

internal data class ThorBackdropFlowOffset(val x: Float, val y: Float)

/** Bounded low-frequency flow for the Canvas backend, sharing the shader's optical motion. */
internal fun thorBackdropAdvection(x: Float, y: Float, phase: Float): ThorBackdropFlowOffset = ThorBackdropFlowOffset(
    sin(y * 6f + sin(x * 3f - phase * ThorBackdropInnerXTime) * .60f + phase * ThorBackdropAdvectXTime),
    cos(x * 5f + cos(y * 3.6f + phase * ThorBackdropInnerYTime) * .55f - phase * ThorBackdropAdvectYTime),
)

/** The broad satin fold travels through the color field; this does not alter palette colors. */
internal fun thorBackdropFoldY(x: Float, phase: Float, advectionY: Float): Float =
    .24f + .42f * x + sin(x * 4.5f - phase * ThorBackdropFoldTime + advectionY * .35f) * .16f

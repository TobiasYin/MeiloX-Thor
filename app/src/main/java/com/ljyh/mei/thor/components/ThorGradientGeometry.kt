package com.ljyh.mei.thor.components

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal data class ThorGradientLine(val startX: Float, val startY: Float, val endX: Float, val endY: Float)

/** CSS 0deg points up, 90deg right, 180deg down; uses CSS's magic-corner line length. */
internal fun thorCssGradientLine(width: Float, height: Float, degrees: Float): ThorGradientLine {
    val radians = Math.toRadians(degrees.toDouble())
    val dx = sin(radians).toFloat()
    val dy = -cos(radians).toFloat()
    val half = (abs(width * dx) + abs(height * dy)) / 2f
    return ThorGradientLine(width / 2f - dx * half, height / 2f - dy * half,
        width / 2f + dx * half, height / 2f + dy * half)
}

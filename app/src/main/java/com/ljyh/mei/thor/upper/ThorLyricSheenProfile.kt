package com.ljyh.mei.thor.upper

import kotlin.math.abs

/** C1 feather: both the opacity and its slope reach zero at each finite reflection edge. */
internal fun thorLyricSheenEnvelope(normalizedDistance: Float): Float {
    if (!normalizedDistance.isFinite()) return 0f
    val d = abs(normalizedDistance).coerceIn(0f, 1f)
    return (1f - d * d * (3f - 2f * d)).coerceIn(0f, 1f)
}

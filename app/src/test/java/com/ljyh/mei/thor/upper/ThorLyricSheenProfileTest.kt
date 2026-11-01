package com.ljyh.mei.thor.upper

import org.junit.Assert.*
import org.junit.Test

class ThorLyricSheenProfileTest {
    @Test fun bothEdgesAndTheirSlopesFadeToZeroWithoutARectangularCut() {
        assertEquals(0f, thorLyricSheenEnvelope(-1f), 0f)
        assertEquals(0f, thorLyricSheenEnvelope(1f), 0f)
        val epsilon = .001f
        assertTrue(thorLyricSheenEnvelope(1f - epsilon) / epsilon < .004f)
        assertTrue(thorLyricSheenEnvelope(-1f + epsilon) / epsilon < .004f)
        assertEquals(0f, thorLyricSheenEnvelope(1.1f), 0f)
    }

    @Test fun reflectionHasOneSoftSymmetricPeakAndNoSecondaryFlashes() {
        assertEquals(1f, thorLyricSheenEnvelope(0f), 0f)
        var previous = 1f
        for (step in 1..100) {
            val distance = step / 100f
            val value = thorLyricSheenEnvelope(distance)
            assertTrue(value <= previous && value >= 0f)
            assertEquals(value, thorLyricSheenEnvelope(-distance), 0f)
            previous = value
        }
    }

    @Test fun invalidGeometryCannotCreateOpaqueOrNonFinitePaint() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE))
            assertEquals(0f, thorLyricSheenEnvelope(value), 0f)
    }
}

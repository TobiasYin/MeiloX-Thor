package com.ljyh.mei.thor.upper

import com.ljyh.mei.thor.model.ThorBackdropCycle
import com.ljyh.mei.thor.model.advanceThorBackdropPhase
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class ThorBackdropPhaseTest {
    private val shaderFrequencies = Regex("""t\s*\*\s*(\d*\.?\d+)""")
        .findAll(ThorBackdropAgsl).map { it.groupValues[1].toDouble() }.toList()

    @Test fun crossingOneRotationDoesNotResetTheNonIntegerShaderHarmonics() {
        val before = 2 * PI - .0001
        val after = advanceThorBackdropPhase(before, .0002)
        assertTrue(after > 2 * PI)
        assertTrue(shaderFrequencies.any { it != it.toInt().toDouble() })
        for (frequency in shaderFrequencies) {
            val bound = .0002 * frequency + .00000001 // |sin/cos derivative| <= 1.
            assertTrue(abs(sin(before * frequency) - sin(after * frequency)) < bound)
            assertTrue(abs(cos(before * frequency) - cos(after * frequency)) < bound)
        }
    }

    @Test fun theActualShaderHarmonicsMeetContinuouslyAtTheLongSessionWrap() {
        val before = ThorBackdropCycle - .0001
        val after = advanceThorBackdropPhase(before, .0002)
        assertEquals(.0001, after, .0000001)
        assertTrue(shaderFrequencies.isNotEmpty())
        for (frequency in shaderFrequencies) {
            assertEquals("Every real shader harmonic must close at 200π", frequency * 100,
                kotlin.math.round(frequency * 100), .0000001)
            val bound = .0002 * frequency + .00000001
            assertTrue("sine seam at $frequency", abs(sin(before * frequency) - sin(after * frequency)) < bound)
            assertTrue("cosine seam at $frequency", abs(cos(before * frequency) - cos(after * frequency)) < bound)
        }
    }

    @Test fun longSessionsRemainBoundedAndKeepSmallPublishedFrameSteps() {
        var phase = ThorBackdropCycle - .01
        repeat(20_000) {
            phase = advanceThorBackdropPhase(phase, .004)
            assertTrue(phase >= 0 && phase < ThorBackdropCycle)
            assertNotEquals(phase.toFloat(), advanceThorBackdropPhase(phase, .004).toFloat())
        }
    }
}

package com.ljyh.mei.thor.upper

import com.ljyh.mei.thor.model.ThorBackdropCycle
import com.ljyh.mei.thor.model.ThorBackgroundIntensity
import com.ljyh.mei.thor.model.ThorBackgroundMode
import com.ljyh.mei.thor.theme.thorBackdropProfile
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class ThorBackdropFlowMotionTest {
    @Test fun twoAndAHalfSecondsProduceSpatialAdvectionInsteadOfOnlyAUniformTranslation() {
        val speed = thorBackdropProfile(ThorBackgroundMode.ArtworkFlow, ThorBackgroundIntensity.Medium, false).speed
        val phase = speed * 2.5f
        val locations = listOf(.12f to .18f, .35f to .42f, .52f to .65f, .8f to .25f, .88f to .8f)
        val changes = locations.map { (x, y) ->
            val before = thorBackdropAdvection(x, y, 0f)
            val after = thorBackdropAdvection(x, y, phase)
            ThorBackdropFlowOffset(after.x - before.x, after.y - before.y)
        }
        assertTrue("The Canvas fallback needs perceptible local flow in the requested observation window",
            changes.sumOf { (abs(it.x) + abs(it.y)).toDouble() } / changes.size > .2)
        assertTrue("The optical field must deform spatially rather than translating a flat sheet",
            changes.maxOf { it.x } - changes.minOf { it.x } > .2f)
        assertTrue("The field must also move vertically", changes.any { abs(it.y) > .25f })
    }

    @Test fun flowAndFoldRemainFiniteAndInsideTheirGeometricBoundsAcrossALongSession() {
        for (step in 0..256) {
            val phase = (ThorBackdropCycle * step / 256).toFloat()
            for (column in 0..8) for (row in 0..6) {
                val x = column / 8f
                val y = row / 6f
                val flow = thorBackdropAdvection(x, y, phase)
                assertTrue(flow.x.isFinite() && flow.x in -1f..1f)
                assertTrue(flow.y.isFinite() && flow.y in -1f..1f)
                val fold = thorBackdropFoldY(x, phase, flow.y)
                assertTrue(fold.isFinite())
                assertTrue(fold >= .08f + .42f * x - .00001f)
                assertTrue(fold <= .40f + .42f * x + .00001f)
            }
        }
    }

    @Test fun theActualCanvasMotionMeetsAtTheSameLongCycleAsTheShader() {
        val end = ThorBackdropCycle.toFloat()
        for (column in 0..8) for (row in 0..6) {
            val x = column / 8f
            val y = row / 6f
            val first = thorBackdropAdvection(x, y, 0f)
            val last = thorBackdropAdvection(x, y, end)
            // Float shader phase has subpixel error at 200π; no perceptible wrap discontinuity.
            assertEquals(first.x, last.x, .0006f)
            assertEquals(first.y, last.y, .0006f)
            assertEquals(thorBackdropFoldY(x, 0f, first.y), thorBackdropFoldY(x, end, last.y), .0002f)
        }
    }
}

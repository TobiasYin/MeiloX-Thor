package com.ljyh.mei.thor.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseGeometryTest {
    @Test fun cssNegativeRollLeansUpwardToTheRightAndPositivePitchTiltsTopAway() {
        val rolled = CaseGeometry.project(CasePose(0.0, 0.0, -5.0)).single { it.face == CaseFace.Front }
        assertTrue(rolled.corners[1].y < rolled.corners[0].y)
        val pitched = CaseGeometry.project(CasePose(0.0, 6.0, 0.0)).single { it.face == CaseFace.Front }
        val top = pitched.corners[1].x - pitched.corners[0].x
        val bottom = pitched.corners[2].x - pitched.corners[3].x
        assertTrue(top < bottom)
    }

    @Test fun displayDensityDoesNotChangeTheSpecifiedPhysicalCaseDepthAndPerspective() {
        val pose = CasePose(-16.0, 6.0, -5.0)
        val logicalHalfSide = 125.0
        val normal = CaseGeometry.project(pose, 900.0 / logicalHalfSide, 14.0 / logicalHalfSide)
        val dense = CaseGeometry.project(pose, (900.0 * 2.30625) / (logicalHalfSide * 2.30625), (14.0 * 2.30625) / (logicalHalfSide * 2.30625))
        normal.zip(dense).forEach { (a, b) -> a.corners.zip(b.corners).forEach { (p, q) ->
            assertEquals(p.x, q.x, 1e-9); assertEquals(p.y, q.y, 1e-9)
        } }
        assertTrue(CaseGeometry.project(pose, cameraDistance = 0.0).isEmpty())
        assertTrue(CaseGeometry.project(pose, depth = Double.NaN).isEmpty())
    }

    @Test fun rearTextureKeepsItsLeftToRightAndTopToBottomOrientation() {
        val front = CaseGeometry.project(CasePose(0.0, 0.0, 0.0)).single { it.face == CaseFace.Front }
        val back = CaseGeometry.project(CasePose(180.0, 0.0, 0.0)).single { it.face == CaseFace.Back }
        for (face in listOf(front, back)) {
            assertTrue(face.corners[0].x < face.corners[1].x)
            assertTrue(face.corners[0].y < face.corners[3].y)
        }
    }

    @Test fun edgeOnViewStillHasThicknessAndDoesNotRenderTheCulledFront() {
        val faces = CaseGeometry.project(CasePose(90.0, 0.0, 0.0))
        assertTrue(faces.isNotEmpty())
        assertTrue(faces.none { it.face == CaseFace.Front || it.face == CaseFace.Back })
        val side = faces.single()
        assertEquals(CaseFace.Left, side.face)
        assertTrue(side.corners.maxOf { it.x } - side.corners.minOf { it.x } > .1)
    }

    @Test fun allAnglesProduceFiniteFacesInBackToFrontOrder() {
        for (yaw in -720..720 step 5) {
            val faces = CaseGeometry.project(CasePose(yaw.toDouble(), 18.0, -5.0))
            assertTrue(faces.size in 1..3)
            assertTrue(faces.flatMap { it.corners }.all { it.x.isFinite() && it.y.isFinite() })
            assertTrue(faces.zipWithNext().all { (a, b) -> a.depth <= b.depth })
        }
        assertTrue(CaseGeometry.project(CasePose(Double.NaN)).isEmpty())
    }
}

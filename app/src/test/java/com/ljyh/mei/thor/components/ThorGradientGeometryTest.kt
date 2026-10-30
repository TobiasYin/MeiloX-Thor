package com.ljyh.mei.thor.components

import org.junit.Assert.*
import org.junit.Test

class ThorGradientGeometryTest {
    @Test fun cssCardinalAnglesMatchTheWebCoordinateSystem() {
        val down = thorCssGradientLine(200f, 80f, 180f)
        assertEquals(0f, down.startY, .001f); assertEquals(80f, down.endY, .001f)
        val right = thorCssGradientLine(200f, 80f, 90f)
        assertEquals(0f, right.startX, .001f); assertEquals(200f, right.endX, .001f)
        val up = thorCssGradientLine(200f, 80f, 0f)
        assertEquals(80f, up.startY, .001f); assertEquals(0f, up.endY, .001f)
    }

    @Test fun ceramicGlintTravelsDownAndRightInsteadOfTheOldCosineDirection() {
        val line = thorCssGradientLine(200f, 80f, 155f)
        assertTrue(line.endX > line.startX); assertTrue(line.endY > line.startY)
        assertEquals(200f, line.startX + line.endX, .001f)
        assertEquals(80f, line.startY + line.endY, .001f)
    }
}

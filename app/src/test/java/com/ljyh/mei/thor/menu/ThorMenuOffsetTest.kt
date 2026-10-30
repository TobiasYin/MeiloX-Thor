package com.ljyh.mei.thor.menu

import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class ThorMenuOffsetTest {
    @Test fun bottomRightAnchorsFlipAboveAndLeftWithoutChangingTheAnchor() {
        assertEquals(IntOffset(220, 80), thorMenuOffset(500f, 440f, 538f, 468f, 280f, 360f, 12f))
    }

    @Test fun offscreenAndInvalidAnchorsStayInsideTheLowerWindow() {
        assertEquals(IntOffset(12, 12), thorMenuOffset(-20f, -10f, 538f, 468f, 280f, 360f, 12f))
        assertEquals(IntOffset(12, 12), thorMenuOffset(Float.NaN, Float.NaN, 538f, 468f, 280f, 360f, 12f))
    }

    @Test fun imeReducedViewportClampsTheSurfaceWithoutASecondCoordinateScale() {
        assertEquals(IntOffset(12, 12), thorMenuOffset(190f, 130f, 200f, 140f, 176f, 116f, 12f))
    }

    @Test fun laterLongSubmenusGrowOnlyInsideTheSpaceBelowTheCachedAnchor() {
        val placement = thorMenuPlacement(500f, 440f, 538f, 468f, 280f, 301f, 360f, 12f)
        assertEquals(IntOffset(220, 139), placement.offset)
        assertEquals(317f, placement.maxHeight, 0f)
        assertEquals(456f, placement.offset.y + placement.maxHeight, 0f)
    }

    @Test fun viewportReductionClampsBothReservedSpaceAndFutureGrowth() {
        val placement = thorMenuPlacement(190f, 130f, 200f, 140f, 176f, 301f, 360f, 12f)
        assertEquals(IntOffset(12, 12), placement.offset)
        assertEquals(116f, placement.maxHeight, 0f)
    }
}

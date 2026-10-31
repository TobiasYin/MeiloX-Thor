package com.ljyh.mei.thor.upper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThorLyricLayoutTest {
    @Test
    fun firstAndLastRowsCanReachTheSameUnfadedAnchorWithoutExtraCorrection() {
        val viewport = thorLyricViewportLayout(1_080, 55f, 83f, 110)
        assertTrue(viewport.anchorPx >= viewport.fadeTopPx)
        assertTrue(viewport.anchorPx < 1_080 - viewport.fadeBottomPx)
        // First item: no negative scroll is needed merely to get below the upper mask.
        assertEquals(viewport.anchorPx, viewport.topPaddingPx)
        // Last item: both a short and a multiline translated row can reach that same anchor.
        val lastItemStart = 2_400
        for (rowHeight in listOf(110, 340)) {
            val contentHeight = viewport.topPaddingPx + lastItemStart + rowHeight + viewport.bottomPaddingPx
            val maximumScroll = contentHeight - 1_080
            val desiredScroll = viewport.topPaddingPx + lastItemStart - viewport.anchorPx
            assertTrue("A multiline final row must not require a second clamp-and-correct jump",
                maximumScroll >= desiredScroll)
        }
    }

    @Test
    fun aSmallViewportKeepsAClearMiddleAndAnAnchorOutsideBothFades() {
        val viewport = thorLyricViewportLayout(100, 55f, 83f, 48)
        assertTrue(viewport.fadeTopPx + viewport.fadeBottomPx <= 40f)
        assertTrue(viewport.anchorPx >= viewport.fadeTopPx)
        assertTrue(viewport.anchorPx < 100 - viewport.fadeBottomPx)
        assertTrue(viewport.topPaddingPx >= 0)
        assertTrue(viewport.bottomPaddingPx >= 0)
    }

    @Test
    fun anUnmeasuredViewportDoesNotProduceInvalidMaskOrPaddingCoordinates() {
        for (height in listOf(0, -1)) {
            val viewport = thorLyricViewportLayout(height, Float.NaN, Float.POSITIVE_INFINITY, 48)
            assertEquals(0, viewport.anchorPx)
            assertEquals(0, viewport.topPaddingPx)
            assertEquals(0, viewport.bottomPaddingPx)
            assertEquals(0f, viewport.fadeTopPx, 0f)
            assertEquals(0f, viewport.fadeBottomPx, 0f)
        }
    }

    @Test
    fun aMeasuredNeighbourUsesItsExactSignedDistanceIncludingContentPaddingCoordinates() {
        // With 80px beforeContentPadding, viewportStart=-80 and the physical 80px
        // focus point is item offset 0. It must not be treated as a second +80px offset.
        val logicalAnchor = -80 + 80
        val below = thorLyricFollowPlan(3, 127, logicalAnchor, positioned = true, reduceMotion = false)
        val above = thorLyricFollowPlan(2, -91, logicalAnchor, positioned = true, reduceMotion = false)
        assertEquals(ThorLyricFollowPlan.AnimateBy(127f), below)
        assertEquals(ThorLyricFollowPlan.AnimateBy(-91f), above)
    }

    @Test
    fun firstPositionReducedMotionAndUnrealizedTargetsNeverGuessAnAnimatedDistance() {
        assertEquals(ThorLyricFollowPlan.Snap(24),
            thorLyricFollowPlan(24, null, 0, positioned = true, reduceMotion = false))
        assertEquals(ThorLyricFollowPlan.Snap(2),
            thorLyricFollowPlan(2, 175, 0, positioned = false, reduceMotion = false))
        assertEquals(ThorLyricFollowPlan.Snap(3),
            thorLyricFollowPlan(3, 175, 0, positioned = true, reduceMotion = true))
    }
}

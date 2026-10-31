package com.ljyh.mei.thor.upper

import org.junit.Assert.*
import org.junit.Test

class ThorLyricBrowseStateTest {
    private fun idleBrowse(atMs: Long = 1_000): ThorLyricBrowseState = ThorLyricBrowseState().apply {
        onTouchStarted(0)
        onDragStarted(10)
        onScrollChanged(true, 11)
        onDragStopped(atMs - 1)
        onTouchEnded(atMs - 1)
        onScrollChanged(false, atMs)
    }

    @Test fun automaticScrollDoesNotEnterBrowsingOrBlockFollowing() {
        val state = ThorLyricBrowseState()
        assertTrue(state.canFollow)
        state.onScrollChanged(true, 100)
        assertTrue(state.isScrolling)
        assertFalse(state.isBrowsing)
        assertTrue(state.canFollow)
        assertNull(state.returnAtMs)
        state.onScrollChanged(false, 500)
        assertFalse(state.resumeIfIdle(50_000))
        assertTrue(state.canFollow)
    }

    @Test fun touchingPausesFollowingButAnOrdinaryTapDoesNotStartBrowseMode() {
        val state = ThorLyricBrowseState()
        state.onTouchStarted(100)
        assertTrue(state.isTouching)
        assertFalse(state.isBrowsing)
        assertFalse(state.canFollow)
        assertNull(state.returnAtMs)
        state.onTouchEnded(400)
        assertTrue(state.canFollow)
        assertFalse(state.isBrowsing)
        assertNull(state.returnAtMs)
    }

    @Test fun browseReturnsAtExactlyFifteenSecondsAfterAllMotionEnds() {
        val state = idleBrowse(1_000)
        assertTrue(state.isBrowsing)
        assertFalse(state.canFollow)
        assertEquals(16_000L, state.returnAtMs)
        assertFalse(state.resumeIfIdle(15_999))
        assertTrue(state.isBrowsing)
        assertTrue(state.resumeIfIdle(16_000))
        assertTrue(state.canFollow)
        assertFalse(state.isBrowsing)
        assertNull(state.returnAtMs)
        assertFalse(state.resumeIfIdle(16_001))
    }

    @Test fun flingTimeDoesNotCountTowardsTheIdleDeadline() {
        val state = ThorLyricBrowseState()
        state.onTouchStarted(0)
        state.onDragStarted(100)
        state.onScrollChanged(true, 101)
        state.onDragStopped(1_000)
        state.onTouchEnded(1_001)
        assertFalse(state.isDragging)
        assertFalse(state.isTouching)
        assertTrue(state.isScrolling)
        assertNull(state.returnAtMs)
        assertFalse(state.resumeIfIdle(60_000))
        state.onScrollChanged(false, 60_100)
        assertEquals(75_100L, state.returnAtMs)
        assertFalse(state.resumeIfIdle(75_099))
        assertTrue(state.resumeIfIdle(75_100))
    }

    @Test fun repeatedIdleNotificationsDoNotExtendAnAlreadyScheduledDeadline() {
        val state = idleBrowse(1_000)
        state.onScrollChanged(false, 2_000)
        state.onDragStopped(3_000)
        state.onTouchEnded(4_000)
        assertEquals(16_000L, state.returnAtMs)
        assertTrue(state.resumeIfIdle(16_000))
    }

    @Test fun aNewTouchCancelsTheOldTimerAndStartsANewOneOnRelease() {
        val state = idleBrowse(1_000)
        state.onTouchStarted(15_900)
        assertTrue(state.isBrowsing)
        assertNull(state.returnAtMs)
        assertFalse(state.resumeIfIdle(16_000))
        assertFalse(state.resumeIfIdle(40_000))
        state.onTouchEnded(40_100)
        assertEquals(55_100L, state.returnAtMs)
        assertFalse(state.resumeIfIdle(55_099))
        assertTrue(state.resumeIfIdle(55_100))
    }

    @Test fun anotherDragCancelsTheOldTimerEvenWithoutATouchNotification() {
        val state = idleBrowse(1_000)
        state.onDragStarted(10_000)
        assertTrue(state.isDragging)
        assertFalse(state.canFollow)
        assertNull(state.returnAtMs)
        assertFalse(state.resumeIfIdle(16_000))
        state.onDragStopped(20_000)
        assertEquals(35_000L, state.returnAtMs)
        assertTrue(state.resumeIfIdle(35_000))
    }

    @Test fun renewedScrollingCancelsTheTimerUntilTheLastScrollEnds() {
        val state = idleBrowse(1_000)
        state.onScrollChanged(true, 10_000)
        assertNull(state.returnAtMs)
        assertFalse(state.resumeIfIdle(16_000))
        state.onScrollChanged(false, 20_000)
        assertEquals(35_000L, state.returnAtMs)
        assertFalse(state.resumeIfIdle(34_999))
        assertTrue(state.resumeIfIdle(35_000))
    }

    @Test fun theLastOfTouchDragAndScrollSignalsOwnsTheIdleStart() {
        for (last in listOf("touch", "drag", "scroll")) {
            val state = ThorLyricBrowseState()
            state.onTouchStarted(0)
            state.onDragStarted(1)
            state.onScrollChanged(true, 2)
            if (last != "touch") state.onTouchEnded(1_000)
            if (last != "drag") state.onDragStopped(1_001)
            if (last != "scroll") state.onScrollChanged(false, 1_002)
            assertNull(state.returnAtMs)
            when (last) {
                "touch" -> state.onTouchEnded(2_000)
                "drag" -> state.onDragStopped(2_000)
                "scroll" -> state.onScrollChanged(false, 2_000)
            }
            assertEquals(17_000L, state.returnAtMs)
        }
    }

    @Test fun selectingALineImmediatelyClearsTheGestureAndReturnsToFollow() {
        val state = idleBrowse()
        state.onTouchStarted(2_000)
        state.onDragStarted(2_001)
        state.onScrollChanged(true, 2_002)
        state.onLineSelected()
        assertCleared(state)
        assertFalse(state.resumeIfIdle(50_000))
        // The seek may start a following animation. That is not another manual browse.
        state.onScrollChanged(true, 50_100)
        assertTrue(state.canFollow)
        assertFalse(state.isBrowsing)
        assertNull(state.returnAtMs)
    }

    @Test fun hidingOrChangingTrackClearsBothTheGestureAndAnyScheduledReturn() {
        val state = idleBrowse()
        val oldDeadline = checkNotNull(state.returnAtMs)
        state.onHidden()
        assertCleared(state)
        assertFalse(state.resumeIfIdle(oldDeadline))
        state.onTouchStarted(20_000)
        state.onDragStarted(20_001)
        state.onScrollChanged(true, 20_002)
        state.onHidden()
        assertCleared(state)
        // Late completion signals from the disposed list cannot recreate browse mode.
        state.onDragStopped(20_100)
        state.onTouchEnded(20_101)
        state.onScrollChanged(false, 20_102)
        assertCleared(state)
    }

    private fun assertCleared(state: ThorLyricBrowseState) {
        assertFalse(state.isBrowsing)
        assertFalse(state.isTouching)
        assertFalse(state.isDragging)
        assertFalse(state.isScrolling)
        assertTrue(state.canFollow)
        assertNull(state.returnAtMs)
    }
}

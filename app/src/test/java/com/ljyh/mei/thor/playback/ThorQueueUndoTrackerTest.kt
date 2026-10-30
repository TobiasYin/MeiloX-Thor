package com.ljyh.mei.thor.playback

import org.junit.Assert.*
import org.junit.Test

class ThorQueueUndoTrackerTest {
    @Test fun afterInsertedAHasPlayedUndoRemovesOnlyStillPendingB() {
        val tracker = ThorQueueUndoTracker()
        tracker.beginEpoch()
        tracker.observeCurrent("original")
        val insertedAt = tracker.epoch
        val inserted = setOf("a", "b")
        assertEquals(inserted, tracker.insertionUndoTargets(insertedAt, inserted, "original"))
        tracker.observeCurrent("a")
        // The user returned to the original track; A is history and B is still pending.
        tracker.observeCurrent("original")
        assertEquals(setOf("b"), tracker.insertionUndoTargets(insertedAt, inserted, "original"))
    }

    @Test fun currentlyPlayingBIsProtectedAlongWithPreviouslyPlayedA() {
        val tracker = ThorQueueUndoTracker()
        val insertedAt = tracker.epoch
        tracker.observeCurrent("a")
        tracker.observeCurrent("b")
        assertEquals(emptySet<String>(), tracker.insertionUndoTargets(insertedAt, setOf("a", "b"), "b"))
    }

    @Test fun currentEntryIsProtectedEvenBeforeItsTransitionCallbackArrives() {
        val tracker = ThorQueueUndoTracker()
        assertEquals(setOf("b"), tracker.insertionUndoTargets(tracker.epoch, setOf("a", "b"), "a"))
    }

    @Test fun oldEpochCannotUndoIntoAReplacementQueueEvenWhenIdentitiesAreReused() {
        val tracker = ThorQueueUndoTracker()
        val old = tracker.epoch
        tracker.observeCurrent("a")
        tracker.beginEpoch()
        assertNull(tracker.insertionUndoTargets(old, setOf("a", "b"), "original"))
        assertEquals(setOf("a", "b"), tracker.insertionUndoTargets(tracker.epoch, setOf("a", "b"), "original"))
    }
}

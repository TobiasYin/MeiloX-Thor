package com.ljyh.mei.thor.ui

import com.ljyh.mei.thor.ThorNotice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThorNoticeLifetimeTest {
    @Test fun ordinarySuccessExpiresAtThreeAndAHalfSeconds() = runTest {
        val expired = mutableListOf<Long>()
        launch { awaitThorNoticeDismiss(10L, true, false, { 10L }, { expired += it }) }
        runCurrent()
        advanceTimeBy(3_499); runCurrent()
        assertTrue(expired.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(10L), expired)
    }

    @Test fun undoSuccessRemainsAvailableForSixSeconds() = runTest {
        val expired = mutableListOf<Long>()
        launch { awaitThorNoticeDismiss(10L, true, true, { 10L }, { expired += it }) }
        runCurrent()
        advanceTimeBy(5_999); runCurrent()
        assertTrue(expired.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(10L), expired)
    }

    @Test fun errorsRemainUntilExplicitlyClearedEvenIfTheyCarryAnUndoToken() = runTest {
        val expired = mutableListOf<Long>()
        for (hasUndo in listOf(false, true)) launch {
            awaitThorNoticeDismiss(10L, false, hasUndo, { 10L }, { expired += it })
        }
        advanceTimeBy(60_000); runCurrent()
        assertTrue(expired.isEmpty())
    }

    @Test fun anOldDeadlineCannotExpireTheReplacementNoticeEvenWithoutCancellation() = runTest {
        var currentId: Long? = 10L
        val expired = mutableListOf<Long>()
        launch { awaitThorNoticeDismiss(10L, true, false, { currentId }, { expired += it }) }
        runCurrent()
        advanceTimeBy(2_000)
        currentId = 11L
        advanceTimeBy(1_500); runCurrent()
        assertTrue(expired.isEmpty())
        assertEquals(11L, currentId)
    }

    @Test fun repeatingTheSameMessageHasANewIdentityAndAFullNewLifetime() = runTest {
        val first = ThorNotice("QA 已喜欢")
        val second = ThorNotice("QA 已喜欢")
        assertNotEquals(first.id, second.id)
        var currentId: Long? = first.id
        val expired = mutableListOf<Long>()
        launch { awaitThorNoticeDismiss(first.id, true, false, { currentId }, { expired += it }) }
        runCurrent()
        advanceTimeBy(2_000)
        currentId = second.id
        launch { awaitThorNoticeDismiss(second.id, true, false, { currentId }, { expired += it }) }
        runCurrent()
        advanceTimeBy(3_499); runCurrent()
        assertTrue(expired.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(second.id), expired)
    }

    @Test fun disposingTheNoticeCancelsItsPendingTimeout() = runTest {
        val expired = mutableListOf<Long>()
        val job = launch { awaitThorNoticeDismiss(10L, true, true, { 10L }, { expired += it }) }
        runCurrent()
        advanceTimeBy(1_000)
        job.cancel()
        advanceTimeBy(10_000); runCurrent()
        assertTrue(expired.isEmpty())
    }
}

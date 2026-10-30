package com.ljyh.mei.thor.data

import com.ljyh.mei.data.repository.LibraryAccount
import com.ljyh.mei.thor.model.ThorAccountState
import com.ljyh.mei.thor.model.ThorSessionType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Actual fence policy with deterministic suspension gates; no Context, DataStore or network. */
class ThorAccountFenceTest {
    private val first = LibraryAccount("7", "synthetic-first-session")
    private val second = LibraryAccount("8", "synthetic-second-session")
    private fun publicAccount(ticket: ThorAccountTicket) = ThorAccountState(
        userId = ticket.account?.userId, sessionType = ThorSessionType.Web, generation = ticket.generation,
    )

    @Test fun anOldPublicProjectionCannotAcceptACreateAfterInternalAccountSwitch() = runBlocking {
        val fence = ThorAccountFence(Dispatchers.Unconfined)
        val oldPublic = publicAccount(fence.changeAccount(first))
        val current = fence.changeAccount(second)
        var createCalls = 0
        val ticket = fence.capture(oldPublic)
        if (ticket != null) fence.withTicket(ticket) { fence.mutate { createCalls++ } }
        assertNull(ticket)
        assertEquals(0, createCalls)
        assertNull(fence.capture(oldPublic.copy(generation = current.generation)))
        assertEquals("ThorAccountTicket", current.toString())
    }

    @Test fun switchingDuringLoadNeverRebindsTheAcceptedCreateToTheNewAccount() = runBlocking {
        withTimeout(10_000) {
            val fence = ThorAccountFence(Dispatchers.Unconfined)
            val oldPublic = publicAccount(fence.changeAccount(first))
            val ticket = requireNotNull(fence.capture(oldPublic))
            val loadStarted = CompletableDeferred<Unit>()
            val finishLoad = CompletableDeferred<Unit>()
            var createCalls = 0
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    fence.withTicket(ticket) {
                        assertSame(ticket, fence.ticketForCall())
                        loadStarted.complete(Unit)
                        finishLoad.await()
                        fence.mutate { createCalls++ }
                    }
                }
            }
            loadStarted.await()
            fence.changeAccount(second)
            // Deliberately leave oldPublic unchanged: UI projection has not caught up.
            finishLoad.complete(Unit)
            assertTrue(pending.await().isFailure)
            assertEquals(0, createCalls)
            assertNull(fence.capture(oldPublic))
        }
    }

    @Test fun aMutationAlreadyWaitingForTheLockKeepsItsOriginalTicket() = runBlocking {
        withTimeout(10_000) {
            val fence = ThorAccountFence(Dispatchers.Unconfined)
            val ticket = fence.changeAccount(first)
            val lockEntered = CompletableDeferred<Unit>()
            val unlock = CompletableDeferred<Unit>()
            val blocker = async(start = CoroutineStart.UNDISPATCHED) {
                fence.mutate { lockEntered.complete(Unit); unlock.await() }
            }
            lockEntered.await()
            var writes = 0
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { fence.withTicket(ticket) { fence.mutate { writes++ } } }
            }
            assertFalse(queued.isCompleted)
            fence.changeAccount(second)
            unlock.complete(Unit)
            blocker.await()
            assertTrue(queued.await().isFailure)
            assertEquals(0, writes)
        }
    }

    @Test fun anUndoWaitingForTheLockCannotRunItsGuardOrWriteUnderANewSession() = runBlocking {
        withTimeout(10_000) {
            val fence = ThorAccountFence(Dispatchers.Unconfined)
            val ticket = fence.changeAccount(first)
            var guardCalls = 0
            var undoWrites = 0
            val undo = fence.withTicket(ticket) {
                fence.mutate {
                    fence.freezeUndo(guard = { guardCalls++ }) { fence.mutate { undoWrites++ } }
                }
            }
            val lockEntered = CompletableDeferred<Unit>()
            val unlock = CompletableDeferred<Unit>()
            val blocker = async(start = CoroutineStart.UNDISPATCHED) {
                fence.mutate { lockEntered.complete(Unit); unlock.await() }
            }
            lockEntered.await()
            val queuedUndo = async(start = CoroutineStart.UNDISPATCHED) { runCatching { undo() } }
            assertFalse(queuedUndo.isCompleted)
            fence.changeAccount(second)
            unlock.complete(Unit)
            blocker.await()
            assertTrue(queuedUndo.await().isFailure)
            assertEquals(0, guardCalls)
            assertEquals(0, undoWrites)
        }
    }

    @Test fun returningToTheSameAccountDoesNotReviveAnOldUndoGeneration() = runBlocking {
        val fence = ThorAccountFence(Dispatchers.Unconfined)
        val ticket = fence.changeAccount(first)
        var writes = 0
        val undo = fence.withTicket(ticket) { fence.freezeUndo(guard = {}) { fence.mutate { writes++ } } }
        fence.changeAccount(second)
        fence.changeAccount(first)
        assertTrue(runCatching { undo() }.isFailure)
        assertEquals(0, writes)
        assertFalse(fence.isCurrent(ticket))
    }

    @Test fun aSessionChangeDuringAnUndoGuardPreventsTheWrite() = runBlocking {
        withTimeout(10_000) {
            val fence = ThorAccountFence(Dispatchers.Unconfined)
            val ticket = fence.changeAccount(first)
            val guardStarted = CompletableDeferred<Unit>()
            val finishGuard = CompletableDeferred<Unit>()
            var writes = 0
            val undo = fence.withTicket(ticket) {
                fence.freezeUndo(guard = { guardStarted.complete(Unit); finishGuard.await() }) {
                    fence.mutate { writes++ }
                }
            }
            val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { undo() } }
            guardStarted.await()
            fence.changeAccount(second)
            finishGuard.complete(Unit)
            assertTrue(pending.await().isFailure)
            assertEquals(0, writes)
        }
    }

    @Test fun acceptedWorkKeepsTheSameTicketAcrossSuspensionAndUiChanges() = runBlocking {
        withTimeout(10_000) {
            val fence = ThorAccountFence(Dispatchers.Unconfined)
            val ticket = fence.changeAccount(first)
            val started = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            var writes = 0
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                fence.withTicket(ticket) {
                    started.complete(Unit)
                    resume.await()
                    fence.mutate { account ->
                        assertEquals(first, account)
                        assertSame(ticket, fence.ticketForCall())
                        writes++
                    }
                }
            }
            started.await()
            assertSame(ticket, fence.capture(publicAccount(ticket).copy(nickname = "Updated UI")))
            // Closing a menu changes UI state, not account identity or this accepted scope.
            resume.complete(Unit)
            pending.await()
            assertEquals(1, writes)
        }
    }
}

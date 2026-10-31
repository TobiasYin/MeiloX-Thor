package com.ljyh.mei.thor.data

import com.ljyh.mei.data.model.PlaylistOrderChangedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the production orchestration with the server's observed reversed-prepend behavior. */
class ThorPlaylistAppendTest {
    @Test fun existingMembersAndRepeatedIncomingIdsAppendInSelectionOrderAfterServerPrepending() = runBlocking {
        val before = listOf(1L, 2L)
        val plan = thorPlaylistAppendPlan(before, listOf(3L, 4L, 3L))
        var server = before
        val additions = mutableListOf<List<Long>>()
        val orderWrites = mutableListOf<Pair<List<Long>, List<Long>>>()
        val result = applyThorPlaylistAppend(plan,
            addMissing = { missing -> additions += missing; server = missing.reversed() + server },
            readCurrent = { server.toList() },
            saveOrder = { observed, expected ->
                // The real repository re-reads and compares this exact observed order before write.
                assertEquals(listOf(4L, 3L, 1L, 2L), observed)
                assertEquals(server, observed)
                orderWrites += observed to expected
                server = expected
            })
        assertEquals(listOf(listOf(3L, 4L)), additions)
        assertEquals(listOf(1L, 2L, 3L, 4L), result)
        assertEquals(result, server)
        assertEquals(listOf(1L, 2L), plan.before)
        assertEquals(listOf(listOf(4L, 3L, 1L, 2L) to listOf(1L, 2L, 3L, 4L)), orderWrites)
    }

    @Test fun addingOnlyExistingMembersDoesNotReadAddOrRewriteTheOrder() = runBlocking {
        val before = listOf(1L, 2L, 3L, 4L)
        val plan = thorPlaylistAppendPlan(before, listOf(3L, 4L, 3L, 1L))
        val calls = mutableListOf<String>()
        val result = applyThorPlaylistAppend(plan,
            addMissing = { calls += "add" },
            readCurrent = { calls += "read"; before.reversed() },
            saveOrder = { _, _ -> calls += "order" })
        assertTrue(plan.added.isEmpty())
        assertEquals(before, result)
        assertTrue("A duplicate add must not mutate even an independently reordered server", calls.isEmpty())
    }

    @Test fun selectionDeduplicatesByIdWithoutMovingAnyExistingMember() {
        val plan = thorPlaylistAppendPlan(listOf(5L, 1L, 2L), listOf(2L, 4L, 1L, 3L, 4L))
        assertEquals(listOf(4L, 3L), plan.added)
        assertEquals(listOf(5L, 1L, 2L, 4L, 3L), plan.expected)
    }

    @Test fun anAlreadyCorrectServerOrderNeedsNoExtraOrderWrite() = runBlocking {
        val plan = thorPlaylistAppendPlan(listOf(1L, 2L), listOf(3L, 4L))
        var reads = 0
        var orders = 0
        var server = plan.before
        val result = applyThorPlaylistAppend(plan,
            addMissing = { server = server + it },
            readCurrent = { reads++; server },
            saveOrder = { _, _ -> orders++ })
        assertEquals(plan.expected, result)
        assertEquals(1, reads)
        assertEquals(0, orders)
    }

    @Test fun aConcurrentNewMemberIsNeverDroppedOrCoarselyRolledBack() = runBlocking {
        val plan = thorPlaylistAppendPlan(listOf(1L, 2L), listOf(3L, 4L))
        var server = plan.before
        var orderWrites = 0
        val failure = runCatching {
            applyThorPlaylistAppend(plan,
                addMissing = { server = it.reversed() + server + 99L },
                readCurrent = { server },
                saveOrder = { _, expected -> orderWrites++; server = expected })
        }.exceptionOrNull()
        assertTrue(failure is PlaylistOrderChangedException)
        assertEquals(listOf(4L, 3L, 1L, 2L, 99L), server)
        assertEquals(0, orderWrites)
    }

    @Test fun preexistingMembersReorderedBeforeTheFirstReadRejectSortingAndPartialUndo() = runBlocking {
        val plan = thorPlaylistAppendPlan(listOf(1L, 2L), listOf(3L, 4L))
        var server = plan.before
        var orderWrites = 0
        val failure = runCatching {
            applyThorPlaylistAppend(plan,
                addMissing = { server = it.reversed() + plan.before.reversed() },
                readCurrent = { server },
                saveOrder = { _, expected -> orderWrites++; server = expected })
        }.exceptionOrNull()
        assertTrue(failure is PlaylistOrderChangedException)
        assertEquals(listOf(4L, 3L, 2L, 1L), server)
        assertEquals(0, orderWrites)
        assertTrue("An exact membership alone cannot authorize partial-failure Undo",
            !hasUnchangedThorPlaylistBaseline(plan, server))
    }

    @Test fun partialUndoEligibilityRequiresCompleteMembershipAndUnchangedOriginalSubsequence() {
        val plan = thorPlaylistAppendPlan(listOf(1L, 2L), listOf(3L, 4L))
        for (actual in listOf(listOf(1L, 2L, 3L, 4L), listOf(4L, 3L, 1L, 2L), listOf(3L, 1L, 4L, 2L))) {
            assertTrue(hasUnchangedThorPlaylistBaseline(plan, actual))
        }
        for (actual in listOf(listOf(4L, 3L, 2L, 1L), listOf(4L, 3L, 1L, 2L, 99L),
            listOf(3L, 1L, 2L), listOf(4L, 3L, 1L, 2L, 2L))) {
            assertTrue(!hasUnchangedThorPlaylistBaseline(plan, actual))
        }
        val newPlaylist = thorPlaylistAppendPlan(emptyList(), listOf(3L, 4L))
        assertTrue(hasUnchangedThorPlaylistBaseline(newPlaylist, listOf(4L, 3L)))
    }

    @Test fun partialServerAdditionOrDuplicateMembershipCannotBeReportedAsOrderedSuccess() = runBlocking {
        val plan = thorPlaylistAppendPlan(listOf(1L, 2L), listOf(3L, 4L))
        for (actual in listOf(listOf(3L, 1L, 2L), listOf(4L, 3L, 1L, 2L, 2L))) {
            var orderWrites = 0
            val failure = runCatching {
                applyThorPlaylistAppend(plan, addMissing = {}, readCurrent = { actual },
                    saveOrder = { _, _ -> orderWrites++ })
            }.exceptionOrNull()
            assertTrue(failure is PlaylistOrderChangedException)
            assertEquals(0, orderWrites)
        }
    }

    @Test fun aConditionalOrderRejectionOrCancellationDoesNotTriggerAnUnconditionalRetry() = runBlocking {
        val plan = thorPlaylistAppendPlan(listOf(1L, 2L), listOf(3L, 4L))
        var attempts = 0
        var server = listOf(4L, 3L, 1L, 2L)
        val changed = runCatching {
            applyThorPlaylistAppend(plan, addMissing = {}, readCurrent = { server },
                saveOrder = { _, _ ->
                    attempts++
                    server = listOf(2L, 1L, 4L, 3L) // Another client edited after the first read.
                    throw PlaylistOrderChangedException()
                })
        }.exceptionOrNull()
        assertTrue(changed is PlaylistOrderChangedException)
        assertEquals(1, attempts)
        assertEquals(listOf(2L, 1L, 4L, 3L), server)

        val cancelled = CancellationException("synthetic cancellation")
        val actual = runCatching {
            applyThorPlaylistAppend(plan, addMissing = {}, readCurrent = { throw cancelled },
                saveOrder = { _, _ -> error("Cancelled request must not order") })
        }.exceptionOrNull()
        assertTrue(actual === cancelled)
    }
}

package com.ljyh.mei.thor.playback

import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.SongPlaybackAvailability
import com.ljyh.mei.data.model.isExplicitlyUnavailable
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class ThorPlaybackSelectionTest {
    private fun song(id: Long, availability: SongPlaybackAvailability = SongPlaybackAvailability.Unknown,
        local: Boolean = false, podcast: Boolean = false, title: String = "QA $id") =
        MediaMetadata(id, title, "", emptyList(), 60_000, MediaMetadata.Album(1, "QA"),
            isLocal = local, isPodcast = podcast, availability = availability,
            availabilityReason = if (availability == SongPlaybackAvailability.Unavailable) "当前歌曲暂不可播放" else null)

    private val unavailable = SongPlaybackAvailability.Unavailable
    private val playable = SongPlaybackAvailability.Playable

    @Test fun aMixedListRemapsTheExactMiddleSelectionAfterFiltering() {
        val tracks = listOf(song(1, unavailable), song(2), song(3, unavailable), song(4, playable), song(5))
        val plan = (thorSelectedPlayback(tracks, 3) as ThorPlaybackDecision.Accepted).selection
        assertEquals(listOf(1, 3, 4), plan.sourceIndices)
        assertEquals(listOf(2L, 4L, 5L), plan.tracks.map { it.id })
        assertEquals(1, plan.index)
        assertSame(tracks[3], plan.tracks[plan.index])
    }

    @Test fun selectingAnUnavailableSongNeverCommitsOrSubstitutesTheNextSong() {
        val existing = mutableListOf(song(90), song(91))
        val original = existing.toList()
        val rejected = mutableListOf<String>()
        var selected = 1
        var commits = 0
        val result = commitThorPlayback(thorSelectedPlayback(listOf(song(1, unavailable), song(2)), 0),
            rejected::add) { plan -> commits++; existing.clear(); existing.addAll(plan.tracks); selected = plan.index }
        assertFalse(result)
        assertEquals(listOf(THOR_NO_PLAYBACK_PERMISSION), rejected)
        assertEquals(0, commits)
        assertEquals(original, existing)
        assertEquals("The currently playing queue occurrence stays selected", 1, selected)
    }

    @Test fun anAllUnavailableBulkRequestLeavesTheExistingQueueUntouched() {
        val queue = mutableListOf(song(90))
        var commits = 0
        val accepted = commitThorPlayback(thorBulkPlayback(listOf(song(1, unavailable), song(2, unavailable)), true),
            {}) { plan -> commits++; queue.clear(); queue.addAll(plan.tracks) }
        assertFalse(accepted)
        assertEquals(0, commits)
        assertEquals(listOf(90L), queue.map { it.id })
    }

    @Test fun emptyAndStaleIndexRequestsAreRejectedBeforeCommit() {
        for ((tracks, index) in listOf(emptyList<MediaMetadata>() to 0,
            listOf(song(1)) to -1, listOf(song(1)) to 1)) {
            var committed = false
            assertFalse(commitThorPlayback(thorSelectedPlayback(tracks, index), {}) { committed = true })
            assertFalse(committed)
        }
    }

    @Test fun unknownLocalAndPodcastSongsRemainCandidates() {
        val tracks = listOf(song(1), song(-2, unavailable, local = true), song(3, unavailable, podcast = true),
            song(4, playable), song(5, unavailable))
        assertEquals(tracks.take(4), thorPlayableTracks(tracks))
        for (index in 0..3) assertTrue(thorSelectedPlayback(tracks, index) is ThorPlaybackDecision.Accepted)
    }

    @Test fun duplicateSongIdsRetainTheirObjectsOrderAndSelectedOccurrence() {
        val first = song(7, playable, title = "First occurrence")
        val blocked = song(7, unavailable, title = "Denied occurrence")
        val second = song(7, playable, title = "Second occurrence")
        val plan = (thorSelectedPlayback(listOf(first, blocked, second), 2) as ThorPlaybackDecision.Accepted).selection
        assertEquals(listOf(0, 2), plan.sourceIndices)
        assertEquals(2, plan.tracks.size)
        assertSame(first, plan.tracks[0]); assertSame(second, plan.tracks[1])
        assertEquals(1, plan.index)
        assertEquals(listOf(first, second), thorPlayableTracks(listOf(first, blocked, second)))
    }

    @Test fun bulkPlayStartsAtTheFirstAdmissibleSongWhenTheOriginalHeadIsUnavailable() {
        val tracks = listOf(song(1, unavailable), song(2), song(3, playable))
        val plan = (thorBulkPlayback(tracks, false) as ThorPlaybackDecision.Accepted).selection
        assertEquals(0, plan.index)
        assertEquals(2L, plan.tracks[plan.index].id)
        assertEquals(listOf(1, 2), plan.sourceIndices)
    }

    @Test fun shuffleRandomnessReceivesOnlyTheFilteredCandidateCount() {
        val random = object : Random() {
            var bound = -1
            override fun nextBits(bitCount: Int) = 0
            override fun nextInt(until: Int): Int { bound = until; return until - 1 }
        }
        val tracks = listOf(song(1, unavailable), song(2), song(3, unavailable), song(4, playable))
        val plan = (thorBulkPlayback(tracks, true, random) as ThorPlaybackDecision.Accepted).selection
        assertEquals(2, random.bound)
        assertEquals(4L, plan.tracks[plan.index].id)
        assertFalse(plan.tracks.any { it.isExplicitlyUnavailable })
    }

    @Test fun filteringNeverSortsOrMutatesTheSourceList() {
        val tracks = mutableListOf(song(9), song(2, unavailable), song(8), song(9))
        val before = tracks.toList()
        val filtered = thorPlayableTracks(tracks)
        assertEquals(before, tracks)
        assertEquals(listOf(9L, 8L, 9L), filtered.map { it.id })
        assertSame(tracks[3], filtered[2])
    }

    @Test fun nextUsesTheProvidedShuffledTimelineAndSkipsOnlyKnownUnavailablePositions() {
        val tracks = listOf(song(7, unavailable), song(7, playable), song(8), song(7, playable))
        val order = listOf(1, 0, 3, 2)
        val target = thorAvailableQueueIndex(1, tracks.size,
            step = { order.getOrNull(order.indexOf(it) + 1) ?: -1 },
            available = { !tracks[it].isExplicitlyUnavailable })
        assertEquals(3, target)
        assertEquals("The other occurrence of the same song remains a separate queue position", 7L, tracks[target!!].id)
    }

    @Test fun aDeniedRepeatCycleTerminatesWithoutSelectingSomethingElseOrLoopingForever() {
        var visits = 0
        assertNull(thorAvailableQueueIndex(0, 3, step = { (it + 1) % 3 },
            available = { visits++; false }))
        assertEquals(3, visits)
        assertNull(thorAvailableQueueIndex(0, 1, step = { 0 }, available = { false }))
    }

    @Test fun aMissingCurrentQueuePositionNeverQueriesTheTimeline() {
        assertNull(thorAvailableQueueIndex(-1, 3, step = { error("No current timeline window") }, available = { true }))
        assertNull(thorAvailableQueueIndex(0, 0, step = { error("Empty timeline") }, available = { true }))
    }

    @Test fun anAccountChangeExpiresOnlyTheOldQueueEntryVerdicts() {
        val scope = ThorQueueAvailabilityScope()
        val denied = song(7, unavailable)
        scope.invalidate(listOf("old-entry"))
        val old = scope.metadata("old-entry", denied)
        assertEquals(SongPlaybackAvailability.Unknown, old.availability)
        assertNull(old.availabilityReason)
        assertFalse(old.isExplicitlyUnavailable)
        assertSame("A newly inserted occurrence with the same song ID belongs to the new account response",
            denied, scope.metadata("new-entry", denied))
        assertTrue(scope.metadata("new-entry", denied).isExplicitlyUnavailable)
    }

    @Test fun removedQueueEntriesDoNotLeaveStalePermissionMemoryBehind() {
        val scope = ThorQueueAvailabilityScope()
        val denied = song(7, unavailable)
        scope.invalidate(listOf("gone", "kept"))
        scope.retainOnly(setOf("kept"))
        assertSame(denied, scope.metadata("gone", denied))
        assertEquals(SongPlaybackAvailability.Unknown, scope.metadata("kept", denied).availability)
    }

    @Test fun lazySongSelectionMatchesBothItsIdAndItsLocalSourceRatherThanTheFirstLoadedTrack() {
        val tracks = listOf(song(3), song(7), song(7, local = true))
        assertEquals(1, thorSongTrackIndex(tracks, 7, local = false))
        assertEquals(2, thorSongTrackIndex(tracks, 7, local = true))
        assertEquals(-1, thorSongTrackIndex(tracks, 88, local = false))
    }

    @Test fun runtimeFailureEventsIgnoreTheConnectionSnapshotDuplicatesAndOlderEvents() {
        val gate = ThorPlaybackFailureGate(10)
        assertFalse(gate.accept(10))
        assertFalse(gate.accept(9))
        assertTrue(gate.accept(11))
        assertFalse(gate.accept(11))
        assertTrue("An explicit retry has a fresh event ID", gate.accept(12))
        assertTrue("A new service attachment owns its own baseline", ThorPlaybackFailureGate(null).accept(1))
    }
}

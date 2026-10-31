package com.ljyh.mei.playback

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRecoveryScanTest {
    private fun candidates(vararg ids: String) = ids.map { PlaybackRecoveryCandidate(it, it) }

    @Test fun unavailableCandidatesAreOnlyProbedAndOnlyTheFirstPlayableEntryIsCommitted() = runTest {
        val campaign = PlaybackRecoveryCampaign(nowMs = { testScheduler.currentTime })
        campaign.markFailed("current")
        val probes = mutableListOf<String>()
        val commits = mutableListOf<String>()
        val result = scanPlaybackRecoveryCandidates(candidates("current", "missing", "denied", "ready", "later"), campaign,
            isCurrent = { true }, probe = { probes += it; it == "ready" },
            commit = { commits += it.entryId; true })
        assertEquals(PlaybackRecoveryResult.Recovered("ready"), result)
        assertEquals(listOf("missing", "denied", "ready"), probes)
        assertEquals(listOf("ready"), commits)
    }

    @Test fun transientProbeExceptionsDoNotBecomeCopyrightOrPerCandidateCommits() = runTest {
        val campaign = PlaybackRecoveryCampaign(nowMs = { testScheduler.currentTime })
        val commits = mutableListOf<String>()
        val result = scanPlaybackRecoveryCandidates(candidates("network", "ready"), campaign,
            isCurrent = { true }, probe = { if (it == "network") throw IOException("temporary") else true },
            commit = { commits += it.entryId; true })
        assertEquals(PlaybackRecoveryResult.Recovered("ready"), result)
        assertEquals(listOf("ready"), commits)
    }

    @Test fun repeatWrapCannotReprobeAnEntryAndTheWholeCampaignHasEightCandidateSlots() = runTest {
        val campaign = PlaybackRecoveryCampaign(nowMs = { testScheduler.currentTime })
        campaign.markFailed("current")
        val probes = mutableListOf<String>()
        val repeated = candidates("current", "a", "a", "b", "c", "d", "e", "f", "g", "h", "i")
        repeat(2) {
            assertEquals(PlaybackRecoveryResult.Exhausted,
                scanPlaybackRecoveryCandidates(repeated, campaign, { true }, { probes += it; false }, { error("No commit") }))
        }
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g", "h"), probes)
        assertEquals(8, campaign.candidatesTried)
    }

    @Test fun anActualFailureAfterAutomaticCommitKeepsTheVisitedEntries() = runTest {
        val campaign = PlaybackRecoveryCampaign(nowMs = { testScheduler.currentTime })
        val probes = mutableListOf<String>()
        campaign.markFailed("original")
        assertEquals(PlaybackRecoveryResult.Recovered("first"),
            scanPlaybackRecoveryCandidates(candidates("first"), campaign, { true }, { probes += it; true }, { true }))
        campaign.markFailed("first")
        assertEquals(PlaybackRecoveryResult.Recovered("second"),
            scanPlaybackRecoveryCandidates(candidates("original", "first", "missing", "second"), campaign, { true },
                { probes += it; it == "second" }, { true }))
        assertEquals(listOf("first", "missing", "second"), probes)
    }

    @Test fun eachCandidateHasFourSecondsAndTheCampaignStopsAtTwelveSeconds() = runTest {
        val campaign = PlaybackRecoveryCampaign(nowMs = { testScheduler.currentTime })
        val probes = mutableListOf<String>()
        assertEquals(PlaybackRecoveryResult.Exhausted,
            scanPlaybackRecoveryCandidates(candidates("a", "b", "c", "d"), campaign, { true },
                { probes += it; delay(5_000); true }, { error("Timed-out probe must never commit") }))
        assertEquals(12_000L, testScheduler.currentTime)
        assertEquals(listOf("a", "b", "c"), probes)
    }

    @Test fun automaticCommitsDoNotRestartTheCampaignDeadline() = runTest {
        val campaign = PlaybackRecoveryCampaign(nowMs = { testScheduler.currentTime })
        assertEquals(PlaybackRecoveryResult.Recovered("first"),
            scanPlaybackRecoveryCandidates(candidates("first"), campaign, { true }, { delay(1_000); true }, { true }))
        advanceTimeBy(9_000)
        campaign.markFailed("first")
        assertEquals(PlaybackRecoveryResult.Exhausted,
            scanPlaybackRecoveryCandidates(candidates("second"), campaign, { true }, { delay(4_000); true },
                { error("Only two seconds remain in this campaign") }))
        assertEquals(12_000L, testScheduler.currentTime)
    }

    @Test fun userPauseOrQueueIdentityChangeDuringAProbePreventsCommit() = runTest {
        var current = true
        val campaign = PlaybackRecoveryCampaign(nowMs = { testScheduler.currentTime })
        val result = scanPlaybackRecoveryCandidates(candidates("ready"), campaign, { current },
            { current = false; true }, { error("Stale scan must not seek") })
        assertEquals(PlaybackRecoveryResult.Superseded, result)
    }

    @Test fun explicitCancellationCannotCommitAndAUserResetStartsAFreshCampaign() = runTest {
        val campaign = PlaybackRecoveryCampaign(nowMs = { testScheduler.currentTime })
        val started = CompletableDeferred<Unit>()
        val job = launch {
            scanPlaybackRecoveryCandidates(candidates("a"), campaign, { true },
                { started.complete(Unit); delay(20_000); true }, { error("Cancelled scan must not seek") })
        }
        runCurrent(); started.await()
        job.cancel(); runCurrent()
        assertTrue(job.isCancelled)
        campaign.reset()
        assertEquals(PlaybackRecoveryResult.Recovered("a"),
            scanPlaybackRecoveryCandidates(candidates("a"), campaign, { true }, { true }, { true }))
        assertEquals(1, campaign.candidatesTried)
    }
}

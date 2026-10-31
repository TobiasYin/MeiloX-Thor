package com.ljyh.mei.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal data class PlaybackRecoveryCandidate<T>(val entryId: String, val value: T)

/** Retained across automatic commits until healthy playback or an explicit user operation. */
internal class PlaybackRecoveryCampaign(
    private val maximumCandidates: Int = 8,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val visited = linkedSetOf<String>()
    private var startedAtMs: Long? = null
    var candidatesTried: Int = 0
        private set

    fun markFailed(entryId: String) { visited += entryId }
    fun remainingBudgetMillis(limitMs: Long): Long {
        val start = startedAtMs ?: nowMs().also { startedAtMs = it }
        return (limitMs - (nowMs() - start).coerceAtLeast(0L)).coerceAtLeast(0L)
    }
    fun tryCandidate(entryId: String): Boolean {
        if (entryId in visited || candidatesTried >= maximumCandidates) return false
        visited += entryId
        candidatesTried++
        return true
    }
    fun reset() { visited.clear(); candidatesTried = 0; startedAtMs = null }
}

internal sealed interface PlaybackRecoveryResult {
    data class Recovered(val entryId: String) : PlaybackRecoveryResult
    data object Exhausted : PlaybackRecoveryResult
    data object Superseded : PlaybackRecoveryResult
}

/** Failed probes do not move the player or publish per-candidate notices. */
internal suspend fun <T> scanPlaybackRecoveryCandidates(
    candidates: List<PlaybackRecoveryCandidate<T>>,
    campaign: PlaybackRecoveryCampaign,
    isCurrent: () -> Boolean,
    probe: suspend (T) -> Boolean,
    commit: (PlaybackRecoveryCandidate<T>) -> Boolean,
    candidateTimeoutMs: Long = 4_000L,
    totalTimeoutMs: Long = 12_000L,
): PlaybackRecoveryResult = withTimeoutOrNull(campaign.remainingBudgetMillis(totalTimeoutMs)) scan@{
    for (candidate in candidates) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return@scan PlaybackRecoveryResult.Superseded
        if (!campaign.tryCandidate(candidate.entryId)) continue
        val ready = try {
            withTimeoutOrNull(candidateTimeoutMs) { probe(candidate.value) } == true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) { false }
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return@scan PlaybackRecoveryResult.Superseded
        if (ready) return@scan if (commit(candidate)) PlaybackRecoveryResult.Recovered(candidate.entryId)
            else PlaybackRecoveryResult.Superseded
    }
    PlaybackRecoveryResult.Exhausted
} ?: if (isCurrent()) PlaybackRecoveryResult.Exhausted else PlaybackRecoveryResult.Superseded

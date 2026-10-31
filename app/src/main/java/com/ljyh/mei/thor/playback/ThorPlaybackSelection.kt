package com.ljyh.mei.thor.playback

import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.isExplicitlyUnavailable
import com.ljyh.mei.data.model.withoutPlaybackAvailability
import kotlin.random.Random

internal const val THOR_NO_PLAYBACK_PERMISSION = "当前歌曲无播放权限"

/** Positions, rather than song IDs, preserve the intended occurrence of duplicate songs. */
internal data class ThorPlaybackSelection(
    val tracks: List<MediaMetadata>,
    val index: Int,
    val sourceIndices: List<Int>,
)

internal sealed interface ThorPlaybackDecision {
    data class Accepted(val selection: ThorPlaybackSelection) : ThorPlaybackDecision
    data class Rejected(val message: String) : ThorPlaybackDecision
}

internal fun thorPlayableTracks(tracks: List<MediaMetadata>): List<MediaMetadata> =
    tracks.filterNot { it.isExplicitlyUnavailable }

/** A direct selection never silently substitutes a different song. Unknown remains admissible. */
internal fun thorSelectedPlayback(tracks: List<MediaMetadata>, index: Int): ThorPlaybackDecision {
    if (tracks.isEmpty()) return ThorPlaybackDecision.Rejected("没有可播放的歌曲")
    if (index !in tracks.indices) return ThorPlaybackDecision.Rejected("播放位置已变化，请重新选择")
    if (tracks[index].isExplicitlyUnavailable) return ThorPlaybackDecision.Rejected(THOR_NO_PLAYBACK_PERMISSION)
    val indices = tracks.indices.filter { !tracks[it].isExplicitlyUnavailable }
    return ThorPlaybackDecision.Accepted(ThorPlaybackSelection(indices.map(tracks::get),
        indices.indexOf(index), indices))
}

/** Bulk play chooses its initial song from the already-filtered candidates, including shuffle. */
internal fun thorBulkPlayback(tracks: List<MediaMetadata>, shuffle: Boolean,
    random: Random = Random.Default): ThorPlaybackDecision {
    val indices = tracks.indices.filter { !tracks[it].isExplicitlyUnavailable }
    if (indices.isEmpty()) return ThorPlaybackDecision.Rejected(if (tracks.isEmpty()) "没有可播放的歌曲"
        else "此列表中的歌曲均无播放权限")
    return ThorPlaybackDecision.Accepted(ThorPlaybackSelection(indices.map(tracks::get),
        if (shuffle) random.nextInt(indices.size) else 0, indices))
}

/** The exact accepted-plan callback contains all destructive queue mutations in the bridge. */
internal inline fun commitThorPlayback(decision: ThorPlaybackDecision, rejected: (String) -> Unit,
    commit: (ThorPlaybackSelection) -> Unit): Boolean = when (decision) {
    is ThorPlaybackDecision.Rejected -> { rejected(decision.message); false }
    is ThorPlaybackDecision.Accepted -> { commit(decision.selection); true }
}

internal fun thorSongTrackIndex(tracks: List<MediaMetadata>, id: Long, local: Boolean): Int =
    tracks.indexOfFirst { it.id == id && it.isLocal == local }

/** Timeline order is supplied by Media3, so shuffled order and repeated song IDs remain intact. */
internal inline fun thorAvailableQueueIndex(current: Int, size: Int, step: (Int) -> Int,
    available: (Int) -> Boolean): Int? {
    if (current !in 0 until size) return null
    val visited = mutableSetOf<Int>()
    var index = step(current)
    while (index in 0 until size && visited.add(index)) {
        if (available(index)) return index
        index = step(index)
    }
    return null
}

/** Old account verdicts can be discarded without editing or restarting the actual queue. */
internal class ThorQueueAvailabilityScope {
    private val staleEntries = mutableSetOf<String>()

    fun invalidate(entryIds: Collection<String>) { staleEntries.addAll(entryIds) }
    fun retainOnly(entryIds: Set<String>) { staleEntries.retainAll(entryIds) }
    fun metadata(entryId: String?, metadata: MediaMetadata): MediaMetadata =
        if (entryId != null && entryId in staleEntries) metadata.withoutPlaybackAvailability() else metadata
}

/** Ignore the connection snapshot and duplicate/stale runtime events; retries get a fresh ID. */
internal class ThorPlaybackFailureGate(baselineId: Long?) {
    private var latest = baselineId ?: Long.MIN_VALUE
    fun accept(id: Long): Boolean {
        if (id <= latest) return false
        latest = id
        return true
    }
}

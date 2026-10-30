package com.ljyh.mei.thor.data

import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey

/** A successful write remains authoritative until a later read confirms it. */
internal data class ThorLikeOverride(
    val revision: Long,
    val item: ThorItem,
    val liked: Boolean,
)

/** No credential is stored here; the repository's account generation scopes this cache. */
internal data class ThorLikeOverrides(
    val generation: Long = 0,
    val entries: Map<Long, ThorLikeOverride> = emptyMap(),
) {
    fun forGeneration(next: Long): ThorLikeOverrides =
        if (generation == next) this else ThorLikeOverrides(generation = next)
}

internal fun projectThorLikedSongs(
    snapshot: List<ThorItem>,
    overrides: ThorLikeOverrides,
    generation: Long,
): List<ThorItem> {
    val pending = overrides.entries.takeIf { overrides.generation == generation }.orEmpty()
    val additions = pending.values.filter { it.liked }.sortedByDescending { it.revision }.map { it.item.copy(collected = true) }
    val retained = snapshot.filter { item ->
        val key = item.key as? ThorItemKey.Song
        key == null || key.local || pending[key.id]?.liked != false
    }
    return (additions + retained).distinctBy { it.key }
}

/**
 * A request that began before an override cannot acknowledge it. A matching request
 * can clear only the same revision and only after it observes the desired membership.
 */
internal fun acknowledgeThorLikes(
    current: ThorLikeOverrides,
    atRequestStart: ThorLikeOverrides,
    readSnapshot: List<ThorItem>,
    generation: Long,
): ThorLikeOverrides {
    if (current.generation != generation || atRequestStart.generation != generation) return current
    val observedIds = readSnapshot.mapNotNull { (it.key as? ThorItemKey.Song)?.takeUnless { key -> key.local }?.id }.toSet()
    val remaining = current.entries.filter { (id, pending) ->
        atRequestStart.entries[id]?.revision != pending.revision || (id in observedIds) != pending.liked
    }
    return if (remaining.size == current.entries.size) current else current.copy(entries = remaining)
}

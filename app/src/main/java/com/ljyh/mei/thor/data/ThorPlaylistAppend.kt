package com.ljyh.mei.thor.data

import com.ljyh.mei.data.model.PlaylistOrderChangedException

/** Cloud and device collections append distinct incoming IDs after the original ordered members. */
internal data class ThorPlaylistAppendPlan(val before: List<Long>, val added: List<Long>) {
    val expected: List<Long> get() = before + added
}

internal fun thorPlaylistAppendPlan(before: List<Long>, incoming: List<Long>): ThorPlaylistAppendPlan {
    require(before.all { it > 0 } && before.distinct().size == before.size) { "歌单曲目无效，请刷新" }
    require(incoming.all { it > 0 }) { "歌曲无效" }
    val existing = before.toSet()
    return ThorPlaylistAppendPlan(before.toList(), incoming.distinct().filterNot { it in existing })
}

/** Both ordering and partial-failure Undo must preserve every pre-existing member's sequence. */
internal fun hasUnchangedThorPlaylistBaseline(plan: ThorPlaylistAppendPlan, actual: List<Long>): Boolean {
    val expected = plan.expected
    val added = plan.added.toSet()
    return actual.size == expected.size && actual.toSet() == expected.toSet() &&
        actual.filterNot { it in added } == plan.before
}

/** Fresh membership and baseline-order checks precede the repository's conditional order write. */
internal suspend fun applyThorPlaylistAppend(
    plan: ThorPlaylistAppendPlan,
    addMissing: suspend (List<Long>) -> Unit,
    readCurrent: suspend () -> List<Long>,
    saveOrder: suspend (observed: List<Long>, expected: List<Long>) -> Unit,
): List<Long> {
    if (plan.added.isEmpty()) return plan.before
    addMissing(plan.added)
    val actual = readCurrent()
    val expected = plan.expected
    if (!hasUnchangedThorPlaylistBaseline(plan, actual)) throw PlaylistOrderChangedException()
    if (actual != expected) saveOrder(actual, expected)
    return expected
}

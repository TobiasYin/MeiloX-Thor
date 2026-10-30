package com.ljyh.mei.thor.playback

/** A conservative 'has been current' ledger, scoped to one attached playback queue. */
internal class ThorQueueUndoTracker {
    var epoch: Long = 0
        private set
    private val seenEntryIds = mutableSetOf<String>()

    fun beginEpoch() {
        epoch++
        seenEntryIds.clear()
    }

    fun observeCurrent(entryId: String?) {
        if (!entryId.isNullOrBlank()) seenEntryIds.add(entryId)
    }

    /** Null means the command belongs to an obsolete queue, not an empty valid undo. */
    fun insertionUndoTargets(expectedEpoch: Long, inserted: Set<String>, currentEntryId: String?): Set<String>? {
        if (expectedEpoch != epoch) return null
        return inserted - seenEntryIds - setOfNotNull(currentEntryId)
    }
}

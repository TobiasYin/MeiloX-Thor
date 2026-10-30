package com.ljyh.mei.playback

import java.util.UUID

/** Identity belongs to an occurrence, independently of its song/media ID. */
internal fun normalizeQueueEntryIds(
    requested: List<String?>,
    retainedIds: Set<String> = emptySet(),
    newId: () -> String = { UUID.randomUUID().toString() },
): List<String> {
    val used = retainedIds.filterTo(hashSetOf()) { it.isNotBlank() }
    // Generated identities must not steal a valid identity appearing later in this batch.
    val reserved = used + requested.filterNotNull().filter { it.isNotBlank() }
    return requested.map { id ->
        if (!id.isNullOrBlank() && used.add(id)) id
        else {
            var candidate: String
            do { candidate = newId() }
            while (candidate.isBlank() || candidate in reserved || !used.add(candidate))
            candidate
        }
    }
}

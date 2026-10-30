package com.ljyh.mei.playback

import org.junit.Assert.*
import org.junit.Test

class QueueEntryIdentityTest {
    @Test fun validPreallocatedIdentitiesSurviveNormalization() {
        assertEquals(listOf("first", "second"), normalizeQueueEntryIds(listOf("first", "second")) {
            error("Valid identities must not be regenerated")
        })
    }

    @Test fun missingBlankDuplicateAndRetainedConflictsGetFreshUniqueIdentities() {
        val generated = listOf("retained", "new", "one", "one", "two", "", "three", "four").iterator()
        val actual = normalizeQueueEntryIds(listOf("new", "retained", "new", null, "  "), setOf("retained")) { generated.next() }
        assertEquals(listOf("new", "one", "two", "three", "four"), actual)
        assertEquals(actual.size, actual.toSet().size)
        assertTrue(actual.none { it.isBlank() || it == "retained" })
    }

    @Test fun generatedIdentitiesCannotStealAValidLaterIdentity() {
        val generated = listOf("later", "generated").iterator()
        assertEquals(listOf("generated", "later"), normalizeQueueEntryIds(listOf(null, "later")) { generated.next() })
    }

    @Test fun aFullReplacementHasNoSurvivingOldOccurrencesButAppendReservesThem() {
        val requested = listOf("stable", "another")
        val replacement = normalizeQueueEntryIds(requested)
        val appended = normalizeQueueEntryIds(requested, replacement.toSet()) {
            // Deterministic unique IDs without relying on random collision probability.
            "appended-${counter++}"
        }
        assertEquals(requested, replacement)
        assertTrue(appended.none { it in replacement })
        assertEquals(2, appended.toSet().size)
    }

    private var counter = 0
}

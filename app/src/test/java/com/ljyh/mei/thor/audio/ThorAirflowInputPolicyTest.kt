package com.ljyh.mei.thor.audio

import org.junit.Assert.*
import org.junit.Test

class ThorAirflowInputPolicyTest {
    @Test fun verifiedThorUsesThePhysicallyWorkingMicBeforeRecognitionAndUnprocessed() {
        assertEquals(listOf(1, 6, 9), airflowSourceCandidates("AYN", "AYN Thor", true))
        assertEquals(listOf(1, 6, 9), airflowSourceCandidates(" ayn ", " AYN THOR ", false))
    }

    @Test fun otherHardwareRetainsTheAdvertisedUnprocessedOrRecognitionFirstPolicy() {
        assertEquals(listOf(9, 6, 1), airflowSourceCandidates("Other", "AYN Thor", true))
        assertEquals(listOf(6, 1), airflowSourceCandidates("Other", "Device", false))
        assertEquals(listOf(9, 6, 1), airflowSourceCandidates("AYN", "Odin 2", true))
        assertFalse(isThorMicrophoneHardware("AYN", "Thor"))
        assertFalse(isThorMicrophoneHardware("Other", "AYN Thor"))
    }

    @Test fun sourceCandidatesAreUniqueAndDoNotDependOnMeasuredQuietSignals() {
        for (thor in listOf(true, false)) for (unprocessed in listOf(true, false)) {
            val candidates = airflowSourceCandidates(if (thor) "AYN" else "Other", "AYN Thor", unprocessed)
            assertEquals(candidates, candidates.distinct())
            assertTrue(candidates.all { it in setOf(1, 6, 9) })
        }
    }

    @Test fun legacyUnlabelledCalibrationBelongsOnlyToUnprocessed() {
        val legacy = ThorAirflowCalibration(.004f, .035f, .16f)
        for (source in listOf(1, 6, 9)) {
            val restored = storedSourceAirflowCalibration(source, null, null, null,
                legacy.noiseFloorRms, legacy.gentleRms, legacy.strongRms)
            assertEquals(if (source == 9) legacy else null, restored)
        }
    }

    @Test fun actualSourceTupleWinsOverLegacyValues() {
        val scoped = ThorAirflowCalibration(.001f, .02f, .08f)
        for (source in listOf(1, 6, 9)) {
            assertEquals(scoped, storedSourceAirflowCalibration(source,
                scoped.noiseFloorRms, scoped.gentleRms, scoped.strongRms,
                .004f, .035f, .16f))
        }
    }

    @Test fun partialOrInvalidScopedTupleDoesNotFallBackToStaleLegacyCalibration() {
        assertNull(storedSourceAirflowCalibration(9, .001f, null, .08f, .004f, .035f, .16f))
        assertNull(storedSourceAirflowCalibration(9, Float.NaN, .02f, .08f, .004f, .035f, .16f))
        assertNull(storedSourceAirflowCalibration(1, null, null, null))
        assertNull(storedSourceAirflowCalibration(6, null, null, null))
    }
}

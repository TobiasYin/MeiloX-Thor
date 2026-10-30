package com.ljyh.mei.thor.display

import org.junit.Assert.*
import org.junit.Test

class ThorDisplaySnapshotTest {
    private fun screen(id: Int, w: Int, h: Int, presentation: Boolean = false) =
        ThorDisplayDescriptor(id, "display-$id", w, h, 369, 1, 2, presentation)

    @Test fun rolesAreDiscoveredBySupportedGeometryAndHostRatherThanHardcodedIds() {
        val snapshot = resolveThorDisplays(listOf(screen(9, 1080, 1920), screen(17, 1080, 1240, true)), 9)
        assertEquals(ThorDeviceProfile.Thor, snapshot.profile)
        assertEquals(9, snapshot.upperDisplayId)
        assertEquals(17, snapshot.lowerDisplayId)
        assertTrue(snapshot.hasSupportedPair)
        assertFalse("Discovery is not proof that the lower window was shown", snapshot.isDualScreen)
        assertTrue(snapshot.copy(lowerPresented = true).isDualScreen)
    }

    @Test fun pocketDuoUsesItsOwnLowerGeometryAndUnknownPairsRemainSingleScreen() {
        val upper = screen(0, 1920, 1080)
        val duo = resolveThorDisplays(listOf(upper, screen(4, 1280, 960, true)), 0)
        assertEquals(ThorDeviceProfile.PocketDuo, duo.profile)
        val unsupported = resolveThorDisplays(listOf(upper, screen(4, 1280, 720, true)), 0)
        assertNull(unsupported.profile)
        assertFalse(unsupported.isDualScreen)
        assertNotNull(unsupported.problem)
    }

    @Test fun hostOnWrongDisplayOrAPrivateLowerCannotBeReportedAsWorkingDualScreen() {
        val displays = listOf(screen(0, 1920, 1080), screen(4, 1240, 1080))
        assertFalse(resolveThorDisplays(displays, 4).hasSupportedPair)
        val restricted = resolveThorDisplays(displays, 0)
        assertTrue(restricted.hasSupportedPair)
        assertFalse(restricted.isDualScreen)
        assertNotNull(restricted.problem)
    }
}

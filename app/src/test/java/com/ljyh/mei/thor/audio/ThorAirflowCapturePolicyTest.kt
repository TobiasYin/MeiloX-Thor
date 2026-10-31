package com.ljyh.mei.thor.audio

import org.junit.Assert.*
import org.junit.Test

class ThorAirflowCapturePolicyTest {
    private val ready = ThorAirflowCapturePolicy(enabled = true, hasPermission = true,
        resumed = true, legacyActive = false, playbackConnected = true, playing = false)

    @Test fun playingSuspendsAnEnabledPreferenceAndPauseRestoresIt() {
        val playing = ready.copy(playing = true)
        assertFalse(playing.shouldCapture)
        assertTrue(playing.pausedForPlayback)
        assertTrue(playing.enabled)
        assertTrue(playing.copy(playing = false).shouldCapture)
    }

    @Test fun pausingDoesNotOptAUserWhoDisabledAirflowBackIn() {
        val disabled = ready.copy(enabled = false, playing = true)
        assertFalse(disabled.shouldCapture)
        assertFalse(disabled.pausedForPlayback)
        assertFalse(disabled.copy(playing = false).shouldCapture)
    }

    @Test fun detachedServiceIsNotMistakenForPausedPlaybackDuringResume() {
        val connecting = ready.copy(playbackConnected = false, playing = false)
        assertFalse(connecting.shouldCapture)
        assertFalse(connecting.pausedForPlayback)
        assertTrue(connecting.copy(playbackConnected = true).shouldCapture)
        assertFalse(connecting.copy(playbackConnected = true, playing = true).shouldCapture)
    }

    @Test fun permissionLifecycleAndLegacyRemainIndependentCaptureRequirements() {
        listOf(
            ready.copy(hasPermission = false),
            ready.copy(resumed = false),
            ready.copy(legacyActive = true),
        ).forEach { blocked ->
            assertFalse(blocked.shouldCapture)
            assertTrue("temporary gates preserve opt-in", blocked.enabled)
            assertFalse(blocked.copy(playing = true).shouldCapture)
        }
    }

}

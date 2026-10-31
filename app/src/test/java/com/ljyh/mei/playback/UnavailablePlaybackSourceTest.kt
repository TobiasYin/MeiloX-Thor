package com.ljyh.mei.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class UnavailablePlaybackSourceTest {
    private class Fixture {
        val entries = listOf("played", "selected-unavailable", "unknown-a", "unknown-b", "unknown-c")
        var current = 1
        var positionMs = 48_000L
        var playWhenReady = true
        var pauses = 0
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "pause" -> { playWhenReady = false; pauses++; null }
                // Examining/advancing another candidate or preparing it recreates the original
                // failure cascade. Even stop() would discard the user's explicit retry position.
                else -> error("A terminal runtime failure must not traverse/rebuild the queue: ${method.name}")
            }
        } as Player
    }

    @Test fun terminalFailurePausesWithoutInspectingOtherCandidatesOrResettingPosition() {
        val fixture = Fixture()
        val originalOrder = fixture.entries.toList()
        handleUnavailablePlaybackSource(fixture.player)
        assertFalse(fixture.playWhenReady)
        assertEquals(1, fixture.pauses)
        assertEquals("selected-unavailable", fixture.entries[fixture.current])
        assertEquals(48_000L, fixture.positionMs)
        assertEquals(originalOrder, fixture.entries)
    }

    @Test fun aBurstOfFailuresNeverSpendsAnAutomaticSkipBudgetOrTraversesUnknownTracks() {
        val fixture = Fixture()
        repeat(12) { handleUnavailablePlaybackSource(fixture.player) }
        assertFalse(fixture.playWhenReady)
        assertEquals(12, fixture.pauses)
        assertEquals(1, fixture.current)
        assertEquals(48_000L, fixture.positionMs)
        assertEquals(5, fixture.entries.size)
    }
}

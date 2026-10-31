package com.ljyh.mei.thor.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class ThorLyricPlaybackTest {
    private class Fixture(var playing: Boolean) {
        val calls = mutableListOf<String>()
        val player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "seekTo" -> {
                    // Only the current-item overload is allowed; the queue index stays intact.
                    assertEquals(1, arguments!!.size)
                    calls += "seek:${arguments[0] as Long}"
                    null
                }
                "play" -> { playing = true; calls += "play"; null }
                else -> error("Lyric playback must not change the queue or toggle: ${method.name}")
            }
        } as Player
    }

    @Test
    fun aPausedSongSeeksBeforeResumingWithoutReplacingItsQueue() {
        val fixture = Fixture(playing = false)
        fixture.player.seekAndPlayLyric(42_000, 180_000)
        assertEquals(listOf("seek:42000", "play"), fixture.calls)
        assertTrue(fixture.playing)
    }

    @Test
    fun anAlreadyPlayingSongContinuesPlayingInsteadOfTogglingToPause() {
        val fixture = Fixture(playing = true)
        fixture.player.seekAndPlayLyric(12_000, 180_000)
        assertEquals(listOf("seek:12000", "play"), fixture.calls)
        assertTrue(fixture.playing)
    }

    @Test
    fun anUnknownDurationPreservesTheRequestedLyricTime() {
        for (duration in listOf(0L, -1L, C.TIME_UNSET)) {
            val fixture = Fixture(playing = false)
            fixture.player.seekAndPlayLyric(85_000, duration)
            assertEquals("duration=$duration", listOf("seek:85000", "play"), fixture.calls)
        }
    }

    @Test
    fun aKnownDurationClampsPastTheEndAndNegativeOffsetsClampToZero() {
        val pastEnd = Fixture(playing = false)
        pastEnd.player.seekAndPlayLyric(Long.MAX_VALUE, 180_000)
        assertEquals(listOf("seek:180000", "play"), pastEnd.calls)

        for (duration in listOf(180_000L, 0L, C.TIME_UNSET)) {
            val negative = Fixture(playing = false)
            negative.player.seekAndPlayLyric(Long.MIN_VALUE, duration)
            assertEquals(listOf("seek:0", "play"), negative.calls)
        }
    }
}

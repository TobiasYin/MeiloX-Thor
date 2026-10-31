package com.ljyh.mei.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class UnavailablePlaybackSourceTest {
    private class Fixture(val failures: PlaybackFailureTracker = PlaybackFailureTracker(),
        val count: Int = 3, val wrap: Boolean = true) {
        var current = 0
        val calls = mutableListOf<String>()
        val items = List(count) { MediaItem.Builder().setMediaId("entry-$it").build() }
        init { failures.onMediaItemTransition(items.firstOrNull()?.mediaId, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) }
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "pause" -> { calls += "pause"; null }
                "stop" -> { calls += "stop"; null }
                "prepare" -> { calls += "prepare"; null }
                "getNextMediaItemIndex" -> if (current + 1 < count) current + 1 else if (wrap && count > 0) 0 else C.INDEX_UNSET
                "getMediaItemCount" -> count
                "getCurrentMediaItemIndex" -> current
                "getMediaItemAt" -> items[args!![0] as Int]
                "seekToDefaultPosition" -> {
                    assertEquals(1, args!!.size)
                    current = args[0] as Int
                    calls += "seek:$current"
                    failures.onMediaItemTransition(items[current].mediaId, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
                    null
                }
                else -> error("Source recovery must not mutate another player setting: ${method.name}")
            }
        } as Player
    }

    @Test fun disabledSkippingOnlyPausesTheCurrentEntryWithoutSpendingTheBudget() {
        val fixture = Fixture()
        repeat(3) { assertEquals(UnavailableSourceResult.PausedCurrent,
            handleUnavailablePlaybackSource(fixture.player, false, fixture.failures)) }
        assertEquals(listOf("pause", "pause", "pause"), fixture.calls)
        assertEquals(0, fixture.current)
        assertEquals(0, fixture.failures.consecutiveFailures)
    }

    @Test fun enablingAfterAPausedFailureSkipsFromTheSameEntryAndPreparesOnce() {
        val fixture = Fixture()
        handleUnavailablePlaybackSource(fixture.player, false, fixture.failures)
        assertEquals(UnavailableSourceResult.Skipped,
            handleUnavailablePlaybackSource(fixture.player, true, fixture.failures))
        assertEquals(listOf("pause", "seek:1", "prepare"), fixture.calls)
        assertEquals(1, fixture.current)
        assertEquals(1, fixture.failures.consecutiveFailures)
    }

    @Test fun automaticSkipTransitionsRetainTheExistingFailureLimitAcrossRepeatWrap() {
        val fixture = Fixture(PlaybackFailureTracker(maximumFailures = 4), count = 2)
        repeat(3) { index ->
            assertEquals(UnavailableSourceResult.Skipped,
                handleUnavailablePlaybackSource(fixture.player, true, fixture.failures))
            assertEquals(index + 1, fixture.failures.consecutiveFailures)
        }
        assertEquals(UnavailableSourceResult.FailureLimit,
            handleUnavailablePlaybackSource(fixture.player, true, fixture.failures))
        assertEquals(listOf("seek:1", "prepare", "seek:0", "prepare", "seek:1", "prepare", "pause", "stop"), fixture.calls)
        assertEquals(4, fixture.failures.consecutiveFailures)
    }

    @Test fun disablingAfterAnAutomaticFailureDoesNotResetOrConsumeTheRemainingBudget() {
        val fixture = Fixture(PlaybackFailureTracker(maximumFailures = 3))
        handleUnavailablePlaybackSource(fixture.player, true, fixture.failures)
        repeat(3) { handleUnavailablePlaybackSource(fixture.player, false, fixture.failures) }
        assertEquals(1, fixture.failures.consecutiveFailures)
        assertEquals(1, fixture.current)
        assertEquals(UnavailableSourceResult.Skipped, handleUnavailablePlaybackSource(fixture.player, true, fixture.failures))
        assertEquals(UnavailableSourceResult.FailureLimit, handleUnavailablePlaybackSource(fixture.player, true, fixture.failures))
    }

    @Test fun queueEndAndRepeatOneCannotSeekBackToTheSameBrokenEntry() {
        for (wrap in listOf(true, false)) {
            val fixture = Fixture(count = 1, wrap = wrap)
            assertEquals(UnavailableSourceResult.QueueEnded,
                handleUnavailablePlaybackSource(fixture.player, true, fixture.failures))
            assertEquals(listOf("pause", "stop"), fixture.calls)
            assertEquals(0, fixture.current)
        }
    }
}

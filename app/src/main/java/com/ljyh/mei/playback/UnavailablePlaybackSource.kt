package com.ljyh.mei.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

internal enum class UnavailableSourceResult { PausedCurrent, Skipped, FailureLimit, QueueEnded }

/** Called only after the existing same-track source/quality recovery paths are exhausted. */
@UnstableApi
internal fun handleUnavailablePlaybackSource(
    player: Player,
    skipEnabled: Boolean,
    failures: PlaybackFailureTracker,
): UnavailableSourceResult {
    if (!skipEnabled) {
        // Preserve the selected entry and position, and do not spend an automatic-skip budget.
        player.pause()
        return UnavailableSourceResult.PausedCurrent
    }
    if (failures.recordFailure()) {
        player.pause()
        player.stop()
        return UnavailableSourceResult.FailureLimit
    }
    val nextIndex = player.nextMediaItemIndex
    if (nextIndex !in 0 until player.mediaItemCount || nextIndex == player.currentMediaItemIndex) {
        player.pause()
        player.stop()
        return UnavailableSourceResult.QueueEnded
    }
    val next = player.getMediaItemAt(nextIndex)
    failures.expectAutomaticSkip(next.queueEntryId ?: next.mediaId)
    player.seekToDefaultPosition(nextIndex)
    player.prepare()
    return UnavailableSourceResult.Skipped
}

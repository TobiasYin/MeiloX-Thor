package com.ljyh.mei.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * The terminal fallback after bounded source/candidate recovery is exhausted or disabled.
 * Candidate checks live in the recovery coordinator: this fallback must never blindly seek to
 * another unknown entry. Normal end-of-track/AutoMix transitions remain separate.
 */
@UnstableApi
internal fun handleUnavailablePlaybackSource(player: Player) {
    // Keep entry, queue order, position and source caches intact for an explicit user retry.
    player.pause()
}

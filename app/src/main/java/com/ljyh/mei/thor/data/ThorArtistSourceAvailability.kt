package com.ljyh.mei.thor.data

import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.SongPlaybackAvailability
import com.ljyh.mei.data.model.SongUrl
import com.ljyh.mei.data.model.isFullSourceFor
import com.ljyh.mei.data.repository.SongSourceSessionChangedException
import com.ljyh.mei.playback.effectivePlaybackQuality
import com.ljyh.mei.playback.isSupportedPlaybackQuality
import com.ljyh.mei.playback.playbackQualityFallbacks
import com.ljyh.mei.thor.model.ThorItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Artist catalogs can omit account playback rights even when every returned URL is a trial.
 * Inspect unresolved songs in batches using the same full-source and quality rules as playback.
 * These hints belong only to this accepted catalog read; no URL or verdict is persisted.
 */
internal suspend fun resolveThorArtistSourceAvailability(
    items: List<ThorItem>,
    requestedQuality: String,
    checkCurrent: () -> Unit,
    fetch: suspend (ids: List<String>, quality: String) -> SongUrl,
): List<ThorItem> {
    val identities = items.flatMap { it.tracks }.filter { it.needsArtistSourceHint() }
        .map { it.id.toString() }.distinct()
    if (identities.isEmpty()) return items
    val evidence = identities.associateWith { ArtistSourceEvidence() }
    for (quality in playbackQualityFallbacks(requestedQuality)) {
        val unresolved = identities.filterNot { evidence.getValue(it).playable }
        for (batch in unresolved.chunked(50)) {
            currentCoroutineContext().ensureActive()
            checkCurrent()
            val response = try {
                fetch(batch, quality)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (changed: SongSourceSessionChangedException) {
                throw changed
            } catch (_: Exception) {
                // A transient request failure supplies no evidence about playback rights.
                currentCoroutineContext().ensureActive()
                checkCurrent()
                batch.forEach { evidence.getValue(it).certainTrialOnly = false }
                continue
            }
            currentCoroutineContext().ensureActive()
            checkCurrent()
            if (response.code != 200) {
                batch.forEach { evidence.getValue(it).certainTrialOnly = false }
                continue
            }
            val sourcesById = response.data.orEmpty().groupBy { it.id.toString() }
            for (id in batch) {
                val hint = evidence.getValue(id)
                val sources = sourcesById[id].orEmpty()
                if (sources.any { it.isFullSourceFor(id) && it.supportsArtistPlayback(quality) }) {
                    hint.playable = true
                } else if (sources.isNotEmpty() && sources.all {
                        it.code == 200 && !it.url.isNullOrBlank() && it.freeTrialInfo != null &&
                            it.supportsArtistPlayback(quality)
                    }) {
                    hint.sawTrial = true
                } else {
                    // Missing IDs/URLs, business errors, or unknown formats are not denials.
                    hint.certainTrialOnly = false
                }
            }
        }
    }
    currentCoroutineContext().ensureActive()
    checkCurrent()
    return items.map { item ->
        val tracks = item.tracks.map { song ->
            if (!song.needsArtistSourceHint()) song
            else {
                val hint = evidence.getValue(song.id.toString())
                when {
                    hint.playable -> song.copy(availability = SongPlaybackAvailability.Playable, availabilityReason = null)
                    hint.sawTrial && hint.certainTrialOnly -> song.copy(
                        availability = SongPlaybackAvailability.Unavailable, availabilityReason = "当前账号仅可试听",
                    )
                    else -> song
                }
            }
        }
        if (tracks == item.tracks) item else item.copy(tracks = tracks)
    }
}

private class ArtistSourceEvidence(
    var playable: Boolean = false,
    var sawTrial: Boolean = false,
    var certainTrialOnly: Boolean = true,
)

private fun MediaMetadata.needsArtistSourceHint() =
    id > 0 && !isLocal && !isPodcast && availability == SongPlaybackAvailability.Unknown

private fun SongUrl.Data.supportsArtistPlayback(attemptedQuality: String) =
    isSupportedPlaybackQuality(effectivePlaybackQuality(level, attemptedQuality))

package com.ljyh.mei.data.model

/** A catalog hint for this response's account. Runtime full-stream resolution remains authoritative. */
enum class SongPlaybackAvailability { Unknown, Playable, Unavailable }

/** Nullable fields distinguish a missing privilege from an explicitly denied zero bitrate. */
data class SongPlaybackPrivilege(
    val id: Long? = null,
    val st: Int? = null,
    val pl: Int? = null,
    val playMaxbr: Int? = null,
    val playMaxBrLevel: String? = null,
)

fun SongPlaybackPrivilege?.playbackAvailability(): SongPlaybackAvailability {
    val hints = this ?: return SongPlaybackAvailability.Unknown
    if (hints.st?.let { it < 0 } == true) return SongPlaybackAvailability.Unavailable
    if (hints.pl?.let { it > 0 } == true) return SongPlaybackAvailability.Playable
    val level = hints.playMaxBrLevel?.trim()?.lowercase().orEmpty()
    val positiveMaximum = hints.playMaxbr?.let { it > 0 } == true ||
        level in setOf("standard", "higher", "exhigh", "lossless", "hires", "jyeffect", "sky", "jymaster", "dolby")
    return if (hints.pl == 0 && !positiveMaximum && (level.isEmpty() || level == "none"))
        SongPlaybackAvailability.Unavailable else SongPlaybackAvailability.Unknown
}

val MediaMetadata.isExplicitlyUnavailable: Boolean
    get() = !isLocal && !isPodcast && availability == SongPlaybackAvailability.Unavailable

/** A privilege saved by a previous account/session cannot gate a device playlist or history. */
fun MediaMetadata.withoutPlaybackAvailability(): MediaMetadata = copy(
    availability = SongPlaybackAvailability.Unknown, availabilityReason = null,
)

internal fun MediaMetadata.withPlaybackPrivilege(hints: SongPlaybackPrivilege?): MediaMetadata {
    val availability = if (isLocal || isPodcast) SongPlaybackAvailability.Unknown else hints.playbackAvailability()
    return copy(availability = availability,
        availabilityReason = if (availability == SongPlaybackAvailability.Unavailable) "当前歌曲暂不可播放" else null)
}

internal fun mergedSongPlaybackPrivilege(
    songId: Long,
    embedded: SongPlaybackPrivilege?,
    separate: SongPlaybackPrivilege?,
    songStatus: Int? = null,
): SongPlaybackPrivilege {
    val extra = separate?.takeIf { it.id == songId }
    val inline = embedded?.takeIf { it.id == null || it.id == songId }
    return SongPlaybackPrivilege(songId, extra?.st ?: inline?.st ?: songStatus,
        extra?.pl ?: inline?.pl, extra?.playMaxbr ?: inline?.playMaxbr,
        extra?.playMaxBrLevel ?: inline?.playMaxBrLevel)
}

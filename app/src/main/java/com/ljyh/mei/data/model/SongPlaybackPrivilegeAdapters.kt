package com.ljyh.mei.data.model

internal fun PlaylistDetail.Privilege.toPlaybackPrivilege() = SongPlaybackPrivilege(id.takeIf { it > 0 }, st, pl, playMaxbr, playMaxBrLevel)
internal fun AlbumDetail.Song.Privilege.toPlaybackPrivilege() = SongPlaybackPrivilege(id.takeIf { it > 0 }, st, pl, playMaxbr, playMaxBrLevel)
internal fun com.ljyh.mei.data.model.weapi.EveryDaySongs.Data.DailySong.Privilege.toPlaybackPrivilege() =
    SongPlaybackPrivilege(id.takeIf { it > 0 }, st, pl, playMaxbr, playMaxBrLevel)
internal fun com.ljyh.mei.data.model.api.ArtistSong.HotSong.Privilege.toPlaybackPrivilege() =
    SongPlaybackPrivilege(id.takeIf { it > 0 }, st, pl, playMaxbr, playMaxBrLevel)
internal fun com.ljyh.mei.data.model.api.Privilege.toPlaybackPrivilege() = SongPlaybackPrivilege(id.takeIf { it > 0 }, st, pl, playMaxbr, playMaxBrLevel)
internal fun com.ljyh.mei.data.model.weapi.Privilege.toPlaybackPrivilege() = SongPlaybackPrivilege(id.takeIf { it > 0 }, st, pl, playMaxbr, playMaxBrLevel)

/** Separate privilege arrays are keyed by song ID, never by response order. */
fun Tracks.toMediaMetadata(): List<MediaMetadata> {
    val byId = privileges.orEmpty().associateBy { it.id }
    return songs.map { it.toMediaMetadata(byId[it.id]) }
}

fun PlaylistDetail.toMediaMetadata(): List<MediaMetadata> {
    val byId = privileges.orEmpty().associateBy { it.id }
    return playlist.tracks.map { it.toMediaMetadata(byId[it.id]) }
}

fun AlbumDetail.toMediaMetadata(): List<MediaMetadata> {
    val byId = privileges.orEmpty().associateBy { it.id }
    return songs.map { song -> song.toMediaMetadata().withPlaybackPrivilege(
        mergedSongPlaybackPrivilege(song.id, song.privilege?.toPlaybackPrivilege(), byId[song.id], song.st)) }
}

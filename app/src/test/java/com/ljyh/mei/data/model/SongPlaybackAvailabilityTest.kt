package com.ljyh.mei.data.model

import com.google.gson.Gson
import com.ljyh.mei.data.model.api.ArtistSong
import com.ljyh.mei.data.model.api.SearchResult
import com.ljyh.mei.data.model.api.toMediaData
import com.ljyh.mei.data.model.api.toSongMediaMetadata
import com.ljyh.mei.data.model.weapi.EveryDaySongs
import org.junit.Assert.*
import org.junit.Test

class SongPlaybackAvailabilityTest {
    private val gson = Gson()
    private val song = """{"id":7,"name":"Fixture","dt":1000,"ar":[],"al":{"id":2,"name":"Album","pic":1,"picUrl":"https://example.invalid/cover"}}"""

    @Test fun nullablePrivilegeEvidenceDistinguishesMissingFromExplicitZero() {
        val missing = gson.fromJson("""{"id":7}""", PlaylistDetail.Privilege::class.java)
        assertNull(missing.pl)
        assertNull(missing.st)
        assertNull(missing.playMaxbr)
        assertEquals(SongPlaybackAvailability.Unknown, missing.toPlaybackPrivilege().playbackAvailability())
        val denied = gson.fromJson("""{"id":7,"pl":0}""", PlaylistDetail.Privilege::class.java)
        assertEquals(0, denied.pl)
        assertEquals(SongPlaybackAvailability.Unavailable, denied.toPlaybackPrivilege().playbackAvailability())
        assertEquals(SongPlaybackAvailability.Unknown, SongPlaybackPrivilege(playMaxbr = 0, playMaxBrLevel = "none").playbackAvailability())
    }

    @Test fun explicitStatusDenialWinsAndPositiveCatalogQualityDoesNotBecomeAFalsePlZeroDenial() {
        assertEquals(SongPlaybackAvailability.Unavailable, SongPlaybackPrivilege(st = -200, pl = 320000).playbackAvailability())
        assertEquals(SongPlaybackAvailability.Playable, SongPlaybackPrivilege(st = 0, pl = 320000).playbackAvailability())
        for (level in listOf("standard", "lossless", "hires")) {
            assertEquals(SongPlaybackAvailability.Unknown, SongPlaybackPrivilege(pl = 0, playMaxBrLevel = level).playbackAvailability())
        }
        assertEquals(SongPlaybackAvailability.Unknown, SongPlaybackPrivilege(pl = 0, playMaxbr = 320000).playbackAvailability())
        assertEquals(SongPlaybackAvailability.Unavailable, SongPlaybackPrivilege(pl = 0, playMaxBrLevel = "none").playbackAvailability())
    }

    @Test fun songDetailPrivilegeArraysMergeByIdRatherThanPosition() {
        val other = song.replace("\"id\":7", "\"id\":8")
        val detail = gson.fromJson("""{"code":200,"songs":[$song,$other],"privileges":[{"id":8,"st":-200},{"id":7,"pl":320000}]}""", Tracks::class.java)
        val mapped = detail.toMediaMetadata()
        assertEquals(listOf(7L, 8L), mapped.map { it.id })
        assertEquals(SongPlaybackAvailability.Playable, mapped[0].availability)
        assertTrue(mapped[1].isExplicitlyUnavailable)
        assertEquals("当前歌曲暂不可播放", mapped[1].availabilityReason)
    }

    @Test fun missingIndependentPrivilegeDoesNotEraseEmbeddedEvidenceAndForeignIdsNeverAttach() {
        val embedded = song.dropLast(1) + """, "privilege":{"pl":0}}"""
        val track = gson.fromJson(embedded, PlaylistDetail.Playlist.Track::class.java)
        assertTrue(track.toMediaMetadata().isExplicitlyUnavailable)
        val foreign = gson.fromJson("""{"id":99,"pl":320000}""", PlaylistDetail.Privilege::class.java)
        assertTrue(track.toMediaMetadata(foreign).isExplicitlyUnavailable)
        val fresh = gson.fromJson("""{"id":7,"pl":320000}""", PlaylistDetail.Privilege::class.java)
        assertFalse(track.toMediaMetadata(fresh).isExplicitlyUnavailable)
    }

    @Test fun playlistAndAlbumTopLevelPrivilegeArraysReachTheirRealConverters() {
        val playlist = gson.fromJson("""{"playlist":{"tracks":[$song]},"privileges":[{"id":7,"pl":0}]}""", PlaylistDetail::class.java)
        assertTrue(playlist.toMediaMetadata().single().isExplicitlyUnavailable)
        val album = gson.fromJson("""{"songs":[$song],"privileges":[{"id":7,"st":-1}]}""", AlbumDetail::class.java)
        assertTrue(album.toMediaMetadata().single().isExplicitlyUnavailable)
    }

    @Test fun albumDailyArtistAndIntelligenceSourcesKeepTheirEmbeddedPrivilege() {
        val denied = song.dropLast(1) + """, "privilege":{"pl":0}}"""
        assertTrue(gson.fromJson(denied, AlbumDetail.Song::class.java).toMediaMetadata().isExplicitlyUnavailable)
        assertTrue(gson.fromJson(denied, EveryDaySongs.Data.DailySong::class.java).toMediaMetadata().isExplicitlyUnavailable)
        assertTrue(gson.fromJson(denied, ArtistSong.HotSong::class.java).toMediaMetadata().isExplicitlyUnavailable)
        val intelligence = gson.fromJson("""{"songInfo":$denied}""", com.ljyh.mei.data.model.api.Data::class.java)
        assertTrue(intelligence.toMediaMetadata().isExplicitlyUnavailable)
    }

    @Test fun searchKeepsInlineAndIndependentHintsButFeeAloneRemainsUnknown() {
        val searchSong = """{"id":7,"name":"Fixture","duration":1000,"artists":[],"album":{"id":2,"name":"Album","picId":1},"fee":1}"""
        assertEquals(SongPlaybackAvailability.Unknown, gson.fromJson(searchSong, SearchResult.Result.Song::class.java).toMediaData().availability)
        val inline = searchSong.dropLast(1) + """, "privilege":{"pl":0}}"""
        assertTrue(gson.fromJson(inline, SearchResult.Result.Song::class.java).toMediaData().isExplicitlyUnavailable)
        val result = gson.fromJson("""{"songs":[$searchSong],"privileges":[{"id":7,"st":-200}]}""", SearchResult.Result::class.java)
        assertTrue(result.toSongMediaMetadata().single().isExplicitlyUnavailable)
    }

    @Test fun legacyOrFuturePersistedMetadataDefaultsSafelyAndAccountCleanupDropsOldDenials() {
        val raw = """{"id":7,"title":"Fixture","coverUrl":"","artists":[],"duration":1000,"album":{"id":2,"title":"Album"}}"""
        val old = gson.fromJson(raw, MediaMetadata::class.java)
        assertEquals(SongPlaybackAvailability.Unknown, old.availability)
        val future = gson.fromJson(raw.dropLast(1) + """, "availability":"FutureValue","availabilityReason":"old"}""", MediaMetadata::class.java)
        assertEquals(SongPlaybackAvailability.Unknown, future.availability)
        assertNull(future.availabilityReason)
        val denied = old.copy(availability = SongPlaybackAvailability.Unavailable, availabilityReason = "old")
        val restored = gson.fromJson(gson.toJson(denied), MediaMetadata::class.java)
        assertTrue(restored.isExplicitlyUnavailable)
        assertEquals(SongPlaybackAvailability.Unknown, restored.withoutPlaybackAvailability().availability)
        assertNull(restored.withoutPlaybackAvailability().availabilityReason)
    }

    @Test fun localFilesAndPodcastProgramsCannotBeBlockedByAnOnlineSongHint() {
        val denied = MediaMetadata(7, "Fixture", "", emptyList(), 1000, MediaMetadata.Album(2, "Album"),
            availability = SongPlaybackAvailability.Unavailable)
        assertTrue(denied.isExplicitlyUnavailable)
        assertFalse(denied.copy(isLocal = true).isExplicitlyUnavailable)
        assertFalse(denied.copy(isPodcast = true).isExplicitlyUnavailable)
    }
}

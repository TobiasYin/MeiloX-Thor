package com.ljyh.mei.data.model.api

import com.google.gson.Gson
import com.ljyh.mei.data.model.SongPlaybackAvailability
import com.ljyh.mei.utils.netease.NeteaseUtils.getResourceLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Literal public-catalog JSON shapes; all names and IDs in these fixtures are synthetic. */
class SearchResultSchemaTest {
    private val gson = Gson()

    @Test
    fun modernSongAliasesProduceCompleteMetadataAndPreferTheActualCoverUrl() {
        val result = decode("""{"code":200,"result":{"songs":[{
            "id":101,"name":"QA modern song","dt":183456,"mv":55,"st":0,
            "ar":[{"id":201,"name":"QA artist","alias":null}],
            "al":{"id":301,"name":"QA album","pic":404,"pic_str":"405",
                "picUrl":"https://example.test/current-cover.jpg"},
            "privilege":{"id":101,"st":0,"pl":320000}
        }]}}""")
        val metadata = result.result.toSongMediaMetadata().single()
        assertEquals(101L, metadata.id)
        assertEquals("QA modern song", metadata.title)
        assertEquals(183456L, metadata.duration)
        assertEquals(301L, metadata.album.id)
        assertEquals("QA album", metadata.album.title)
        assertEquals("https://example.test/current-cover.jpg", metadata.coverUrl)
        assertEquals(201L, metadata.artists.single().id)
        assertEquals("QA artist", metadata.artists.single().name)
        assertNull(metadata.artists.single().alias)
        assertNull("Missing artist imagery must not become 0.jpg", metadata.artists.single().picUrl)
        assertEquals(SongPlaybackAvailability.Playable, metadata.availability)
        assertEquals(55, result.result.songs!!.single().mvid)
    }

    @Test
    fun modernArtworkUsesPreciseStringIdThenNumericPicWithoutInventingMissingImages() {
        val result = decode("""{"code":200,"result":{"songs":[
            {"id":1,"name":"String ID","dt":60000,"ar":[{"id":9,"name":"A"}],
             "al":{"id":10,"name":"Album","pic":123,"pic_str":"109951171111111","picUrl":""}},
            {"id":2,"name":"Numeric ID","dt":60000,"ar":[{"id":9,"name":"A"}],
             "al":{"id":10,"name":"Album","pic":456}},
            {"id":3,"name":"No image","dt":60000,"ar":[{"id":9,"name":"A"}],
             "al":{"id":10,"name":"Album","pic":0,"pic_str":"0"}}
        ]}}""")
        val songs = result.result.toSongMediaMetadata()
        assertEquals(getResourceLink("109951171111111", "jpg"), songs[0].coverUrl)
        assertEquals(getResourceLink("456", "jpg"), songs[1].coverUrl)
        assertEquals("", songs[2].coverUrl)
        assertTrue(songs.all { it.artists.single().picUrl == null })
    }

    @Test
    fun shuffledSeparatePrivilegesMatchSongIdsAndRetainInlineAndStatusEvidence() {
        val songs = decode("""{"code":200,"result":{
            "songs":[
                {"id":1,"name":"Allowed","dt":60000,"ar":[{"id":9,"name":"A"}],
                 "al":{"id":10,"name":"Album"},"privilege":{"id":1,"pl":0}},
                {"id":2,"name":"Denied","dt":60000,"ar":[{"id":9,"name":"A"}],
                 "al":{"id":10,"name":"Album"},"privilege":{"id":2,"pl":320000}},
                {"id":3,"name":"Status only","dt":60000,"st":-200,"ar":[{"id":9,"name":"A"}],
                 "al":{"id":10,"name":"Album"}},
                {"id":4,"name":"Unknown","dt":60000,"fee":1,"ar":[{"id":9,"name":"A"}],
                 "al":{"id":10,"name":"Album"}}
            ],
            "privileges":[{"id":2,"st":-200},{"id":1,"pl":320000},{"id":999,"pl":0}]
        }}""").result.toSongMediaMetadata()
        assertEquals(listOf(1L, 2L, 3L, 4L), songs.map { it.id })
        assertEquals(listOf(SongPlaybackAvailability.Playable, SongPlaybackAvailability.Unavailable,
            SongPlaybackAvailability.Unavailable, SongPlaybackAvailability.Unknown), songs.map { it.availability })
    }

    @Test
    fun legacySongFieldsRetainAlbumArtistsDurationCoverAndAliases() {
        val result = decode("""{"code":200,"result":{"songs":[{
            "id":11,"name":"QA legacy song","duration":123000,"status":0,
            "artists":[{"id":12,"name":"QA legacy artist","picId":13,"alias":["QA alias"]}],
            "album":{"id":14,"name":"QA legacy album","picId":15}
        }]}}""")
        val metadata = result.result.toSongMediaMetadata().single()
        assertEquals("QA legacy song", metadata.title)
        assertEquals(123000L, metadata.duration)
        assertEquals(14L, metadata.album.id)
        assertEquals("QA legacy album", metadata.album.title)
        assertEquals(getResourceLink("15", "jpg"), metadata.coverUrl)
        assertEquals(getResourceLink("13", "jpg"), metadata.artists.single().picUrl)
        assertEquals(listOf("QA alias"), metadata.artists.single().alias)
        assertEquals(SongPlaybackAvailability.Unknown, metadata.availability)
    }

    @Test
    fun modernAlbumAndPlaylistTypesRetainNamesCoversArtistsAndTrackCounts() {
        val result = decode("""{"code":200,"result":{
            "albums":[{"id":21,"name":"QA album","size":7,"picUrl":"https://example.test/album.jpg",
                "artists":[{"id":22,"name":"QA album artist"}]}],
            "playlists":[{"id":31,"name":"QA playlist","trackCount":8,
                "coverImgUrl":"https://example.test/playlist.jpg",
                "creator":{"userId":32,"nickname":"QA creator","avatarUrl":"https://example.test/avatar.jpg"}}]
        }}""").result
        val album = result.albums!!.single().toAlbum()
        assertEquals(21L, album.id)
        assertEquals("QA album", album.title)
        assertEquals("https://example.test/album.jpg", album.cover)
        assertEquals(7, album.size)
        assertEquals("QA album artist", album.artist.single().name)
        assertEquals(22L, album.artist.single().id)
        val playlist = result.playlists!!.single().toPlaylist()
        assertEquals("31", playlist.id)
        assertEquals("QA playlist", playlist.title)
        assertEquals("https://example.test/playlist.jpg", playlist.cover)
        assertEquals(8, playlist.count)
        assertEquals("QA creator", playlist.authorName)
        assertEquals("32", playlist.author)
    }

    private fun decode(json: String): SearchResult = gson.fromJson(json, SearchResult::class.java)
}

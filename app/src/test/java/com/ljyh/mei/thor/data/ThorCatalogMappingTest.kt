package com.ljyh.mei.thor.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.data.model.SongPlaybackAvailability
import com.ljyh.mei.data.model.isExplicitlyUnavailable
import com.ljyh.mei.thor.model.ThorItemKey
import org.junit.Assert.*
import org.junit.Test

/** Real service JSON mappers and continuation policy, independent of transport or account fixtures. */
class ThorCatalogMappingTest {
    @Test fun offsetsAdvanceByTheRawServerPageAndStopOnAnEmptyOrTerminalPage() {
        assertEquals(84, thorCatalogNextOffset(40, 44, true))
        assertNull(thorCatalogNextOffset(40, 44, false))
        assertNull(thorCatalogNextOffset(40, 0, true))
        assertThrows(IllegalArgumentException::class.java) { thorCatalogNextOffset(-1, 40, true) }
        assertThrows(IllegalArgumentException::class.java) { thorCatalogNextOffset(0, -1, true) }
        assertThrows(ArithmeticException::class.java) { thorCatalogNextOffset(Int.MAX_VALUE - 1, 2, true) }
    }

    @Test fun playlistCursorsKeepTimestampPrecisionAndRejectRepeatedOrForwardContinuation() {
        val previous = 1_730_426_400_999L
        val next = 1_730_426_400_123L
        assertEquals(next, thorCatalogNextCursor(0, next, 40, true))
        assertEquals(next, thorCatalogNextCursor(previous, next, 40, true))
        assertNull(thorCatalogNextCursor(previous, 0, 40, false))
        assertNull(thorCatalogNextCursor(previous, previous, 0, true))
        assertThrows(IllegalStateException::class.java) { thorCatalogNextCursor(previous, previous, 40, true) }
        assertThrows(IllegalStateException::class.java) { thorCatalogNextCursor(previous, previous + 1, 40, true) }
        assertThrows(IllegalStateException::class.java) { thorCatalogNextCursor(0, 0, 40, true) }
        assertThrows(IllegalArgumentException::class.java) { thorCatalogNextCursor(-1, next, 40, true) }
    }

    @Test fun artistMappingUsesArtistIdentityAndRawPageLengthInsteadOfTheNumberOfAcceptedRows() {
        val page = parseThorCatalogArtists(json("""{"more":true,"artists":[
            {"id":7,"name":"First artist","alias":["Alias A","Alias B"],"picUrl":"https://example.invalid/first","briefDesc":"Profile","musicSize":12},
            false,{"id":0,"name":"Invalid identity"},
            {"id":3,"name":"Second artist","img1v1Url":"https://example.invalid/fallback"}
        ]}"""), 40)
        assertEquals(listOf(ThorItemKey.Artist(7), ThorItemKey.Artist(3)), page.items.map { it.key })
        assertEquals(44, page.nextOffset)
        assertNull(page.nextCursor)
        assertEquals("Alias A / Alias B", page.items[0].subtitle)
        assertEquals("Profile", page.items[0].description)
        assertEquals(12, page.items[0].trackCount)
        assertEquals("https://example.invalid/first", page.items[0].artworkUri)
        assertEquals("https://example.invalid/fallback", page.items[1].artworkUri)
        assertTrue(page.items.all { it.tracks.isEmpty() })
    }

    @Test fun recommendedPlaylistsPreserveLongIdentityMetadataAndTimestampContinuation() {
        val page = parseThorCatalogPlaylists(json("""{"more":true,"lasttime":1730426400123,"playlists":[
            {"id":2147483789,"name":"Public playlist","creator":{"nickname":"Public curator"},"coverImgUrl":"https://example.invalid/playlist","description":"Public description","trackCount":4},
            {"id":0,"name":"Invalid identity"}
        ]}"""), 1_730_426_400_999)
        val item = page.items.single()
        assertEquals(ThorItemKey.Playlist("2147483789"), item.key)
        assertEquals("Public curator", item.subtitle)
        assertEquals("Public description", item.description)
        assertEquals(4, item.trackCount)
        assertEquals(1_730_426_400_123L, page.nextCursor)
        assertNull(page.nextOffset)
        assertTrue(item.tracks.isEmpty())
        assertFalse(item.owned)
    }

    @Test fun anIncompletePlaylistContinuationFailsRatherThanRepeatingTheFirstPage() {
        val row = """{"id":7,"name":"Public playlist"}"""
        assertThrows(IllegalStateException::class.java) {
            parseThorCatalogPlaylists(json("""{"more":true,"playlists":[$row]}"""), 0)
        }
        assertThrows(IllegalStateException::class.java) {
            parseThorCatalogPlaylists(json("""{"more":true,"lasttime":1000,"playlists":[$row]}"""), 1000)
        }
        assertFalse(parseThorCatalogPlaylists(json("""{"more":false,"playlists":[$row]}"""), 1000).hasMore)
        assertFalse(parseThorCatalogPlaylists(json("""{"more":true,"lasttime":1000,"playlists":[]}"""), 1000).hasMore)
    }

    @Test fun toplistsAreOrderedPlaylistDestinationsWithUpdateFrequencyAndNoInventedNextPage() {
        val page = parseThorCatalogToplists(json("""{"more":true,"list":[
            {"id":9,"name":"First chart","updateFrequency":"Daily","trackCount":100,"description":"Chart description"},
            {"id":8,"name":"Second chart","updateFrequency":"Weekly"},
            {"id":0,"name":"Invalid chart"}
        ]}"""))
        assertEquals(listOf(ThorItemKey.Playlist("9"), ThorItemKey.Playlist("8")), page.items.map { it.key })
        assertEquals(listOf("Daily", "Weekly"), page.items.map { it.subtitle })
        assertEquals(100, page.items[0].trackCount)
        assertEquals("Chart description", page.items[0].description)
        assertFalse(page.hasMore)
    }

    @Test fun songCatalogsMergeIndependentPrivilegeByIdInsteadOfArrayPosition() {
        val response = json("""{"songs":[
            ${song(7, "\"privilege\":{\"pl\":320000}")},
            ${song(8, "\"privilege\":{\"pl\":0}")},
            ${song(9, "\"fee\":1")}
        ],"privileges":[{"id":8,"pl":320000},{"id":999,"pl":0},{"id":7,"pl":0}]}""")
        val page = parseThorCatalogSongs(response, "songs", 0, false)
        val tracks = page.items.map { it.tracks.single() }
        assertEquals(listOf(7L, 8L, 9L), tracks.map { it.id })
        assertEquals(listOf(SongPlaybackAvailability.Unavailable, SongPlaybackAvailability.Playable,
            SongPlaybackAvailability.Unknown), tracks.map { it.availability })
        assertTrue(tracks[0].isExplicitlyUnavailable)
        assertFalse(tracks[1].isExplicitlyUnavailable)
        assertFalse(tracks[2].isExplicitlyUnavailable)
        assertEquals("Song 7", tracks[0].title)
        assertEquals(180_000L, tracks[0].duration)
        assertEquals(31L, tracks[0].album.id)
        assertEquals("Album", tracks[0].album.title)
        assertEquals("Artist", tracks[0].artists.single().name)
        assertEquals(tracks[0].coverUrl, page.items[0].artworkUri)
    }

    @Test fun dailyAndHotSongsKeepInlineDenialsWhileMissingPrivilegeAndFeeStayUnknown() {
        for (field in listOf("dailySongs", "hotSongs")) {
            val page = parseThorCatalogSongs(json("""{"$field":[
                ${song(7, "\"privilege\":{\"st\":-200}")},${song(8, "\"fee\":1")}
            ]}"""), field, null, false)
            assertEquals(listOf(ThorItemKey.Song(7), ThorItemKey.Song(8)), page.items.map { it.key })
            assertTrue(page.items[0].tracks.single().isExplicitlyUnavailable)
            assertEquals(SongPlaybackAvailability.Unknown, page.items[1].tracks.single().availability)
            assertFalse(page.hasMore)
        }
    }

    @Test fun allSongPagingPreservesServerOrderAndCountsSkippedRowsInItsNextOffset() {
        val page = parseThorCatalogSongs(json("""{"songs":[${song(9)},null,${song(7)},{"id":0}]}"""), "songs", 40, true)
        assertEquals(listOf(ThorItemKey.Song(9), ThorItemKey.Song(7)), page.items.map { it.key })
        assertEquals(44, page.nextOffset)
        assertNull(page.nextCursor)
        assertFalse(parseThorCatalogSongs(json("""{"songs":[]}"""), "songs", 44, true).hasMore)
    }

    private fun json(source: String): JsonObject = JsonParser.parseString(source).asJsonObject
    private fun song(id: Long, extra: String? = null): String = """{
        "id":$id,"name":"Song $id","dt":180000,"ar":[{"id":21,"name":"Artist"}],
        "al":{"id":31,"name":"Album","pic":1,"picUrl":"https://example.invalid/art"}
        ${extra?.let { ",$it" }.orEmpty()}
    }"""
}

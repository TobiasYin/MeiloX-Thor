package com.ljyh.mei.thor.data

import com.google.gson.JsonParser
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.thor.data.local.ThorPlaylistTrackEntity
import com.ljyh.mei.thor.data.local.appendUniqueTracks
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.model.toThorItem
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class ThorDataProjectionTest {
    @Test fun appendPreservesOrderAndDeduplicatesByIdentity() {
        fun member(key: String, position: Int) = ThorPlaylistTrackEntity("p", key, position, "{}")
        val previous = listOf(member("netease:1", 0), member("local:1", 3))
        val added = appendUniqueTracks(previous, listOf(member("netease:1", 0), member("netease:2", 0),
            member("netease:2", 1), member("local:2", 2)))
        assertEquals(listOf("netease:2", "local:2"), added.map { it.songKey })
        assertEquals(listOf(4, 5), added.map { it.position })
        assertEquals(listOf(0, 3), previous.map { it.position })
    }

    @Test fun localAlbumIdentityIncludesArtistAndIsLocaleIndependent() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val id = localAlbumIdentity("INDIGO", "ARTIST")
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(id, localAlbumIdentity(" indigo ", "artist"))
            assertNotEquals(id, localAlbumIdentity("indigo", "another artist"))
        } finally { Locale.setDefault(before) }
    }

    @Test fun partialOrUnknownTrackCollectionsMustNotPretendToBeComplete() {
        val song = MediaMetadata(1, "Track", "", emptyList(), 120_000, MediaMetadata.Album(1, "Album"))
        val partial = ThorItem(ThorItemKey.Album(1), "Album", trackCount = 3, tracks = listOf(song))
        assertFalse(hasCompleteTracks(partial))
        assertFalse(hasCompleteTracks(partial.copy(trackCount = null)))
        assertTrue(hasCompleteTracks(partial.copy(trackCount = 1)))
        assertNotEquals(songIdentity(song), songIdentity(song.copy(isLocal = true)))
    }

    @Test fun localLikesCannotMarkACloudSongWithTheSameNumericId() {
        val cloud = MediaMetadata(7, "Cloud", "", emptyList(), 120_000, MediaMetadata.Album(1, "Album"))
        val local = cloud.copy(title = "Local", isLocal = true)
        assertTrue(cloudLikeProjection(listOf(local.toThorItem())).isEmpty())
        assertEquals(mapOf(7L to true), cloudLikeProjection(listOf(local.toThorItem(), cloud.toThorItem())))
    }

    @Test fun homeOnlyProjectsActualSupportedRoutableResources() {
        val payload = JsonParser.parseString("""{
            "resources": [
                {"resourceType":"playList","resourceId":"42","title":"Real playlist","coverImg":"https://example.test/cover"},
                {"resourceType":"album","resourceId":"43","title":"Real album"},
                {"resourceType":"podcast","resourceId":"44","title":"Unsupported"},
                {"resourceType":"album","resourceId":"not-an-id","title":"Invalid"},
                {"resourceType":"playList","resourceId":"42","title":"Duplicate"}
            ]
        }""")
        val items = parseHomeResources(listOf(payload))
        assertEquals(listOf(ThorItemKey.Playlist("42"), ThorItemKey.Album(43)), items.map { it.key })
        assertEquals("Real playlist", items.first().title)
        assertTrue(items.all { it.tracks.isEmpty() })
    }
}

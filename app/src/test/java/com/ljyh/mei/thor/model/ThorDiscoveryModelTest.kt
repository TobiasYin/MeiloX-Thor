package com.ljyh.mei.thor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThorDiscoveryModelTest {
    @Test fun fourHomeCategoriesResolveToIndependentRealCatalogCollections() {
        assertEquals(listOf("推荐", "歌单", "榜单", "歌手"), ThorHomeCategory.entries.map { it.title })
        assertEquals(listOf(ThorCollection.Home, ThorCollection.RecommendedPlaylists,
            ThorCollection.Toplists, ThorCollection.Artists), ThorHomeCategory.entries.map { it.collection })
        assertEquals(4, ThorHomeCategory.entries.map { it.collection }.distinct().size)
        assertFalse("Daily songs are a deliberate destination inside recommendations, not a fifth category",
            ThorHomeCategory.entries.any { it.collection == ThorCollection.DailySongs })
    }

    @Test fun catalogContinuationPreservesLongCursorAndDoesNotTreatItemsAsProofOfAnotherPage() {
        val rows = listOf(ThorItem(ThorItemKey.Playlist("71"), "Catalog playlist"))
        val cursor = Int.MAX_VALUE.toLong() + 8_000_000_123L
        val cursorPage = ThorCatalogPage(rows, nextCursor = cursor)
        assertTrue(cursorPage.hasMore)
        assertEquals(cursor, cursorPage.nextCursor)
        assertNull(cursorPage.nextOffset)
        val offsetPage = ThorCatalogPage(rows, nextOffset = 60)
        assertTrue(offsetPage.hasMore)
        assertEquals(60, offsetPage.nextOffset)
        assertNull(offsetPage.nextCursor)
        assertFalse("A full terminal page still ends when neither continuation exists",
            ThorCatalogPage(rows).hasMore)
        assertFalse(ThorCatalogPage(emptyList()).hasMore)
    }

    @Test fun artistSectionsDoNotLeakAnotherSectionsPaginationLoadingOrFailure() {
        val artist = ThorItem(ThorItemKey.Artist(13), "Artist")
        val hot = listOf(ThorItem(ThorItemKey.Song(101), "Hot song"))
        val songs = listOf(ThorItem(ThorItemKey.Song(102), "Song"))
        val albums = listOf(ThorItem(ThorItemKey.Album(103), "Album"))
        val state = ThorArtistBrowseState(artist,
            pages = mapOf(ThorArtistSection.HotSongs to hot, ThorArtistSection.Songs to songs, ThorArtistSection.Albums to albums),
            nextOffsets = mapOf(ThorArtistSection.Songs to 60, ThorArtistSection.Albums to 30),
            loading = setOf(ThorArtistSection.Songs), errors = mapOf(ThorArtistSection.Albums to "Retry albums"))
        assertEquals(hot, state.items)
        assertFalse(state.hasMore)
        assertFalse(state.isLoading)
        assertNull(state.error)
        val allSongs = state.copy(section = ThorArtistSection.Songs)
        assertEquals(songs, allSongs.items)
        assertTrue(allSongs.hasMore)
        assertTrue(allSongs.isLoading)
        assertNull(allSongs.error)
        val albumPage = state.copy(section = ThorArtistSection.Albums)
        assertEquals(albums, albumPage.items)
        assertTrue(albumPage.hasMore)
        assertFalse(albumPage.isLoading)
        assertEquals("Retry albums", albumPage.error)
        assertEquals(hot, state.items)
    }

    @Test fun artistAndSectionIdentitiesKeepIndependentStableFocusScenes() {
        val first = ThorItem(ThorItemKey.Artist(51), "Same display name")
        val second = ThorItem(ThorItemKey.Artist(52), "Same display name")
        val keys = listOf(first, second).flatMap { item ->
            ThorArtistSection.entries.map { ThorArtistBrowseState(item, section = it).sceneKey }
        }
        assertEquals(6, keys.distinct().size)
        val original = ThorArtistBrowseState(first, section = ThorArtistSection.Albums)
        val renamed = original.copy(item = first.copy(title = "Updated profile", artworkUri = "updated-art"))
        assertEquals(original.sceneKey, renamed.sceneKey)
        assertNotEquals(first.key.stableId, ThorItemKey.Album(51).stableId)
        assertNotEquals(first.key.stableId, ThorItemKey.Song(51).stableId)
    }
}

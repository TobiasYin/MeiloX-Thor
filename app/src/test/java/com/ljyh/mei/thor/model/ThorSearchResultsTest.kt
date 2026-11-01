package com.ljyh.mei.thor.model

import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.SongPlaybackAvailability
import com.ljyh.mei.thor.ThorSearchState
import com.ljyh.mei.thor.state.ThorNavigation
import com.ljyh.mei.thor.state.ThorOverlay
import com.ljyh.mei.thor.state.ThorPage
import com.ljyh.mei.thor.state.ThorQuickPlayerHistory
import com.ljyh.mei.thor.state.ThorTab
import org.junit.Assert.*
import org.junit.Test

/** Actual search policies; no replacement merger, API traffic, or Android ViewModel fixture. */
class ThorSearchResultsTest {
    @Test fun allResourceTypesAppearInTheFirstFourRowsWithoutLosingEachApisRelevanceOrder() {
        val songs = listOf(song(1), song(2), song(3))
        val artists = listOf(artist(11), artist(12))
        val albums = listOf(album(21), album(22))
        val playlists = listOf(playlist("31"), playlist("32"))
        val mixed = thorMixedSearchResults(songs + artists + albums + playlists, "query")

        assertEquals(listOf(songs[0], artists[0], albums[0], playlists[0],
            songs[1], artists[1], albums[1], playlists[1], songs[2]), mixed)
        assertEquals(listOf(ThorSearchCategory.Songs, ThorSearchCategory.Artists,
            ThorSearchCategory.Albums, ThorSearchCategory.Playlists), mixed.take(4).map { it.searchCategory() })
        assertEquals(songs, thorVisibleSearchResults(mixed, ThorSearchCategory.Songs))
        assertEquals(artists, thorVisibleSearchResults(mixed, ThorSearchCategory.Artists))
        assertEquals(albums, thorVisibleSearchResults(mixed, ThorSearchCategory.Albums))
        assertEquals(playlists, thorVisibleSearchResults(mixed, ThorSearchCategory.Playlists))
    }

    @Test fun unevenResponsesAndNonCatalogDestinationsDoNotTruncateTheLongerResourceGroup() {
        val first = song(1)
        val second = song(2)
        val third = song(3)
        val fourth = song(4)
        val onlyArtist = artist(11)
        val onlyPlaylist = playlist("31")
        val destination = ThorItem(ThorItemKey.Destination("search-tool"), "Tool")
        val mixed = thorMixedSearchResults(listOf(destination, first, second, third, fourth,
            onlyArtist, onlyPlaylist), "query")

        assertEquals(listOf(first, onlyArtist, onlyPlaylist, second, third, fourth, destination), mixed)
        assertTrue(thorVisibleSearchResults(mixed, ThorSearchCategory.Albums).isEmpty())
        assertEquals(listOf(destination), mixed.filter { it.searchCategory() == null })
        assertTrue(thorMixedSearchResults(emptyList(), "query").isEmpty())
    }

    @Test fun anExactArtistNameIsPromotedCaseInsensitivelyAfterTrimmingTheSubmittedQuery() {
        val firstSong = song(1).copy(title = "Mixed Name")
        val prefixArtist = artist(11).copy(title = "Mixed Name Revival")
        val exactArtist = artist(12).copy(title = "MIXED NAME")
        val otherArtist = artist(13)
        val firstAlbum = album(21)
        val mixed = thorMixedSearchResults(listOf(firstSong, prefixArtist, exactArtist, otherArtist,
            firstAlbum), "  mIxEd NaMe  ")

        assertEquals(listOf(exactArtist, firstSong, firstAlbum, prefixArtist, otherArtist), mixed)
        assertSame(exactArtist, mixed.first())
        assertEquals(1, mixed.count { it.key == exactArtist.key })
    }

    @Test fun withoutAnExactNameOnlyTheFirstExactAliasIsPromotedWhileAllResourceTypesStayVisible() {
        val firstSong = song(1)
        val nonMatch = artist(11).copy(title = "Target Tribute")
        val aliasMatch = artist(12).copy(title = "Different name", subtitle = "One / Target / Three")
        val laterAlias = artist(13).copy(title = "Another name", subtitle = "Target")
        val firstAlbum = album(21)
        val firstPlaylist = playlist("31")
        val mixed = thorMixedSearchResults(listOf(firstSong, nonMatch, aliasMatch, laterAlias,
            firstAlbum, firstPlaylist, aliasMatch.copy(title = "Later duplicate")), "target")

        assertEquals(listOf(aliasMatch, firstSong, firstAlbum, firstPlaylist, nonMatch, laterAlias), mixed)
        assertSame(aliasMatch, mixed[0])
        assertEquals(1, mixed.count { it.key == aliasMatch.key })
        assertEquals(1, mixed.count { it.key == laterAlias.key })
    }

    @Test fun manyUnrelatedAliasMatchesCannotHideSongsOrOutrankTheExactArtistName() {
        val songs = listOf(song(1), song(2), song(3))
        val aliases = (1L..8L).map { id ->
            artist(100 + id).copy(title = "Related performer $id", subtitle = "Another alias / 陈奕迅")
        }
        val exactArtist = artist(999).copy(title = "陈奕迅")
        val firstAlbum = album(21)
        val firstPlaylist = playlist("31")
        val mixed = thorMixedSearchResults(songs + aliases + exactArtist + firstAlbum + firstPlaylist, "陈奕迅")

        // The exact name is deliberately last in the artist API response, after many alias matches.
        assertEquals(listOf(exactArtist, songs[0], firstAlbum, firstPlaylist), mixed.take(4))
        assertEquals(listOf(ThorSearchCategory.Artists, ThorSearchCategory.Songs,
            ThorSearchCategory.Albums, ThorSearchCategory.Playlists), mixed.take(4).map { it.searchCategory() })
        assertEquals(listOf(exactArtist) + aliases, thorVisibleSearchResults(mixed, ThorSearchCategory.Artists))
        assertEquals(songs, thorVisibleSearchResults(mixed, ThorSearchCategory.Songs))
        assertEquals(14, mixed.size)
        assertEquals(1, mixed.count { it.key == exactArtist.key })
    }

    @Test fun prefixesSubstringsSongTitlesAndBlankQueriesDoNotCreateAnExactArtistMatch() {
        val firstSong = song(1).copy(title = "Ann")
        val prefix = artist(11).copy(title = "Anna")
        val substringAlias = artist(12).copy(title = "Another artist", subtitle = "Joanna / Annette")
        val firstAlbum = album(21)
        val input = listOf(firstSong, prefix, substringAlias, firstAlbum)
        val expected = listOf(firstSong, prefix, firstAlbum, substringAlias)

        assertEquals(expected, thorMixedSearchResults(input, "Ann"))
        assertEquals(expected, thorMixedSearchResults(input, "   "))
        assertEquals(expected, thorMixedSearchResults(input, ""))
    }

    @Test fun deduplicationUsesTypedOccurrenceIdentityAndPreservesLocalAndRemoteNumericCollisions() {
        val remoteSong = song(7)
        val localSong = song(7, local = true)
        val remoteAlbum = album(7)
        val localAlbum = remoteAlbum.copy(key = ThorItemKey.Album(7, local = true))
        val remotePlaylist = playlist("7")
        val localPlaylist = remotePlaylist.copy(key = ThorItemKey.Playlist("7", local = true))
        val performer = artist(7)
        val firstOccurrence = remoteSong.copy(key = ThorItemKey.QueueEntry("first-occurrence"))
        val secondOccurrence = remoteSong.copy(key = ThorItemKey.QueueEntry("second-occurrence"))
        val destination = ThorItem(ThorItemKey.Destination("7"), "Destination")
        val originals = listOf(remoteSong, localSong, remoteAlbum, localAlbum, remotePlaylist,
            localPlaylist, performer, firstOccurrence, secondOccurrence, destination)
        val mixed = thorMixedSearchResults(originals + originals.map { it.copy(title = "Later duplicate") }, "query")

        assertEquals(10, mixed.size)
        assertEquals(originals.map { it.key }.toSet(), mixed.map { it.key }.toSet())
        for (original in originals) assertSame(original, mixed.single { it.key == original.key })
        assertEquals(listOf(remoteSong, localSong, firstOccurrence, secondOccurrence),
            thorVisibleSearchResults(mixed, ThorSearchCategory.Songs))
    }

    @Test fun categoryProjectionPreservesItemAndMetadataIdentityOrderAndPlaybackRestrictions() {
        val denied = song(7, availability = SongPlaybackAvailability.Unavailable)
        val local = song(7, local = true, availability = SongPlaybackAvailability.Unavailable)
        val unknown = song(9)
        val performer = artist(11)
        val record = album(21)
        val list = playlist("31")
        val mixed = thorMixedSearchResults(listOf(denied, local, unknown, performer, record, list), "query")
        val state = ThorSearchState(query = "query", items = mixed, searched = true,
            failedCategories = setOf(ThorSearchCategory.Playlists))
        val expected = mapOf(
            ThorSearchCategory.All to mixed,
            ThorSearchCategory.Songs to listOf(denied, local, unknown),
            ThorSearchCategory.Artists to listOf(performer),
            ThorSearchCategory.Albums to listOf(record),
            ThorSearchCategory.Playlists to listOf(list),
        )
        for ((category, rows) in expected) {
            val projected = state.copy(category = category)
            assertEquals(rows, thorVisibleSearchResults(mixed, category))
            assertEquals(rows, projected.visibleItems)
            for ((original, visible) in rows.zip(projected.visibleItems)) {
                assertSame(original, visible)
                original.tracks.firstOrNull()?.let { assertSame(it, visible.tracks.single()) }
            }
        }
        assertTrue(denied.isPlaybackUnavailable)
        assertFalse(local.isPlaybackUnavailable)
        assertEquals(SongPlaybackAvailability.Unknown, unknown.tracks.single().availability)
        assertEquals("Current account denial", denied.tracks.single().availabilityReason)
        assertEquals(ThorSearchCategory.All, state.category)
        assertEquals(mixed, state.visibleItems)
    }

    @Test fun categoryScenesAreIndependentAndNewRequestsCannotReuseAnOldCategorysFocusScene() {
        assertEquals("search:results:41", thorSearchResultsScene(41, ThorSearchCategory.All))
        val current = ThorSearchCategory.entries.map { thorSearchResultsScene(41, it) }
        val next = ThorSearchCategory.entries.map { thorSearchResultsScene(42, it) }
        assertEquals(5, current.toSet().size)
        assertEquals(5, next.toSet().size)
        assertTrue(current.toSet().intersect(next.toSet()).isEmpty())
        assertEquals("search:results:41:Artists", thorSearchResultsScene(41, ThorSearchCategory.Artists))
        assertNotEquals(thorSearchResultsScene(41, ThorSearchCategory.Artists),
            thorSearchResultsScene(41, ThorSearchCategory.Songs))
    }

    @Test fun artistAlbumAndQuickPlayerReturnToTheSameSearchPageWithoutAddingAnotherSearch() {
        val search = ThorNavigation(tab = ThorTab.Discover).push(ThorPage.Search)
        val profile = search.push(ThorPage.Artist(artist(11)))
        val detail = profile.push(ThorPage.Detail(album(21)))
        val history = ThorQuickPlayerHistory()
        val player = history.enter(detail, null, 7)
        val queue = player.toggle(ThorOverlay.Queue)
        val backToPlayer = queue.back()!!
        assertEquals(player, backToPlayer)
        assertEquals(detail, history.leave(backToPlayer, 7)!!.navigation)
        assertEquals(profile, detail.back())
        val returned = detail.back()!!.back()!!
        assertEquals(search, returned)
        assertEquals(ThorPage.Search, returned.page)
        assertEquals(ThorTab.Discover, returned.tab)
        assertEquals(1, returned.stacks.getValue(ThorTab.Discover).count { it == ThorPage.Search })
        assertEquals("search", returned.sceneKey)
    }

    private fun artist(id: Long) = ThorItem(ThorItemKey.Artist(id), "Artist $id")
    private fun album(id: Long) = ThorItem(ThorItemKey.Album(id), "Album $id")
    private fun playlist(id: String) = ThorItem(ThorItemKey.Playlist(id), "Playlist $id")
    private fun song(id: Long, local: Boolean = false,
        availability: SongPlaybackAvailability = SongPlaybackAvailability.Unknown): ThorItem = MediaMetadata(
        id, "Song $id", "https://example.invalid/art", listOf(MediaMetadata.Artist(11, "Artist")),
        180_000, MediaMetadata.Album(21, "Album"), explicit = true, tns = "Translation", isLocal = local,
        availability = availability,
        availabilityReason = if (availability == SongPlaybackAvailability.Unavailable) "Current account denial" else null,
    ).toThorItem()
}

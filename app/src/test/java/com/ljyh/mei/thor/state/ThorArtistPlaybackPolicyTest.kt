package com.ljyh.mei.thor.state

import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.thor.menu.ThorMenuAction
import com.ljyh.mei.thor.menu.ThorMenuAnchor
import com.ljyh.mei.thor.menu.ThorMenuState
import com.ljyh.mei.thor.menu.actionRows
import com.ljyh.mei.thor.model.*
import org.junit.Assert.*
import org.junit.Test

class ThorArtistPlaybackPolicyTest {
    private val artist = ThorItem(ThorItemKey.Artist(51), "Artist")
    private val navigation = ThorNavigation().push(ThorPage.Artist(artist))
    private val first = track(3)
    private val second = track(1)

    @Test fun dailyRecommendationsExposeOnlyTheirOwnVisibleLogicalSongOrder() {
        val library = ThorLibraryState(collections = mapOf(
            ThorCollection.DailySongs to listOf(first.toThorItem(), second.toThorItem(), first.toThorItem()),
            ThorCollection.LikedSongs to listOf(track(90).toThorItem()),
        ))
        val page = ThorNavigation().push(ThorPage.Collection(ThorCollection.DailySongs))
        val playback = thorPagePlayback(page, library)!!
        assertEquals(listOf(3L, 1L, 3L), playback.tracks.map { it.id })
        assertEquals(ThorCollection.DailySongs.title, playback.title)
        for (overlay in ThorOverlay.entries) assertNull(thorPagePlayback(page.toggle(overlay), library))
    }

    @Test fun artistBulkActionsUseOnlyTheCurrentMatchingSongSectionAndPreserveItsOrder() {
        val state = ThorArtistBrowseState(artist, pages = mapOf(
            ThorArtistSection.HotSongs to listOf(first.toThorItem(), second.toThorItem()),
            ThorArtistSection.Songs to listOf(second.toThorItem(), first.toThorItem(), second.toThorItem()),
        ))
        assertEquals(listOf(3L, 1L), thorPagePlayback(navigation, ThorLibraryState(), state)!!.tracks.map { it.id })
        val allSongs = state.copy(section = ThorArtistSection.Songs)
        val playback = thorPagePlayback(navigation, ThorLibraryState(), allSongs)!!
        assertEquals(listOf(1L, 3L, 1L), playback.tracks.map { it.id })
        assertEquals("Artist · 全部歌曲", playback.title)
        assertNull(thorPagePlayback(navigation, ThorLibraryState(), allSongs.copy(item = artist.copy(key = ThorItemKey.Artist(52)))))
        assertNull(thorPagePlayback(navigation, ThorLibraryState()))
        for (overlay in ThorOverlay.entries) assertNull(thorPagePlayback(navigation.toggle(overlay), ThorLibraryState(), allSongs))
    }

    @Test fun artistAlbumListsAreNeverFlattenedEvenWhenCardsAlreadyContainTracks() {
        val state = ThorArtistBrowseState(artist, section = ThorArtistSection.Albums, pages = mapOf(
            ThorArtistSection.Albums to listOf(ThorItem(ThorItemKey.Album(7), "Album", tracks = listOf(first, second))),
            ThorArtistSection.HotSongs to listOf(first.toThorItem()),
        ))
        assertNull(thorPagePlayback(navigation, ThorLibraryState(), state))
        assertNull(thorPagePlayback(navigation, ThorLibraryState(), state.copy(section = ThorArtistSection.Songs)))
        for (collection in listOf(ThorCollection.Artists, ThorCollection.RecommendedPlaylists, ThorCollection.Toplists)) {
            val library = ThorLibraryState(collections = mapOf(collection to state.pages.getValue(ThorArtistSection.Albums)))
            assertNull(thorPagePlayback(ThorNavigation().push(ThorPage.Collection(collection)), library, state))
        }
    }

    @Test fun artistContextMenusCannotTreatAProfileAsASongAlbumOrEditablePlaylist() {
        // Even enriched profiles or stale generic ownership flags must not expose track mutations.
        for (item in listOf(artist, artist.copy(tracks = listOf(first, second), owned = true, collected = true))) {
            val menu = ThorMenuState(ThorMenuSubject(item, "artist-row", sourcePlaylist = ThorItemKey.Playlist("71"),
                queueEntryId = "stale-entry"), ThorMenuAnchor(10f, 10f))
            for (liked in listOf(false, true)) {
                val actions = menu.actionRows(liked)
                assertEquals(listOf(ThorMenuAction.Artist), actions.map { it.action })
                assertEquals("查看歌手", actions.single().title)
                assertFalse(actions.single().destructive)
            }
        }
    }

    private fun track(id: Long) = MediaMetadata(id, "Song $id", "", emptyList(), 180_000,
        MediaMetadata.Album(7, "Album"))
}

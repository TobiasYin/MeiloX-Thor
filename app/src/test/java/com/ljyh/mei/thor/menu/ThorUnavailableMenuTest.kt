package com.ljyh.mei.thor.menu

import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.SongPlaybackAvailability
import com.ljyh.mei.thor.model.ThorMenuSubject
import com.ljyh.mei.thor.model.toThorItem
import org.junit.Assert.*
import org.junit.Test

class ThorUnavailableMenuTest {
    @Test fun unavailableSongRetainsManagementWithoutOfferingPlaybackInsertion() {
        val track = MediaMetadata(81, "Restricted", "", emptyList(), 100_000,
            MediaMetadata.Album(31, "Album"), availability = SongPlaybackAvailability.Unavailable)
        val item = track.toThorItem()
        fun actions(song: MediaMetadata) = ThorMenuState(ThorMenuSubject(song.toThorItem(), item.key.stableId),
            ThorMenuAnchor(0f, 0f)).actionRows(false).map { it.action }
        val blocked = actions(track)
        assertFalse(ThorMenuAction.PlayNext in blocked)
        assertFalse(ThorMenuAction.Enqueue in blocked)
        assertTrue(ThorMenuAction.Like in blocked)
        assertTrue(ThorMenuAction.AddToPlaylist in blocked)
        assertTrue(ThorMenuAction.Album in blocked)
        for (playable in listOf(track.copy(availability = SongPlaybackAvailability.Unknown),
            track.copy(isLocal = true), track.copy(isPodcast = true))) {
            assertTrue(ThorMenuAction.PlayNext in actions(playable))
            assertTrue(ThorMenuAction.Enqueue in actions(playable))
        }
    }
}

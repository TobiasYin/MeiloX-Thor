package com.ljyh.mei.thor.data

import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.toThorItem
import org.junit.Assert.*
import org.junit.Test

class ThorLikeProjectionTest {
    private fun song(id: Long, title: String = "Track $id", local: Boolean = false): ThorItem = MediaMetadata(
        id, title, "https://example.test/$id.jpg", listOf(MediaMetadata.Artist(1, "Artist")),
        120_000, MediaMetadata.Album(1, "Album"), isLocal = local,
    ).toThorItem()

    private fun pending(id: Long, liked: Boolean, revision: Long = 1, title: String = "Track $id") =
        ThorLikeOverride(revision, song(id, title), liked)

    @Test fun unlikeRemovesLastCloudSongFromAStaleSnapshotWithoutRemovingSameIdLocalSong() {
        val cloud = song(7)
        val local = song(7, local = true)
        val overrides = ThorLikeOverrides(4, mapOf(7L to pending(7, false)))
        assertTrue(projectThorLikedSongs(listOf(cloud), overrides, 4).isEmpty())
        assertEquals(listOf(local), projectThorLikedSongs(listOf(cloud, local), overrides, 4))
    }

    @Test fun likeRetainsTheActualMetadataUntilALaterReadConfirmsMembership() {
        val entry = pending(7, true, title = "Newly liked")
        val overrides = ThorLikeOverrides(4, mapOf(7L to entry))
        val projected = projectThorLikedSongs(emptyList(), overrides, 4).single()
        assertEquals("Newly liked", projected.title)
        assertEquals(entry.item.tracks, projected.tracks)
        assertEquals(entry.item.artworkUri, projected.artworkUri)
        assertTrue(projected.collected)

        val oldRequest = ThorLikeOverrides(generation = 4)
        assertEquals(overrides, acknowledgeThorLikes(overrides, oldRequest, listOf(song(7)), 4))
        val newestFirst = overrides.copy(entries = overrides.entries + (8L to pending(8, true, revision = 2)))
        assertEquals(listOf(song(8).key, song(7).key), projectThorLikedSongs(emptyList(), newestFirst, 4).map { it.key })
    }

    @Test fun unsuccessfulConfirmationKeepsBothLikeAndUnlikeOverrides() {
        val like = ThorLikeOverrides(4, mapOf(7L to pending(7, true)))
        assertEquals(like, acknowledgeThorLikes(like, like, emptyList(), 4))
        val unlike = ThorLikeOverrides(4, mapOf(7L to pending(7, false)))
        assertEquals(unlike, acknowledgeThorLikes(unlike, unlike, listOf(song(7)), 4))
    }

    @Test fun matchingLaterReadAcknowledgesOnlyTheObservedRevisionAndDesiredMembership() {
        val overrides = ThorLikeOverrides(4, mapOf(
            7L to pending(7, true),
            8L to pending(8, false),
            9L to pending(9, true),
        ))
        val confirmed = acknowledgeThorLikes(overrides, overrides, listOf(song(7)), 4)
        assertEquals(setOf(9L), confirmed.entries.keys)
    }

    @Test fun aReadStartedBeforeASecondToggleCannotClearTheNewerIntent() {
        val old = ThorLikeOverrides(4, mapOf(7L to pending(7, false, revision = 1)))
        val current = ThorLikeOverrides(4, mapOf(7L to pending(7, true, revision = 2)))
        assertEquals(current, acknowledgeThorLikes(current, old, listOf(song(7)), 4))
        assertEquals("Track 7", projectThorLikedSongs(emptyList(), current, 4).single().title)
    }

    @Test fun switchingAccountsClearsOverridesAndOldAccountReadsCannotAcknowledgeNewOnes() {
        val old = ThorLikeOverrides(4, mapOf(7L to pending(7, true)))
        assertSame(old, old.forGeneration(4))
        val reset = old.forGeneration(5)
        assertTrue(reset.entries.isEmpty())
        assertEquals(5L, reset.generation)
        assertTrue(projectThorLikedSongs(emptyList(), old, 5).isEmpty())

        val current = reset.copy(entries = mapOf(8L to pending(8, true, revision = 2)))
        assertEquals(current, acknowledgeThorLikes(current, old, listOf(song(8)), 4))
    }

    @Test fun confirmedReadCanReplaceOptimisticMetadataWithoutCreatingDuplicateSongs() {
        val overrides = ThorLikeOverrides(4, mapOf(7L to pending(7, true, title = "Write metadata")))
        val read = listOf(song(7, "Server metadata"))
        assertEquals(1, projectThorLikedSongs(read, overrides, 4).size)
        val confirmed = acknowledgeThorLikes(overrides, overrides, read, 4)
        assertTrue(confirmed.entries.isEmpty())
        assertEquals("Server metadata", projectThorLikedSongs(read, confirmed, 4).single().title)
    }
}

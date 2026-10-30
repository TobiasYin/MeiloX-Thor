package com.ljyh.mei.thor.menu

import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.model.ThorMenuSubject
import org.junit.Assert.*
import org.junit.Test

class ThorPlaylistTargetSelectionTest {
    private val first = ThorItemKey.Playlist("first")
    private val second = ThorItemKey.Playlist("second")
    private val localSecond = ThorItemKey.Playlist("second", local = true)
    private fun parent() = ThorMenuState(
        ThorMenuSubject(ThorItem(ThorItemKey.Song(7), "歌曲"), "song:7"),
        ThorMenuAnchor(400f, 420f), selection = 3,
    )

    @Test fun anInitiallyEmptyListKeepsCreateSelectedAfterTargetsLoad() {
        val loading = parent().intoPlaylists(emptyList())
        assertSame(ThorPlaylistTarget.Create, loading.playlistTarget)
        assertEquals(0, loading.selection)
        val loaded = loading.syncPlaylistTargets(listOf(first, second))
        assertEquals(2, loaded.selection)
        assertEquals(2, loaded.playlistSelection)
        assertSame(ThorPlaylistTarget.Create, loaded.resolvePlaylistTarget(listOf(first, second)))
        assertEquals(THOR_CREATE_PLAYLIST_TARGET_KEY, loaded.playlistTarget!!.stableKey)
    }

    @Test fun insertionAndReorderingKeepTheExactPlaylistAndLocalOrigin() {
        val original = listOf(first, second, localSecond)
        val target = ThorPlaylistTarget.Existing(localSecond)
        val selected = parent().intoPlaylists(original).selectPlaylistTarget(target, original)
        val reordered = selected.syncPlaylistTargets(listOf(localSecond, first, second))
        assertEquals(0, reordered.selection)
        assertEquals(target, reordered.resolvePlaylistTarget(listOf(localSecond, first, second)))
        val inserted = reordered.syncPlaylistTargets(listOf(ThorItemKey.Playlist("new"), second, first, localSecond))
        assertEquals(3, inserted.selection)
        assertEquals(target, inserted.playlistTarget)
        assertNotEquals(target.stableKey, ThorPlaylistTarget.Existing(second).stableKey)
        val localRemoved = inserted.syncPlaylistTargets(listOf(first, second))
        assertNull(localRemoved.resolvePlaylistTarget(listOf(first, second)))
        assertEquals(target, localRemoved.playlistTarget)
        assertEquals(target, selected.copy(busy = true).syncPlaylistTargets(listOf(localSecond, second)).playlistTarget)
    }

    @Test fun aRemovedTargetNeverResolvesToTheRowThatReplacesItsIndex() {
        val original = listOf(first, second)
        val target = ThorPlaylistTarget.Existing(first)
        val selected = parent().intoPlaylists(original)
        val removed = selected.syncPlaylistTargets(listOf(second))
        assertEquals(-1, removed.selection)
        assertEquals(-1, removed.playlistSelection)
        assertEquals(target, removed.playlistTarget)
        assertNull(removed.resolvePlaylistTarget(listOf(second)))
        assertNull(removed.resolvePlaylistTarget(emptyList()))
        val reopened = removed.back()!!.intoPlaylists(listOf(second))
        assertEquals(target, reopened.playlistTarget)
        assertNull(reopened.resolvePlaylistTarget(listOf(second)))
    }

    @Test fun aFrozenTouchTargetUsesItsKeyAfterTheDisplayedRowMovesOrDisappears() {
        val original = listOf(first, second)
        val frozenTouch = ThorPlaylistTarget.Existing(second)
        val reordered = listOf(second, first)
        val touched = parent().intoPlaylists(original).selectPlaylistTarget(frozenTouch, reordered)
        assertEquals(0, touched.selection)
        assertEquals(frozenTouch, touched.resolvePlaylistTarget(reordered))
        val staleTouch = touched.selectPlaylistTarget(frozenTouch, listOf(first))
        assertEquals(-1, staleTouch.selection)
        assertNull(staleTouch.resolvePlaylistTarget(listOf(first)))
        assertEquals(frozenTouch, staleTouch.playlistTarget)
    }

    @Test fun aFrozenCreateTouchAndNameBackRemainCreateWhileDataLoads() {
        val original = parent().intoPlaylists(emptyList())
        val frozenCreate = requireNotNull(original.playlistTarget)
        val loaded = listOf(first, second, localSecond)
        val touched = original.selectPlaylistTarget(frozenCreate, loaded)
        assertSame(ThorPlaylistTarget.Create, touched.resolvePlaylistTarget(loaded))
        val name = touched.intoName(false).copy(name = "草稿")
            .syncPlaylistTargets(listOf(localSecond, first))
        val returned = name.back()!!.syncPlaylistTargets(listOf(localSecond, first))
        assertEquals(ThorMenuLevel.Playlists, returned.level)
        assertEquals(2, returned.selection)
        assertSame(ThorPlaylistTarget.Create, returned.resolvePlaylistTarget(listOf(localSecond, first)))
        assertEquals(parent().anchor, returned.anchor)
        val parentReturned = returned.back()!!
        assertEquals(parent().selection, parentReturned.selection)
        assertSame(ThorPlaylistTarget.Create, parentReturned.intoPlaylists(loaded).playlistTarget)
    }

    @Test fun onlyAnExplicitNewMoveCanChooseAnotherTargetAfterRemoval() {
        val removed = parent().intoPlaylists(listOf(first)).syncPlaylistTargets(listOf(second))
        assertNull(removed.resolvePlaylistTarget(listOf(second)))
        assertEquals(removed, removed.movePlaylist(0, listOf(second)))
        assertEquals(removed.copy(busy = true), removed.copy(busy = true).movePlaylist(1, listOf(second)))
        val moved = removed.movePlaylist(1, listOf(second))
        assertEquals(ThorPlaylistTarget.Existing(second), moved.resolvePlaylistTarget(listOf(second)))
        assertSame(ThorPlaylistTarget.Create, moved.movePlaylist(-1, listOf(second)).resolvePlaylistTarget(listOf(second)))
    }

    @Test fun explicitNavigationWrapsThroughCreateAndUsesRefreshedOrder() {
        val selected = parent().intoPlaylists(listOf(first, second))
        val moved = selected.movePlaylist(-1, listOf(second, first))
        // first moved to index 1; Up therefore selects second rather than a stale index.
        assertEquals(ThorPlaylistTarget.Existing(second), moved.playlistTarget)
        val wrapped = moved.movePlaylist(-1, listOf(second, first))
        assertSame(ThorPlaylistTarget.Create, wrapped.playlistTarget)
        assertEquals(ThorPlaylistTarget.Existing(second), wrapped.movePlaylist(1, listOf(second, first)).playlistTarget)
    }
}

package com.ljyh.mei.thor

import com.ljyh.mei.thor.menu.*
import com.ljyh.mei.thor.model.*
import com.ljyh.mei.thor.state.*
import org.junit.Assert.*
import org.junit.Test

class ThorNavigationMenuTest {
    @Test fun shouldersOnlySwitchAtRoot() {
        val root = ThorNavigation(tab = ThorTab.Library)
        assertEquals(ThorTab.My, root.switchTab(1).tab)
        val child = root.push(ThorPage.Collection(ThorCollection.LikedSongs))
        assertEquals(child, child.switchTab(1))
        val player = root.toggle(ThorOverlay.Player)
        assertEquals(player, player.switchTab(-1))
    }

    @Test fun playerAndQueueRestoreTheExactOrigin() {
        val origin = ThorNavigation(tab = ThorTab.Library).push(ThorPage.Collection(ThorCollection.LocalSongs))
        val player = origin.toggle(ThorOverlay.Player)
        val queue = player.toggle(ThorOverlay.Queue)
        assertEquals(player, queue.back())
        assertEquals(origin, player.toggle(ThorOverlay.Player))
        assertEquals(origin, queue.back()?.back())
    }

    @Test fun aNewTabPreservesAnotherTabsBackStack() {
        val page = ThorPage.Collection(ThorCollection.CollectedAlbums)
        val library = ThorNavigation(tab = ThorTab.Library).push(page)
        val root = library.back()!!
        val my = root.switchTab(1)
        assertEquals(ThorTab.My, my.tab)
        assertEquals(ThorPage.Root(ThorTab.Library), my.switchTab(-1).page)
    }

    @Test fun siblingCollectionTabsDoNotAddBackStepsOrDiscardOtherTabState() {
        val homeDetail = ThorItem(ThorItemKey.Album(71), "首页专辑")
        val start = ThorNavigation().push(ThorPage.Detail(homeDetail)).copy(tab = ThorTab.Library)
            .push(ThorPage.Collection(ThorCollection.CollectedAlbums))
        val changed = start.replaceCollection(ThorCollection.LikedSongs)
            .replaceCollection(ThorCollection.OwnedPlaylists)
        assertEquals(2, changed.stacks[ThorTab.Library]?.size)
        assertEquals(ThorPage.Root(ThorTab.Library), changed.back()?.page)
        assertEquals(ThorPage.Detail(homeDetail), changed.copy(tab = ThorTab.Home).page)
    }

    @Test fun repeatedSongsAndCrossSourceIdsRemainDistinct() {
        assertNotEquals(ThorItemKey.Song(7, true), ThorItemKey.Song(7, false))
        assertNotEquals(ThorItemKey.Album(7, true).stableId, ThorItemKey.Album(7, false).stableId)
        assertNotEquals(ThorItemKey.QueueEntry("first").stableId, ThorItemKey.QueueEntry("second").stableId)
    }

    private fun menu(owned: Boolean = false) = ThorMenuState(
        ThorMenuSubject(ThorItem(ThorItemKey.Playlist("21"), "歌单", owned = owned), "origin"),
        ThorMenuAnchor(30f, 40f), selection = 2,
    )

    @Test fun playlistSubmenuPreservesAnchorWidthContractAndSelectionMemory() {
        val parent = menu()
        val targets = (0..4).map { ThorItemKey.Playlist("$it") }
        val target = ThorPlaylistTarget.Existing(targets[4])
        val child = parent.intoPlaylists(targets).selectPlaylistTarget(target, targets)
        val returned = child.back()!!
        assertEquals(parent.anchor, child.anchor)
        assertSame(parent.subject, child.subject)
        assertEquals(parent.selection, returned.selection)
        assertEquals(target, returned.intoPlaylists(targets).playlistTarget)
        assertEquals(4, returned.intoPlaylists(targets).selection)
    }

    @Test fun cancelNameReturnsToTheSamePlaylistTarget() {
        val targets = (0..2).map { ThorItemKey.Playlist("$it") }
        val child = menu().intoPlaylists(targets).selectPlaylistTarget(ThorPlaylistTarget.Create, targets)
        val name = child.intoName(false).copy(name = "尚未提交")
        val back = name.back()!!
        assertEquals(ThorMenuLevel.Playlists, back.level)
        assertEquals(3, back.selection)
        assertEquals(ThorPlaylistTarget.Create, back.playlistTarget)
        assertSame(child.subject, back.subject)
    }

    @Test fun readOnlyPlaylistHasNoOwnershipMutations() {
        val actions = menu().actionRows(false).map { it.action }
        assertFalse(actions.contains(ThorMenuAction.Rename))
        assertFalse(actions.contains(ThorMenuAction.Delete))
        assertFalse(actions.contains(ThorMenuAction.RemoveFromPlaylist))
        val ownActions = menu(true).actionRows(false).map { it.action }
        assertTrue(ownActions.contains(ThorMenuAction.Rename))
        assertTrue(ownActions.contains(ThorMenuAction.Delete))
    }

    @Test fun menuDirectionWrapsAndBusyDoesNotExecuteAnotherSelection() {
        assertEquals(4, menu().copy(selection = 0).move(-1, 5).selection)
        val busy = menu().copy(busy = true)
        assertEquals(busy, busy.move(1, 5))
    }
}

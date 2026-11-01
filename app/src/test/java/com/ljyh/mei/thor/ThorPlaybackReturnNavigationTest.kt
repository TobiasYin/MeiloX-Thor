package com.ljyh.mei.thor

import com.ljyh.mei.thor.model.ThorCollection
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.state.*
import org.junit.Assert.*
import org.junit.Test

class ThorPlaybackReturnNavigationTest {
    @Test fun successfulPlaybackReturnsToEverySongSourceWithoutAddingAnUnderlyingBackStep() {
        val album = ThorItem(ThorItemKey.Album(41), "Album")
        val playlist = ThorItem(ThorItemKey.Playlist("42"), "Playlist")
        val sources = listOf(
            ThorPage.Collection(ThorCollection.LocalSongs),
            ThorPage.Collection(ThorCollection.LikedSongs),
            ThorPage.Collection(ThorCollection.Downloads),
            ThorPage.Collection(ThorCollection.History),
            ThorPage.Detail(album), ThorPage.Detail(playlist), ThorPage.Search,
        )
        for (page in sources) {
            val history = ThorQuickPlayerHistory()
            val source = ThorNavigation(tab = ThorTab.Library).push(page)
            val player = history.enter(source, null, 5)
            assertEquals(listOf(ThorOverlay.Player), player.overlays)
            assertEquals(source.stacks, player.stacks)
            val returned = history.leave(player, 5)!!.navigation
            assertEquals(source, returned)
            assertEquals(page.key, returned.sceneKey)
            assertEquals(ThorPage.Root(ThorTab.Library), returned.back()!!.page)
        }
    }

    @Test fun selectingAnotherSongFromQueueKeepsOnePlayerAndTheOriginalBrowsingReturn() {
        val history = ThorQuickPlayerHistory()
        val source = ThorNavigation(tab = ThorTab.Library).push(ThorPage.Collection(ThorCollection.LocalSongs))
        var navigation = history.enter(source, null, 9)
        repeat(4) {
            navigation = history.enter(navigation.toggle(ThorOverlay.Queue), null, 9)
            assertEquals(listOf(ThorOverlay.Player), navigation.overlays)
        }
        assertEquals(source, history.leave(navigation, 9)!!.navigation)
        assertNull(history.leave(navigation, 9))
    }

    @Test fun bareQueueSelectionReturnsToItsSourceAndDoesNotDestroyAnotherTabsStack() {
        val history = ThorQuickPlayerHistory()
        val album = ThorPage.Detail(ThorItem(ThorItemKey.Album(77), "Other tab"))
        val source = ThorNavigation().push(album).copy(tab = ThorTab.Library)
            .push(ThorPage.Collection(ThorCollection.LikedSongs))
        val player = history.enter(source.toggle(ThorOverlay.Queue), null, 2)
        val restored = history.leave(player, 2)!!.navigation
        assertEquals(source, restored)
        assertEquals(album, restored.copy(tab = ThorTab.Home).page)
        assertEquals(source.sceneKey, restored.sceneKey)
    }

    @Test fun repeatedIdenticalNoticeMessagesStillHaveIndependentExpiryIdentities() {
        val first = ThorNotice("已完成")
        val second = ThorNotice("已完成")
        assertNotEquals(first.id, second.id)
        assertEquals(first.id, first.copy(message = "已撤销").id)
    }

    @Test fun qualitySelectionReturnsThroughPlayerToTheOriginalMusicList() {
        val source = ThorNavigation(tab = ThorTab.Library)
            .push(ThorPage.Detail(ThorItem(ThorItemKey.Album(41), "Album")))
        val history = ThorQuickPlayerHistory()
        val player = history.enter(source, null, 5)
        val quality = player.toggle(ThorOverlay.Quality)
        assertEquals("overlay:Quality", quality.sceneKey)
        assertFalse(quality.isRoot)
        assertEquals(quality, quality.switchTab(1))
        assertNull("B in quality must pop its own child before leaving playback", history.leave(quality, 5))
        assertEquals(player, quality.back())
        assertEquals(source, history.leave(quality.back()!!, 5)!!.navigation)
    }

    @Test fun globalPlayerShortcutFromQualityKeepsTheOriginalBrowsingReturn() {
        val source = ThorNavigation().push(ThorPage.Search)
        val history = ThorQuickPlayerHistory()
        val player = history.enter(source, null, 7)
        val shortcut = history.enter(player.toggle(ThorOverlay.Quality), null, 7)
        assertEquals(listOf(ThorOverlay.Player), shortcut.overlays)
        assertEquals(source, history.leave(shortcut, 7)!!.navigation)
        assertNull(history.leave(shortcut, 7))
    }
}

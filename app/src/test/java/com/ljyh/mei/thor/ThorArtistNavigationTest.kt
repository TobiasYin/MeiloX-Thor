package com.ljyh.mei.thor

import com.ljyh.mei.thor.model.ThorCollection
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.state.ThorNavigation
import com.ljyh.mei.thor.state.ThorOverlay
import com.ljyh.mei.thor.state.ThorPage
import com.ljyh.mei.thor.state.ThorQuickPlayerHistory
import com.ljyh.mei.thor.state.ThorTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThorArtistNavigationTest {
    private val artist = ThorItem(ThorItemKey.Artist(51), "Artist profile")
    private val album = ThorItem(ThorItemKey.Album(61), "Artist album")

    @Test fun artistAndAlbumBackReturnThroughTheExactOriginalHomeCollectionOrSearch() {
        val origins = listOf(
            ThorNavigation(tab = ThorTab.Home, stacks = mapOf(ThorTab.Home to listOf(ThorPage.Root(ThorTab.Home)))),
            ThorNavigation(tab = ThorTab.Home).push(ThorPage.Collection(ThorCollection.Artists)),
            ThorNavigation(tab = ThorTab.Discover).push(ThorPage.Search),
        )
        for (origin in origins) {
            val profile = origin.push(ThorPage.Artist(artist))
            val detail = profile.push(ThorPage.Detail(album))
            assertEquals(profile, detail.back())
            assertEquals(origin, detail.back()?.back())
            assertFalse(profile.isRoot)
            assertEquals(profile, profile.switchTab(1))
            assertEquals(profile, profile.switchTab(-1))
        }
    }

    @Test fun globalPlayerAndQueueReturnToTheArtistWithoutAddingAnotherUnderlyingPage() {
        val origin = ThorNavigation(tab = ThorTab.Home).push(ThorPage.Artist(artist))
        val history = ThorQuickPlayerHistory()
        val player = history.enter(origin, null, 11)
        assertEquals(origin.stacks, player.stacks)
        assertEquals(ThorOverlay.Player, player.overlay)
        val fromQueue = history.enter(player.toggle(ThorOverlay.Queue), null, 11)
        assertEquals(listOf(ThorOverlay.Player), fromQueue.overlays)
        val returned = history.leave(fromQueue, 11)!!.navigation
        assertEquals(origin, returned)
        assertEquals(origin.sceneKey, returned.sceneKey)
        assertTrue(returned.back()!!.isRoot)
    }

    @Test fun accountChangeRemovesArtistsAndLoadedDetailsFromEveryTabsStackButKeepsGenericOrigins() {
        val topLists = ThorPage.Collection(ThorCollection.Toplists)
        val liked = ThorPage.Collection(ThorCollection.LikedSongs)
        val before = ThorNavigation(stacks = mapOf(
            ThorTab.Home to listOf(ThorPage.Root(ThorTab.Home), ThorPage.Artist(artist)),
            ThorTab.Discover to listOf(ThorPage.Root(ThorTab.Discover), topLists,
                ThorPage.Artist(artist), ThorPage.Detail(album)),
            ThorTab.Library to listOf(ThorPage.Root(ThorTab.Library), liked,
                ThorPage.Detail(ThorItem(ThorItemKey.Playlist("private-synthetic"), "Private fixture", owned = true)),
                ThorPage.Artist(artist)),
            ThorTab.My to listOf(ThorPage.Root(ThorTab.My), ThorPage.Appearance, ThorPage.Artist(artist)),
        ))
        val changed = before.forChangedAccount()
        assertFalse(changed.stacks.values.flatten().any { it is ThorPage.Artist || it is ThorPage.Detail })
        assertEquals(ThorTab.entries.toSet(), changed.stacks.keys)
        assertEquals(ThorPage.Root(ThorTab.Home), changed.page)
        assertEquals(topLists, changed.copy(tab = ThorTab.Discover).page)
        assertEquals(liked, changed.copy(tab = ThorTab.Library).page)
        assertEquals(ThorPage.Appearance, changed.copy(tab = ThorTab.My).page)
        for ((tab, stack) in changed.stacks) assertEquals(ThorPage.Root(tab), stack.first())
        assertEquals(changed, changed.forChangedAccount())
    }

    @Test fun anOldAccountQuickPlayerHistoryCannotRestoreTheDiscardedArtist() {
        val origin = ThorNavigation().push(ThorPage.Artist(artist))
        val history = ThorQuickPlayerHistory()
        val player = history.enter(origin, null, 11)
        assertNull(history.leave(player, 12))
        assertNull(history.leave(player, 11))
        assertEquals(ThorPage.Root(ThorTab.Home), player.forChangedAccount().back()!!.page)
    }

    @Test fun anArtistProfileHasItsOwnIdentityAndRepeatedOpenDoesNotDuplicateItsBackStack() {
        val root = ThorNavigation(stacks = mapOf(ThorTab.Home to listOf(ThorPage.Root(ThorTab.Home))))
        val profile = root.push(ThorPage.Artist(artist))
        assertEquals(profile, profile.push(ThorPage.Artist(artist.copy(title = "Refreshed profile"))))
        assertNotEquals(ThorPage.Artist(artist).key, ThorPage.Detail(album.copy(key = ThorItemKey.Album(51))).key)
        assertEquals(root, profile.back())
    }
}

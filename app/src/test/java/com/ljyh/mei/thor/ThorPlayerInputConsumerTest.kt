package com.ljyh.mei.thor

import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.thor.menu.*
import com.ljyh.mei.thor.model.*
import com.ljyh.mei.thor.state.*
import org.junit.Assert.*
import org.junit.Test

class ThorPlayerInputConsumerTest {
    @Test fun shortShoulderSeekDoesNotCreateOrCommitAHoldPreview() {
        val input = ThorPlayerShoulderState()
        assertEquals(ThorShoulderSeekAction.None, input.accept(true, ThorShoulderPhase.Press, "a", true))
        assertEquals(ThorShoulderSeekAction.ForwardTap, input.accept(true, ThorShoulderPhase.Tap, "a", true))
        assertEquals(ThorShoulderSeekAction.None, input.accept(true, ThorShoulderPhase.Release, "a", true))
        assertEquals(ThorShoulderSeekAction.None, input.accept(true, ThorShoulderPhase.Release, "a", true))
    }

    @Test fun aHoldPreviewsEachStepAndCommitsOnceOnRelease() {
        val input = ThorPlayerShoulderState()
        input.accept(false, ThorShoulderPhase.Press, "a", true)
        assertEquals(ThorShoulderSeekAction.Begin, input.accept(false, ThorShoulderPhase.HoldStart, "a", true))
        assertEquals(ThorShoulderSeekAction.BackwardStep, input.accept(false, ThorShoulderPhase.HoldStep, "a", true))
        assertEquals(ThorShoulderSeekAction.BackwardStep, input.accept(false, ThorShoulderPhase.HoldStep, "a", true))
        assertEquals(ThorShoulderSeekAction.Apply, input.accept(false, ThorShoulderPhase.Release, "a", true))
        assertEquals(ThorShoulderSeekAction.None, input.accept(false, ThorShoulderPhase.Release, "a", true))
    }

    @Test fun cancellationAndFocusLossCannotCommitALateRelease() {
        for (explicitCancel in listOf(true, false)) {
            val input = ThorPlayerShoulderState()
            input.accept(true, ThorShoulderPhase.Press, "a", true)
            input.accept(true, ThorShoulderPhase.HoldStart, "a", true)
            assertEquals(ThorShoulderSeekAction.Cancel, input.accept(true,
                if (explicitCancel) ThorShoulderPhase.Cancel else ThorShoulderPhase.HoldStep, "a", explicitCancel))
            assertEquals(ThorShoulderSeekAction.None, input.accept(true, ThorShoulderPhase.Release, "a", true))
            assertEquals(ThorShoulderSeekAction.None, input.accept(true, ThorShoulderPhase.Tap, "a", true))
        }
    }

    @Test fun theOppositeShoulderOwnsItsNewPreviewAndIgnoresTheOlderRelease() {
        val input = ThorPlayerShoulderState()
        input.accept(false, ThorShoulderPhase.Press, "a", true)
        input.accept(false, ThorShoulderPhase.HoldStart, "a", true)
        assertEquals(ThorShoulderSeekAction.Cancel, input.accept(true, ThorShoulderPhase.Press, "a", true))
        assertEquals(ThorShoulderSeekAction.Begin, input.accept(true, ThorShoulderPhase.HoldStart, "a", true))
        assertEquals(ThorShoulderSeekAction.None, input.accept(false, ThorShoulderPhase.Release, "a", true))
        assertEquals(ThorShoulderSeekAction.ForwardStep, input.accept(true, ThorShoulderPhase.HoldStep, "a", true))
        assertEquals(ThorShoulderSeekAction.Apply, input.accept(true, ThorShoulderPhase.Release, "a", true))
    }

    @Test fun aTrackChangeCancelsOldWorkButANewPressCanTargetTheNewEntry() {
        val input = ThorPlayerShoulderState()
        input.accept(true, ThorShoulderPhase.Press, "old", true)
        input.accept(true, ThorShoulderPhase.HoldStart, "old", true)
        assertEquals(ThorShoulderSeekAction.Cancel, input.accept(true, ThorShoulderPhase.Release, "new", true))
        input.accept(false, ThorShoulderPhase.Press, "new", true)
        assertEquals(ThorShoulderSeekAction.BackwardTap, input.accept(false, ThorShoulderPhase.Tap, "new", true))
        input.reset()
        assertEquals(ThorShoulderSeekAction.None, input.accept(false, ThorShoulderPhase.Release, "new", true))
    }

    @Test fun startPreservesAnUnsubmittedNameAndTheBrowsingReturnPath() {
        val history = ThorQuickPlayerHistory()
        val source = ThorNavigation(tab = ThorTab.Library).push(ThorPage.Collection(ThorCollection.OwnedPlaylists))
        val name = nameDraft()
        val player = history.enter(source, name, 7)
        assertEquals(listOf(ThorOverlay.Player), player.overlays)
        val restored = history.leave(player, 7)!!
        assertEquals(source, restored.navigation)
        assertSame(name, restored.menu)
        assertEquals("尚未提交的新名称", restored.menu!!.name)
        assertNull(history.leave(player, 7))
    }

    @Test fun aMenuOpenedOverPlayerRestoresBeforeTheOriginalBrowsingOrigin() {
        val history = ThorQuickPlayerHistory()
        val source = ThorNavigation().push(ThorPage.Collection(ThorCollection.LocalSongs))
        val player = history.enter(source, null, 3)
        val reopened = history.enter(player, nameDraft(), 3)
        assertEquals(player, reopened)
        val menuReturn = history.leave(reopened, 3)!!
        assertEquals(player, menuReturn.navigation)
        assertEquals(ThorMenuLevel.Name, menuReturn.menu!!.level)
        val browsingReturn = history.leave(menuReturn.navigation, 3)!!
        assertEquals(source, browsingReturn.navigation)
        assertNull(browsingReturn.menu)
    }

    @Test fun quickPlayerDiscardsForeignAccountDraftsAndDoesNotRestoreBusyOperations() {
        val history = ThorQuickPlayerHistory()
        val source = ThorNavigation().push(ThorPage.Collection(ThorCollection.OwnedPlaylists))
        val player = history.enter(source, nameDraft(), 1)
        assertNull(history.leave(player, 2))
        val busy = history.enter(source, nameDraft().copy(busy = true), 2)
        assertNull(history.leave(busy, 2)!!.menu)
    }

    @Test fun asynchronousOrPlayerMenuNavigationDoesNotDiscardTheFrozenNameDraft() {
        val history = ThorQuickPlayerHistory()
        val source = ThorNavigation().push(ThorPage.Collection(ThorCollection.OwnedPlaylists))
        val draft = nameDraft()
        val player = history.enter(source, draft, 1)
        val album = ThorItem(ThorItemKey.Album(88), "播放中查看的专辑")
        val updated = player.push(ThorPage.Detail(album))
        val restored = history.leave(updated, 1)!!
        assertEquals(ThorPage.Detail(album), restored.navigation.page)
        assertSame(draft, restored.menu)
        assertEquals("尚未提交的新名称", restored.menu!!.name)
    }

    @Test fun startFromABareQueueReturnsToBrowsingWithoutRecursivelyStackingPlayers() {
        val history = ThorQuickPlayerHistory()
        val source = ThorNavigation().push(ThorPage.Collection(ThorCollection.LikedSongs))
        val queue = source.toggle(ThorOverlay.Queue)
        val player = history.enter(queue, null, 0)
        assertEquals(listOf(ThorOverlay.Player), player.overlays)
        assertEquals(player, history.enter(player, null, 0))
        assertEquals(source, history.leave(player, 0)!!.navigation)
    }

    @Test fun ordinaryMenusCloseWithoutRevivingTheirOldQueueActions() {
        val history = ThorQuickPlayerHistory()
        val source = ThorNavigation().push(ThorPage.Collection(ThorCollection.LikedSongs))
        val actions = nameDraft().copy(level = ThorMenuLevel.Actions)
        val player = history.enter(source.toggle(ThorOverlay.Queue), actions, 0)
        val restored = history.leave(player, 0)!!
        assertEquals(source, restored.navigation)
        assertNull(restored.menu)
    }

    @Test fun bulkPlaybackUsesTheVisibleLogicalSongOrderAndNeverFlattensAlbumLists() {
        val tracks = listOf(song(3), song(1), song(3))
        val library = ThorLibraryState(collections = mapOf(
            ThorCollection.LikedSongs to tracks.map { it.toThorItem() },
            ThorCollection.CollectedAlbums to listOf(ThorItem(ThorItemKey.Album(1), "专辑", tracks = tracks)),
        ))
        val songs = ThorNavigation().push(ThorPage.Collection(ThorCollection.LikedSongs))
        assertEquals(listOf(3L, 1L, 3L), thorPagePlayback(songs, library)!!.tracks.map { it.id })
        assertNull(thorPagePlayback(songs.toggle(ThorOverlay.Player), library))
        assertNull(thorPagePlayback(songs.replaceCollection(ThorCollection.CollectedAlbums), library))
        assertNull(thorPagePlayback(ThorNavigation(), library))
        assertNull(thorPagePlayback(ThorNavigation(tab = ThorTab.Discover), library))
    }

    @Test fun albumAndPlaylistBulkPlaybackFreezeTheirTracksAndEmptyDetailsDoNothing() {
        for (key in listOf(ThorItemKey.Album(1), ThorItemKey.Playlist("p"))) {
            val source = mutableListOf(song(9), song(4))
            val item = ThorItem(key, "真实集合", tracks = source)
            val navigation = ThorNavigation().push(ThorPage.Detail(item))
            val target = thorPagePlayback(navigation, ThorLibraryState())!!
            source.clear()
            assertEquals(listOf(9L, 4L), target.tracks.map { it.id })
            assertEquals("真实集合", target.title)
            assertNull(thorPagePlayback(navigation, ThorLibraryState()))
        }
    }

    private fun nameDraft() = ThorMenuState(
        ThorMenuSubject(ThorItem(ThorItemKey.Playlist("p"), "原名称", owned = true), "entry"), ThorMenuAnchor(31f, 70f),
        level = ThorMenuLevel.Name, renaming = true, name = "尚未提交的新名称",
    )
    private fun song(id: Long) = MediaMetadata(id, "歌曲$id", "", emptyList(), 120_000, MediaMetadata.Album(1, "专辑"))
}

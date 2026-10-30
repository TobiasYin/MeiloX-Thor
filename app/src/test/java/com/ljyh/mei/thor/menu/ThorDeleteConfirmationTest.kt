package com.ljyh.mei.thor.menu

import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.model.ThorMenuSubject
import org.junit.Assert.*
import org.junit.Test

class ThorDeleteConfirmationTest {
    private val target = ThorItemKey.Playlist("owned-21")
    private fun parent(key: ThorItemKey.Playlist = target, owned: Boolean = true): ThorMenuState {
        val state = ThorMenuState(ThorMenuSubject(ThorItem(key, "歌单", owned = owned), "origin"), ThorMenuAnchor(480f, 420f))
        return state.copy(selection = state.actionRows(false).indexOfFirst { it.action == ThorMenuAction.Delete })
    }

    @Test fun enteringConfirmationDoesNotAuthorizeADeleteAndDefaultsToCancel() {
        val origin = parent().copy(renaming = true, name = "旧名称草稿")
        val confirmation = origin.intoDeleteConfirmation()
        assertEquals(ThorMenuLevel.ConfirmDelete, confirmation.level)
        assertEquals(ThorDeleteChoice.Cancel, confirmation.deleteChoice())
        assertEquals(0, confirmation.selection)
        assertNull(confirmation.confirmedDeleteKey())
        assertNull(origin.confirmedDeleteKey())
        assertSame(origin.subject, confirmation.subject)
        assertEquals(origin.anchor, confirmation.anchor)
        assertFalse(confirmation.renaming)
        // A held Down from the parent must not silently arm the destructive choice.
        assertEquals(confirmation, confirmation.move(1, 2, repeated = true))
    }

    @Test fun defaultConfirmCancelsAndRestoresTheExactDeleteRow() {
        val origin = parent()
        val confirmation = origin.intoDeleteConfirmation()
        var writes = 0
        confirmation.confirmedDeleteKey()?.let { writes++ }
        val returned = if (confirmation.deleteChoice() == ThorDeleteChoice.Cancel) confirmation.back() else null
        assertEquals(0, writes)
        assertEquals(ThorMenuLevel.Actions, returned!!.level)
        assertEquals(origin.selection, returned.selection)
        assertEquals(ThorMenuAction.Delete, returned.actionRows(false)[returned.selection].action)
        assertSame(origin.subject, returned.subject)
    }

    @Test fun backReturnsToDeleteEvenAfterTheDestructiveChoiceWasHighlighted() {
        val origin = parent()
        val confirmation = origin.intoDeleteConfirmation().selectDeleteChoice(ThorDeleteChoice.Delete, target)
        val returned = confirmation.back()!!
        assertEquals(ThorMenuLevel.Actions, returned.level)
        assertEquals(origin.selection, returned.selection)
        assertNull(returned.confirmedDeleteKey())
        assertEquals(origin.anchor, returned.anchor)
    }

    @Test fun onlyAnExplicitDestructiveChoiceAuthorizesTheFrozenPlaylistIdentity() {
        val localTarget = ThorItemKey.Playlist(target.id, local = true)
        val confirmation = parent(localTarget).intoDeleteConfirmation()
        val staleTouch = confirmation.selectDeleteChoice(ThorDeleteChoice.Delete, target)
        assertEquals(confirmation, staleTouch)
        assertNull(staleTouch.confirmedDeleteKey())
        val submitted = confirmation.selectDeleteChoice(ThorDeleteChoice.Delete, localTarget)
        assertEquals(localTarget, submitted.confirmedDeleteKey())
        assertNotEquals(target, submitted.confirmedDeleteKey())
        val controllerSelected = confirmation.move(1, ThorDeleteChoice.entries.size)
        assertEquals(localTarget, controllerSelected.confirmedDeleteKey())
    }

    @Test fun busyConfirmationCannotChangeSelectionOrSubmitAgain() {
        val busyParent = parent().copy(busy = true)
        assertEquals(busyParent, busyParent.intoDeleteConfirmation())
        val busy = parent().intoDeleteConfirmation().selectDeleteChoice(ThorDeleteChoice.Delete, target).copy(busy = true)
        assertEquals(busy, busy.move(-1, 2))
        assertEquals(busy, busy.selectDeleteChoice(ThorDeleteChoice.Cancel, target))
        assertEquals(busy, busy.back())
        assertNull(busy.deleteChoice())
        assertNull(busy.confirmedDeleteKey())
    }

    @Test fun readOnlyOrUnrelatedObjectsCannotAuthorizeDeletion() {
        val readOnly = parent(owned = false)
        assertEquals(readOnly, readOnly.intoDeleteConfirmation())
        assertNull(readOnly.copy(level = ThorMenuLevel.ConfirmDelete, selection = ThorDeleteChoice.Delete.ordinal).confirmedDeleteKey())
        val song = parent().copy(subject = ThorMenuSubject(ThorItem(ThorItemKey.Song(7), "歌曲", owned = true), "song"))
        assertEquals(song, song.intoDeleteConfirmation())
        assertNull(song.copy(level = ThorMenuLevel.ConfirmDelete, selection = ThorDeleteChoice.Delete.ordinal).confirmedDeleteKey())
    }

    @Test fun successfulDeleteOnlyLeavesTheMatchingDetailWithoutAnOverlay() {
        val submitted = parent().intoDeleteConfirmation().selectDeleteChoice(ThorDeleteChoice.Delete, target)
        assertTrue(submitted.shouldPopDeletedDetail(target, overlayVisible = false))
        assertFalse(submitted.shouldPopDeletedDetail(target, overlayVisible = true))
        assertFalse(submitted.shouldPopDeletedDetail(ThorItemKey.Playlist("another"), overlayVisible = false))
        assertFalse(submitted.shouldPopDeletedDetail(ThorItemKey.Playlist(target.id, local = true), overlayVisible = false))
        assertFalse(submitted.shouldPopDeletedDetail(null, overlayVisible = false))
        assertFalse(parent().intoDeleteConfirmation().shouldPopDeletedDetail(target, overlayVisible = false))
    }
}

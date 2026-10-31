package com.ljyh.mei.thor.ui

import androidx.compose.ui.focus.FocusRequester
import com.ljyh.mei.thor.model.ThorDirection
import org.junit.Assert.*
import org.junit.Test

class ThorFocusRegistryTest {
    @Test fun anUnscopedRegistryCannotSelectRequestOrConfirmChrome() {
        val registry = ThorFocusRegistry()
        var actions = 0
        registry.register("header", ThorFocusEntry(FocusRequester(), { actions++ }))
        registry.select("header")
        assertNull(registry.selectedKey)
        assertNull(registry.current)
        assertFalse(registry.request("header"))
        assertFalse(registry.requestFirst())
        assertFalse(registry.move(ThorDirection.Down, repeated = false))
        assertFalse(registry.confirm())
        assertEquals(0, actions)
    }

    @Test fun anOffscreenLogicalFocusKeepsTheExactActionAndCannotBeRetargetedToChrome() {
        val registry = ThorFocusRegistry()
        val owner = Any()
        var rowActions = 0
        var chromeActions = 0
        registry.installScope(owner, ThorFocusScope("list", listOf("row")))
        registry.register("footer", ThorFocusEntry(FocusRequester(), { chromeActions++ }))
        assertTrue(registry.prepareFocus(owner, "row", null) { rowActions++ })
        assertTrue(registry.request("row"))
        registry.select("footer")
        assertEquals("row", registry.selectedKey)
        assertFalse(registry.request("footer"))
        assertTrue(registry.confirm())
        assertEquals(1, rowActions)
        assertEquals(0, chromeActions)
    }

    @Test fun replacingARegionClearsAnOldPreparedActionAndItsLateDisposalCannotClearTheNewOne() {
        val registry = ThorFocusRegistry()
        val old = Any()
        val current = Any()
        var oldActions = 0
        var currentActions = 0
        registry.installScope(old, ThorFocusScope("old", listOf("old:row")))
        registry.prepareFocus(old, "old:row", null) { oldActions++ }
        registry.installScope(current, ThorFocusScope("new", listOf("new:row")))
        assertNull(registry.selectedKey)
        assertNull(registry.current)
        assertFalse(registry.confirm())
        registry.prepareFocus(current, "new:row", null) { currentActions++ }
        registry.clearPreparedFocus(old)
        registry.removeScope(old)
        assertTrue(registry.isAllowed("new:row"))
        assertTrue(registry.confirm())
        assertEquals(0, oldActions)
        assertEquals(1, currentActions)
    }

    @Test fun aRecompositionWithTheSameScopePreservesPendingFocus() {
        val registry = ThorFocusRegistry()
        val owner = Any()
        registry.installScope(owner, ThorFocusScope("list", listOf("row", "row")))
        registry.prepareFocus(owner, "row", null) {}
        repeat(5) { registry.installScope(owner, ThorFocusScope("list", listOf("row"))) }
        assertEquals("row", registry.selectedKey)
        assertTrue(registry.request("row"))
    }

    @Test fun aBlockedRegionCannotSelectPrepareMoveOrRunAnAction() {
        val registry = ThorFocusRegistry()
        val owner = Any()
        var actions = 0
        registry.installScope(owner, ThorFocusScope("list", listOf("first", "second")))
        registry.prepareFocus(owner, "first", null) { actions++ }
        registry.blocked = true
        registry.select("second")
        assertFalse(registry.prepareFocus(owner, "second", null) { actions++ })
        assertFalse(registry.request("first"))
        assertFalse(registry.move(ThorDirection.Down, repeated = false))
        assertTrue(registry.confirm())
        assertEquals("first", registry.selectedKey)
        assertEquals(0, actions)
        registry.blocked = false
        assertTrue(registry.confirm())
        assertEquals(1, actions)
    }

    @Test fun onlyTheActiveScopeOwnerCanProvideVirtualListMovement() {
        val registry = ThorFocusRegistry()
        val old = Any()
        val current = Any()
        var oldMoves = 0
        registry.installScope(current, ThorFocusScope("new", emptyList()))
        registry.installListMove(old) { _, _ -> oldMoves++; true }
        assertFalse(registry.move(ThorDirection.Down, repeated = true))
        assertEquals(0, oldMoves)
        assertFalse(registry.prepareFocus(old, "new:row", null) {})
    }
}

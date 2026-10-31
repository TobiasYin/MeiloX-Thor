package com.ljyh.mei.thor

import com.ljyh.mei.thor.account.ThorAccountInputHost
import com.ljyh.mei.thor.model.ThorCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThorAccountInputHostTest {
    @Test
    fun disposingThePreviousLeafCannotRemoveItsReplacementConsumer() {
        val host = ThorAccountInputHost()
        val previous = Any()
        val current = Any()
        val calls = mutableListOf<String>()
        host.install(previous) { calls += "previous"; true }
        host.install(current) { calls += "current"; true }
        host.remove(previous)
        assertTrue(host.handle(ThorCommand.Back))
        assertEquals(listOf("current"), calls)
        host.remove(current)
        assertFalse(host.handle(ThorCommand.Back))
    }

    @Test
    fun ownerRemovalUsesIdentityAndDoesNotConfuseEqualPageKeys() {
        data class Owner(val route: String)
        val host = ThorAccountInputHost()
        val active = Owner("account:web")
        host.install(active) { it == ThorCommand.Confirm }
        host.remove(Owner("account:web"))
        assertTrue(host.handle(ThorCommand.Confirm))
        host.remove(active)
        assertFalse(host.handle(ThorCommand.Confirm))
    }

    @Test
    fun aLeafCanConsumeLocalBackAndExplicitlyDelegateOtherCommandsToTheShell() {
        val host = ThorAccountInputHost()
        var webHistoryEntries = 1
        host.install(Any()) { command ->
            if (command == ThorCommand.Back && webHistoryEntries > 0) {
                webHistoryEntries--
                true
            } else false
        }
        assertTrue(host.handle(ThorCommand.Back))
        assertEquals(0, webHistoryEntries)
        assertFalse(host.handle(ThorCommand.Back))
        assertFalse(host.handle(ThorCommand.ContextMenu))
    }
}

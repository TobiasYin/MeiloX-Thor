package com.ljyh.mei.thor.input

import com.ljyh.mei.thor.model.ThorCommand
import org.junit.Assert.*
import org.junit.Test

class ThorSelectInputTest {
    private class Fixture {
        val commands = mutableListOf<ThorCommand>()
        val state = ThorInputState({ commands += it; true })
        fun select(down: Boolean, now: Long, repeat: Boolean = false) = state.select("pad:select", down, now, repeat)
    }

    @Test fun shortSelectWaitsForReleaseAndOpensQueueExactlyOnce() {
        val f = Fixture()
        f.select(true, 0)
        assertEquals(500L, f.state.nextTickAtMs)
        f.state.tick(499)
        assertTrue(f.commands.isEmpty())
        f.select(false, 499)
        f.select(false, 500)
        assertEquals(listOf(ThorCommand.ToggleQueue), f.commands)
        assertNull(f.state.nextTickAtMs)
    }

    @Test fun longSelectOpensPageActionsOnceAndNeverAlsoOpensQueue() {
        val f = Fixture()
        f.select(true, 0)
        f.select(true, 100, repeat = true)
        f.state.tick(500)
        assertEquals(listOf(ThorCommand.PageActions), f.commands)
        assertNull(f.state.nextTickAtMs)
        f.state.tick(5_000)
        f.select(true, 5_001, repeat = true)
        f.select(false, 5_010)
        assertEquals(listOf(ThorCommand.PageActions), f.commands)
    }

    @Test fun releaseAtTheLongThresholdStillWorksBeforeTheHandlerRuns() {
        val f = Fixture()
        f.select(true, 10)
        f.select(false, 510)
        f.state.tick(511)
        assertEquals(listOf(ThorCommand.PageActions), f.commands)
    }

    @Test fun resetCancelsPendingSelectAndItsLateUpCannotToggleTheQueue() {
        val f = Fixture()
        f.select(true, 0)
        f.state.reset(100)
        f.state.tick(1_000)
        f.select(true, 1_001, repeat = true)
        f.select(false, 1_010)
        assertTrue(f.commands.isEmpty())
        f.select(true, 1_020)
        f.select(false, 1_030)
        assertEquals(listOf(ThorCommand.ToggleQueue), f.commands)
    }

    @Test fun aRediscoveredHeldSelectMustReleaseBeforeStartingANewGesture() {
        val f = Fixture()
        f.select(true, 0, repeat = true)
        f.state.tick(600)
        f.select(false, 601)
        assertTrue(f.commands.isEmpty())
        f.select(true, 700)
        f.state.tick(1_200)
        assertEquals(listOf(ThorCommand.PageActions), f.commands)
    }

    @Test fun openingThePageMenuCanSynchronouslyResetItsOwnInputOwner() {
        val commands = mutableListOf<ThorCommand>()
        lateinit var state: ThorInputState
        state = ThorInputState({ command ->
            commands += command
            if (command == ThorCommand.PageActions) state.reset(500)
            true
        })
        state.select("select", true, 0)
        state.tick(500)
        state.select("select", false, 501)
        state.tick(1_000)
        assertEquals(listOf(ThorCommand.PageActions), commands)
        assertNull(state.nextTickAtMs)
    }

    @Test fun selectGesturesKeepDifferentControllersIndependent() {
        val f = Fixture()
        f.state.select("one", true, 0)
        f.state.select("two", true, 100)
        f.state.select("two", false, 150)
        f.state.tick(500)
        f.state.select("one", false, 501)
        assertEquals(listOf(ThorCommand.ToggleQueue, ThorCommand.PageActions), f.commands)
    }
}

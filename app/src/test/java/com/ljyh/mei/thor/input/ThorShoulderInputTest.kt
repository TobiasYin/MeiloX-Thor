package com.ljyh.mei.thor.input

import com.ljyh.mei.thor.model.ThorCommand
import com.ljyh.mei.thor.model.ThorShoulderPhase
import org.junit.Assert.*
import org.junit.Test

class ThorShoulderInputTest {
    private class Fixture {
        val commands = mutableListOf<ThorCommand>()
        val input = ThorInputState({ commands += it; true })
        val phases get() = commands.filterIsInstance<ThorCommand.Shoulder>().map { it.phase }
        fun left(down: Boolean, time: Long, repeated: Boolean = false) = input.shoulder("1:L1", false, down, time, repeated)
    }

    @Test fun shortPressIsImmediateAndReleaseEmitsExactlyOneTap() {
        val f = Fixture()
        f.left(true, 0)
        assertEquals(listOf(ThorShoulderPhase.Press), f.phases)
        assertEquals(320L, f.input.nextTickAtMs)
        f.left(true, 100, repeated = true)
        f.input.tick(319)
        f.left(false, 319)
        f.left(false, 320)
        assertEquals(listOf(ThorShoulderPhase.Press, ThorShoulderPhase.Tap, ThorShoulderPhase.Release), f.phases)
        assertFalse(f.input.needsRepeat)
    }

    @Test fun longPressHasOneHoldStartAndFiftyMillisecondStepsWithoutTap() {
        val f = Fixture()
        f.left(true, 0)
        f.input.tick(320)
        assertEquals(listOf(ThorShoulderPhase.Press, ThorShoulderPhase.HoldStart), f.phases)
        assertEquals(370L, f.input.nextTickAtMs)
        f.input.tick(369)
        f.input.tick(370)
        f.input.tick(420)
        f.left(false, 421)
        assertEquals(listOf(ThorShoulderPhase.Press, ThorShoulderPhase.HoldStart,
            ThorShoulderPhase.HoldStep, ThorShoulderPhase.HoldStep, ThorShoulderPhase.Release), f.phases)
        assertTrue(f.commands.filterIsInstance<ThorCommand.Shoulder>().all { !it.right })
    }

    @Test fun aStalledUiNeverReplaysAccumulatedHoldSteps() {
        val f = Fixture()
        f.left(true, 0)
        f.input.tick(5_000)
        assertEquals(listOf(ThorShoulderPhase.Press, ThorShoulderPhase.HoldStart), f.phases)
        f.input.tick(10_000)
        assertEquals(1, f.phases.count { it == ThorShoulderPhase.HoldStep })
        assertEquals(10_050L, f.input.nextTickAtMs)
        f.input.tick(10_000)
        assertEquals(1, f.phases.count { it == ThorShoulderPhase.HoldStep })
    }

    @Test fun releasingAfterThresholdBeforeTheScheduledTickIsStillAHold() {
        val f = Fixture()
        f.left(true, 0)
        f.left(false, 600)
        assertEquals(listOf(ThorShoulderPhase.Press, ThorShoulderPhase.HoldStart, ThorShoulderPhase.Release), f.phases)
        assertFalse(f.input.needsRepeat)
    }

    @Test fun focusLossCancelsPreviewAndLateReleaseCannotApplyOrTap() {
        val f = Fixture()
        f.left(true, 0)
        f.input.tick(320)
        f.input.tick(370)
        f.input.reset(380)
        f.left(true, 400, repeated = true)
        f.input.tick(1_000)
        f.left(false, 1_010)
        assertEquals(listOf(ThorShoulderPhase.Press, ThorShoulderPhase.HoldStart,
            ThorShoulderPhase.HoldStep, ThorShoulderPhase.Cancel), f.phases)
        assertFalse(f.input.needsRepeat)
        f.left(true, 1_020)
        f.left(false, 1_030)
        assertEquals(listOf(ThorShoulderPhase.Press, ThorShoulderPhase.Tap, ThorShoulderPhase.Release), f.phases.takeLast(3))
    }

    @Test fun aShortPressCancelledByFocusLossDoesNotBecomeATap() {
        val f = Fixture()
        f.left(true, 0)
        f.input.reset(100)
        f.left(false, 200)
        assertEquals(listOf(ThorShoulderPhase.Press, ThorShoulderPhase.Cancel), f.phases)
    }

    @Test fun repeatRediscoveredAfterResetMustReleaseBeforeANewShoulderGesture() {
        val f = Fixture()
        f.input.reset(0)
        f.left(true, 100, repeated = true)
        f.input.tick(1_000)
        f.left(false, 1_100)
        assertTrue(f.commands.isEmpty())
        f.left(true, 1_200)
        f.left(false, 1_201)
        assertEquals(listOf(ThorShoulderPhase.Press, ThorShoulderPhase.Tap, ThorShoulderPhase.Release), f.phases)
    }

    @Test fun oppositeShouldersKeepIndependentGestureIdentityAndBothCancelOnReset() {
        val f = Fixture()
        f.left(true, 0)
        f.input.shoulder("1:R1", true, true, 100)
        f.input.tick(320)
        f.input.tick(420)
        f.input.reset(421)
        val shoulders = f.commands.filterIsInstance<ThorCommand.Shoulder>()
        assertEquals(1, shoulders.count { !it.right && it.phase == ThorShoulderPhase.HoldStart })
        assertEquals(1, shoulders.count { it.right && it.phase == ThorShoulderPhase.HoldStart })
        assertEquals(2, shoulders.count { it.phase == ThorShoulderPhase.Cancel })
        assertFalse(f.input.needsRepeat)
    }

    @Test fun aSynchronousOwnershipResetDuringHoldStartCannotEmitLaterSteps() {
        val commands = mutableListOf<ThorCommand>()
        lateinit var input: ThorInputState
        input = ThorInputState({ command ->
            commands += command
            if (command == ThorCommand.Shoulder(false, ThorShoulderPhase.HoldStart)) input.reset(320)
            true
        })
        input.shoulder("left", false, true, 0)
        input.tick(320)
        input.tick(5_000)
        input.shoulder("left", false, false, 5_001)
        assertEquals(listOf(ThorShoulderPhase.Press, ThorShoulderPhase.HoldStart, ThorShoulderPhase.Cancel),
            commands.filterIsInstance<ThorCommand.Shoulder>().map { it.phase })
    }
}

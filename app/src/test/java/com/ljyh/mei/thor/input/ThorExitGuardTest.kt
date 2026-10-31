package com.ljyh.mei.thor.input

import com.ljyh.mei.thor.model.ThorCommand
import org.junit.Assert.*
import org.junit.Test

class ThorExitGuardTest {
    @Test fun aSecondFreshBackWithinTwoSecondsExitsAndConsumesTheArm() {
        val guard = ThorExitGuard()
        assertEquals(ThorExitDecision.Armed, guard.onBack(100))
        assertEquals(2_100L, guard.armedUntilMs)
        assertEquals(ThorExitDecision.Exit, guard.onBack(2_099))
        assertNull(guard.armedUntilMs)
        assertEquals(ThorExitDecision.Armed, guard.onBack(2_100))
    }

    @Test fun exactDeadlineIsAcceptedButExpiredBackStartsANewWindow() {
        assertEquals(ThorExitDecision.Exit, ThorExitGuard().run { onBack(0); onBack(2_000) })
        val guard = ThorExitGuard()
        guard.onBack(0)
        assertEquals(ThorExitDecision.Armed, guard.onBack(2_001))
        assertEquals(4_001L, guard.armedUntilMs)
    }

    @Test fun repeatsNeitherArmConfirmNorExtendTheWindow() {
        val guard = ThorExitGuard()
        assertEquals(ThorExitDecision.IgnoreRepeat, guard.onBack(0, repeated = true))
        assertNull(guard.armedUntilMs)
        guard.onBack(10)
        assertEquals(ThorExitDecision.IgnoreRepeat, guard.onBack(1_999, repeated = true))
        assertEquals(2_010L, guard.armedUntilMs)
        assertEquals(ThorExitDecision.Armed, guard.onBack(2_011))
    }

    @Test fun callerClearingForOtherInputNavigationOrBackgroundRequiresTwoNewPresses() {
        for (reason in listOf("other input", "navigation", "background")) {
            val guard = ThorExitGuard()
            guard.onBack(0)
            guard.clear()
            assertNull(reason, guard.armedUntilMs)
            assertEquals(reason, ThorExitDecision.Armed, guard.onBack(100))
            assertEquals(reason, ThorExitDecision.Exit, guard.onBack(200))
        }
    }

    @Test fun clockRollbackCannotConfirmAnOldArm() {
        val guard = ThorExitGuard()
        guard.onBack(1_000)
        assertEquals(ThorExitDecision.Armed, guard.onBack(999))
        assertEquals(2_999L, guard.armedUntilMs)
    }

    @Test fun heldAndroidBackDownsCannotReachTheExitGuardTwice() {
        val guard = ThorExitGuard()
        val decisions = mutableListOf<ThorExitDecision>()
        var now = 0L
        val state = ThorInputState({ command ->
            if (command == ThorCommand.Back) decisions += guard.onBack(now)
            true
        })
        state.button("B", true, ThorCommand.Back)
        now = 100
        state.button("B", true, ThorCommand.Back)
        state.button("B", true, ThorCommand.Back, repeated = true)
        assertEquals(listOf(ThorExitDecision.Armed), decisions)
        state.button("B", false, ThorCommand.Back)
        state.button("B", true, ThorCommand.Back)
        assertEquals(listOf(ThorExitDecision.Armed, ThorExitDecision.Exit), decisions)
    }

    @Test fun deadlineArithmeticDoesNotWrapNearTheClockLimit() {
        val guard = ThorExitGuard()
        guard.onBack(Long.MAX_VALUE - 10)
        assertEquals(Long.MAX_VALUE, guard.armedUntilMs)
        assertEquals(ThorExitDecision.Exit, guard.onBack(Long.MAX_VALUE))
    }
}

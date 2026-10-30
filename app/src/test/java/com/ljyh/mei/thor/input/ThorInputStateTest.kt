package com.ljyh.mei.thor.input

import com.ljyh.mei.thor.model.ThorCommand
import com.ljyh.mei.thor.model.ThorDirection
import org.junit.Assert.*
import org.junit.Test

class ThorInputStateTest {
    @Test fun faceButtonsFireOncePerPressAndAResetDoesNotTreatSystemRepeatAsANewPress() {
        val commands = mutableListOf<ThorCommand>()
        val input = ThorInputState({ commands += it; true })
        input.button("A", true, ThorCommand.Confirm)
        repeat(4) { input.button("A", true, ThorCommand.Confirm, repeated = true) }
        input.button("A", false, ThorCommand.Confirm)
        input.button("A", true, ThorCommand.Confirm)
        assertEquals(listOf(ThorCommand.Confirm, ThorCommand.Confirm), commands)
        input.reset()
        input.button("A", true, ThorCommand.Confirm, repeated = true)
        input.button("A", false, ThorCommand.Confirm)
        assertEquals(2, commands.size)
    }

    @Test fun heldDirectionRepeatsOnlyAfterDelayAndDoesNotReplayAStalledFramesBacklog() {
        val commands = mutableListOf<ThorCommand>()
        val input = ThorInputState({ commands += it; true })
        input.direction("down", true, ThorDirection.Down, 0)
        input.tick(319)
        assertEquals(listOf(ThorCommand.Move(ThorDirection.Down)), commands)
        input.tick(320)
        input.tick(5_000)
        assertEquals(3, commands.size)
        assertEquals(ThorCommand.Move(ThorDirection.Down, repeated = true), commands.last())
        input.direction("down", false, ThorDirection.Down, 5_001)
        input.tick(9_000)
        assertEquals(3, commands.size)
        assertFalse(input.needsRepeat)
    }

    @Test fun aRejectedBoundaryMoveLatchesUntilNeutralSoRepeatsCannotEscapeTheList() {
        val commands = mutableListOf<ThorCommand>()
        val input = ThorInputState({ commands += it; it !is ThorCommand.Move || !it.repeated })
        input.stick(5, 0f, 1f, 0)
        input.tick(320)
        input.stick(5, 0f, .9f, 500)
        input.tick(900)
        assertEquals(2, commands.size)
        assertFalse(input.needsRepeat)
        input.stick(5, 0f, 0f, 1_000)
        input.stick(5, 0f, 1f, 1_010)
        assertEquals(ThorCommand.Move(ThorDirection.Down), commands.last())
        assertEquals(3, commands.size)
    }

    @Test fun hatAndDpadReportingTheSameDirectionDoNotMoveTwice() {
        val commands = mutableListOf<ThorCommand>()
        val input = ThorInputState({ commands += it; true })
        input.direction("dpad", true, ThorDirection.Right, 0)
        input.stick(1, 1f, 0f, 1)
        input.direction("dpad", false, ThorDirection.Right, 100)
        assertEquals(listOf(ThorCommand.Move(ThorDirection.Right)), commands)
        input.stick(1, 0f, 0f, 101)
        input.stick(1, 1f, 0f, 200)
        assertEquals(2, commands.size)
    }

    @Test fun triggerAxisAndDigitalEdgesAreDeduplicatedInEitherArrivalOrder() {
        val commands = mutableListOf<ThorCommand>()
        val input = ThorInputState({ commands += it; true })
        input.triggerAxis(1, true, .9f, 100)
        input.trigger(1, true, digital = true, down = true, nowMs = 101)
        input.triggerAxis(1, true, .4f, 120)
        input.trigger(1, true, digital = true, down = false, nowMs = 130)
        input.triggerAxis(1, true, 0f, 140)
        input.trigger(1, true, digital = true, down = true, nowMs = 250)
        input.triggerAxis(1, true, .8f, 251)
        assertEquals(listOf(ThorCommand.NextTrack, ThorCommand.NextTrack), commands)
        input.trigger(1, false, digital = true, down = true, nowMs = 252)
        assertEquals(ThorCommand.PreviousTrack, commands.last())
    }

    @Test fun joystickHysteresisRejectsDriftAndResetStopsAllRepeats() {
        assertNull(stickDirection(.2f, .2f, null))
        assertEquals(ThorDirection.Left, stickDirection(-.5f, .1f, null))
        assertEquals(ThorDirection.Left, stickDirection(-.3f, .1f, ThorDirection.Left))
        assertNull(stickDirection(-.2f, .1f, ThorDirection.Left))
        assertNull(stickDirection(.3f, .1f, ThorDirection.Left))
        assertNull(stickDirection(Float.NaN, 1f, null))
        val commands = mutableListOf<ThorCommand>()
        val input = ThorInputState({ commands += it; true })
        input.direction("up", true, ThorDirection.Up, 0)
        input.reset()
        input.tick(1_000)
        input.direction("up", true, ThorDirection.Up, 1_010, repeated = true)
        assertEquals(1, commands.size)
    }

    @Test fun controlsStillHeldAcrossFocusLossMustReturnToNeutralBeforeTheyCanActAgain() {
        val commands = mutableListOf<ThorCommand>()
        val input = ThorInputState({ commands += it; true })
        input.stick(3, 0f, -1f, 100)
        input.triggerAxis(3, true, 1f, 110)
        input.reset(200)
        input.stick(3, 0f, -1f, 300)
        input.triggerAxis(3, true, 1f, 310)
        assertEquals(2, commands.size)
        input.stick(3, 0f, 0f, 400)
        input.triggerAxis(3, true, 0f, 410)
        input.stick(3, 0f, -1f, 500)
        input.triggerAxis(3, true, 1f, 510)
        assertEquals(4, commands.size)
    }
}

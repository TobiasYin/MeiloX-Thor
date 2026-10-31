package com.ljyh.mei.thor.input

import com.ljyh.mei.thor.model.ThorCommand
import org.junit.Assert.*
import org.junit.Test

class ThorLinearTriggerTest {
    private class Fixture {
        val commands = mutableListOf<ThorCommand>()
        val input = ThorInputState({ commands += it; true })
        fun axis(value: Float, time: Long, right: Boolean = true) = input.triggerAxis(1, right, value, time)
        fun digital(down: Boolean, time: Long, right: Boolean = true, axisExpected: Boolean = true, repeated: Boolean = false) =
            input.trigger(1, right, true, down, time, axisExpected, repeated)
    }

    @Test fun fullLinearTravelAndSlowReleaseProduceOneTrackChange() {
        val f = Fixture()
        val stroke = listOf(0f, .02f, .08f, .19f, .32f, .48f, .56f, .69f, .84f, 1f,
            .97f, .78f, .55f, .42f, .24f, .13f, .08f, .05f, .01f, 0f)
        stroke.forEachIndexed { index, value -> f.axis(value, index * 100L) }
        assertEquals(listOf(ThorCommand.NextTrack), f.commands)
        f.axis(.7f, 2_100)
        assertEquals(listOf(ThorCommand.NextTrack, ThorCommand.NextTrack), f.commands)
    }

    @Test fun middleTravelAndThresholdChatterNeverRearmBeforeRealNeutral() {
        val f = Fixture()
        f.axis(.8f, 0)
        listOf(.54f, .56f, .25f, .7f, .06f, .6f, .3f, .9f).forEachIndexed { index, value ->
            f.axis(value, 200L * (index + 1))
        }
        assertEquals(listOf(ThorCommand.NextTrack), f.commands)
        f.axis(.05f, 1_700)
        f.axis(.55f, 1_701)
        assertEquals(2, f.commands.size)
    }

    @Test fun aFastGenuineSecondStrokeIsAcceptedWithoutAnEightyMillisecondDebounce() {
        val f = Fixture()
        f.axis(.8f, 100)
        f.axis(0f, 115)
        f.axis(.8f, 130)
        assertEquals(listOf(ThorCommand.NextTrack, ThorCommand.NextTrack), f.commands)
    }

    @Test fun digitalReleaseBeforeAnalogNeutralCannotRearmOrDuplicateAnAxisCrossing() {
        val f = Fixture()
        f.axis(.2f, 0)
        f.digital(true, 1)
        f.digital(false, 100)
        f.axis(.7f, 101)
        f.axis(.15f, 200)
        f.axis(.9f, 300)
        assertEquals(listOf(ThorCommand.NextTrack), f.commands)
        f.axis(0f, 400)
        f.digital(true, 401)
        assertEquals(2, f.commands.size)
    }

    @Test fun aDigitalFirstControllerMustReportActualAxisNeutralBeforeRearming() {
        val f = Fixture()
        f.digital(true, 0)
        f.digital(false, 100)
        // The first analog packet may lag both digital edges. It is the same stroke.
        f.axis(.8f, 101)
        assertEquals(listOf(ThorCommand.NextTrack), f.commands)
        f.axis(0f, 200)
        f.axis(.8f, 201)
        assertEquals(2, f.commands.size)
    }

    @Test fun axisNeutralBeforeDigitalReleaseStillKeepsTheStrokeLocked() {
        val f = Fixture()
        f.axis(.8f, 0)
        f.digital(true, 1)
        f.axis(0f, 100)
        f.axis(.8f, 200)
        assertEquals(1, f.commands.size)
        f.axis(0f, 300)
        f.digital(false, 301)
        f.axis(.8f, 302)
        assertEquals(2, f.commands.size)
    }

    @Test fun mixedArrivalOrdersStayOneEventPerStrokeOnBothSides() {
        val f = Fixture()
        f.axis(.9f, 0, right = false)
        f.digital(true, 1, right = false)
        f.digital(false, 100, right = false)
        f.axis(0f, 101, right = false)
        f.digital(true, 102, right = true)
        f.axis(.8f, 103, right = true)
        f.axis(0f, 200, right = true)
        f.digital(false, 201, right = true)
        assertEquals(listOf(ThorCommand.PreviousTrack, ThorCommand.NextTrack), f.commands)
        f.digital(true, 202, right = false)
        f.axis(.7f, 203, right = false)
        assertEquals(listOf(ThorCommand.PreviousTrack, ThorCommand.NextTrack, ThorCommand.PreviousTrack), f.commands)
    }

    @Test fun focusLossWhileHeldRequiresBothRealAxisNeutralAndDigitalRelease() {
        val f = Fixture()
        f.axis(.9f, 0)
        f.digital(true, 1)
        f.input.reset(100)
        f.digital(false, 200)
        f.axis(.3f, 300)
        f.axis(.8f, 400)
        assertEquals(1, f.commands.size)
        f.axis(.05f, 500)
        f.digital(true, 501)
        f.axis(.8f, 502)
        assertEquals(2, f.commands.size)
    }

    @Test fun aHeldTriggerDiscoveredAsSystemRepeatCannotBecomeAFreshPress() {
        val f = Fixture()
        f.input.reset(10)
        f.digital(true, 20, repeated = true)
        f.axis(.9f, 21)
        f.digital(false, 100)
        f.axis(.1f, 101)
        assertTrue(f.commands.isEmpty())
        f.axis(0f, 102)
        f.digital(true, 103)
        assertEquals(listOf(ThorCommand.NextTrack), f.commands)
    }

    @Test fun digitalOnlyControllersDoNotWaitForNonexistentAnalogPackets() {
        val f = Fixture()
        f.digital(true, 0, axisExpected = false)
        f.digital(false, 1, axisExpected = false)
        f.digital(true, 2, axisExpected = false)
        f.digital(false, 3, axisExpected = false)
        assertEquals(2, f.commands.size)
    }

    @Test fun invalidAxisSamplesNeverCountAsNeutralOrASecondPress() {
        val f = Fixture()
        f.axis(.9f, 0)
        f.axis(Float.NaN, 100)
        f.axis(Float.POSITIVE_INFINITY, 200)
        f.axis(.9f, 300)
        assertEquals(1, f.commands.size)
        f.axis(0f, 400)
        f.axis(.9f, 401)
        assertEquals(2, f.commands.size)
    }

    @Test fun textEntryObservesReleaseWithoutPlayingAndAStillHeldTriggerCannotFireOnReturn() {
        val f = Fixture()
        f.input.trigger(1, true, digital = true, down = true, nowMs = 0,
            axisExpected = true, allowActions = false)
        f.input.triggerAxis(1, true, .9f, 1, allowActions = false)
        f.input.reset(100) // Text entry closed; the physical trigger is still held.
        f.axis(.9f, 101)
        assertTrue(f.commands.isEmpty())
        f.digital(false, 200)
        f.axis(0f, 201)
        f.digital(true, 202)
        f.axis(.9f, 203)
        assertEquals(listOf(ThorCommand.NextTrack), f.commands)
    }

    @Test fun neutralObservedDuringTextEntryDoesNotDiscardTheFirstFreshPressAfterItCloses() {
        val f = Fixture()
        f.axis(.9f, 0)
        f.digital(true, 1)
        f.input.reset(100)
        f.input.trigger(1, true, digital = true, down = false, nowMs = 200, allowActions = false)
        f.input.triggerAxis(1, true, 0f, 201, allowActions = false)
        f.input.reset(300)
        f.digital(true, 301)
        assertEquals(listOf(ThorCommand.NextTrack, ThorCommand.NextTrack), f.commands)
    }
}

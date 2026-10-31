package com.ljyh.mei.thor.input

import com.ljyh.mei.thor.model.ThorCommand
import com.ljyh.mei.thor.model.ThorDirection
import com.ljyh.mei.thor.model.ThorShoulderPhase
import kotlin.math.abs

/** Event-independent controller state. Time is supplied by the Android adapter or a test. */
internal class ThorInputState(
    private val onCommand: (ThorCommand) -> Boolean,
    private val repeatDelayMs: Long = 320,
    private val repeatIntervalMs: Long = 100,
    private val shoulderHoldDelayMs: Long = 320,
    private val shoulderStepIntervalMs: Long = 50,
) {
    private val buttons = mutableMapOf<String, Boolean>()
    private val directions = linkedMapOf<String, ThorDirection>()
    private val sticks = linkedMapOf<Int, ThorDirection>()
    private val sticksAwaitingNeutral = mutableSetOf<Int>()
    private val triggers = mutableMapOf<Pair<Int, Boolean>, Trigger>()
    private val shoulders = linkedMapOf<String, Shoulder>()
    private val shouldersAwaitingRelease = mutableSetOf<String>()
    private var direction: ThorDirection? = null
    private var blocked = false
    private var nextRepeatMs = Long.MAX_VALUE

    val needsRepeat: Boolean get() = nextTickAtMs != null
    val nextTickAtMs: Long? get() = listOfNotNull(
        nextRepeatMs.takeIf { direction != null && !blocked },
        shoulders.values.minOfOrNull { if (it.holding) it.nextStepMs else it.startedAtMs + shoulderHoldDelayMs },
    ).minOrNull()

    fun button(token: String, down: Boolean, command: ThorCommand, repeated: Boolean = false): Boolean {
        if (!down) return buttons.remove(token) ?: false
        buttons[token]?.let { return it }
        if (repeated) return true.also { buttons[token] = it }
        return onCommand(command).also { buttons[token] = it }
    }

    fun direction(token: String, down: Boolean, value: ThorDirection, nowMs: Long, repeated: Boolean = false): Boolean {
        if (down) {
            if (repeated && !directions.containsKey(token)) return true
            if (!directions.containsKey(token)) directions[token] = value
        } else directions.remove(token)
        updateDirection(nowMs)
        // Directional events have exactly one consumer; never also let Compose move focus.
        return true
    }

    fun stick(deviceId: Int, x: Float, y: Float, nowMs: Long) {
        if (deviceId in sticksAwaitingNeutral) {
            if (maxOf(abs(x), abs(y)) < .25f) sticksAwaitingNeutral.remove(deviceId)
            return
        }
        val value = stickDirection(x, y, sticks[deviceId])
        if (value == null) sticks.remove(deviceId) else sticks[deviceId] = value
        updateDirection(nowMs)
    }

    fun trigger(
        deviceId: Int, right: Boolean, digital: Boolean, down: Boolean, nowMs: Long,
        axisExpected: Boolean = false, repeated: Boolean = false, allowActions: Boolean = true,
    ): Boolean {
        val key = deviceId to right
        val trigger = triggers.getOrPut(key) { Trigger() }
        trigger.axisExpected = trigger.axisExpected || axisExpected || !digital
        if (digital && down && repeated && !trigger.digital) {
            // A held key rediscovered after ownership loss is not a fresh physical press.
            trigger.digital = true
            trigger.armed = false
            trigger.awaitingAxisNeutral = trigger.axisExpected
            return true
        }
        val newDigitalPress = digital && down && !trigger.digital
        if (digital) trigger.digital = down else {
            trigger.axis = down
            if (!down) trigger.awaitingAxisNeutral = false
        }
        if (!allowActions && down) {
            trigger.armed = false
            trigger.awaitingAxisNeutral = trigger.axisExpected
        } else if ((newDigitalPress || (!digital && down)) && trigger.armed) fireTrigger(trigger, right)
        rearmTrigger(trigger)
        return trigger.consumed
    }

    fun triggerAxis(deviceId: Int, right: Boolean, value: Float, nowMs: Long, allowActions: Boolean = true) {
        val latch = triggers.getOrPut(deviceId to right) { Trigger() }
        // The entire physical stroke owns the latch, including its low/middle travel.
        // Crossing .55 repeatedly is not a release; only <=.05 plus digital UP rearms.
        val travel = if (value.isFinite()) value.coerceIn(0f, 1f) else return
        latch.axisExpected = true
        latch.axis = travel > .05f
        if (!latch.axis) latch.awaitingAxisNeutral = false
        if (!allowActions && latch.axis) {
            latch.armed = false
            latch.awaitingAxisNeutral = true
        } else if (allowActions && travel >= .55f && latch.armed) fireTrigger(latch, right)
        rearmTrigger(latch)
    }

    fun tick(nowMs: Long) {
        val current = direction
        if (current != null && !blocked && nowMs >= nextRepeatMs) {
            // Update the deadline before dispatch: a callback may synchronously reset ownership.
            nextRepeatMs = nowMs + repeatIntervalMs
            if (!onCommand(ThorCommand.Move(current, repeated = true)) && direction == current) blocked = true
        }
        for ((token, shoulder) in shoulders.toMap()) {
            if (shoulders[token] !== shoulder) continue
            if (!shoulder.holding && nowMs - shoulder.startedAtMs >= shoulderHoldDelayMs) {
                shoulder.holding = true
                shoulder.nextStepMs = nowMs + shoulderStepIntervalMs
                onCommand(ThorCommand.Shoulder(shoulder.right, ThorShoulderPhase.HoldStart))
            } else if (shoulder.holding && nowMs >= shoulder.nextStepMs) {
                // One step per tick. Never replay steps accumulated while the UI was stalled.
                shoulder.nextStepMs = nowMs + shoulderStepIntervalMs
                onCommand(ThorCommand.Shoulder(shoulder.right, ThorShoulderPhase.HoldStep))
            }
        }
    }

    fun shoulder(token: String, right: Boolean, down: Boolean, nowMs: Long, repeated: Boolean = false): Boolean {
        if (token in shouldersAwaitingRelease) {
            if (!down) shouldersAwaitingRelease.remove(token)
            return true
        }
        if (down) {
            if (token in shoulders) return true
            if (repeated) {
                shouldersAwaitingRelease += token
                return true
            }
            shoulders[token] = Shoulder(right, nowMs)
            onCommand(ThorCommand.Shoulder(right, ThorShoulderPhase.Press))
        } else {
            val shoulder = shoulders.remove(token) ?: return true
            if (!shoulder.holding && nowMs - shoulder.startedAtMs >= shoulderHoldDelayMs) {
                onCommand(ThorCommand.Shoulder(shoulder.right, ThorShoulderPhase.HoldStart))
            } else if (!shoulder.holding) {
                onCommand(ThorCommand.Shoulder(shoulder.right, ThorShoulderPhase.Tap))
            }
            onCommand(ThorCommand.Shoulder(shoulder.right, ThorShoulderPhase.Release))
        }
        return true
    }

    fun reset(nowMs: Long = 0) {
        buttons.clear()
        directions.clear()
        sticksAwaitingNeutral += sticks.keys
        sticks.clear()
        triggers.entries.removeAll { !it.value.digital && !it.value.axis && !it.value.awaitingAxisNeutral }
        triggers.values.forEach {
            it.armed = false
            if (it.axis) it.awaitingAxisNeutral = true
        }
        val cancelledShoulders = shoulders.values.toList()
        shouldersAwaitingRelease += shoulders.keys
        shoulders.clear()
        direction = null
        blocked = false
        nextRepeatMs = Long.MAX_VALUE
        cancelledShoulders.forEach { onCommand(ThorCommand.Shoulder(it.right, ThorShoulderPhase.Cancel)) }
    }

    private fun updateDirection(nowMs: Long) {
        val current = directions.values.lastOrNull() ?: sticks.values.lastOrNull()
        if (current == direction) return
        direction = current
        blocked = false
        nextRepeatMs = nowMs + repeatDelayMs
        if (current != null && !onCommand(ThorCommand.Move(current))) blocked = true
    }

    private class Trigger(
        var digital: Boolean = false,
        var axis: Boolean = false,
        var consumed: Boolean = true,
        var armed: Boolean = true,
        var axisExpected: Boolean = false,
        var awaitingAxisNeutral: Boolean = false,
    )

    private fun fireTrigger(trigger: Trigger, right: Boolean) {
        trigger.armed = false
        trigger.awaitingAxisNeutral = trigger.axisExpected
        trigger.consumed = onCommand(if (right) ThorCommand.NextTrack else ThorCommand.PreviousTrack)
    }

    private fun rearmTrigger(trigger: Trigger) {
        if (!trigger.digital && !trigger.axis && !trigger.awaitingAxisNeutral) trigger.armed = true
    }

    private class Shoulder(
        val right: Boolean,
        val startedAtMs: Long,
        var holding: Boolean = false,
        var nextStepMs: Long = Long.MAX_VALUE,
    )
}

/** Hysteresis rejects drift while allowing a held stick to reach its actual neutral. */
internal fun stickDirection(x: Float, y: Float, previous: ThorDirection?): ThorDirection? {
    if (!x.isFinite() || !y.isFinite()) return null
    val candidate = if (abs(x) > abs(y)) {
        if (x < 0) ThorDirection.Left else ThorDirection.Right
    } else if (y < 0) ThorDirection.Up else ThorDirection.Down
    val threshold = if (candidate == previous) .25f else .45f
    return candidate.takeIf { maxOf(abs(x), abs(y)) >= threshold }
}

package com.ljyh.mei.thor.input

import com.ljyh.mei.thor.model.ThorCommand
import com.ljyh.mei.thor.model.ThorDirection
import kotlin.math.abs

/** Event-independent controller state. Time is supplied by the Android adapter or a test. */
internal class ThorInputState(
    private val onCommand: (ThorCommand) -> Boolean,
    private val repeatDelayMs: Long = 320,
    private val repeatIntervalMs: Long = 100,
) {
    private val buttons = mutableMapOf<String, Boolean>()
    private val directions = linkedMapOf<String, ThorDirection>()
    private val sticks = linkedMapOf<Int, ThorDirection>()
    private val sticksAwaitingNeutral = mutableSetOf<Int>()
    private val triggers = mutableMapOf<Pair<Int, Boolean>, Trigger>()
    private var direction: ThorDirection? = null
    private var blocked = false
    private var nextRepeatMs = Long.MAX_VALUE

    val needsRepeat: Boolean get() = direction != null && !blocked

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

    fun trigger(deviceId: Int, right: Boolean, digital: Boolean, down: Boolean, nowMs: Long): Boolean {
        val key = deviceId to right
        val trigger = triggers.getOrPut(key) { Trigger() }
        val wasDown = trigger.digital || trigger.axis
        if (digital) trigger.digital = down else trigger.axis = down
        val isDown = trigger.digital || trigger.axis
        if (!wasDown && isDown && trigger.armed && nowMs - trigger.lastFireMs >= 80L) {
            trigger.lastFireMs = nowMs
            trigger.consumed = onCommand(if (right) ThorCommand.NextTrack else ThorCommand.PreviousTrack)
        }
        if (!isDown) trigger.armed = true
        return trigger.consumed
    }

    fun triggerAxis(deviceId: Int, right: Boolean, value: Float, nowMs: Long) {
        val latch = triggers.getOrPut(deviceId to right) { Trigger() }
        val down = if (latch.axis) value > .25f else value >= .55f
        trigger(deviceId, right, digital = false, down = down, nowMs = nowMs)
    }

    fun tick(nowMs: Long) {
        val current = direction ?: return
        if (blocked || nowMs < nextRepeatMs) return
        if (!onCommand(ThorCommand.Move(current, repeated = true))) blocked = true
        // Do not replay a backlog of moves after a stalled UI thread.
        nextRepeatMs = nowMs + repeatIntervalMs
    }

    fun reset(nowMs: Long = 0) {
        buttons.clear()
        directions.clear()
        sticksAwaitingNeutral += sticks.keys
        sticks.clear()
        triggers.entries.removeAll { !it.value.digital && !it.value.axis }
        triggers.values.forEach { it.armed = false; it.lastFireMs = nowMs }
        direction = null
        blocked = false
        nextRepeatMs = Long.MAX_VALUE
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
        var lastFireMs: Long = -1_000_000L,
        var armed: Boolean = true,
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

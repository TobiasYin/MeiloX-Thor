package com.ljyh.mei.thor.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.ljyh.mei.thor.model.ThorCommand
import com.ljyh.mei.thor.model.ThorDirection
import com.ljyh.mei.thor.model.ThorShoulderPhase
import java.io.Closeable
import kotlin.math.abs

/** The same instance receives Activity and Presentation events, with no input injection. */
class ThorInputRouter(
    private val onNonBackInput: () -> Unit = {},
    private val onCommand: (ThorCommand) -> Boolean,
) : Closeable {
    /** Preserve existing positional and trailing-lambda construction. */
    constructor(onCommand: (ThorCommand) -> Boolean) : this({}, onCommand)
    private val handler = Handler(Looper.getMainLooper())
    private val state = ThorInputState(onCommand)
    private var repeatPosted = false
    private var repeatAtMs = Long.MAX_VALUE
    private val reportedTriggerAxes = mutableSetOf<Pair<Int, Boolean>>()
    private var closed = false
    var isTextEditing: Boolean = false
        private set
    private val repeat = object : Runnable {
        override fun run() {
            repeatPosted = false
            repeatAtMs = Long.MAX_VALUE
            if (closed) return
            state.tick(SystemClock.uptimeMillis())
            scheduleRepeat()
        }
    }

    fun setTextEditing(editing: Boolean) {
        if (isTextEditing != editing) reset()
        isTextEditing = editing
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (closed || event.action !in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) return false
        val key = event.keyCode
        val command = thorCommandForKey(key) ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down && command != ThorCommand.Back) onNonBackInput()
        val now = SystemClock.uptimeMillis()
        val token = "${event.deviceId}:$key"
        val route = thorKeyRoute(key, isTextEditing)
        if (route != ThorKeyRoute.Application && (key == KeyEvent.KEYCODE_BUTTON_L2 || key == KeyEvent.KEYCODE_BUTTON_R2)) {
            state.trigger(event.deviceId, key == KeyEvent.KEYCODE_BUTTON_R2, digital = true,
                down = down, nowMs = now, axisExpected = hasTriggerAxis(event.device, event.deviceId, key == KeyEvent.KEYCODE_BUTTON_R2),
                repeated = event.repeatCount > 0, allowActions = false)
        }
        if (route != ThorKeyRoute.Application && !down) {
            // A reset cancelled the gesture when the IME took ownership. Still observe UP,
            // so its tombstone cannot swallow the first new gesture after text entry closes.
            if (command is ThorCommand.Shoulder) state.shoulder(token, command.right, false, now)
            else if (key == KeyEvent.KEYCODE_BUTTON_SELECT) state.select(token, false, now)
            else state.button(token, false, command)
        }
        when (route) {
            ThorKeyRoute.Native -> return false
            ThorKeyRoute.Consume -> return true
            ThorKeyRoute.Application -> Unit
        }
        val consumed = when {
            command is ThorCommand.Move -> state.direction(token, down, command.direction, now, event.repeatCount > 0)
            command is ThorCommand.Shoulder -> state.shoulder(token, command.right, down, now, event.repeatCount > 0)
            key == KeyEvent.KEYCODE_BUTTON_SELECT -> state.select(token, down, now, event.repeatCount > 0)
            key == KeyEvent.KEYCODE_BUTTON_L2 || key == KeyEvent.KEYCODE_BUTTON_R2 ->
                state.trigger(event.deviceId, key == KeyEvent.KEYCODE_BUTTON_R2, digital = true, down = down,
                    nowMs = now, axisExpected = hasTriggerAxis(event.device, event.deviceId, key == KeyEvent.KEYCODE_BUTTON_R2),
                    repeated = event.repeatCount > 0)
            else -> state.button(token, down, command, event.repeatCount > 0)
        }
        scheduleRepeat()
        return consumed
    }

    fun onMotionEvent(event: MotionEvent): Boolean {
        if (closed || event.actionMasked != MotionEvent.ACTION_MOVE ||
            event.source and InputDevice.SOURCE_CLASS_JOYSTICK == 0
        ) return false
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X).finite()
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y).finite()
        val x = if (abs(hatX) > .25f || abs(hatY) > .25f) hatX else event.getAxisValue(MotionEvent.AXIS_X).finite()
        val y = if (abs(hatX) > .25f || abs(hatY) > .25f) hatY else event.getAxisValue(MotionEvent.AXIS_Y).finite()
        val now = SystemClock.uptimeMillis()
        val leftTrigger = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER).finite(), event.getAxisValue(MotionEvent.AXIS_BRAKE).finite())
        val rightTrigger = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER).finite(), event.getAxisValue(MotionEvent.AXIS_GAS).finite())
        // Ignore resting motion reports and stick drift; real trigger travel starts above .05.
        if (abs(x) > .25f || abs(y) > .25f || leftTrigger > .05f || rightTrigger > .05f) onNonBackInput()
        if (!isTextEditing) state.stick(event.deviceId, x, y, now)
        // Thor's Odin Controller advertises BRAKE/GAS; other supported controllers use L/RTRIGGER.
        routeTriggerAxis(event, false, leftTrigger, now)
        routeTriggerAxis(event, true, rightTrigger, now)
        scheduleRepeat()
        return !isTextEditing
    }

    /** Reset on focus loss, backgrounding, controller removal and modal ownership changes. */
    fun reset() {
        state.reset(SystemClock.uptimeMillis())
        handler.removeCallbacks(repeat)
        repeatPosted = false
        repeatAtMs = Long.MAX_VALUE
    }

    fun dispatch(command: ThorCommand): Boolean = !closed && onCommand(command)

    override fun close() {
        reset()
        closed = true
    }

    private fun scheduleRepeat() {
        val next = state.nextTickAtMs
        if (closed || next == null) {
            handler.removeCallbacks(repeat)
            repeatPosted = false
            repeatAtMs = Long.MAX_VALUE
        } else if (!repeatPosted || next < repeatAtMs) {
            handler.removeCallbacks(repeat)
            repeatPosted = true
            repeatAtMs = next
            handler.postAtTime(repeat, next)
        }
    }

    private fun routeTriggerAxis(event: MotionEvent, right: Boolean, value: Float, nowMs: Long) {
        val key = event.deviceId to right
        // Absent axes read as zero. Do not invent an analog capability for digital-only pads.
        if (value > 0f || hasTriggerAxis(event.device, event.deviceId, right)) reportedTriggerAxes += key
        if (key in reportedTriggerAxes) state.triggerAxis(event.deviceId, right, value, nowMs, allowActions = !isTextEditing)
    }

    private fun hasTriggerAxis(device: InputDevice?, deviceId: Int, right: Boolean): Boolean {
        if (deviceId to right in reportedTriggerAxes) return true
        val axes = if (right) intArrayOf(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)
            else intArrayOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)
        return axes.any { device?.getMotionRange(it) != null }
    }

    private fun Float.finite() = if (isFinite()) coerceIn(-1f, 1f) else 0f

}

internal fun thorCommandForKey(key: Int): ThorCommand? = when (key) {
        KeyEvent.KEYCODE_DPAD_UP -> ThorCommand.Move(ThorDirection.Up)
        KeyEvent.KEYCODE_DPAD_DOWN -> ThorCommand.Move(ThorDirection.Down)
        KeyEvent.KEYCODE_DPAD_LEFT -> ThorCommand.Move(ThorDirection.Left)
        KeyEvent.KEYCODE_DPAD_RIGHT -> ThorCommand.Move(ThorDirection.Right)
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> ThorCommand.Confirm
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> ThorCommand.Back
        KeyEvent.KEYCODE_BUTTON_X -> ThorCommand.ContextMenu
        KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> ThorCommand.TogglePlayback
        KeyEvent.KEYCODE_BUTTON_L1 -> ThorCommand.Shoulder(false, ThorShoulderPhase.Press)
        KeyEvent.KEYCODE_BUTTON_R1 -> ThorCommand.Shoulder(true, ThorShoulderPhase.Press)
        KeyEvent.KEYCODE_BUTTON_START -> ThorCommand.TogglePlayer
        KeyEvent.KEYCODE_BUTTON_SELECT -> ThorCommand.ToggleQueue
        KeyEvent.KEYCODE_BUTTON_L2 -> ThorCommand.PreviousTrack
        KeyEvent.KEYCODE_BUTTON_R2 -> ThorCommand.NextTrack
        KeyEvent.KEYCODE_BUTTON_THUMBL -> ThorCommand.ShuffleAll
        KeyEvent.KEYCODE_BUTTON_THUMBR -> ThorCommand.PlayAll
        else -> null
    }

internal enum class ThorKeyRoute { Application, Native, Consume }

/** Keep Start available from a native IME; normal text navigation remains IME-owned. */
internal fun thorKeyRoute(key: Int, textEditing: Boolean): ThorKeyRoute {
    if (!textEditing) return ThorKeyRoute.Application
    return when (key) {
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> ThorKeyRoute.Application
        KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR -> ThorKeyRoute.Consume
        else -> ThorKeyRoute.Native
    }
}

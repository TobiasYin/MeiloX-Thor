package com.ljyh.mei.thor.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.ljyh.mei.thor.model.ThorCommand
import com.ljyh.mei.thor.model.ThorDirection
import java.io.Closeable
import kotlin.math.abs

/** The same instance receives Activity and Presentation events, with no input injection. */
class ThorInputRouter(private val onCommand: (ThorCommand) -> Boolean) : Closeable {
    private val handler = Handler(Looper.getMainLooper())
    private val state = ThorInputState(onCommand)
    private var repeatPosted = false
    private var closed = false
    var isTextEditing: Boolean = false
        private set
    private val repeat = object : Runnable {
        override fun run() {
            repeatPosted = false
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
        val command = commandForKey(key) ?: return false
        // Physical Enter/Dpad/system Back belong to the IME; gamepad A/B keep app form actions.
        if (isTextEditing && command !in playbackCommands && key != KeyEvent.KEYCODE_BUTTON_A && key != KeyEvent.KEYCODE_BUTTON_B) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        val now = SystemClock.uptimeMillis()
        val token = "${event.deviceId}:$key"
        val consumed = when {
            command is ThorCommand.Move -> state.direction(token, down, command.direction, now, event.repeatCount > 0)
            key == KeyEvent.KEYCODE_BUTTON_L2 || key == KeyEvent.KEYCODE_BUTTON_R2 ->
                if (down && event.repeatCount > 0) true else
                    state.trigger(event.deviceId, key == KeyEvent.KEYCODE_BUTTON_R2, digital = true, down = down, nowMs = now)
            else -> state.button(token, down, command, event.repeatCount > 0)
        }
        scheduleRepeat()
        return consumed
    }

    fun onMotionEvent(event: MotionEvent): Boolean {
        if (closed || isTextEditing || event.actionMasked != MotionEvent.ACTION_MOVE ||
            event.source and InputDevice.SOURCE_CLASS_JOYSTICK == 0
        ) return false
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X).finite()
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y).finite()
        val x = if (abs(hatX) > .25f || abs(hatY) > .25f) hatX else event.getAxisValue(MotionEvent.AXIS_X).finite()
        val y = if (abs(hatX) > .25f || abs(hatY) > .25f) hatY else event.getAxisValue(MotionEvent.AXIS_Y).finite()
        val now = SystemClock.uptimeMillis()
        state.stick(event.deviceId, x, y, now)
        // Thor's Odin Controller advertises BRAKE/GAS; other supported controllers use L/RTRIGGER.
        state.triggerAxis(event.deviceId, false, maxOf(
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER).finite(),
            event.getAxisValue(MotionEvent.AXIS_BRAKE).finite(),
        ), now)
        state.triggerAxis(event.deviceId, true, maxOf(
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER).finite(),
            event.getAxisValue(MotionEvent.AXIS_GAS).finite(),
        ), now)
        scheduleRepeat()
        return true
    }

    /** Reset on focus loss, backgrounding, controller removal and modal ownership changes. */
    fun reset() {
        state.reset(SystemClock.uptimeMillis())
        handler.removeCallbacks(repeat)
        repeatPosted = false
    }

    fun dispatch(command: ThorCommand): Boolean = !closed && onCommand(command)

    override fun close() {
        reset()
        closed = true
    }

    private fun scheduleRepeat() {
        if (!closed && state.needsRepeat && !repeatPosted) {
            repeatPosted = true
            handler.postDelayed(repeat, 16L)
        }
    }

    private fun Float.finite() = if (isFinite()) coerceIn(-1f, 1f) else 0f

    private fun commandForKey(key: Int): ThorCommand? = when (key) {
        KeyEvent.KEYCODE_DPAD_UP -> ThorCommand.Move(ThorDirection.Up)
        KeyEvent.KEYCODE_DPAD_DOWN -> ThorCommand.Move(ThorDirection.Down)
        KeyEvent.KEYCODE_DPAD_LEFT -> ThorCommand.Move(ThorDirection.Left)
        KeyEvent.KEYCODE_DPAD_RIGHT -> ThorCommand.Move(ThorDirection.Right)
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> ThorCommand.Confirm
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> ThorCommand.Back
        KeyEvent.KEYCODE_BUTTON_X -> ThorCommand.ContextMenu
        KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> ThorCommand.TogglePlayback
        KeyEvent.KEYCODE_BUTTON_L1 -> ThorCommand.TabPrevious
        KeyEvent.KEYCODE_BUTTON_R1 -> ThorCommand.TabNext
        KeyEvent.KEYCODE_BUTTON_START -> ThorCommand.TogglePlayer
        KeyEvent.KEYCODE_BUTTON_SELECT -> ThorCommand.ToggleQueue
        KeyEvent.KEYCODE_BUTTON_L2 -> ThorCommand.PreviousTrack
        KeyEvent.KEYCODE_BUTTON_R2 -> ThorCommand.NextTrack
        else -> null
    }

    private val playbackCommands = setOf(ThorCommand.TogglePlayback, ThorCommand.PreviousTrack, ThorCommand.NextTrack)
}

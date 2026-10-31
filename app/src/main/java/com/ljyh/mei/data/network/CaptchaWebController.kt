package com.ljyh.mei.data.network

import android.os.Handler
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import kotlin.math.abs

/** Routes the modal window's own input to its real WebView, without the activity's page focus. */
internal class CaptchaWebController(
    private val web: WebView,
    private val handler: Handler,
    private val onGlobalKey: (KeyEvent) -> Boolean,
    private val onCancel: () -> Unit,
    private val onGlobalDeparture: () -> Unit,
) {
    private var heldKey: Int? = null
    private var repeats = 0
    private var heldSince = 0L
    private var closed = false
    private val repeat = object : Runnable {
        override fun run() {
            val key = heldKey ?: return
            if (closed) return
            send(key, KeyEvent.ACTION_DOWN, ++repeats)
            handler.postDelayed(this, 80)
        }
    }

    fun onKey(event: KeyEvent): Boolean {
        if (closed) return false
        if (onGlobalKey(event)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onGlobalDeparture()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onCancel()
            return true
        }
        val native = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_DPAD_CENTER
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> event.keyCode
            else -> return false
        }
        web.requestFocus()
        web.dispatchKeyEvent(KeyEvent(event.downTime, event.eventTime, event.action, native,
            event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags, event.source))
        return true
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (closed || event.actionMasked != MotionEvent.ACTION_MOVE || event.source and InputDevice.SOURCE_CLASS_JOYSTICK == 0) return false
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val x = if (abs(hatX) > .25f || abs(hatY) > .25f) hatX else event.getAxisValue(MotionEvent.AXIS_X)
        val y = if (abs(hatX) > .25f || abs(hatY) > .25f) hatY else event.getAxisValue(MotionEvent.AXIS_Y)
        val next = captchaAxisKey(x, y)
        if (next == heldKey) return true
        heldKey?.let { send(it, KeyEvent.ACTION_UP) }
        handler.removeCallbacks(repeat)
        heldKey = next
        repeats = 0
        heldSince = SystemClock.uptimeMillis()
        next?.let { send(it, KeyEvent.ACTION_DOWN); handler.postDelayed(repeat, 350) }
        return true
    }

    fun close() {
        closed = true
        handler.removeCallbacks(repeat)
        heldKey?.let { send(it, KeyEvent.ACTION_UP, focus = false) }
        heldKey = null
    }

    private fun send(key: Int, action: Int, repeatCount: Int = 0, focus: Boolean = true) {
        if (focus) web.requestFocus()
        web.dispatchKeyEvent(KeyEvent(heldSince, SystemClock.uptimeMillis(), action, key,
            repeatCount, 0, -1, 0, 0, InputDevice.SOURCE_DPAD))
    }
}

internal fun captchaAxisKey(x: Float, y: Float): Int? = when {
    !x.isFinite() || !y.isFinite() || maxOf(abs(x), abs(y)) < .55f -> null
    abs(x) > abs(y) -> if (x > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
    else -> if (y > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
}

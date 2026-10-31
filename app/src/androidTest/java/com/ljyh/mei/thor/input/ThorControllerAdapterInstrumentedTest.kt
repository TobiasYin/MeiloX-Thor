package com.ljyh.mei.thor.input

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.thor.model.ThorCommand
import com.ljyh.mei.thor.model.ThorShoulderPhase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android event objects and main-looper timers; no event injection or app navigation. */
@RunWith(AndroidJUnit4::class)
class ThorControllerAdapterInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun triggerStrokesUseAttachedAxisCapabilitiesOrDigitalFallback() = onMain {
        val device = triggerDevice()
        val commands = mutableListOf<ThorCommand>()
        ThorInputRouter { commands += it; true }.use { router ->
            for (right in listOf(false, true)) {
                commands.clear()
                val keyCode = if (right) KeyEvent.KEYCODE_BUTTON_R2 else KeyEvent.KEYCODE_BUTTON_L2
                val command = if (right) ThorCommand.NextTrack else ThorCommand.PreviousTrack
                val axisId = device?.let { triggerAxis(it, right) }
                if (device == null || axisId == null) {
                    // A CI/emulator keyboard must not acquire an invented analog capability.
                    key(router, keyCode, true)
                    key(router, keyCode, true, repeat = 1)
                    key(router, keyCode, false)
                    key(router, keyCode, false)
                    assertEquals(listOf(command), commands)
                    key(router, keyCode, true)
                    key(router, keyCode, false)
                    assertEquals(listOf(command, command), commands)
                    continue
                }

                // Axis-first stroke, including duplicate digital input and partial rebound.
                axis(router, device.id, axisId, .7f)
                key(router, keyCode, true, device.id)
                axis(router, device.id, axisId, 1f)
                key(router, keyCode, false, device.id)
                axis(router, device.id, axisId, .2f)
                axis(router, device.id, axisId, .8f)
                assertEquals(listOf(command), commands)
                axis(router, device.id, axisId, 0f)

                // Digital-first stroke; axis neutral alone cannot rearm while DOWN is held.
                key(router, keyCode, true, device.id)
                axis(router, device.id, axisId, .8f)
                axis(router, device.id, axisId, 0f)
                axis(router, device.id, axisId, .7f)
                key(router, keyCode, false, device.id)
                assertEquals(listOf(command, command), commands)
                axis(router, device.id, axisId, 0f)

                // Only a fully neutral stroke permits the next physical activation.
                axis(router, device.id, axisId, .8f)
                key(router, keyCode, true, device.id)
                key(router, keyCode, false, device.id)
                axis(router, device.id, axisId, 0f)
                assertEquals(listOf(command, command, command), commands)
            }
        }
    }

    @Test
    fun bothShoulderTapsEmitOneTapAndOneRelease() = onMain {
        val commands = mutableListOf<ThorCommand>()
        ThorInputRouter { commands += it; true }.use { router ->
            for (right in listOf(false, true)) {
                commands.clear()
                val code = if (right) KeyEvent.KEYCODE_BUTTON_R1 else KeyEvent.KEYCODE_BUTTON_L1
                key(router, code, true)
                key(router, code, true, repeat = 1)
                key(router, code, false)
                key(router, code, false)
                assertEquals(listOf(
                    ThorCommand.Shoulder(right, ThorShoulderPhase.Press),
                    ThorCommand.Shoulder(right, ThorShoulderPhase.Tap),
                    ThorCommand.Shoulder(right, ThorShoulderPhase.Release),
                ), commands)
            }
        }
    }

    @Test
    fun bothShoulderHoldsUseTheMainLooperAndReleaseWithoutTap() {
        for (right in listOf(false, true)) {
            val commands = mutableListOf<ThorCommand>()
            val step = CountDownLatch(1)
            val code = if (right) KeyEvent.KEYCODE_BUTTON_R1 else KeyEvent.KEYCODE_BUTTON_L1
            lateinit var router: ThorInputRouter
            onMain {
                router = ThorInputRouter { command ->
                    commands += command
                    if (command == ThorCommand.Shoulder(right, ThorShoulderPhase.HoldStep)) step.countDown()
                    true
                }
                key(router, code, true)
            }
            try {
                // Wait off the main thread, so the production Handler must produce the hold.
                assertTrue("A shoulder hold must produce a timed step", step.await(3, TimeUnit.SECONDS))
                var countAfterRelease = 0
                onMain {
                    key(router, code, false)
                    val phases = commands.filterIsInstance<ThorCommand.Shoulder>()
                    assertTrue(phases.all { it.right == right })
                    assertEquals(1, phases.count { it.phase == ThorShoulderPhase.Press })
                    assertEquals(1, phases.count { it.phase == ThorShoulderPhase.HoldStart })
                    assertTrue(phases.any { it.phase == ThorShoulderPhase.HoldStep })
                    assertEquals(0, phases.count { it.phase == ThorShoulderPhase.Tap })
                    assertEquals(ThorShoulderPhase.Release, phases.last().phase)
                    assertEquals(1, phases.count { it.phase == ThorShoulderPhase.Release })
                    countAfterRelease = commands.size
                }
                SystemClock.sleep(120)
                onMain { assertEquals(countAfterRelease, commands.size) }
            } finally { onMain { router.close() } }
        }
    }

    @Test
    fun startStillRoutesExactlyOnceDuringTextEditing() = onMain {
        val commands = mutableListOf<ThorCommand>()
        ThorInputRouter { commands += it; true }.use { router ->
            router.setTextEditing(true)
            key(router, KeyEvent.KEYCODE_BUTTON_START, true)
            key(router, KeyEvent.KEYCODE_BUTTON_START, true, repeat = 1)
            key(router, KeyEvent.KEYCODE_BUTTON_START, false)
            assertEquals(listOf(ThorCommand.TogglePlayer), commands)
        }
    }

    @Test
    fun selectTapDefersQueueUntilUpAndLongHoldOpensOnlyPageActions() {
        val commands = mutableListOf<ThorCommand>()
        val held = CountDownLatch(1)
        lateinit var router: ThorInputRouter
        onMain {
            router = ThorInputRouter { command ->
                commands += command
                if (command == ThorCommand.PageActions) held.countDown()
                true
            }
            key(router, KeyEvent.KEYCODE_BUTTON_SELECT, true)
            assertTrue(commands.isEmpty())
            key(router, KeyEvent.KEYCODE_BUTTON_SELECT, false)
            assertEquals(listOf(ThorCommand.ToggleQueue), commands)
            commands.clear()
            key(router, KeyEvent.KEYCODE_BUTTON_SELECT, true)
        }
        try {
            assertTrue("A Select hold must reach the real Android Handler", held.await(3, TimeUnit.SECONDS))
            onMain {
                key(router, KeyEvent.KEYCODE_BUTTON_SELECT, true, repeat = 1)
                key(router, KeyEvent.KEYCODE_BUTTON_SELECT, false)
                assertEquals(listOf(ThorCommand.PageActions), commands)
            }
        } finally { onMain { router.close() } }
    }

    @Test
    fun editingResetAndNativeSelectUpDoNotConsumeTheNextFreshTap() = onMain {
        val commands = mutableListOf<ThorCommand>()
        ThorInputRouter { commands += it; true }.use { router ->
            key(router, KeyEvent.KEYCODE_BUTTON_SELECT, true)
            router.setTextEditing(true)
            assertTrue(commands.isEmpty())
            assertTrue(!key(router, KeyEvent.KEYCODE_BUTTON_SELECT, false))
            router.setTextEditing(false)
            key(router, KeyEvent.KEYCODE_BUTTON_SELECT, true)
            key(router, KeyEvent.KEYCODE_BUTTON_SELECT, false)
            assertEquals(listOf(ThorCommand.ToggleQueue), commands)
        }
    }

    @Test
    fun nonBackInputObserverIgnoresBackNeutralAndDriftButSeesRealActivity() = onMain {
        var interactions = 0
        ThorInputRouter(onNonBackInput = { interactions++ }, onCommand = { true }).use { router ->
            key(router, KeyEvent.KEYCODE_BUTTON_B, true)
            key(router, KeyEvent.KEYCODE_BUTTON_B, true, repeat = 1)
            key(router, KeyEvent.KEYCODE_BUTTON_B, false)
            assertEquals(0, interactions)
            key(router, KeyEvent.KEYCODE_BUTTON_SELECT, true)
            assertEquals("Select DOWN must clear an exit arm before its deferred command", 1, interactions)
            key(router, KeyEvent.KEYCODE_BUTTON_SELECT, false)
            axis(router, KeyCharacterMap.VIRTUAL_KEYBOARD, MotionEvent.AXIS_X, 0f)
            axis(router, KeyCharacterMap.VIRTUAL_KEYBOARD, MotionEvent.AXIS_X, .1f)
            assertEquals(1, interactions)
            axis(router, KeyCharacterMap.VIRTUAL_KEYBOARD, MotionEvent.AXIS_X, .8f)
            assertEquals(2, interactions)
            axis(router, KeyCharacterMap.VIRTUAL_KEYBOARD, MotionEvent.AXIS_X, 0f)
            axis(router, KeyCharacterMap.VIRTUAL_KEYBOARD, MotionEvent.AXIS_BRAKE, .2f)
            assertEquals(3, interactions)
            axis(router, KeyCharacterMap.VIRTUAL_KEYBOARD, MotionEvent.AXIS_BRAKE, 0f)
            assertEquals(3, interactions)
        }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun key(router: ThorInputRouter, code: Int, down: Boolean,
                    deviceId: Int = KeyCharacterMap.VIRTUAL_KEYBOARD, repeat: Int = 0): Boolean {
        val now = SystemClock.uptimeMillis()
        return router.onKeyEvent(KeyEvent(now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            code, repeat, 0, deviceId, 0, 0, InputDevice.SOURCE_GAMEPAD))
    }

    private fun axis(router: ThorInputRouter, deviceId: Int, axis: Int, value: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN }),
            arrayOf(MotionEvent.PointerCoords().apply { setAxisValue(axis, value) }),
            0, 0, 1f, 1f, deviceId, 0, InputDevice.SOURCE_JOYSTICK, 0)
        try { assertTrue(router.onMotionEvent(event)) } finally { event.recycle() }
    }

    private fun triggerDevice(): InputDevice? = InputDevice.getDeviceIds().map { InputDevice.getDevice(it) }.filterNotNull()
        .filter { it.sources and InputDevice.SOURCE_CLASS_JOYSTICK != 0 }
        .sortedByDescending { it.name.contains("odin", ignoreCase = true) }
        .firstOrNull { triggerAxis(it, false) != null && triggerAxis(it, true) != null }

    private fun triggerAxis(device: InputDevice, right: Boolean): Int? =
        (if (right) listOf(MotionEvent.AXIS_GAS, MotionEvent.AXIS_RTRIGGER)
        else listOf(MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_LTRIGGER))
            .firstOrNull { axis -> device.getMotionRange(axis)?.let { it.min <= 0f && it.max >= 1f } == true }
}

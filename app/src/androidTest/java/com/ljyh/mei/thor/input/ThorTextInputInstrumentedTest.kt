package com.ljyh.mei.thor.input

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.thor.model.ThorCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThorTextInputInstrumentedTest {
    @Test
    fun editingConsumesControllerPlaybackAndKeepsHeadsetControls() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val commands = mutableListOf<ThorCommand>()
            ThorInputRouter { commands += it; true }.use { router ->
                router.setTextEditing(true)
                for (key in listOf(KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2)) {
                    assertTrue(router.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)))
                    assertTrue(router.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key)))
                }
                assertTrue(commands.isEmpty())
                assertTrue(router.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
                router.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                assertEquals(listOf(ThorCommand.TogglePlayback), commands)
                router.setTextEditing(false)
                router.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_Y))
                router.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_Y))
                assertEquals(listOf(ThorCommand.TogglePlayback, ThorCommand.TogglePlayback), commands)
            }
        }
    }

    @Test
    fun editingRetainsGamepadFormActionsAndLeavesPhysicalNavigationToIme() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val commands = mutableListOf<ThorCommand>()
            ThorInputRouter { commands += it; true }.use { router ->
                router.setTextEditing(true)
                for (key in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK)) {
                    assertFalse(router.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)))
                }
                for (key in listOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B)) {
                    assertTrue(router.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)))
                    router.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                }
                assertEquals(listOf(ThorCommand.Confirm, ThorCommand.Back), commands)
            }
        }
    }
}

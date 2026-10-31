package com.ljyh.mei.thor.input

import android.view.KeyEvent
import com.ljyh.mei.thor.model.ThorCommand
import com.ljyh.mei.thor.model.ThorShoulderPhase
import org.junit.Assert.*
import org.junit.Test

/** Pure adapter policy: no Context, Looper, input injection or Android runtime required. */
class ThorInputRouterPolicyTest {
    @Test fun startStillReachesTheAppFromANativeTextField() {
        assertEquals(ThorCommand.TogglePlayer, thorCommandForKey(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(ThorKeyRoute.Application, thorKeyRoute(KeyEvent.KEYCODE_BUTTON_START, textEditing = true))
        assertEquals(ThorKeyRoute.Application, thorKeyRoute(KeyEvent.KEYCODE_BUTTON_A, textEditing = true))
        assertEquals(ThorKeyRoute.Application, thorKeyRoute(KeyEvent.KEYCODE_BUTTON_B, textEditing = true))
    }

    @Test fun shouldersArePhasedCommandsAndThumbClicksAreExplicitBatchActions() {
        assertEquals(ThorCommand.Shoulder(false, ThorShoulderPhase.Press), thorCommandForKey(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(ThorCommand.Shoulder(true, ThorShoulderPhase.Press), thorCommandForKey(KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals(ThorCommand.ShuffleAll, thorCommandForKey(KeyEvent.KEYCODE_BUTTON_THUMBL))
        assertEquals(ThorCommand.PlayAll, thorCommandForKey(KeyEvent.KEYCODE_BUTTON_THUMBR))
    }

    @Test fun nativeTextNavigationAndPlaybackSuppressionKeepTheirSeparateOwners() {
        for (key in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_L1)) {
            assertEquals(ThorKeyRoute.Native, thorKeyRoute(key, textEditing = true))
        }
        for (key in listOf(KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR)) {
            assertEquals(ThorKeyRoute.Consume, thorKeyRoute(key, textEditing = true))
            assertEquals(ThorKeyRoute.Application, thorKeyRoute(key, textEditing = false))
        }
        assertEquals(ThorKeyRoute.Application, thorKeyRoute(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, textEditing = true))
    }

    @Test fun faceButtonsKeepTheirExistingCommandContract() {
        assertEquals(ThorCommand.Confirm, thorCommandForKey(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(ThorCommand.Back, thorCommandForKey(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(ThorCommand.ContextMenu, thorCommandForKey(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(ThorCommand.TogglePlayback, thorCommandForKey(KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals(ThorCommand.PreviousTrack, thorCommandForKey(KeyEvent.KEYCODE_BUTTON_L2))
        assertEquals(ThorCommand.NextTrack, thorCommandForKey(KeyEvent.KEYCODE_BUTTON_R2))
    }

    @Test fun selectKeepsItsQueueIdentityAndNativeTextPolicyWhileStateDistinguishesHold() {
        assertEquals(ThorCommand.ToggleQueue, thorCommandForKey(KeyEvent.KEYCODE_BUTTON_SELECT))
        assertEquals(ThorKeyRoute.Application, thorKeyRoute(KeyEvent.KEYCODE_BUTTON_SELECT, textEditing = false))
        assertEquals(ThorKeyRoute.Native, thorKeyRoute(KeyEvent.KEYCODE_BUTTON_SELECT, textEditing = true))
    }
}

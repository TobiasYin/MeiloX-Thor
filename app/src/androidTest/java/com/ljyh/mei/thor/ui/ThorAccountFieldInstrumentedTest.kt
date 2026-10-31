package com.ljyh.mei.thor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.thor.model.ThorDirection
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.thor.theme.ThorTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real production editor without a login ViewModel, account or network. */
@RunWith(AndroidJUnit4::class)
class ThorAccountFieldInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var registry: ThorFocusRegistry
    private lateinit var session: ThorAccountEditingSession
    private var editing = false
    private var nextClicks = 0
    private var fieldValue = ""
    private val fieldVisible = mutableStateOf(true)

    @Test
    fun controllerFocusDoesNotEditAndExplicitCancelRestoresNormalNavigation() {
        showField()
        compose.runOnIdle { assertTrue(registry.request("phone")) }
        compose.onNodeWithTag("account-field:phone").assertIsFocused()
        compose.onNodeWithTag("account-input:phone").assertIsNotFocused()
        compose.runOnIdle { assertFalse(editing); assertFalse(session.hasActiveEditor()) }

        compose.runOnIdle { assertTrue(registry.confirm()) }
        compose.onNodeWithTag("account-input:phone").assertIsFocused()
        compose.runOnIdle { assertTrue(editing); assertTrue(session.hasActiveEditor()) }

        // This is the same real editor-session close used by the production B handler.
        compose.runOnIdle { session.finish() }
        compose.onNodeWithTag("account-field:phone").assertIsFocused()
        compose.onNodeWithTag("account-input:phone").assertIsNotFocused()
        compose.runOnIdle {
            assertFalse(editing)
            assertFalse(session.hasActiveEditor())
            assertTrue(registry.move(ThorDirection.Down, repeated = false))
        }
        compose.onNodeWithTag("account-next").assertIsFocused()
        compose.runOnIdle {
            assertEquals(0, nextClicks)
            assertTrue(registry.confirm())
            assertEquals(1, nextClicks)
            assertTrue(registry.request("phone"))
            assertTrue(registry.confirm())
            // A fast B can arrive before the editor's first focus frame is delivered.
            session.finish()
        }
        compose.onNodeWithTag("account-field:phone").assertIsFocused()
        compose.onNodeWithTag("account-input:phone").assertIsNotFocused()
        compose.runOnIdle { assertFalse(editing); assertFalse(session.hasActiveEditor()) }
    }

    @Test
    fun imeDoneDoesNotSubmitAndTouchEditorDisposalReleasesItsSession() {
        showField()
        compose.runOnIdle { assertTrue(registry.request("phone")); assertTrue(registry.confirm()) }
        compose.onNodeWithTag("account-input:phone").assertIsFocused().performTextInput("QA fixture")
        compose.onNodeWithTag("account-input:phone").performImeAction()
        compose.onNodeWithTag("account-field:phone").assertIsFocused()
        compose.onNodeWithTag("account-input:phone").assertIsNotFocused()
        compose.runOnIdle {
            assertEquals("QA fixture", fieldValue)
            assertFalse(editing)
            assertFalse(session.hasActiveEditor())
            assertEquals("IME Done must never invoke a login/submit action", 0, nextClicks)
        }
        compose.onNodeWithTag("account-field:phone").performTouchInput { click(center) }
        compose.onNodeWithTag("account-input:phone").assertIsFocused()
        compose.runOnIdle {
            assertTrue(editing)
            assertTrue(session.hasActiveEditor())
            fieldVisible.value = false
        }
        compose.onNodeWithTag("account-field:phone").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse("Removing a live field must release the parent editing flag", editing)
            assertFalse(session.hasActiveEditor())
            assertEquals(0, nextClicks)
        }
    }

    private fun showField() {
        compose.setContent {
            ThorTheme(ThorPreferences(reduceMotion = true)) {
                val focus = remember { ThorFocusRegistry() }
                val editor = remember { ThorAccountEditingSession() }
                val owner = remember { Any() }
                var value by remember { mutableStateOf("") }
                SideEffect {
                    registry = focus
                    session = editor
                    fieldValue = value
                    focus.installScope(owner, ThorFocusScope("field-fixture", listOf("phone", "next"), ThorFocusLayout.Vertical))
                }
                DisposableEffect(owner) { onDispose { focus.removeScope(owner) } }
                Column(Modifier.width(320.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (fieldVisible.value) ThorAccountField(focus, "phone", "Synthetic input", value, { value = it },
                        onEditingChange = { editing = it }, editingSession = editor)
                    ThorButton(focus, "next", "Synthetic next action", Modifier.testTag("account-next"), onClick = { nextClicks++ })
                }
            }
        }
        compose.waitForIdle()
    }
}

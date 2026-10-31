package com.ljyh.mei.thor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.thor.model.ThorCommand
import com.ljyh.mei.thor.model.ThorDirection
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.thor.theme.ThorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real popover, scroll and touch; the parent fixture supplies the shell's command ownership. */
@RunWith(AndroidJUnit4::class)
class ThorPageActionMenuInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private class Fixture(val actions: List<ThorPageAction>, initiallyOpen: Boolean = true) {
        val menuRegistry = ThorFocusRegistry()
        val contentRegistry = ThorFocusRegistry()
        val contentOwner = Any()
        val open = mutableStateOf(initiallyOpen)
        val selected = mutableStateOf(actions.firstOrNull { it.enabled }?.id)
        val activated = mutableListOf<String>()
        var rememberedContent = "content:first"
        var contentActivations = 0

        fun openMenu() {
            rememberedContent = contentRegistry.selectedKey ?: rememberedContent
            selected.value = actions.firstOrNull { it.enabled }?.id
            open.value = true
        }

        fun close() { open.value = false }
        fun activate(action: ThorPageAction) {
            if (!action.enabled) return
            close()
            activated += action.id
            action.onClick()
        }

        // The input adapter has separate tests. These are the same renderer registry operations
        // used by the shell after it has interpreted controller A/B and directional commands.
        fun command(command: ThorCommand): Boolean = when (command) {
            is ThorCommand.Move -> (if (open.value) menuRegistry else contentRegistry)
                .move(command.direction, command.repeated)
            ThorCommand.Confirm -> (if (open.value) menuRegistry else contentRegistry).confirm()
            ThorCommand.Back -> if (open.value) { close(); true } else false
            else -> false
        }
    }

    private fun action(index: Int, calls: MutableList<String>, enabled: Boolean = true) =
        ThorPageAction("action-$index", "操作 $index", enabled) { calls += "action-$index" }

    private fun show(fixture: Fixture) {
        compose.setContent {
            ThorTheme(ThorPreferences(reduceMotion = true)) {
                SideEffect {
                    fixture.contentRegistry.installScope(fixture.contentOwner,
                        ThorFocusScope("content", listOf("content:first", "content:second")))
                    fixture.contentRegistry.blocked = fixture.open.value
                }
                DisposableEffect(fixture) {
                    onDispose { fixture.contentRegistry.removeScope(fixture.contentOwner) }
                }
                LaunchedEffect(fixture.open.value) {
                    if (!fixture.open.value) {
                        withFrameNanos { }
                        fixture.contentRegistry.request(fixture.rememberedContent)
                    }
                }
                Box(Modifier.width(360.dp).height(320.dp)) {
                    Column {
                        ThorButton(fixture.contentRegistry, "content:first", "内容一",
                            modifier = Modifier.testTag("content-first"), onClick = { fixture.contentActivations++ })
                        ThorButton(fixture.contentRegistry, "content:second", "内容二",
                            modifier = Modifier.testTag("content-second"), onClick = { fixture.contentActivations++ })
                    }
                    if (fixture.open.value) ThorPageActionMenu(
                        actions = fixture.actions,
                        selectedId = fixture.selected.value,
                        registry = fixture.menuRegistry,
                        onSelect = { fixture.selected.value = it },
                        onActivate = fixture::activate,
                        onClose = fixture::close,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun disabledItemsAreSkippedAndNeitherBoundaryNorHorizontalInputLeavesTheMenu() {
        val calls = mutableListOf<String>()
        val fixture = Fixture(listOf(action(0, calls, false), action(1, calls), action(2, calls, false), action(3, calls)))
        show(fixture)
        compose.onNodeWithText("操作 0").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle {
            assertTrue(fixture.open.value)
            assertEquals("action-1", fixture.selected.value)
            assertEquals("page-action:action-1", fixture.menuRegistry.selectedKey)
            assertFalse(fixture.command(ThorCommand.Move(ThorDirection.Up)))
            assertFalse(fixture.command(ThorCommand.Move(ThorDirection.Left)))
            assertFalse(fixture.command(ThorCommand.Move(ThorDirection.Right)))
            assertTrue(fixture.command(ThorCommand.Move(ThorDirection.Down)))
            assertEquals("action-3", fixture.selected.value)
            assertFalse(fixture.command(ThorCommand.Move(ThorDirection.Down)))
            assertEquals("page-action:action-3", fixture.menuRegistry.selectedKey)
            assertTrue(calls.isEmpty())
        }
    }

    @Test
    fun rapidMovementScrollsBeyondTheViewportAndAConfirmsTheLatestUncomposedAction() {
        val calls = mutableListOf<String>()
        val fixture = Fixture(List(32) { action(it, calls) })
        show(fixture)
        compose.onNodeWithText("操作 18").assertDoesNotExist()
        compose.runOnIdle {
            // Several controller messages can arrive before Compose renders another frame.
            repeat(18) { assertTrue(fixture.command(ThorCommand.Move(ThorDirection.Down))) }
            assertEquals("action-18", fixture.selected.value)
        }
        compose.waitForIdle()
        compose.onNodeWithText("操作 18").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(fixture.menuRegistry.contains("page-action:action-31"))
            repeat(13) { assertTrue(fixture.command(ThorCommand.Move(ThorDirection.Down))) }
            assertEquals("action-31", fixture.selected.value)
            assertTrue(fixture.command(ThorCommand.Confirm))
            assertEquals(listOf("action-31"), fixture.activated)
            assertEquals(listOf("action-31"), calls)
            assertFalse(fixture.open.value)
        }
    }

    @Test
    fun closeAndActionRowsRemainTouchableWithoutMakingTheCloseButtonAControllerTarget() {
        val calls = mutableListOf<String>()
        val fixture = Fixture(listOf(action(0, calls), action(1, calls)))
        show(fixture)
        compose.runOnIdle { assertFalse(fixture.menuRegistry.isAllowed("page-actions:close")) }
        compose.onNodeWithContentDescription("关闭页面操作").assertIsDisplayed().performTouchInput { click() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(fixture.open.value)
            assertTrue(calls.isEmpty())
            fixture.openMenu()
        }
        compose.onNodeWithText("操作 1").assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle {
            assertFalse(fixture.open.value)
            assertEquals(listOf("action-1"), fixture.activated)
            assertEquals(listOf("action-1"), calls)
        }
    }

    @Test
    fun BClosesWithoutActivationAndRestoresThePreviousContentScopeAndFocusedRow() {
        val calls = mutableListOf<String>()
        val fixture = Fixture(listOf(action(0, calls), action(1, calls)), initiallyOpen = false)
        show(fixture)
        compose.runOnIdle {
            assertTrue(fixture.contentRegistry.request("content:second"))
            fixture.openMenu()
        }
        compose.onNodeWithText("页面操作").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(fixture.contentRegistry.blocked)
            assertFalse(fixture.contentRegistry.move(ThorDirection.Up, false))
            assertEquals("content:second", fixture.contentRegistry.selectedKey)
            assertTrue(fixture.command(ThorCommand.Move(ThorDirection.Down)))
            assertTrue(fixture.command(ThorCommand.Back))
        }
        compose.waitForIdle()
        compose.onNodeWithText("页面操作").assertDoesNotExist()
        compose.onNodeWithTag("content-second").assertIsFocused()
        compose.runOnIdle {
            assertFalse(fixture.contentRegistry.blocked)
            assertEquals("content:second", fixture.contentRegistry.selectedKey)
            assertTrue(fixture.contentRegistry.isAllowed("content:second"))
            assertFalse(fixture.menuRegistry.isAllowed("page-action:action-1"))
            assertTrue(fixture.command(ThorCommand.Confirm))
            assertEquals(1, fixture.contentActivations)
            assertTrue(calls.isEmpty())
        }
    }
}

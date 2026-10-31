package com.ljyh.mei.thor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.thor.model.ThorDirection
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.thor.theme.ThorTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real focus targets + the production virtual-list binder, without a VM/player/account. */
@RunWith(AndroidJUnit4::class)
class ThorFocusScopeInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val registry = ThorFocusRegistry()
    private val memory = Memory()
    private val activated = mutableListOf<String>()
    private var chromeClicks = 0
    private lateinit var list: LazyListState

    @Test fun listBoundariesCannotReachChromeAndTouchingChromeDoesNotRetargetConfirm() {
        showLists(count = mutableStateOf(3))
        compose.onNodeWithText("Row A 0").assertIsFocused()
        compose.runOnIdle {
            assertFalse(registry.move(ThorDirection.Up, false))
            assertFalse(registry.move(ThorDirection.Left, false))
            assertFalse(registry.move(ThorDirection.Right, true))
            assertFalse(registry.request("header"))
            assertTrue(registry.move(ThorDirection.Down, false))
            assertTrue(registry.move(ThorDirection.Down, false))
            assertFalse(registry.move(ThorDirection.Down, false))
            assertFalse(registry.move(ThorDirection.Down, true))
            assertFalse(registry.move(ThorDirection.Right, false))
        }
        compose.onNodeWithText("Row A 2").assertIsFocused()
        for (label in listOf("Header", "Footer", "Now playing")) {
            compose.onNodeWithText(label).performTouchInput { click(center) }
            compose.onNodeWithText(label).assertIsNotFocused()
        }
        compose.runOnIdle {
            assertEquals(3, chromeClicks)
            assertEquals("A:2", registry.selectedKey)
            assertTrue(registry.confirm())
            assertEquals(listOf("A:2"), activated)
        }
    }

    @Test fun rapidOffscreenMovementConfirmsThePreparedRowAndThenObtainsItsNativeFocus() {
        showLists(count = mutableStateOf(30))
        compose.runOnIdle {
            repeat(14) { assertTrue(registry.move(ThorDirection.Down, true)) }
            assertEquals("A:14", registry.selectedKey)
            assertTrue("Confirm must act before lazy realization catches up", registry.confirm())
            assertEquals(listOf("A:14"), activated)
        }
        compose.onNodeWithText("Row A 14").assertIsFocused()
        compose.runOnIdle { assertTrue(list.firstVisibleItemIndex > 0) }
    }

    @Test fun anInitiallyEmptyAsyncListWaitsForItsFirstRowWithoutFocusingChrome() {
        val count = mutableStateOf(0)
        showLists(count = count)
        compose.runOnIdle {
            assertNull(registry.selectedKey)
            assertFalse(registry.requestFirst())
            assertFalse(registry.move(ThorDirection.Down, false))
            assertFalse(registry.request("footer"))
            count.value = 5
        }
        compose.onNodeWithText("Row A 0").assertIsFocused()
        compose.runOnIdle { assertEquals("A:0", memory.focus["A"]) }
    }

    @Test fun aFreshEntryResetsEvenAStillVisiblePartiallyScrolledFirstRow() {
        showLists(count = mutableStateOf(20), initialOffset = 23)
        compose.onNodeWithText("Row A 0").assertIsFocused()
        compose.runOnIdle {
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, list.firstVisibleItemScrollOffset)
        }
    }

    @Test fun returningToAListRestoresItsOwnKeyAndScrollInsteadOfTheOtherScene() {
        val scene = mutableStateOf("A")
        showLists(scene = scene, count = mutableStateOf(30))
        compose.runOnIdle { repeat(12) { assertTrue(registry.move(ThorDirection.Down, false)) } }
        compose.onNodeWithText("Row A 12").assertIsFocused()
        val oldPosition = compose.runOnIdle { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
        compose.runOnIdle { scene.value = "B" }
        compose.onNodeWithText("Row B 0").assertIsFocused()
        compose.runOnIdle {
            assertEquals("A:12", memory.focus["A"])
            assertEquals("A:${oldPosition.first}" to oldPosition.second, memory.scroll["A"])
            scene.value = "A"
        }
        compose.onNodeWithText("Row A 12").assertIsFocused()
        compose.runOnIdle {
            assertEquals(oldPosition.first, list.firstVisibleItemIndex)
            assertEquals(oldPosition.second, list.firstVisibleItemScrollOffset)
        }
    }

    @Test fun aRemovedSavedKeyReturnsToTheFirstRowAndNeverToARegisteredHeader() {
        memory.focus["A"] = "A:removed"
        showLists(count = mutableStateOf(3))
        compose.runOnIdle {
            assertTrue(registry.isAllowed("A:0"))
            assertEquals("The initial restore must not be dropped before scope installation", "A:0", registry.selectedKey)
            assertEquals("A:0", memory.focus["A"])
        }
        compose.onNodeWithText("Row A 0").assertIsFocused()
        compose.onNodeWithText("Header").assertIsNotFocused()
    }

    @Test fun aValidSavedFocusWithoutScrollWaitsForScopeBeforeItsNativeRequest() {
        // This branch performs no initial restoration scroll, so scrollToItem cannot
        // incidentally defer preparation until SideEffect has installed the scope.
        memory.focus["A"] = "A:1"
        showLists(count = mutableStateOf(3))
        compose.runOnIdle {
            assertTrue(registry.isAllowed("A:1"))
            assertEquals("A:1", registry.selectedKey)
            assertEquals("A:1", memory.focus["A"])
        }
        compose.onNodeWithText("Row A 1").assertIsFocused()
        compose.onNodeWithText("Header").assertIsNotFocused()
    }

    @Test fun invalidSavedFocusAndScrollResetAPartiallyVisibleFirstRow() {
        memory.focus["A"] = "A:removed"
        memory.scroll["A"] = "A:removed" to 23
        showLists(count = mutableStateOf(20), initialOffset = 23)
        compose.runOnIdle {
            assertEquals("A:0", registry.selectedKey)
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, list.firstVisibleItemScrollOffset)
        }
        compose.onNodeWithText("Row A 0").assertIsFocused()
        compose.onNodeWithText("Header").assertIsNotFocused()
    }

    @Test fun explicitlyAllowedSpatialControlsUseNativeFocusAndSkipDisabledAndChromeTargets() {
        val owner = Any()
        registry.installScope(owner, ThorFocusScope("settings", listOf("first", "disabled", "last"), ThorFocusLayout.Spatial))
        compose.setContent {
            ThorTheme(ThorPreferences(reduceMotion = true)) {
                Column(Modifier.width(320.dp)) {
                    ThorButton(registry, "header", "Header", onClick = { chromeClicks++ })
                    Row {
                        ThorButton(registry, "first", "First", onClick = {})
                        ThorButton(registry, "disabled", "Disabled", enabled = false, onClick = {})
                        ThorButton(registry, "last", "Last", onClick = {})
                    }
                    ThorButton(registry, "footer", "Footer", onClick = { chromeClicks++ })
                }
            }
        }
        compose.runOnIdle { assertTrue(registry.requestFirst()) }
        compose.onNodeWithText("First").assertIsFocused()
        compose.runOnIdle { assertTrue(registry.move(ThorDirection.Right, false)) }
        compose.onNodeWithText("Last").assertIsFocused()
        compose.runOnIdle {
            assertFalse(registry.move(ThorDirection.Right, false))
            assertFalse(registry.move(ThorDirection.Down, false))
            assertFalse(registry.request("header"))
        }
        compose.onNodeWithText("Header").assertIsNotFocused()
        compose.onNodeWithText("Footer").assertIsNotFocused()
    }

    private fun showLists(
        scene: MutableState<String> = mutableStateOf("A"),
        count: MutableState<Int>,
        initialOffset: Int = 0,
    ) {
        compose.setContent {
            ThorTheme(ThorPreferences(reduceMotion = true)) {
                Column(Modifier.width(320.dp).height(360.dp).padding(8.dp)) {
                    ThorButton(registry, "header", "Header", Modifier.height(48.dp), onClick = { chromeClicks++ })
                    key(scene.value) { ListFixture(scene.value, count.value, initialOffset) }
                    ThorButton(registry, "footer", "Footer", Modifier.height(48.dp), onClick = { chromeClicks++ })
                    ThorButton(registry, "mini", "Now playing", Modifier.height(48.dp), onClick = { chromeClicks++ })
                }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun ListFixture(scene: String, count: Int, initialOffset: Int) {
        val state = rememberLazyListState(initialFirstVisibleItemScrollOffset = initialOffset)
        val keys = remember(scene, count) { List(count) { "$scene:$it" } }
        val items = remember(scene, count) { List(count) { index ->
            ThorItem(ThorItemKey.Playlist("$scene:$index", true), "Row $scene $index")
        } }
        val binding = BindThorListFocus(registry, scene, keys, items, state, memory) { activated += keys[it] }
        SideEffect { list = state }
        LazyColumn(Modifier.height(180.dp).width(304.dp), state = state) {
            itemsIndexed(items, key = { index, _ -> keys[index] }) { index, item ->
                ThorMusicRow(registry, keys[index], item,
                    onClick = { binding.activate(index) }, onMenu = {}, onFocused = { binding.onFocused(index) })
            }
        }
    }

    private class Memory : ThorListFocusMemory {
        val focus = mutableMapOf<String, String>()
        val scroll = mutableMapOf<String, Pair<String, Int>>()
        override fun readFocus(scene: String) = focus[scene]
        override fun readScroll(scene: String) = scroll[scene]
        override fun saveFocus(scene: String, key: String) { focus[scene] = key }
        override fun saveScroll(scene: String, key: String, offset: Int) { scroll[scene] = key to offset }
        override fun preview(item: ThorItem?) = Unit
    }
}

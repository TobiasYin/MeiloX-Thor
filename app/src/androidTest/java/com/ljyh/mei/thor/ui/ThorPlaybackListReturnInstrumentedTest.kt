package com.ljyh.mei.thor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.thor.model.*
import com.ljyh.mei.thor.state.*
import com.ljyh.mei.thor.theme.ThorTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production list binder, stable-key memory, player return history and actual saveable scene owner. */
@RunWith(AndroidJUnit4::class)
class ThorPlaybackListReturnInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val registry = ThorFocusRegistry()
    private val memory = Memory()
    private val history = ThorQuickPlayerHistory()
    private val origin = ThorNavigation(tab = ThorTab.Library).push(ThorPage.Collection(ThorCollection.LocalSongs))
    private val navigation = mutableStateOf(origin)
    private lateinit var list: LazyListState
    private lateinit var scope: CoroutineScope
    private var positionAtActivation: Pair<String, Int>? = null
    private var focusAtActivation: String? = null

    @Test fun controllerPlaybackAndBackRestoreTheSelectedSongAndPixelScrollOffset() {
        showFixture()
        prepareViewport()
        compose.runOnIdle {
            assertTrue(registry.request(key(22)))
        }
        compose.onNodeWithText("Song 22").assertIsFocused()
        val position = compose.runOnIdle { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
        compose.runOnIdle { assertTrue(registry.confirm()) }
        assertPlayerThenReturn(position, 22)
    }

    @Test fun touchingAnotherSongPersistsItsIdentityBeforeTheListIsReplaced() {
        showFixture()
        prepareViewport()
        val position = compose.runOnIdle { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
        compose.onNodeWithText("Song 22").performTouchInput { click(center) }
        assertPlayerThenReturn(position, 22)
    }

    @Test fun touchingThePartiallyVisibleFirstSongKeepsItsExactClippedOffsetAfterBack() {
        showFixture()
        prepareViewport()
        val position = compose.runOnIdle { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
        // Row 20 starts 13 px above the viewport. Its remaining visible touch area still owns A.
        compose.onNodeWithText("Song 20").performTouchInput { click(center) }
        assertPlayerThenReturn(position, 20)
    }

    private fun prepareViewport() {
        compose.runOnIdle { scope.launch { list.scrollToItem(20, 13) } }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(20, list.firstVisibleItemIndex)
            assertEquals(13, list.firstVisibleItemScrollOffset)
        }
    }

    private fun assertPlayerThenReturn(position: Pair<Int, Int>, selected: Int) {
        compose.onNodeWithText("Player").assertIsFocused()
        compose.runOnIdle {
            assertEquals(key(selected), focusAtActivation)
            assertEquals(key(position.first) to position.second, positionAtActivation)
            assertEquals(listOf(ThorOverlay.Player), navigation.value.overlays)
            navigation.value = history.leave(navigation.value, 1)!!.navigation
        }
        compose.onNodeWithText("Song $selected").assertIsFocused()
        compose.runOnIdle {
            assertEquals(origin, navigation.value)
            assertEquals(key(selected), registry.selectedKey)
            assertEquals(position.first, list.firstVisibleItemIndex)
            assertEquals(position.second, list.firstVisibleItemScrollOffset)
        }
    }

    private fun showFixture() {
        compose.setContent {
            ThorTheme(ThorPreferences(reduceMotion = true)) {
                val states = rememberSaveableStateHolder()
                Box(Modifier.width(400.dp).height(260.dp)) {
                    states.SaveableStateProvider(navigation.value.sceneKey) {
                        if (navigation.value.overlay == ThorOverlay.Player) PlayerFixture()
                        else SongListFixture()
                    }
                }
            }
        }
        compose.onNodeWithText("Song 0").assertIsFocused()
    }

    @Composable
    private fun SongListFixture() {
        val state = rememberLazyListState()
        val coroutineScope = rememberCoroutineScope()
        val keys = remember { List(80, ::key) }
        val items = remember { List(80) { ThorItem(ThorItemKey.Song(it.toLong(), true), "Song $it", "Fixture") } }
        val binding = BindThorListFocus(registry, origin.sceneKey, keys, items, state, memory) { _ ->
            focusAtActivation = memory.focus[origin.sceneKey]
            positionAtActivation = memory.scroll[origin.sceneKey]
            navigation.value = history.enter(navigation.value, null, 1)
        }
        SideEffect { list = state; scope = coroutineScope }
        LazyColumn(state = state) {
            itemsIndexed(items, key = { index, _ -> keys[index] }) { index, item ->
                ThorMusicRow(registry, keys[index], item, onClick = { binding.activate(index) },
                    onMenu = {}, onFocused = { binding.onFocused(index) })
            }
        }
    }

    @Composable
    private fun PlayerFixture() {
        val owner = remember { Any() }
        SideEffect { registry.installScope(owner, ThorFocusScope("overlay:Player", listOf("player:toggle"))) }
        DisposableEffect(owner) { onDispose { registry.removeScope(owner) } }
        LaunchedEffect(owner) { withFrameNanos { }; registry.request("player:toggle") }
        ThorButton(registry, "player:toggle", "Player", onClick = {})
    }

    private fun key(index: Int) = "${origin.sceneKey}:song:$index"
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

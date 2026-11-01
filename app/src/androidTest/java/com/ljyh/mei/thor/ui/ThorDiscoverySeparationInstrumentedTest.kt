package com.ljyh.mei.thor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.thor.model.ThorCatalogFilters
import com.ljyh.mei.thor.model.ThorHomeCategory
import com.ljyh.mei.thor.model.ThorDirection
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.thor.theme.ThorTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production Home/Discover headers, with no remote data, fake VM or account mutation. */
@RunWith(AndroidJUnit4::class)
class ThorDiscoverySeparationInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private var density = 1f

    @Composable private fun Fixture(content: @Composable () -> Unit) {
        val actual = LocalDensity.current.density
        SideEffect { density = actual }
        CompositionLocalProvider(LocalDensity provides Density(actual, fontScale = 1f)) {
            ThorTheme(ThorPreferences(reduceMotion = true), content)
        }
    }

    @Test fun homeRecommendedPlaylistsHaveNoClassificationControlAndKeepTheListCursor() {
        val registry = registry()
        var classifierCalls = 0
        var searches = 0
        var confirms = 0
        compose.setContent {
            Fixture {
                Column(Modifier.width(465.dp)) {
                    ThorHomeCatalogHeader(ThorHomeCategory.Playlists, ThorCatalogFilters(playlistCategory = "华语"), {},
                        { searches++ }, { classifierCalls++ }, {}, {})
                    ThorButton(registry, "row", "Fixture recommendation", onClick = { confirms++ })
                }
            }
        }
        compose.runOnIdle { assertTrue(registry.request("row")) }
        compose.onNodeWithTag("home-category:Playlists").assertIsSelected().assertIsNotFocused()
        compose.onNodeWithContentDescription("歌单分类").assertDoesNotExist()
        compose.onNodeWithText("华语").assertDoesNotExist()
        compose.onNodeWithContentDescription("搜索").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(0, classifierCalls)
            assertEquals(1, searches)
            assertEquals("row", registry.selectedKey)
            assertTrue(registry.confirm()); assertEquals(1, confirms)
        }
    }

    @Test fun discoveryHasFortyEightDpClassificationAndSearchControlsOutsideItsListScope() {
        val width = mutableStateOf(465.dp)
        val registry = registry()
        var classifierCalls = 0
        var searches = 0
        var confirms = 0
        compose.setContent {
            Fixture {
                Column(Modifier.width(width.value)) {
                    ThorDiscoverCatalogHeader("华语", { classifierCalls++ }, { searches++ })
                    ThorButton(registry, "row", "Fixture discovery playlist", onClick = { confirms++ })
                }
            }
        }
        compose.runOnIdle { assertTrue(registry.request("row")) }
        for (targetWidth in listOf(465.dp, 483.dp)) {
            compose.runOnIdle { width.value = targetWidth }
            compose.onNodeWithText("发现").assertIsDisplayed()
            compose.onNodeWithText("华语").assertIsDisplayed()
            val header = compose.onNodeWithTag("discover-catalog-header").fetchSemanticsNode().boundsInRoot
            assertEquals(56f * density, header.height, 1f)
            for (description in listOf("歌单分类", "搜索")) {
                val control = compose.onNodeWithContentDescription(description).assertIsDisplayed().assertIsNotFocused()
                val bounds = control.fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.width >= 48f * density - 1f)
                assertEquals(48f * density, bounds.height, 1f)
                assertTrue(bounds.left >= header.left && bounds.right <= header.right + 1f)
                control.performTouchInput { click(center) }
                control.assertIsNotFocused()
            }
        }
        compose.runOnIdle {
            assertEquals(2, classifierCalls); assertEquals(2, searches)
            assertEquals("row", registry.selectedKey)
            assertFalse(registry.request("discover-playlist-category"))
            assertTrue(registry.confirm()); assertEquals(1, confirms)
        }
    }

    @Test fun cancellingTheRealPickerRestoresTheMainListDespiteTheShellCursorChanging() {
        val registry = ThorFocusRegistry()
        val memory = Memory()
        val picker = mutableStateOf(false)
        val items = List(20) { ThorItem(ThorItemKey.Playlist("fixture-$it"), "Fixture playlist $it") }
        val keys = items.indices.map { "$THOR_DISCOVER_LIST_SCENE:item:$it" }
        lateinit var actualList: LazyListState
        compose.setContent {
            Fixture {
                // Exercise the real shell's generic focus projection alongside the production
                // Binder's scene-local memory. Picker movement must not replace the latter.
                SideEffect {
                    registry.onFocus = { key, _ ->
                        if (registry.isAllowed(key)) memory.saveFocus("root:Discover", key)
                    }
                }
                if (picker.value) {
                    ThorCatalogPicker(registry, memory, "$THOR_DISCOVER_LIST_SCENE:filter:Playlist", "歌单分类",
                        List(6) { ThorCatalogChoice("$it", "Fixture category $it") }, "0",
                        onSelect = { picker.value = false }, onBack = { picker.value = false },
                        Modifier.width(465.dp).height(416.dp))
                } else {
                    val list = rememberLazyListState()
                    SideEffect { actualList = list }
                    val binding = BindThorListFocus(registry, THOR_DISCOVER_LIST_SCENE, keys, items, list, memory) {}
                    Column(Modifier.width(465.dp).height(416.dp)) {
                        ThorDiscoverCatalogHeader("全部", { picker.value = true }, {})
                        LazyColumn(Modifier.weight(1f).fillMaxSize(), state = list,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(items, key = { index, _ -> keys[index] }) { index, item ->
                                ThorMusicRow(registry, keys[index], item, onClick = { binding.activate(index) },
                                    onMenu = {}, onFocused = { binding.onFocused(index) })
                            }
                        }
                    }
                }
            }
        }
        compose.runOnIdle { repeat(10) { assertTrue(registry.move(ThorDirection.Down, true)) } }
        val row = compose.onNodeWithText("Fixture playlist 10").assertIsDisplayed()
        val originalBounds = row.fetchSemanticsNode().boundsInRoot
        lateinit var originalViewport: Pair<Int, Int>
        compose.runOnIdle {
            assertEquals(keys[10], registry.selectedKey)
            originalViewport = actualList.firstVisibleItemIndex to actualList.firstVisibleItemScrollOffset
            assertTrue("Fixture must really leave the first viewport", originalViewport.first > 0)
        }
        compose.onNodeWithContentDescription("歌单分类").performTouchInput { click(center) }
        compose.onNodeWithTag("catalog-choice:0").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(registry.move(ThorDirection.Down, false))
            assertEquals(keys[10], memory.readFocus(THOR_DISCOVER_LIST_SCENE))
            assertNotEquals("The generic shell cursor must actually have entered the picker",
                keys[10], memory.readFocus("root:Discover"))
        }
        compose.onNodeWithContentDescription("返回").performTouchInput { click(center) }
        compose.onNodeWithText("Fixture playlist 10").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(keys[10], registry.selectedKey)
            assertEquals(originalViewport, actualList.firstVisibleItemIndex to actualList.firstVisibleItemScrollOffset)
        }
        assertEquals(originalBounds, compose.onNodeWithText("Fixture playlist 10").fetchSemanticsNode().boundsInRoot)
    }

    private fun registry() = ThorFocusRegistry().apply {
        installScope(Any(), ThorFocusScope("discovery-fixture", listOf("row"), ThorFocusLayout.Vertical))
    }

    private class Memory : ThorListFocusMemory {
        private val focus = mutableMapOf<String, String>()
        private val scroll = mutableMapOf<String, Pair<String, Int>>()
        override fun readFocus(scene: String) = focus[scene]
        override fun readScroll(scene: String) = scroll[scene]
        override fun saveFocus(scene: String, key: String) { focus[scene] = key }
        override fun saveScroll(scene: String, key: String, offset: Int) { scroll[scene] = key to offset }
        override fun preview(item: ThorItem?) = Unit
    }
}

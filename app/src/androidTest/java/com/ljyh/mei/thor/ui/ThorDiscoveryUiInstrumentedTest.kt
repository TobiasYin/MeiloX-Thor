package com.ljyh.mei.thor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.thor.model.*
import com.ljyh.mei.thor.theme.ThorTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production catalog controls + list binder, no network, fake ViewModel or account mutation. */
@RunWith(AndroidJUnit4::class)
class ThorDiscoveryUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private var density = 1f

    @Composable private fun Fixture(content: @Composable () -> Unit) {
        val actual = LocalDensity.current.density
        SideEffect { density = actual }
        CompositionLocalProvider(LocalDensity provides Density(actual, fontScale = 1f)) {
            ThorTheme(ThorPreferences(reduceMotion = true), content)
        }
    }

    @Test fun homeTabsAndBothFiltersFitThorAndDuoAndDoNotJoinControllerFocus() {
        val width = mutableStateOf(465.dp) // lower width minus the existing 72dp root rail
        val category = mutableStateOf(ThorHomeCategory.Recommended)
        val registry = scopedRegistry(listOf("retained"))
        var confirms = 0
        var areaClicks = 0
        var typeClicks = 0
        var searches = 0
        compose.setContent {
            Fixture {
                Column(Modifier.width(width.value).testTag("catalog-container")) {
                    ThorHomeCatalogHeader(category.value, ThorCatalogFilters(), { category.value = it }, { searches++ }, {}, { areaClicks++ }, { typeClicks++ })
                    ThorButton(registry, "retained", "Fixture list row", onClick = { confirms++ })
                }
            }
        }
        compose.runOnIdle { assertTrue(registry.request("retained")) }
        for (targetWidth in listOf(465.dp, 483.dp)) {
            compose.runOnIdle { width.value = targetWidth }
            compose.onNodeWithTag("home-category:Artists").performTouchInput { click(center) }
            compose.onNodeWithTag("home-category:Artists").assertIsSelected().assertIsNotFocused()
            val container = compose.onNodeWithTag("catalog-container").fetchSemanticsNode().boundsInRoot
            for (item in ThorHomeCategory.entries) {
                val bounds = compose.onNodeWithTag("home-category:${item.name}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("Tab target width", bounds.width >= 48f * density - 1f)
                assertEquals(48f * density, bounds.height, 1f)
                assertTrue(bounds.left >= container.left && bounds.right <= container.right + 1f)
            }
            for (label in listOf("歌手地区", "歌手类型", "搜索")) {
                val node = compose.onNodeWithContentDescription(label).assertIsDisplayed().assertIsNotFocused()
                val bounds = node.fetchSemanticsNode().boundsInRoot
                assertTrue("$label fits the horizontal header", bounds.right <= container.right + 1f)
                assertTrue(bounds.width >= 48f * density - 1f && bounds.height >= 48f * density - 1f)
                node.performTouchInput { click(center) }
            }
        }
        compose.runOnIdle {
            assertEquals(2, areaClicks); assertEquals(2, typeClicks); assertEquals(2, searches)
            assertEquals("retained", registry.selectedKey)
            assertTrue(registry.confirm()); assertEquals(1, confirms)
            assertFalse(registry.request("home-category:Artists"))
        }
    }

    @Test fun artistSectionsChangeByTouchWithoutRetargetingTheSongListCursor() {
        val section = mutableStateOf(ThorArtistSection.HotSongs)
        val registry = scopedRegistry(listOf("artist-song"))
        var backs = 0
        var confirms = 0
        compose.setContent {
            Fixture {
                Column(Modifier.width(537.dp)) {
                    ThorArtistCatalogHeader("Fixture artist", section.value, 4, { section.value = it }, { backs++ })
                    ThorButton(registry, "artist-song", "Fixture song", onClick = { confirms++ })
                }
            }
        }
        compose.runOnIdle { assertTrue(registry.request("artist-song")) }
        val baseline = compose.onNodeWithTag("artist-section:Albums").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("artist-section:Albums").performTouchInput { click(center) }
        compose.onNodeWithTag("artist-section:Albums").assertIsSelected().assertIsNotFocused()
        compose.onNodeWithText("4张").assertIsDisplayed()
        compose.onNodeWithTag("artist-section:Songs").performTouchInput { click(center) }
        compose.onNodeWithText("4首").assertIsDisplayed()
        assertEquals(baseline, compose.onNodeWithTag("artist-section:Albums").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("返回").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(1, backs)
            assertEquals("artist-song", registry.selectedKey)
            assertTrue(registry.confirm()); assertEquals(1, confirms)
            assertFalse(registry.request("artist-section:Albums"))
        }
    }

    @Test fun theActualFilterPickerNavigatesUnrealizedRowsAndCannotFocusItsHeader() {
        val registry = ThorFocusRegistry()
        val memory = Memory()
        val choices = List(18) { ThorCatalogChoice("$it", "Fixture choice $it") }
        val selected = mutableListOf<String>()
        var backs = 0
        compose.setContent {
            Fixture {
                ThorCatalogPicker(registry, memory, "catalog-filter", "Fixture category", choices, "0",
                    { selected += it }, { backs++ }, Modifier.width(465.dp).height(416.dp))
            }
        }
        compose.runOnIdle {
            assertEquals("catalog-filter:0", registry.selectedKey)
            assertFalse(registry.request("header:back"))
            assertFalse(registry.move(ThorDirection.Left, false))
            assertFalse(registry.move(ThorDirection.Right, false))
            repeat(12) { assertTrue(registry.move(ThorDirection.Down, true)) }
            assertEquals("catalog-filter:12", registry.selectedKey)
            assertTrue("Prepared selection must confirm the intended unrealized row", registry.confirm())
            assertEquals(listOf("12"), selected)
        }
        // Complete the real offscreen focus transaction before starting a touch scroll.
        compose.onNodeWithTag("catalog-choice:12").assertIsDisplayed().assertIsFocused()
        // Row 13 is intentionally beyond the realized viewport after focusing row 12 at its
        // lower edge. Scroll the actual lazy parent to a matcher; child.performScrollTo()
        // cannot find a node which has not been composed yet.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("catalog-choice:13"))
        compose.waitForIdle()
        compose.onNodeWithTag("catalog-choice:13").assertIsDisplayed().performTouchInput { click(center) }
        compose.onNodeWithContentDescription("返回").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(listOf("12", "13"), selected)
            assertEquals(1, backs)
            assertEquals("catalog-filter:13", registry.selectedKey)
        }
    }

    @Test fun paginationRetainsItsBoundsAndIgnoresRepeatedActivationWhileLoading() {
        val registry = scopedRegistry(listOf("more"))
        val loading = mutableStateOf(false)
        val error = mutableStateOf<String?>(null)
        var requests = 0
        compose.setContent {
            Fixture {
                Column(Modifier.width(465.dp)) {
                    ThorCatalogContinuationRow(registry, "more", loading.value, error.value) { requests++ }
                }
            }
        }
        val baseline = compose.onNodeWithTag("catalog-load-more").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            assertTrue(registry.request("more"))
            assertTrue(registry.confirm()); assertEquals(1, requests)
            loading.value = true
        }
        compose.onNodeWithText("正在加载").performTouchInput { click(center) }
        compose.runOnIdle {
            repeat(4) { assertTrue(registry.confirm()) }
            assertEquals(1, requests)
            assertEquals("more", registry.selectedKey)
            loading.value = false; error.value = "Fixture retry"
        }
        assertEquals(baseline, compose.onNodeWithTag("catalog-load-more").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { assertTrue(registry.confirm()); assertEquals(2, requests) }
        val rowBounds = compose.onNodeWithTag("catalog-load-more").fetchSemanticsNode().boundsInRoot
        assertEquals(56f * density, rowBounds.height, 1f)
    }

    private fun scopedRegistry(keys: List<String>) = ThorFocusRegistry().apply {
        installScope(Any(), ThorFocusScope("fixture", keys, ThorFocusLayout.Vertical))
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

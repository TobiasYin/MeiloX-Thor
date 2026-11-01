package com.ljyh.mei.thor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.thor.model.ThorSearchCategory
import com.ljyh.mei.thor.theme.ThorTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real search header/editor controls, with no network, fake VM or queue mutation. */
@RunWith(AndroidJUnit4::class)
class ThorSearchCategoryInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private var density = 1f

    @Composable private fun Fixture(content: @Composable () -> Unit) {
        val actual = LocalDensity.current.density
        SideEffect { density = actual }
        CompositionLocalProvider(LocalDensity provides Density(actual, fontScale = 1f)) {
            ThorTheme(ThorPreferences(reduceMotion = true), content)
        }
    }

    @Test fun fiveSearchTypesFitTheExistingHeaderOnBothLowerWidthsWithoutTakingListFocus() {
        val width = mutableStateOf(537.dp)
        val category = mutableStateOf(ThorSearchCategory.All)
        val registry = registry()
        var confirms = 0
        var backs = 0
        compose.setContent {
            Fixture {
                Column(Modifier.width(width.value).testTag("search-container")) {
                    ThorSearchCatalogHeader(category.value, { category.value = it }, { backs++ })
                    ThorButton(registry, "retained", "Fixture result", onClick = { confirms++ })
                }
            }
        }
        compose.runOnIdle { assertTrue(registry.request("retained")) }
        for (targetWidth in listOf(537.dp, 555.dp)) {
            compose.runOnIdle { width.value = targetWidth }
            val header = compose.onNodeWithTag("thor-search-header").fetchSemanticsNode().boundsInRoot
            assertEquals(56f * density, header.height, 1f)
            for (type in ThorSearchCategory.entries) {
                val tab = compose.onNodeWithTag("search-category:${type.name}").assertIsDisplayed()
                val bounds = tab.fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.width >= 48f * density - 1f)
                assertEquals(48f * density, bounds.height, 1f)
                assertTrue(bounds.left >= header.left && bounds.right <= header.right + 1f)
                tab.performTouchInput { click(center) }
                tab.assertIsSelected().assertIsNotFocused()
                compose.runOnIdle { assertEquals("retained", registry.selectedKey) }
            }
        }
        compose.onNodeWithContentDescription("返回").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(1, backs)
            assertTrue(registry.confirm()); assertEquals(1, confirms)
            assertFalse(registry.request("search-category:Artists"))
        }
    }

    @Test fun switchingTypeClosesTheActualEditorAndKeepsItsDraftWithoutSubmittingIt() {
        val registry = registry()
        val bindings = ThorPageActionBindings()
        val category = mutableStateOf(ThorSearchCategory.All)
        val query = mutableStateOf("")
        val editing = mutableStateOf(false)
        val submitted = mutableListOf<String>()
        compose.setContent {
            Fixture {
                Column(Modifier.width(537.dp)) {
                    ThorSearchCatalogHeader(category.value,
                        { selectThorSearchCategory(it, bindings) { selected -> category.value = selected } }, {})
                    ThorSearchInput(query.value, false, registry, bindings, { query.value = it }, { submitted += it }, { editing.value = it })
                    ThorButton(registry, "retained", "Fixture result", onClick = {})
                }
            }
        }
        compose.runOnIdle { assertTrue(registry.request("retained")); assertTrue(bindings.invoke("search:edit")) }
        val input = compose.onNodeWithTag("thor-search-input")
        input.performTextReplacement("Fixture artist")
        input.assertIsFocused()
        compose.runOnIdle { assertTrue(editing.value) }
        compose.onNodeWithTag("search-category:Artists").performTouchInput { click(center) }
        input.assertIsNotFocused().assertTextEquals("Fixture artist")
        compose.runOnIdle {
            assertEquals(ThorSearchCategory.Artists, category.value)
            assertEquals("Fixture artist", query.value)
            assertFalse(editing.value)
            assertTrue("Changing type must never execute the submit binding", submitted.isEmpty())
            // The long-Select path calls the same real editor binding before selecting a type.
            assertTrue(bindings.invoke("search:edit"))
        }
        input.assertIsFocused()
        compose.runOnIdle { selectThorSearchCategory(ThorSearchCategory.Albums, bindings) { category.value = it } }
        input.assertIsNotFocused().assertTextEquals("Fixture artist")
        compose.runOnIdle {
            assertEquals(ThorSearchCategory.Albums, category.value)
            assertFalse(editing.value)
            assertTrue(submitted.isEmpty())
            assertTrue(bindings.invoke("search:submit"))
            assertEquals(listOf("Fixture artist"), submitted)
        }
    }

    @Test fun aPartialFailureOffersARealRetryTargetWithoutMovingTheResultCursor() {
        val registry = registry()
        var retries = 0
        var confirms = 0
        compose.setContent {
            Fixture {
                Column(Modifier.width(537.dp)) {
                    ThorSearchPartialFailure({ retries++ })
                    ThorButton(registry, "retained", "Fixture result", onClick = { confirms++ })
                }
            }
        }
        compose.runOnIdle { assertTrue(registry.request("retained")) }
        compose.onNodeWithText("部分结果未能加载").assertIsDisplayed()
        val retry = compose.onNodeWithTag("search-partial-retry").assertIsDisplayed()
        val bounds = retry.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width >= 48f * density - 1f)
        assertEquals(48f * density, bounds.height, 1f)
        retry.performTouchInput { click(center) }
        retry.assertIsNotFocused()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals("retained", registry.selectedKey)
            assertTrue(registry.confirm()); assertEquals(1, confirms)
        }
    }

    private fun registry() = ThorFocusRegistry().apply {
        installScope(Any(), ThorFocusScope("search-fixture", listOf("retained"), ThorFocusLayout.Vertical))
    }
}

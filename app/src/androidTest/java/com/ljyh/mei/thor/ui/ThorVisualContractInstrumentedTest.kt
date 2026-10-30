package com.ljyh.mei.thor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.thor.model.ThorAppearance
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.thor.model.ThorThemePack
import com.ljyh.mei.thor.theme.ThorTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production controls, real layout/focus/touch and rendered state changes. No ViewModel, account,
 * audio service or remote images. Geometry is checked at fontScale=1 while retaining device dpi;
 * large-font acceptance remains a separate device matrix. Reduced motion keeps virtual time finite.
 */
@RunWith(AndroidJUnit4::class)
class ThorVisualContractInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private var pixelsPerDp = 1f

    @Composable
    private fun FixtureTheme(
        preferences: ThorPreferences = ThorPreferences(reduceMotion = true),
        content: @Composable () -> Unit,
    ) {
        val density = LocalDensity.current.density
        SideEffect { pixelsPerDp = density }
        CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1f)) {
            ThorTheme(preferences, content)
        }
    }

    private fun assertSize(label: String, bounds: Rect, widthDp: Float, heightDp: Float) {
        assertEquals("$label width", widthDp * pixelsPerDp, bounds.width, 1f)
        assertEquals("$label height", heightDp * pixelsPerDp, bounds.height, 1f)
    }

    private fun assertSameBounds(label: String, expected: Rect, actual: Rect) {
        assertEquals("$label left", expected.left, actual.left, .5f)
        assertEquals("$label top", expected.top, actual.top, .5f)
        assertEquals("$label right", expected.right, actual.right, .5f)
        assertEquals("$label bottom", expected.bottom, actual.bottom, .5f)
    }

    @Test fun flatIconAndCircularTransportKeepFortyEightSixtyEightySixtyDpBounds() {
        val registry = ThorFocusRegistry()
        compose.setContent {
            FixtureTheme {
                Column(Modifier.width(360.dp).padding(16.dp)) {
                    ThorButton(registry, "flat", "", Modifier.testTag("flat"), icon = Icons.Outlined.Search,
                        flat = true, contentDescription = "Fixture search", onClick = {})
                    Row(Modifier.fillMaxWidth().height(88.dp),
                        horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically) {
                        ThorButton(registry, "previous", "", Modifier.size(60.dp).testTag("previous"),
                            icon = Icons.Outlined.SkipPrevious, round = true, iconSize = 26.dp, onClick = {})
                        ThorButton(registry, "play", "", Modifier.size(80.dp).testTag("play"),
                            icon = Icons.Outlined.PlayArrow, primary = true, round = true, iconSize = 30.dp, onClick = {})
                        ThorButton(registry, "next", "", Modifier.size(60.dp).testTag("next"),
                            icon = Icons.Outlined.SkipNext, round = true, iconSize = 26.dp, onClick = {})
                    }
                }
            }
        }
        val bounds = listOf("flat", "previous", "play", "next").associateWith { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        assertSize("flat", bounds.getValue("flat"), 48f, 48f)
        assertSize("previous", bounds.getValue("previous"), 60f, 60f)
        assertSize("play", bounds.getValue("play"), 80f, 80f)
        assertSize("next", bounds.getValue("next"), 60f, 60f)
        assertEquals(bounds.getValue("play").center.y, bounds.getValue("previous").center.y, .5f)
        assertEquals(bounds.getValue("play").center.y, bounds.getValue("next").center.y, .5f)
    }

    @Test fun musicRowIsFiftySixDpWithARealThirtySixDpCover() {
        val registry = ThorFocusRegistry()
        val title = "Cover layout fixture"
        // Android's bundled resource goes through the real ThorCover/AsyncImage path, never HTTP.
        val item = ThorItem(ThorItemKey.Song(1, true), title, "Fixture artist",
            artworkUri = "android.resource://android/${android.R.drawable.ic_media_play}")
        compose.setContent {
            FixtureTheme {
                Column(Modifier.width(360.dp).padding(16.dp)) {
                    ThorMusicRow(registry, "row", item, onClick = {}, onMenu = {})
                }
            }
        }
        val row = compose.onNode(hasText(title) and hasClickAction()).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val cover = compose.onNodeWithContentDescription(title, useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertSize("row", row, 328f, 56f)
        assertSize("cover", cover, 36f, 36f)
        assertEquals("cover left inset", row.left + 10f * pixelsPerDp, cover.left, 1f)
        assertEquals("cover vertical center", row.center.y, cover.center.y, 1f)
    }

    @Test fun allSixThemesKeepTheSameNativeFocusAndControlBounds() {
        val registry = ThorFocusRegistry()
        val preferences = mutableStateOf(ThorPreferences(reduceMotion = true, appearance = ThorAppearance.Day))
        val title = "Stable row fixture"
        compose.setContent {
            FixtureTheme(preferences.value) {
                Column(Modifier.width(360.dp).padding(16.dp)) {
                    ThorButton(registry, "retained", "Keep focus", Modifier.testTag("retained"), onClick = {})
                    ThorMusicRow(registry, "row", ThorItem(ThorItemKey.Song(2, true), title, "Fixture artist"),
                        onClick = {}, onMenu = {})
                }
            }
        }
        compose.runOnIdle { assertTrue(registry.request("retained")) }
        val button = compose.onNodeWithTag("retained").assertIsFocused()
        val row = compose.onNode(hasText(title) and hasClickAction())
        val buttonBounds = button.fetchSemanticsNode().boundsInRoot
        val rowBounds = row.fetchSemanticsNode().boundsInRoot
        for (pack in ThorThemePack.entries) for (appearance in listOf(ThorAppearance.Day, ThorAppearance.Night)) {
            compose.runOnIdle {
                preferences.value = ThorPreferences(theme = pack, appearance = appearance, reduceMotion = true)
            }
            button.assertIsFocused().assertIsDisplayed()
            assertSameBounds("$pack/$appearance button", buttonBounds, button.fetchSemanticsNode().boundsInRoot)
            assertSameBounds("$pack/$appearance row", rowBounds, row.fetchSemanticsNode().boundsInRoot)
            compose.runOnIdle { assertEquals("retained", registry.selectedKey) }
        }
    }

    @Test fun selectedFocusedAndHeldPressPaintTogetherWithoutMovingTheRow() {
        val registry = ThorFocusRegistry()
        val selected = mutableStateOf(false)
        var clicks = 0
        val title = "Combined state fixture"
        compose.setContent {
            FixtureTheme {
                Column(Modifier.width(360.dp).padding(16.dp)) {
                    ThorMusicRow(registry, "row", ThorItem(ThorItemKey.Song(3, true), title, "Fixture artist"),
                        selected = selected.value, onClick = { clicks++ }, onMenu = {})
                }
            }
        }
        val row = compose.onNode(hasText(title) and hasClickAction())
        val original = row.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { assertTrue(registry.request("row")); selected.value = true }
        row.assertIsFocused().assertIsSelected()
        assertSameBounds("selected/focused", original, row.fetchSemanticsNode().boundsInRoot)
        val resting = row.captureToImage().toPixelMap()
        row.performTouchInput { down(center); moveTo(center, delayMillis = 32L) }
        row.assertIsFocused().assertIsSelected()
        assertSameBounds("held press", original, row.fetchSemanticsNode().boundsInRoot)
        val held = row.captureToImage().toPixelMap()
        assertEquals(resting.width, held.width); assertEquals(resting.height, held.height)
        var changed = false
        pixelLoop@ for (y in 0 until resting.height) for (x in 0 until resting.width) {
            if (resting[x, y] != held[x, y]) { changed = true; break@pixelLoop }
        }
        assertTrue("The real press must paint feedback, not just keep identical bounds", changed)
        row.performTouchInput { cancel() }
        assertSameBounds("release", original, row.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { assertEquals(0, clicks); assertEquals("row", registry.selectedKey) }
        row.performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}

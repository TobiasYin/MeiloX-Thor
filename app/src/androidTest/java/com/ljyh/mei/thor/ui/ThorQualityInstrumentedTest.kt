package com.ljyh.mei.thor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.thor.model.ThorDirection
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.thor.theme.ThorTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual quality controls and virtual-list binder, without a VM/service/network or preference edits. */
@RunWith(AndroidJUnit4::class)
class ThorQualityInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private var density = 1f

    @Composable private fun Fixture(content: @Composable () -> Unit) {
        val actual = LocalDensity.current.density
        SideEffect { density = actual }
        CompositionLocalProvider(LocalDensity provides Density(actual, fontScale = 1f)) {
            ThorTheme(ThorPreferences(reduceMotion = true), content)
        }
    }

    @Test fun initialFocusUsesTheDefaultRatherThanActualOrOldCursorAndAllEightOptionsCanBeConfirmed() {
        val registry = ThorFocusRegistry()
        val memory = Memory("quality:options:STANDARD")
        val selected = mutableListOf<MusicQuality>()
        compose.setContent {
            Fixture {
                ThorQualitySelector(registry, memory, MusicQuality.JYMASTER, MusicQuality.LOSSLESS, false,
                    { selected += it }, {}, Modifier.width(537.dp).height(416.dp))
            }
        }
        compose.onNodeWithTag("quality-summary").assertTextEquals("默认 超清母带 · 当前 无损")
        compose.onNodeWithTag("quality-choice:JYMASTER").assertIsDisplayed().assertIsFocused().assertIsSelected()
        compose.runOnIdle {
            assertEquals("quality:options:JYMASTER", registry.selectedKey)
            assertFalse(registry.request("quality:back"))
            assertFalse(registry.move(ThorDirection.Left, false))
            assertFalse(registry.move(ThorDirection.Right, false))
            repeat(6) { assertTrue(registry.move(ThorDirection.Up, true)) }
            assertFalse(registry.move(ThorDirection.Up, false))
            MusicQuality.entries.forEachIndexed { index, quality ->
                if (index > 0) assertTrue(registry.move(ThorDirection.Down, true))
                assertTrue(registry.confirm())
                assertEquals(quality, selected.last())
            }
            assertEquals(MusicQuality.entries.toList(), selected)
            assertFalse(registry.move(ThorDirection.Down, false))
        }
        compose.onNodeWithTag("quality-choice:DOLBY").assertIsDisplayed().assertIsFocused().assertIsNotSelected()
        compose.onNodeWithTag("quality-choice:JYMASTER").assertIsSelected().assertIsNotFocused()
    }

    @Test fun actualChangesDoNotMoveTheCursorAndLocalOrUnknownSourcesNeverPretendToBeACloudTier() {
        val registry = ThorFocusRegistry()
        val actual = mutableStateOf<MusicQuality?>(MusicQuality.LOSSLESS)
        val local = mutableStateOf(false)
        val selected = mutableListOf<MusicQuality>()
        var backs = 0
        compose.setContent {
            Fixture {
                ThorQualitySelector(registry, MemoryRemembered(), MusicQuality.HIRES, actual.value, local.value,
                    { selected += it }, { backs++ }, Modifier.width(537.dp).height(416.dp))
            }
        }
        compose.onNodeWithTag("quality-current:LOSSLESS").assertExists()
        compose.runOnIdle { actual.value = MusicQuality.STANDARD }
        compose.onNodeWithTag("quality-current:STANDARD").assertExists()
        compose.runOnIdle {
            assertEquals("quality:options:HIRES", registry.selectedKey)
            local.value = true
        }
        compose.onNodeWithTag("quality-summary").assertTextEquals("默认 Hi-Res · 当前 本地文件")
        compose.onNodeWithTag("quality-current:STANDARD").assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("quality-choice:DOLBY"))
        compose.onNodeWithTag("quality-choice:DOLBY").assertIsEnabled().performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(listOf(MusicQuality.DOLBY), selected)
            local.value = false; actual.value = null
        }
        compose.onNodeWithTag("quality-summary").assertTextEquals("默认 Hi-Res · 当前 —")
        compose.onNodeWithTag("quality-current:STANDARD").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, backs); assertEquals(listOf(MusicQuality.DOLBY), selected) }
    }

    @Test fun theCompactPlayerChipShowsActualQualityAndTouchKeepsThePlayerControllerTarget() {
        val registry = ThorFocusRegistry().apply {
            installScope(Any(), ThorFocusScope("player-fixture", listOf("player:toggle"), ThorFocusLayout.Vertical))
        }
        val actual = mutableStateOf<MusicQuality?>(MusicQuality.LOSSLESS)
        val local = mutableStateOf(false)
        var opened = 0
        compose.setContent {
            Fixture {
                Row(Modifier.width(537.dp)) {
                    ThorPlayerQualityButton(registry, MusicQuality.JYMASTER, actual.value, local.value) { opened++ }
                    ThorButton(registry, "player:toggle", "Fixture playback", onClick = {})
                }
            }
        }
        compose.runOnIdle { assertTrue(registry.request("player:toggle")) }
        val chip = compose.onNodeWithTag("player-quality").assertTextContains("无损")
        var bounds = chip.fetchSemanticsNode().boundsInRoot
        assertEquals(48f * density, bounds.height, 1f)
        assertTrue(bounds.width >= 48f * density - 1f && bounds.width <= 96f * density + 1f)
        chip.assertContentDescriptionEquals("播放音质，当前无损，默认超清母带").performTouchInput { click(center) }
        chip.assertIsNotFocused()
        compose.runOnIdle {
            assertEquals(1, opened)
            assertEquals("player:toggle", registry.selectedKey)
            assertFalse(registry.request("player:quality"))
            actual.value = MusicQuality.DOLBY
        }
        chip.assertTextContains("杜比全景声")
        bounds = chip.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width <= 96f * density + 1f)
        compose.runOnIdle { local.value = true }
        chip.assertTextContains("本地").assertContentDescriptionEquals("播放音质，当前本地文件，默认超清母带")
        compose.runOnIdle { local.value = false; actual.value = null }
        chip.assertTextContains("音质").assertContentDescriptionEquals("播放音质，当前未知，默认超清母带")
    }

    @Composable private fun MemoryRemembered(): ThorListFocusMemory = remember { Memory() }

    private class Memory(initial: String? = null) : ThorListFocusMemory {
        private var focus = initial
        override fun readFocus(scene: String) = focus
        override fun readScroll(scene: String): Pair<String, Int>? = null
        override fun saveFocus(scene: String, key: String) { focus = key }
        override fun saveScroll(scene: String, key: String, offset: Int) = Unit
        override fun preview(item: ThorItem?) = Unit
    }
}

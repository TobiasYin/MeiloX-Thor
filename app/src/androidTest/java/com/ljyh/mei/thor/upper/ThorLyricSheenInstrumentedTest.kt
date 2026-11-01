package com.ljyh.mei.thor.upper

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.thor.model.ThorAppearance
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.thor.model.ThorThemePack
import com.ljyh.mei.thor.theme.LocalThorTheme
import com.ljyh.mei.thor.theme.ThorTheme
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native text rendering only: synthetic Unicode/word clocks, no account, network or audio. */
@RunWith(AndroidJUnit4::class)
class ThorLyricSheenInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val preferences = mutableStateOf(ThorPreferences(reduceMotion = false))
    private val decorated = mutableStateOf(false)
    private val position = AtomicInteger(1_200)
    private val tick = mutableLongStateOf(0)
    private val diagnostics = ThorLyricFlowDiagnostics()
    private var pixelsPerDp = 1f

    @Test fun sixMaterialsRenderGlyphOnlyPearlAndReducedTransparencyRemovesItsWholeFinish() {
        show("Pearl 光泽 satin", rtlFixture = false)
        for (pack in ThorThemePack.entries) for (appearance in listOf(ThorAppearance.Day, ThorAppearance.Night)) {
            compose.runOnIdle {
                preferences.value = preferences.value.copy(theme = pack, appearance = appearance, reduceTransparency = false)
                decorated.value = false
            }
            val base = pixels()
            val layouts = diagnostics.layouts.get()
            compose.runOnIdle { decorated.value = true }
            val sheen = pixels()
            assertTrue("$pack/$appearance must add a real, visible finish", changed(base, sheen) > 20)
            assertEquals("A decorative pass must not remeasure words", layouts, diagnostics.layouts.get())
            assertTrue(diagnostics.reflectionDrawn)
            assertEquals(appearance == ThorAppearance.Night, diagnostics.haloDrawn)
            assertGlyphOnlyChange(base, sheen)
            compose.runOnIdle { preferences.value = preferences.value.copy(reduceTransparency = true) }
            assertArrayEquals("Reduced transparency retains the exact opaque progress ink", base.argb, pixels().argb)
            assertFalse(diagnostics.haloDrawn)
            assertFalse(diagnostics.reflectionDrawn)
        }
    }

    @Test fun wrappedArabicCjkSupplementaryAndCombiningCharactersAdvanceWithoutReshaping() {
        val text = "مرحبا 🌙 繁星 e\u0301 随风越过海岸 إلى البعيد"
        preferences.value = preferences.value.copy(appearance = ThorAppearance.Night)
        decorated.value = true
        show(text, rtlFixture = true)
        val layout = layout()
        assertTrue("The fixture must exercise wrapped runs", layout.lineCount >= 2)
        assertEquals(text, layout.layoutInput.text.text)
        val baselineBounds = node().fetchSemanticsNode().boundsInRoot
        val baselineLayouts = diagnostics.layouts.get()
        val before = pixels()
        compose.runOnIdle { position.set(1_800); tick.longValue++ }
        val after = pixels()
        assertTrue("A real RTL syllable must make visible progress", changed(before, after) > 20)
        assertEquals(baselineLayouts, diagnostics.layouts.get())
        assertEquals(layout.size, layout().size)
        assertEquals(text, layout().layoutInput.text.text)
        assertEquals(baselineBounds, node().fetchSemanticsNode().boundsInRoot)
    }

    private fun show(text: String, rtlFixture: Boolean) {
        val syllables = if (rtlFixture) listOf(KaraokeSyllable("مرحبا ", 0, 2_000),
            KaraokeSyllable(text.removePrefix("مرحبا "), 2_000, 8_000))
        else listOf(KaraokeSyllable(text, 0, 4_000))
        val line = KaraokeLine.MainKaraokeLine(syllables, null, KaraokeAlignment.Start, 0, 8_000)
        val timeline = thorLyricTimeline(line, true)
        compose.setContent {
            ThorTheme(preferences.value) {
                val theme = LocalThorTheme.current
                val density = androidx.compose.ui.platform.LocalDensity.current
                SideEffect { pixelsPerDp = density.density }
                CompositionLocalProvider(LocalThorLyricFlowDiagnostics provides diagnostics) {
                    Box(Modifier.background(theme.background).padding(8.dp)) {
                        ThorLyricFlowText(timeline, TextStyle(fontSize = 24.sp, lineHeight = 1.5.em, textDirection = TextDirection.Content),
                            positionProvider = position::get, frameTick = tick,
                            textColor = theme.lyricSheen.ink, secondaryColor = theme.secondary, sheen = theme.lyricSheen,
                            decorate = decorated.value && !theme.reduceMotion && !theme.reduceTransparency,
                            modifier = Modifier.width(if (rtlFixture) 170.dp else 300.dp).testTag("sheen-text"))
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun node() = compose.onNodeWithTag("sheen-text")
    private fun layout(): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        node().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single()
    }
    private data class Pixels(val width: Int, val height: Int, val argb: IntArray)
    private fun pixels(): Pixels {
        val map = node().captureToImage().toPixelMap()
        return Pixels(map.width, map.height, IntArray(map.width * map.height) { map[it % map.width, it / map.width].toArgb() })
    }
    private fun changed(a: Pixels, b: Pixels): Int {
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        return a.argb.indices.count { a.argb[it] != b.argb[it] }
    }
    private fun assertGlyphOnlyChange(base: Pixels, sheen: Pixels) {
        val background = base.argb.first()
        fun different(a: Int, b: Int): Boolean = (0..2).any { channel ->
            kotlin.math.abs(((a ushr (channel * 8)) and 255) - ((b ushr (channel * 8)) and 255)) > 3
        }
        val radius = kotlin.math.ceil(pixelsPerDp * .45f).toInt() + 1
        for (index in base.argb.indices) if (different(base.argb[index], sheen.argb[index]) && !different(base.argb[index], background)) {
            val x = index % base.width; val y = index / base.width
            val nearInk = ((y - radius).coerceAtLeast(0)..(y + radius).coerceAtMost(base.height - 1)).any { py ->
                ((x - radius).coerceAtLeast(0)..(x + radius).coerceAtMost(base.width - 1)).any { px ->
                    different(base.argb[py * base.width + px], background)
                }
            }
            assertTrue("A reflection may feather glyphs, never paint a rectangular light band", nearInk)
        }
    }
}

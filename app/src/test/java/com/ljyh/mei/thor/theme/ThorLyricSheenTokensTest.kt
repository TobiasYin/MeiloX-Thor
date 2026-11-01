package com.ljyh.mei.thor.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.ljyh.mei.thor.artwork.thorArtworkLuminance
import com.ljyh.mei.thor.model.ThorThemePack
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class ThorLyricSheenTokensTest {
    @Test fun everyOpaqueInkKeepsContrastOnSixMaterialsAndOnTheActualShaderReadingLimit() {
        val artwork = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF1648.toInt(),
            0xFF14FF84.toInt(), 0xFF1438FF.toInt(), 0xFFFFCA10.toInt(), 0xFF18DFFF.toInt())
        for (pack in ThorThemePack.entries) for (night in listOf(false, true)) {
            val theme = thorThemeTokens(pack, night)
            val finish = theme.lyricSheen
            val guard = thorBackdropContrast(night, intArrayOf(theme.text.toArgb(), theme.secondary.toArgb()))
            val backgrounds = listOf(theme.background, theme.surface, theme.elevated, theme.inset).map(Color::toArgb) +
                artwork.map { readableThorBackdropColor(it, guard) }
            val inks = listOf(finish.ink, finish.warmReflection, finish.coolReflection, finish.ridge)
            for (ink in inks) for (background in backgrounds) {
                val a = thorArtworkLuminance(ink.toArgb())
                val b = thorArtworkLuminance(background)
                val contrast = (maxOf(a, b) + .05) / (minOf(a, b) + .05)
                assertTrue("$pack/$night opaque lyric finish contrast=$contrast", contrast >= 4.6)
            }
            val reference = thorArtworkLuminance(theme.secondary.toArgb())
            inks.forEach { ink ->
                val brightness = thorArtworkLuminance(ink.toArgb())
                assertTrue("Finish stays inside the existing reading-ink guard",
                    if (night) brightness >= reference else brightness <= reference)
            }
        }
    }

    @Test fun pearlAndSilverInksStayLowChromaAndIgnoreTheInteractionAccent() {
        for (pack in ThorThemePack.entries) for (night in listOf(false, true)) {
            val theme = thorThemeTokens(pack, night)
            assertEquals(theme.lyricSheen, theme.copy(accent = Color.Magenta).lyricSheen)
            listOf(theme.lyricSheen.ink, theme.lyricSheen.warmReflection,
                theme.lyricSheen.coolReflection, theme.lyricSheen.ridge).forEach { color ->
                val spread = maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue)
                assertTrue("Reading finish must not become fluorescent", spread <= .13f)
                assertEquals(1f, color.alpha, 0f)
            }
        }
    }

    @Test fun finishFollowsTheAnimatedInkContinuouslyInsteadOfTheDestinationNightFlag() {
        val day = thorThemeTokens(ThorThemePack.Ceramic, false)
        val night = thorThemeTokens(ThorThemePack.Glass, true)
        val start = interpolateThorTheme(day, night, 0f)
        assertEquals(day.lyricSheen.ink, start.lyricSheen.ink)
        var previous = start.lyricSheen.ink
        for (step in 1..100) {
            val current = interpolateThorTheme(day, night, step / 100f).lyricSheen.ink
            assertTrue(abs(previous.red - current.red) < .06f)
            assertTrue(abs(previous.green - current.green) < .06f)
            assertTrue(abs(previous.blue - current.blue) < .06f)
            previous = current
        }
        assertEquals(night.lyricSheen.ink, previous)
    }

    @Test fun reducedTransparencyImmediatelyRemovesTheFinishWithoutChangingOpaqueReadingInk() {
        val day = thorThemeTokens(ThorThemePack.Ceramic, false)
        val opaqueNight = thorThemeTokens(ThorThemePack.Titanium, true, reduceTransparency = true)
        for (fraction in listOf(0f, .1f, .5f, 1f)) {
            val frame = interpolateThorTheme(day, opaqueNight, fraction)
            assertEquals(0f, frame.lyricSheen.haloOpacity, 0f)
            assertEquals(0f, frame.lyricSheen.satinOpacity, 0f)
            assertEquals(0f, frame.lyricSheen.ridgeOpacity, 0f)
            assertFalse(frame.lyricSheen.decorationsAllowed)
            assertEquals(1f, frame.lyricSheen.ink.alpha, 0f)
            assertEquals(frame.copy(reduceTransparency = false).lyricSheen.ink, frame.lyricSheen.ink)
        }
    }
}

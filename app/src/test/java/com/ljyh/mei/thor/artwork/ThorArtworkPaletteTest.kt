package com.ljyh.mei.thor.artwork

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ThorArtworkPaletteTest {
    @Test fun threeSubstantialRegionsRemainDistinctInsteadOfAveragingIntoMud() {
        val red = 0xFFD03B42.toInt()
        val blue = 0xFF245CB8.toInt()
        val yellow = 0xFFDFC845.toInt()
        val image = IntArray(100) { if (it < 60) red else if (it < 90) blue else yellow }
        val palette = extractThorArtworkPalette(image, 10, 10)
        assertFalse(palette.neutral)
        assertRgbNear(red, palette.dominantArgb, 2)
        assertRgbNear(blue, palette.secondaryArgb, 2)
        assertRgbNear(yellow, palette.accentArgb, 2)
    }

    @Test fun transparentBorderAndNearWhiteBlackNoiseDoNotWinTheDominantColor() {
        val blue = 0xFF2E65AC.toInt()
        val image = IntArray(400) { index -> when {
            index < 140 -> 0x00FF0000
            index < 220 -> 0x7FFF0000
            index < 280 -> 0xFFFCFCFA.toInt()
            index < 340 -> 0xFF08090A.toInt()
            else -> blue
        } }
        val palette = extractThorArtworkPalette(image, 20, 20)
        assertFalse(palette.neutral)
        assertRgbNear(blue, palette.dominantArgb, 2)
    }

    @Test fun aSingleBrightColoredSpeckCannotReplaceARealAccent() {
        val red = 0xFFA94145.toInt()
        val blue = 0xFF345D9F.toInt()
        val image = IntArray(4096) { if (it < 3000) red else blue }
        image[300] = 0xFF00FF00.toInt()
        val palette = extractThorArtworkPalette(image, 64, 64)
        assertRgbNear(red, palette.dominantArgb, 2)
        assertRgbNear(blue, palette.secondaryArgb, 2)
        assertTrue("noise must not become a green accent", green(palette.accentArgb) < red(palette.accentArgb) + 30)
    }

    @Test fun blackWhiteAndGrayscaleCoversReturnUsableOpaqueNeutralColors() {
        listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF858585.toInt()).forEach { gray ->
            val palette = extractThorArtworkPalette(IntArray(100) { gray }, 10, 10)
            assertTrue(palette.neutral)
            assertTrue(palette.dominantArgb != palette.secondaryArgb)
            listOf(palette.dominantArgb, palette.secondaryArgb, palette.accentArgb).forEach {
                assertEquals(255, it ushr 24)
                assertEquals(red(it), green(it))
                assertEquals(green(it), blue(it))
            }
        }
    }

    @Test fun mutedPhotographicCoverKeepsItsActualWarmHueInsteadOfBecomingGray() {
        val warm = 0xFFB6A79A.toInt()
        val palette = extractThorArtworkPalette(IntArray(400) { warm }, 20, 20)
        assertFalse(palette.neutral)
        assertRgbNear(warm, palette.dominantArgb, 2)
        assertTrue(red(palette.dominantArgb) > blue(palette.dominantArgb))
    }

    @Test fun malformedOrFullyTransparentImagesUseTheSameSafeFallback() {
        assertEquals(ThorArtworkPalette.Neutral, extractThorArtworkPalette(intArrayOf(), 0, 0))
        assertEquals(ThorArtworkPalette.Neutral, extractThorArtworkPalette(intArrayOf(0), Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(ThorArtworkPalette.Neutral, extractThorArtworkPalette(IntArray(9), 4, 4))
        assertEquals(ThorArtworkPalette.Neutral, extractThorArtworkPalette(IntArray(9), -1, 9))
        assertEquals(ThorArtworkPalette.Neutral, extractThorArtworkPalette(IntArray(16) { 0x00FFFFFF }, 4, 4))
    }

    @Test fun tinyAndTranslucentButVisibleCoversStillYieldTheirActualHue() {
        val orange = 0xFFD17036.toInt()
        assertRgbNear(orange, extractThorArtworkPalette(intArrayOf(orange), 1, 1).dominantArgb, 2)
        val translucent = 0xD0D17036.toInt()
        assertRgbNear(orange, extractThorArtworkPalette(intArrayOf(translucent), 1, 1).dominantArgb, 2)
    }

    @Test fun largeImageSamplingCoversTheWholeImageAndIgnoresUnusedArrayTail() {
        val blue = 0xFF386BA5.toInt()
        val red = 0xFFAF4949.toInt()
        val width = 200
        val height = 100
        val image = IntArray(width * height + 500) { if (it >= width * height) red else if (it % width < 150) blue else red }
        assertRgbNear(blue, extractThorArtworkPalette(image, width, height).dominantArgb, 2)
        val a = extractThorArtworkPalette(image, width, height)
        val b = extractThorArtworkPalette(image.copyOf(width * height), width, height)
        assertEquals(a, b)
    }

    @Test fun dayAndNightMappingKeepAllEnvironmentalColorsInSafeLuminanceBands() {
        val palette = ThorArtworkPalette(0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0xFF00FF00.toInt())
        val day = palette.forAmbient(false)
        val night = palette.forAmbient(true)
        listOf(day.dominantArgb, day.secondaryArgb, day.accentArgb).forEach {
            assertTrue("day field must keep usable mid-light cover color", thorArtworkLuminance(it) in .57.. .73)
            assertTrue(contrast(it, 0xFF18211F.toInt()) >= 4.5)
        }
        listOf(night.dominantArgb, night.secondaryArgb, night.accentArgb).forEach {
            assertTrue("night field must remain dark while still showing its cover hue", thorArtworkLuminance(it) in .06.. .15)
            assertTrue(contrast(it, 0xFFEDF4EF.toInt()) >= 4.5)
        }
        assertTrue(red(day.dominantArgb) > blue(day.dominantArgb))
        assertTrue(blue(night.secondaryArgb) > red(night.secondaryArgb))
    }

    @Test fun dayMappingRetainsVisibleCoverChromaInsteadOfWhiteningEveryRegion() {
        val mapped = ThorArtworkPalette(0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0xFF00FF00.toInt()).forAmbient(false)
        assertTrue("The dominant red must not become an almost-white tint",
            red(mapped.dominantArgb) - green(mapped.dominantArgb) >= 50)
        assertTrue("The blue secondary must remain visibly blue",
            blue(mapped.secondaryArgb) - red(mapped.secondaryArgb) >= 30)
        assertTrue(green(mapped.accentArgb) > red(mapped.accentArgb))
    }

    @Test fun mappingAlsoHandlesExtremeSourceColorsWithoutNaNOrTransparentOutput() {
        listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFEFEFE.toInt()).forEach { color ->
            listOf(false, true).forEach { dark ->
                val mapped = ThorArtworkPalette(color, color, color).forAmbient(dark)
                listOf(mapped.dominantArgb, mapped.secondaryArgb, mapped.accentArgb).forEach {
                    assertEquals(255, it ushr 24)
                    assertTrue(thorArtworkLuminance(it).isFinite())
                }
            }
        }
    }

    private fun contrast(a: Int, b: Int): Double {
        val first = thorArtworkLuminance(a); val second = thorArtworkLuminance(b)
        return (maxOf(first, second) + .05) / (minOf(first, second) + .05)
    }
    private fun assertRgbNear(expected: Int, actual: Int, tolerance: Int) {
        assertTrue("red", abs(red(expected) - red(actual)) <= tolerance)
        assertTrue("green", abs(green(expected) - green(actual)) <= tolerance)
        assertTrue("blue", abs(blue(expected) - blue(actual)) <= tolerance)
    }
    private fun red(argb: Int) = (argb ushr 16) and 255
    private fun green(argb: Int) = (argb ushr 8) and 255
    private fun blue(argb: Int) = argb and 255
}

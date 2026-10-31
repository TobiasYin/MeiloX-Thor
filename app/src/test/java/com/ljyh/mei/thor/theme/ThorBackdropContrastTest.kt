package com.ljyh.mei.thor.theme

import com.ljyh.mei.thor.artwork.thorArtworkLuminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThorBackdropContrastTest {
    private val dayInks = listOf(
        intArrayOf(0xFF202B28.toInt(), 0xFF526059.toInt()),
        intArrayOf(0xFF202D43.toInt(), 0xFF4D5D75.toInt()),
        intArrayOf(0xFF2C2A25.toInt(), 0xFF58554C.toInt()),
    )
    private val nightInks = listOf(
        intArrayOf(0xFFEEF3EB.toInt(), 0xFFB8C5B8.toInt()),
        intArrayOf(0xFFEDF2FF.toInt(), 0xFFBCC9E3.toInt()),
        intArrayOf(0xFFF2EFE5.toInt(), 0xFFC4C2B4.toInt()),
    )
    private val coverColors = intArrayOf(0xFFFF2800.toInt(), 0xFF164BFF.toInt(), 0xFF43E69B.toInt(),
        0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF8B7056.toInt())

    @Test fun everyMaterialActualReadingInkHasContrastHeadroomAgainstCoverColorsInBothAppearances() {
        for ((dark, palettes) in listOf(false to dayInks, true to nightInks)) for (ink in palettes) {
            val profile = thorBackdropContrast(dark, ink)
            assertTrue(profile.luminanceLimit.isFinite())
            for (cover in coverColors) {
                val environment = readableThorBackdropColor(cover, profile)
                assertEquals(255, environment ushr 24)
                for (foreground in ink) assertTrue("Reading ink needs margin above 4.5:1 after encoding",
                    contrast(foreground, environment) >= 4.6)
            }
        }
    }

    @Test fun alreadyReadableColorsRemainUnchangedAndToneProtectionDoesNotReplaceTheHue() {
        val day = thorBackdropContrast(false, dayInks.first())
        val night = thorBackdropContrast(true, nightInks.first())
        assertEquals(0xFFFFFFFF.toInt(), readableThorBackdropColor(0xFFFFFFFF.toInt(), day))
        assertEquals(0xFF000000.toInt(), readableThorBackdropColor(0xFF000000.toInt(), night))
        for (profile in listOf(day, night)) {
            val red = readableThorBackdropColor(0xFFFF0000.toInt(), profile)
            assertTrue(((red ushr 16) and 255) > ((red ushr 8) and 255))
            val blue = readableThorBackdropColor(0xFF0000FF.toInt(), profile)
            assertTrue((blue and 255) > ((blue ushr 16) and 255))
        }
    }

    @Test fun secondaryInkDeterminesTheSafeLimitRatherThanAssumingOnlyLargeCurrentLyrics() {
        for ((dark, palettes) in listOf(false to dayInks, true to nightInks)) for (ink in palettes) {
            val primaryOnly = thorBackdropContrast(dark, intArrayOf(ink[0]))
            val reading = thorBackdropContrast(dark, ink)
            if (dark) assertTrue(reading.luminanceLimit < primaryOnly.luminanceLimit)
            else assertTrue(reading.luminanceLimit > primaryOnly.luminanceLimit)
        }
    }

    private fun contrast(a: Int, b: Int): Double {
        val first = thorArtworkLuminance(a)
        val second = thorArtworkLuminance(b)
        return (maxOf(first, second) + .05) / (minOf(first, second) + .05)
    }
}

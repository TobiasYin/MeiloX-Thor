package com.ljyh.mei.playback

import com.ljyh.mei.constants.MusicQuality
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerQualityPreferenceTest {
    @Test fun persistedEnumNamesAndApiTextSelectTheSameGlobalTargetForEveryQuality() {
        for (quality in MusicQuality.entries) {
            assertEquals(quality, requestedMusicQualityPreference(quality.name))
            assertEquals(quality, requestedMusicQualityPreference(quality.text))
            assertEquals(quality, requestedMusicQualityPreference("  ${quality.name}  "))
        }
    }

    @Test fun absentBlankOrFuturePreferencesUseTheNormalExhighTarget() {
        for (raw in listOf(null, "", "   ", "future-format", "not-a-quality")) {
            assertEquals(MusicQuality.EXHIGH, requestedMusicQualityPreference(raw))
        }
    }

    @Test fun requestedQualityDoesNotDependOnTheDevicesLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(MusicQuality.HIRES, requestedMusicQualityPreference("HIRES"))
            assertEquals(MusicQuality.JYEFFECT, requestedMusicQualityPreference("JYEFFECT"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}

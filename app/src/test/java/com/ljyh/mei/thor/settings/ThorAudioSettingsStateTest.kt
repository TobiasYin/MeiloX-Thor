package com.ljyh.mei.thor.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.ljyh.mei.constants.*
import com.ljyh.mei.playback.EqualizerPreset
import org.junit.Assert.*
import org.junit.Test

class ThorAudioSettingsStateTest {
    @Test fun absentPreferencesMatchTheActualServiceDefaultsWithoutPersistingAnything() {
        val p = emptyPreferences()
        val s = decodeThorAudioSettings(p)
        assertTrue(s.loaded)
        assertEquals(AudioOutputMode.AudioTrack, s.output)
        assertEquals(MusicQuality.EXHIGH, s.quality)
        assertEquals(8f, s.mixDuration, 0f)
        assertEquals(8f, s.maximumTempo, 0f)
        assertEquals(0, s.tailCutBars)
        assertFalse(s.autoMix)
        assertFalse(s.equalizer)
        assertTrue(p.asMap().isEmpty())
    }

    @Test fun existingKeysAndCaseInsensitiveEnumsAreReused() {
        val p = preferencesOf(MusicQualityKey to "lossless", AudioOutputModeKey to "AAudio",
            NotificationLeftActionKey to "FloatingLyrics", AutoMixModeKey to "Fixed",
            PreviousPlaybackKey to false, RestorePlaybackStateKey to false)
        val s = decodeThorAudioSettings(p)
        assertEquals(MusicQuality.LOSSLESS, s.quality)
        assertEquals(AudioOutputMode.AAudio, s.output)
        assertEquals(NotificationAction.FloatingLyrics, s.notificationLeft)
        assertEquals(AutoMixMode.Fixed, s.mixMode)
        assertFalse(s.previousDirect)
        assertFalse(s.restoreState)
    }

    @Test fun unsupportedUsbIsNotSelectableBelowAndroidFourteenButStoredChoiceRemainsVisible() {
        assertFalse(AudioOutputMode.UsbBitPerfect in supportedThorAudioOutputs(33))
        assertTrue(AudioOutputMode.UsbBitPerfect in supportedThorAudioOutputs(34))
        val s = decodeThorAudioSettings(preferencesOf(AudioOutputModeKey to "UsbBitPerfect",
            AutoMixEnabledKey to true, EqualizerEnabledKey to true))
        assertEquals(AudioOutputMode.UsbBitPerfect, s.output)
        assertFalse(s.processingAvailable)
        assertTrue(s.autoMix)
        assertTrue(s.equalizer)
    }

    @Test fun malformedOrNonFiniteGainTuplesUseACompletePresetInsteadOfShiftingBands() {
        val preset = EqualizerPreset.BassBoost
        for (value in listOf("1,2", "1,2,3,4,5,6,7,8,9,NaN", "1,2,3,4,5,6,7,8,9,Infinity",
            "1,2,3,4,5,6,7,8,9,invalid")) {
            assertEquals(preset.gains, decodeThorEqualizerGains(value, preset))
        }
        assertEquals(EqualizerPreset.Flat.gains, decodeThorEqualizerGains(null, EqualizerPreset.Custom))
    }

    @Test fun validBandValuesAreBoundedAndCustomValuesSurviveProjection() {
        val values = listOf(-20f, -10f, -8f, -6f, -4f, 0f, 2f, 4f, 8f, 20f)
        val s = decodeThorAudioSettings(preferencesOf(EqualizerPresetKey to "Custom",
            EqualizerPreampKey to 100f, EqualizerBandGainsKey to values.joinToString(",")))
        assertEquals(EqualizerPreset.Custom, s.preset)
        assertEquals(6f, s.preamp, 0f)
        assertEquals(values.map { it.coerceIn(-12f, 12f) }, s.bandGains)
    }

    @Test fun nonFiniteNumbersAndInvalidEnumsCannotReachSliders() {
        val s = decodeThorAudioSettings(preferencesOf(AutoMixDurationKey to Float.NaN,
            AutoMixMaxTempoAdjustmentKey to Float.POSITIVE_INFINITY, EqualizerPreampKey to Float.NEGATIVE_INFINITY,
            AudioOutputModeKey to "unknown", EqualizerPresetKey to "unknown",
            AutoMixTransitionBarsKey to 999, AutoMixTailCutBarsKey to -9))
        assertEquals(8f, s.mixDuration, 0f)
        assertEquals(8f, s.maximumTempo, 0f)
        assertEquals(0f, s.preamp, 0f)
        assertEquals(AudioOutputMode.AudioTrack, s.output)
        assertEquals(16, s.transitionBars)
        assertEquals(0, s.tailCutBars)
        assertTrue(s.bandGains.all { it.isFinite() })
    }
}

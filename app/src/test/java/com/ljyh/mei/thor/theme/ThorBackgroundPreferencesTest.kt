package com.ljyh.mei.thor.theme

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ljyh.mei.thor.model.ThorAppearance
import com.ljyh.mei.thor.model.ThorBackgroundIntensity
import com.ljyh.mei.thor.model.ThorBackgroundMode
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.thor.model.ThorThemePack
import org.junit.Assert.assertEquals
import org.junit.Test

class ThorBackgroundPreferencesTest {
    // These literals are the saved-data contract, deliberately independent of production constants.
    private val modeKey = stringPreferencesKey("thor.background.mode")
    private val intensityKey = stringPreferencesKey("thor.background.intensity")
    private val themeKey = stringPreferencesKey("thor.theme")
    private val appearanceKey = stringPreferencesKey("application.appearance")
    private val reduceMotionKey = booleanPreferencesKey("thor.reduceMotion")
    private val reduceTransparencyKey = booleanPreferencesKey("appearance.reduceTransparency")
    private val airflowKey = booleanPreferencesKey("thor.airflowEnabled")

    private val modes = listOf(
        "ArtworkFlow" to ThorBackgroundMode.ArtworkFlow,
        "SoftGradient" to ThorBackgroundMode.SoftGradient,
        "Clean" to ThorBackgroundMode.Clean,
    )
    private val intensities = listOf(
        "Low" to ThorBackgroundIntensity.Low,
        "Medium" to ThorBackgroundIntensity.Medium,
        "High" to ThorBackgroundIntensity.High,
    )

    @Test fun missingFieldsDefaultToArtworkFlowAndMediumWithoutChangingExistingPreferences() {
        assertEquals(ThorPreferences(), decodeThorPreferences(emptyPreferences()))
        assertEquals(
            ThorPreferences(theme = ThorThemePack.Glass, appearance = ThorAppearance.Night),
            decodeThorPreferences(mutablePreferencesOf(themeKey to "Glass", appearanceKey to "Dark")),
        )
    }

    @Test fun unknownFieldsFallbackIndependentlyWithoutDiscardingTheOtherValidSelection() {
        for (invalid in listOf("", "future-option", "clean")) {
            assertEquals(
                ThorPreferences(),
                decodeThorPreferences(mutablePreferencesOf(modeKey to invalid, intensityKey to invalid)),
            )
            assertEquals(
                ThorPreferences(backgroundIntensity = ThorBackgroundIntensity.High),
                decodeThorPreferences(mutablePreferencesOf(modeKey to invalid, intensityKey to "High")),
            )
            assertEquals(
                ThorPreferences(backgroundMode = ThorBackgroundMode.Clean),
                decodeThorPreferences(mutablePreferencesOf(modeKey to "Clean", intensityKey to invalid)),
            )
        }
    }

    @Test fun eitherBackgroundKeyCanBeStoredWithoutTheOther() {
        for ((stored, mode) in modes) {
            assertEquals(
                ThorPreferences(backgroundMode = mode),
                decodeThorPreferences(mutablePreferencesOf(modeKey to stored)),
            )
        }
        for ((stored, intensity) in intensities) {
            assertEquals(
                ThorPreferences(backgroundIntensity = intensity),
                decodeThorPreferences(mutablePreferencesOf(intensityKey to stored)),
            )
        }
    }

    @Test fun everyPersistedModeAndIntensityCombinationRestoresIndependently() {
        for ((storedMode, mode) in modes) for ((storedIntensity, intensity) in intensities) {
            assertEquals(
                "$storedMode / $storedIntensity",
                ThorPreferences(backgroundMode = mode, backgroundIntensity = intensity),
                decodeThorPreferences(mutablePreferencesOf(modeKey to storedMode, intensityKey to storedIntensity)),
            )
        }
    }

    @Test fun cleanRetainsHighIntensityEvenWhenBothAccessibilityOverridesAreEnabled() {
        val actual = decodeThorPreferences(mutablePreferencesOf(
            modeKey to "Clean",
            intensityKey to "High",
            reduceMotionKey to true,
            reduceTransparencyKey to true,
        ))
        assertEquals(
            ThorPreferences(
                reduceMotion = true,
                reduceTransparency = true,
                backgroundMode = ThorBackgroundMode.Clean,
                backgroundIntensity = ThorBackgroundIntensity.High,
            ),
            actual,
        )
    }

    @Test fun backgroundCoexistsWithMaterialAppAppearanceAndAllExistingEffectFlags() {
        val packs = listOf("Ceramic" to ThorThemePack.Ceramic, "Glass" to ThorThemePack.Glass, "Titanium" to ThorThemePack.Titanium)
        val appearances = listOf("System" to ThorAppearance.System, "Light" to ThorAppearance.Day, "Dark" to ThorAppearance.Night)
        for ((storedPack, pack) in packs) for ((storedAppearance, appearance) in appearances) {
            for (mask in 0 until 8) {
                val motion = mask and 1 != 0
                val transparency = mask and 2 != 0
                val airflow = mask and 4 != 0
                val actual = decodeThorPreferences(mutablePreferencesOf(
                    themeKey to storedPack,
                    appearanceKey to storedAppearance,
                    reduceMotionKey to motion,
                    reduceTransparencyKey to transparency,
                    airflowKey to airflow,
                    modeKey to "SoftGradient",
                    intensityKey to "Low",
                    // Similarly named keys must not supersede the existing application-wide keys.
                    stringPreferencesKey("thor.appearance") to "Dark",
                    booleanPreferencesKey("thor.reduceTransparency") to !transparency,
                ))
                assertEquals(
                    "$storedPack / $storedAppearance / flags=$mask",
                    ThorPreferences(
                        theme = pack,
                        appearance = appearance,
                        reduceMotion = motion,
                        reduceTransparency = transparency,
                        airflowEnabled = airflow,
                        backgroundMode = ThorBackgroundMode.SoftGradient,
                        backgroundIntensity = ThorBackgroundIntensity.Low,
                    ),
                    actual,
                )
            }
        }
    }
}

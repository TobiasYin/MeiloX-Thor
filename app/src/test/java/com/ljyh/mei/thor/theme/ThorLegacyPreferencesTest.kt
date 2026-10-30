package com.ljyh.mei.thor.theme

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ljyh.mei.thor.model.ThorAppearance
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.thor.model.ThorThemePack
import org.junit.Assert.assertEquals
import org.junit.Test

class ThorLegacyPreferencesTest {
    // Literal saved keys/values are deliberate: changing a production constant must not make a
    // silent storage-format change pass merely because the test imports that same new constant.
    private val theme = stringPreferencesKey("thor.theme")
    private val appearance = stringPreferencesKey("application.appearance")
    private val reduceMotion = booleanPreferencesKey("thor.reduceMotion")
    private val reduceTransparency = booleanPreferencesKey("appearance.reduceTransparency")
    private val airflow = booleanPreferencesKey("thor.airflowEnabled")

    @Test
    fun persistedPacksAndAppearanceModesRestoreAsIndependentDimensions() {
        val packs = listOf("Ceramic" to ThorThemePack.Ceramic, "Glass" to ThorThemePack.Glass, "Titanium" to ThorThemePack.Titanium)
        val modes = listOf("System" to ThorAppearance.System, "Light" to ThorAppearance.Day, "Dark" to ThorAppearance.Night)
        for ((storedPack, pack) in packs) for ((storedMode, mode) in modes) {
            val stored = mutablePreferencesOf(
                theme to storedPack,
                appearance to storedMode,
                // The application-wide appearance key remains authoritative for compatibility pages.
                stringPreferencesKey("thor.appearance") to "Dark",
            )
            assertEquals("$storedPack / $storedMode", ThorPreferences(theme = pack, appearance = mode), decodeThorPreferences(stored))
        }
    }

    @Test
    fun missingAndInvalidSelectionsFallbackWithoutResettingTheOtherDimension() {
        assertEquals(ThorPreferences(), decodeThorPreferences(emptyPreferences()))
        assertEquals(
            ThorPreferences(theme = ThorThemePack.Ceramic, appearance = ThorAppearance.Night),
            decodeThorPreferences(mutablePreferencesOf(theme to "future-pack", appearance to "Dark")),
        )
        assertEquals(
            ThorPreferences(theme = ThorThemePack.Titanium, appearance = ThorAppearance.System),
            decodeThorPreferences(mutablePreferencesOf(theme to "Titanium", appearance to "future-mode")),
        )
        assertEquals(
            ThorPreferences(),
            decodeThorPreferences(mutablePreferencesOf(theme to "", appearance to "")),
        )
    }

    @Test
    fun motionTransparencyAndAirflowRestoreIndependentlyFromTheirExistingKeys() {
        for (mask in 0 until 8) {
            val motion = mask and 1 != 0
            val transparency = mask and 2 != 0
            val air = mask and 4 != 0
            val stored = mutablePreferencesOf(
                theme to "Glass",
                appearance to "Light",
                reduceMotion to motion,
                reduceTransparency to transparency,
                airflow to air,
                // A similarly named Thor-only key must not override the shared transparency key.
                booleanPreferencesKey("thor.reduceTransparency") to !transparency,
            )
            assertEquals("flags=$mask", ThorPreferences(
                theme = ThorThemePack.Glass,
                appearance = ThorAppearance.Day,
                reduceMotion = motion,
                reduceTransparency = transparency,
                airflowEnabled = air,
            ), decodeThorPreferences(stored))
        }
    }
}

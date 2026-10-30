package com.ljyh.mei.thor.audio

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import com.ljyh.mei.utils.dataStore
import kotlinx.coroutines.flow.first

internal class ThorAirflowCalibrationStore(context: Context) {
    private val store = context.applicationContext.dataStore

    suspend fun load(): ThorAirflowCalibration? {
        val preferences = store.data.first()
        return storedAirflowCalibration(preferences[NOISE], preferences[GENTLE], preferences[STRONG])
    }

    suspend fun save(value: ThorAirflowCalibration) {
        if (!value.isValid) return
        // All three values are one transaction; a partial tuple is never treated as calibrated.
        store.edit { preferences ->
            preferences[NOISE] = value.noiseFloorRms
            preferences[GENTLE] = value.gentleRms
            preferences[STRONG] = value.strongRms
        }
    }

    private companion object {
        val NOISE = floatPreferencesKey("thor.airflow.noise_rms")
        val GENTLE = floatPreferencesKey("thor.airflow.gentle_rms")
        val STRONG = floatPreferencesKey("thor.airflow.strong_rms")
    }
}

internal fun storedAirflowCalibration(noise: Float?, gentle: Float?, strong: Float?): ThorAirflowCalibration? {
    if (noise == null || gentle == null || strong == null) return null
    return ThorAirflowCalibration(noise, gentle, strong).takeIf { it.isValid }
}

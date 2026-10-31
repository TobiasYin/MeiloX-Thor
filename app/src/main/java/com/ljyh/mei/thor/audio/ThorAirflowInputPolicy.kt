package com.ljyh.mei.thor.audio

/** Android source constants kept as integers so this hardware policy is testable on the JVM. */
internal fun isThorMicrophoneHardware(manufacturer: String, model: String): Boolean =
    manufacturer.trim().equals("AYN", ignoreCase = true) && model.trim().equals("AYN Thor", ignoreCase = true)

/**
 * Physical Thor testing found UNPROCESSED (9) unresponsive while MIC (1) captured breath.
 * These are initialization fallbacks only: quiet or low-amplitude audio never changes source.
 */
internal fun airflowSourceCandidates(manufacturer: String, model: String, supportsUnprocessed: Boolean): List<Int> = when {
    isThorMicrophoneHardware(manufacturer, model) -> listOf(1, 6, 9)
    supportsUnprocessed -> listOf(9, 6, 1)
    else -> listOf(6, 1)
}

/** A partially written scoped tuple must not resurrect an unrelated older calibration. */
internal fun storedSourceAirflowCalibration(
    source: Int,
    noise: Float?,
    gentle: Float?,
    strong: Float?,
    legacyNoise: Float? = null,
    legacyGentle: Float? = null,
    legacyStrong: Float? = null,
): ThorAirflowCalibration? = when {
    noise != null || gentle != null || strong != null -> storedAirflowCalibration(noise, gentle, strong)
    // The old unlabelled values came from the original UNPROCESSED path. Never copy to MIC/VR.
    source == 9 -> storedAirflowCalibration(legacyNoise, legacyGentle, legacyStrong)
    else -> null
}

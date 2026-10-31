package com.ljyh.mei.thor.audio

/** Temporary capture gates never edit the user's persistent airflow preference. */
internal data class ThorAirflowCapturePolicy(
    val enabled: Boolean,
    val hasPermission: Boolean,
    val resumed: Boolean,
    val legacyActive: Boolean,
    val playbackConnected: Boolean,
    val playing: Boolean,
) {
    val shouldCapture: Boolean get() = enabled && hasPermission && resumed && !legacyActive && playbackConnected && !playing
    val pausedForPlayback: Boolean get() = enabled && playing
}

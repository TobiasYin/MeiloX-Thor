package com.ljyh.mei.thor.settings

import androidx.datastore.preferences.core.Preferences
import com.ljyh.mei.constants.*
import com.ljyh.mei.playback.EqualizerBand
import com.ljyh.mei.playback.EqualizerPreset
import com.ljyh.mei.utils.toEnum

/** Only audio preferences enter this projection; account/session preferences stay in their owner. */
data class ThorAudioSettingsState(
    val loaded: Boolean = false,
    val loopPlayback: Boolean = true,
    val concurrentPlayback: Boolean = false,
    val cloudShuffle: Boolean = true,
    val skipUnavailable: Boolean = true,
    val previousDirect: Boolean = true,
    val restorePosition: Boolean = true,
    val restoreState: Boolean = true,
    val quality: MusicQuality = MusicQuality.EXHIGH,
    val output: AudioOutputMode = AudioOutputMode.AudioTrack,
    val notificationLeft: NotificationAction = NotificationAction.PlayMode,
    val notificationRight: NotificationAction = NotificationAction.Like,
    val autoMix: Boolean = false,
    val mixMode: AutoMixMode = AutoMixMode.Smart,
    val mixDuration: Float = 8f,
    val fadeCurve: AutoMixFadeCurve = AutoMixFadeCurve.EqualPower,
    val tempoMatching: Boolean = true,
    val maximumTempo: Float = 8f,
    val transitionBars: Int = 8,
    val tailCutBars: Int = 0,
    val equalizer: Boolean = false,
    val preset: EqualizerPreset = EqualizerPreset.Flat,
    val preamp: Float = 0f,
    val bandGains: List<Float> = EqualizerPreset.Flat.gains,
) {
    val processingAvailable get() = output != AudioOutputMode.UsbBitPerfect
}

internal fun decodeThorAudioSettings(p: Preferences): ThorAudioSettingsState {
    val preset = p[EqualizerPresetKey].toEnum(EqualizerPreset.Flat)
    return ThorAudioSettingsState(
        loaded = true,
        loopPlayback = p[LoopPlaybackKey] ?: true,
        concurrentPlayback = p[AllowConcurrentPlaybackKey] ?: false,
        cloudShuffle = p[CloudShuffleEnabledKey] ?: true,
        skipUnavailable = p[NoAudioSourceKey] ?: true,
        previousDirect = p[PreviousPlaybackKey] ?: true,
        restorePosition = p[RestorePlaybackPositionKey] ?: true,
        restoreState = p[RestorePlaybackStateKey] ?: true,
        quality = p[MusicQualityKey].toEnum(MusicQuality.EXHIGH),
        output = p[AudioOutputModeKey].toEnum(AudioOutputMode.AudioTrack),
        notificationLeft = NotificationAction.fromPreference(p[NotificationLeftActionKey], NotificationAction.PlayMode),
        notificationRight = NotificationAction.fromPreference(p[NotificationRightActionKey], NotificationAction.Like),
        autoMix = p[AutoMixEnabledKey] ?: false,
        mixMode = p[AutoMixModeKey].toEnum(AutoMixMode.Smart),
        mixDuration = finiteAudioValue(p[AutoMixDurationKey], 8f, 3f..20f),
        fadeCurve = p[AutoMixFadeCurveKey].toEnum(AutoMixFadeCurve.EqualPower),
        tempoMatching = p[AutoMixTempoMatchingKey] ?: true,
        // Match AutoMixController's effective defaults, without writing on page entry.
        maximumTempo = finiteAudioValue(p[AutoMixMaxTempoAdjustmentKey], 8f, 0f..8f),
        transitionBars = (p[AutoMixTransitionBarsKey] ?: 8).coerceIn(4, 16),
        tailCutBars = (p[AutoMixTailCutBarsKey] ?: 0).coerceIn(0, 8),
        equalizer = p[EqualizerEnabledKey] ?: false,
        preset = preset,
        preamp = finiteAudioValue(p[EqualizerPreampKey], preset.preamp, -12f..6f),
        bandGains = decodeThorEqualizerGains(p[EqualizerBandGainsKey], preset),
    )
}

internal fun finiteAudioValue(value: Float?, fallback: Float, range: ClosedFloatingPointRange<Float>): Float =
    (value?.takeIf { it.isFinite() } ?: fallback).coerceIn(range)

internal fun decodeThorEqualizerGains(serialized: String?, preset: EqualizerPreset): List<Float> {
    val parsed = serialized?.split(',')?.map { it.toFloatOrNull() }
        ?.takeIf { it.size == EqualizerBand.entries.size && it.all { value -> value != null && value.isFinite() } }
        ?.map { checkNotNull(it).coerceIn(-12f, 12f) }
    return parsed ?: preset.gains.takeIf { it.size == EqualizerBand.entries.size } ?: EqualizerPreset.Flat.gains
}

enum class ThorAudioToggle(internal val key: Preferences.Key<Boolean>, internal val default: Boolean) {
    Loop(LoopPlaybackKey, true), Concurrent(AllowConcurrentPlaybackKey, false),
    CloudShuffle(CloudShuffleEnabledKey, true), SkipUnavailable(NoAudioSourceKey, true),
    PreviousDirect(PreviousPlaybackKey, true), RestorePosition(RestorePlaybackPositionKey, true),
    RestoreState(RestorePlaybackStateKey, true), AutoMix(AutoMixEnabledKey, false),
    TempoMatching(AutoMixTempoMatchingKey, true), Equalizer(EqualizerEnabledKey, false),
}

enum class ThorAudioChoice {
    Quality, Output, NotificationLeft, NotificationRight, MixMode, FadeCurve, TransitionBars, TailCutBars, Preset,
}

data class ThorAudioNumber(
    val id: String,
    val title: String,
    val minimum: Float,
    val maximum: Float,
    val step: Float,
    val unit: String,
    val band: Int? = null,
) {
    companion object {
        val MixDuration = ThorAudioNumber("duration", "过渡时长", 3f, 20f, 1f, "秒")
        val MaximumTempo = ThorAudioNumber("tempo", "最大节拍调整", 1f, 8f, 1f, "%")
        val Preamp = ThorAudioNumber("preamp", "前级增益", -12f, 6f, .5f, "dB")
        fun band(index: Int) = ThorAudioNumber("band:$index", EqualizerBand.entries[index].title, -12f, 12f, .5f, "dB", index)
    }
}

internal fun supportedThorAudioOutputs(api: Int): List<AudioOutputMode> = AudioOutputMode.entries
    .filter { it != AudioOutputMode.UsbBitPerfect || api >= 34 }

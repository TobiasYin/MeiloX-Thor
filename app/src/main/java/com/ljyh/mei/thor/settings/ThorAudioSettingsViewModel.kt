package com.ljyh.mei.thor.settings

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.constants.*
import com.ljyh.mei.playback.EqualizerPreset
import com.ljyh.mei.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Writes existing settings only; the existing MusicService owns DSP/output and playback. */
@HiltViewModel
class ThorAudioSettingsViewModel @Inject constructor(@ApplicationContext context: Context) : ViewModel() {
    private val store = context.applicationContext.dataStore
    private val mutableError = MutableStateFlow<String?>(null)
    private val retryGeneration = MutableStateFlow(0)
    val error = mutableError.asStateFlow()
    @OptIn(ExperimentalCoroutinesApi::class)
    val state = retryGeneration.flatMapLatest { store.data.map(::decodeThorAudioSettings)
        .catch { error ->
            if (error is CancellationException) throw error
            mutableError.value = "无法读取音频设置，请重试"
            emit(ThorAudioSettingsState())
        } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThorAudioSettingsState())

    fun retry() { mutableError.value = null; retryGeneration.value++ }

    private fun write(edit: suspend () -> Unit) {
        viewModelScope.launch {
            try { edit(); mutableError.value = null }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableError.value = "设置未保存，请重试" }
        }
    }

    fun toggle(option: ThorAudioToggle) = write {
        store.edit { p -> p[option.key] = !(p[option.key] ?: option.default) }
    }

    /** Quality while connected must instead use PlayerConnection.changeQuality(..., false). */
    fun setQualityWithoutPlayer(quality: MusicQuality) = write { store.edit { it[MusicQualityKey] = quality.name } }

    fun setChoice(option: ThorAudioChoice, value: String) = write {
        store.edit { p ->
            when (option) {
                ThorAudioChoice.Quality -> p[MusicQualityKey] = MusicQuality.valueOf(value).name
                ThorAudioChoice.Output -> {
                    val output = AudioOutputMode.valueOf(value)
                    require(output in supportedThorAudioOutputs(Build.VERSION.SDK_INT))
                    p[AudioOutputModeKey] = output.name
                }
                ThorAudioChoice.NotificationLeft -> p[NotificationLeftActionKey] = NotificationAction.valueOf(value).name
                ThorAudioChoice.NotificationRight -> p[NotificationRightActionKey] = NotificationAction.valueOf(value).name
                ThorAudioChoice.MixMode -> p[AutoMixModeKey] = AutoMixMode.valueOf(value).name
                ThorAudioChoice.FadeCurve -> p[AutoMixFadeCurveKey] = AutoMixFadeCurve.valueOf(value).name
                ThorAudioChoice.TransitionBars -> { val n = value.toInt(); require(n in listOf(4, 8, 16)); p[AutoMixTransitionBarsKey] = n }
                ThorAudioChoice.TailCutBars -> { val n = value.toInt(); require(n in listOf(0, 2, 4, 8)); p[AutoMixTailCutBarsKey] = n }
                ThorAudioChoice.Preset -> {
                    val preset = EqualizerPreset.valueOf(value)
                    p[EqualizerPresetKey] = preset.name
                    if (preset != EqualizerPreset.Custom) {
                        p[EqualizerPreampKey] = preset.preamp
                        p[EqualizerBandGainsKey] = preset.gains.joinToString(",")
                    }
                }
            }
        }
    }

    fun setNumber(option: ThorAudioNumber, requested: Float) {
        if (!requested.isFinite()) return
        val value = requested.coerceIn(option.minimum, option.maximum)
        write {
            store.edit { p ->
                when (option.id) {
                    ThorAudioNumber.MixDuration.id -> p[AutoMixDurationKey] = value.coerceIn(3f, 20f)
                    ThorAudioNumber.MaximumTempo.id -> p[AutoMixMaxTempoAdjustmentKey] = value.coerceIn(1f, 8f)
                    ThorAudioNumber.Preamp.id -> { p[EqualizerPresetKey] = EqualizerPreset.Custom.name; p[EqualizerPreampKey] = value.coerceIn(-12f, 6f) }
                    else -> {
                        val index = option.band ?: return@edit
                        val current = decodeThorAudioSettings(p)
                        if (index !in current.bandGains.indices) return@edit
                        val next = current.bandGains.toMutableList().apply { this[index] = value.coerceIn(-12f, 12f) }
                        p[EqualizerPresetKey] = EqualizerPreset.Custom.name
                        p[EqualizerBandGainsKey] = next.joinToString(",")
                    }
                }
            }
        }
    }
}

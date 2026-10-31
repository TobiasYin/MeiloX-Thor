package com.ljyh.mei.thor.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit physical-input diagnostic, never a CI breath-detection acceptance test.
 * Enable with thor.airflowProbe=true; sources default to 9,6,1, phase length to 8000 ms,
 * and sample rate to 16000 Hz. Optional thor.airflowProbeSources=6 (or comma separated),
 * thor.airflowProbePhaseMs=8000..10000 and thor.airflowProbeSampleRate=48000.
 * The foreground test Activity pauses ThorActivity before acquiring its own recorder.
 * Only scalar statistics reach logcat/cache; the reusable PCM buffer is wiped on release.
 */
@RunWith(AndroidJUnit4::class)
class ThorAirflowProbeInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val prompt = mutableStateOf("准备麦克风对比，请保持安静")

    @Test
    fun comparePhysicalMicrophoneSourcesWithVisiblePrompts() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Physical microphone probe requires explicit opt-in", args.getString("thor.airflowProbe") == "true")
        val context = instrumentation.targetContext
        assumeTrue("Grant microphone permission before the explicit probe",
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val audio = context.getSystemService(AudioManager::class.java)
        val builtIn = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        assumeTrue("A built-in microphone is required for this comparison", builtIn != null)
        val sources = (args.getString("thor.airflowProbeSources") ?: "9,6,1").split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in SOURCES }.distinct()
        assertTrue("Choose at least one supported probe source: 9,6,1", sources.isNotEmpty())
        val sampleRate = args.getString("thor.airflowProbeSampleRate")?.toIntOrNull() ?: 16_000
        assertTrue("Probe sample rate must be 16000 or 48000", sampleRate in listOf(16_000, 48_000))
        val phaseMs = (args.getString("thor.airflowProbePhaseMs")?.toLongOrNull() ?: 8_000L)
            .coerceIn(8_000L, 10_000L)
        compose.setContent {
            val view = LocalView.current
            SideEffect { view.keepScreenOn = true }
            Column(Modifier.fillMaxSize().background(Color(0xff15191f)).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                BasicText("麦克风输入源对比", style = TextStyle(color = Color.White, fontSize = 28.sp))
                BasicText(prompt.value, style = TextStyle(color = Color.White, fontSize = 32.sp))
                BasicText("只记录幅度数值，不保存录音。请按提示吹 2–3 秒，随后保持安静。",
                    style = TextStyle(color = Color(0xffc5cbd3), fontSize = 20.sp))
            }
        }
        // setContent/waitForIdle also ensure ThorActivity.onPause stopped the production source.
        compose.waitForIdle()
        SystemClock.sleep(600)
        val report = JSONObject().put("sampleRate", sampleRate).put("phaseMs", phaseMs)
            .put("builtInCount", audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .count { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC })
            .put("supportsUnprocessed", audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
        val captures = JSONArray()
        report.put("captures", captures)
        var frameCount = 0L
        try {
            sources.forEachIndexed { index, source ->
                show("${index + 1}/${sources.size} · 输入源 $source\n准备中，请保持安静")
                SystemClock.sleep(1_000)
                val capture = captureSource(audio, checkNotNull(builtIn), source, sampleRate, phaseMs)
                captures.put(capture)
                frameCount += capture.optLong("frames")
                if (index < sources.lastIndex) {
                    show("本组结束，请保持安静\n即将比较下一输入源")
                    SystemClock.sleep(2_000)
                }
            }
        } finally {
            File(context.cacheDir, "thor-airflow-probe.json").writeText(report.toString(2))
            show("对比完成\n已保存幅度统计，没有保存录音")
        }
        // Passing means capture completed, not that physical breath was detected.
        assertTrue("No requested input source returned samples; inspect scalar probe results", frameCount > 0)
    }

    private fun captureSource(audio: AudioManager, builtIn: AudioDeviceInfo, source: Int,
        sampleRate: Int, phaseMs: Long): JSONObject {
        val result = JSONObject().put("source", source).put("frames", 0)
        val phases = JSONArray()
        result.put("phases", phases)
        val samples = ShortArray(sampleRate / 25)
        var record: AudioRecord? = null
        // Error codes are deliberately local integers; no exception text or device identity.
        var failureStage = 1
        try {
            val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            result.put("minimumBufferBytes", minimum)
            if (minimum <= 0) return result.put("errorStage", failureStage)
            val current = AudioRecord.Builder().setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum * 2, samples.size * 4)).build()
            record = current
            failureStage = 2
            result.put("initialized", current.state == AudioRecord.STATE_INITIALIZED)
            if (current.state != AudioRecord.STATE_INITIALIZED) return result.put("errorStage", failureStage)
            result.put("preferredAccepted", current.setPreferredDevice(builtIn))
            failureStage = 3
            current.startRecording()
            result.put("recording", current.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            if (current.recordingState != AudioRecord.RECORDSTATE_RECORDING) return result.put("errorStage", failureStage)
            failureStage = 4
            for ((phase, text) in listOf("quiet" to "安静：请不要吹气", "gentle" to "轻吹：请吹 2–3 秒", "strong" to "强吹：请吹 2–3 秒")) {
                show("输入源 $source · ${phaseMs / 1_000} 秒\n$text")
                Log.i(TAG, "phase_start source=$source phase=$phase durationMs=$phaseMs")
                val stats = samplePhase(current, audio, samples, phaseMs, source, phase)
                phases.put(stats.put("phase", phase).put("activeMicrophones", microphoneNumbers(current)))
                result.put("frames", result.getLong("frames") + stats.getLong("frames"))
                result.put("routeType", current.routedDevice?.type ?: 0)
                    .put("preferredMatchesActual", current.routedDevice?.id == builtIn.id)
                Log.i(TAG, "phase_result source=$source $stats")
            }
            return result.put("errorStage", 0)
        } catch (_: Exception) {
            return result.put("errorStage", failureStage)
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            samples.fill(0)
            Log.i(TAG, "source_result $result")
        }
    }

    private fun samplePhase(record: AudioRecord, audio: AudioManager, samples: ShortArray,
        durationMs: Long, source: Int, phase: String): JSONObject {
        val started = SystemClock.elapsedRealtime()
        val rms = ArrayList<Double>()
        var zeroReads = 0
        var negativeReads = 0
        var lastReadError = 0
        var sampleCount = 0L
        var peak = 0.0
        var dcSum = 0.0
        var maximumDc = 0.0
        var silenceFrames = 0
        var unknownConfigurationFrames = 0
        var mutedFrames = 0
        var lastLogMs = started
        while (SystemClock.elapsedRealtime() - started < durationMs) {
            val count = record.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
            if (count < 0) { negativeReads++; lastReadError = count; break }
            if (count == 0) { zeroReads++; SystemClock.sleep(10); continue }
            var mean = 0.0
            for (i in 0 until count) mean += samples[i].toDouble() / 32768.0
            mean /= count
            var energy = 0.0
            for (i in 0 until count) {
                val centered = samples[i].toDouble() / 32768.0 - mean
                energy += centered * centered
                peak = maxOf(peak, abs(centered))
            }
            rms += sqrt(energy / count)
            sampleCount += count
            dcSum += abs(mean)
            maximumDc = maxOf(maximumDc, abs(mean))
            val configuration = record.activeRecordingConfiguration
            if (configuration == null) unknownConfigurationFrames++
            if (configuration?.isClientSilenced == true) silenceFrames++
            if (audio.isMicrophoneMute) mutedFrames++
            val now = SystemClock.elapsedRealtime()
            if (now - lastLogMs >= 1_000) {
                lastLogMs = now
                Log.i(TAG, String.format(Locale.US,
                    "sample source=%d phase=%s elapsedMs=%d rms=%.7f peak=%.7f dc=%.7f routeType=%d silenced=%b mute=%b zeroReads=%d readError=%d",
                    source, phase, now - started, rms.last(), peak, abs(mean),
                    record.routedDevice?.type ?: 0, configuration?.isClientSilenced == true,
                    audio.isMicrophoneMute, zeroReads, lastReadError))
            }
        }
        val sorted = rms.sorted()
        fun percentile(fraction: Double) = sorted.getOrNull((ceil(sorted.size * fraction).toInt() - 1)
            .coerceAtLeast(0)) ?: 0.0
        return JSONObject().put("frames", rms.size).put("samples", sampleCount)
            .put("zeroReads", zeroReads).put("negativeReads", negativeReads).put("readError", lastReadError)
            .put("elapsedMs", SystemClock.elapsedRealtime() - started)
            .put("rmsMin", sorted.firstOrNull() ?: 0.0).put("rmsMedian", percentile(.5))
            .put("rmsP95", percentile(.95)).put("rmsMax", sorted.lastOrNull() ?: 0.0)
            .put("peakMax", peak).put("meanAbsoluteDc", dcSum / rms.size.coerceAtLeast(1))
            .put("dcMax", maximumDc).put("silencedFrames", silenceFrames)
            .put("unknownConfigurationFrames", unknownConfigurationFrames).put("mutedFrames", mutedFrames)
    }

    private fun microphoneNumbers(record: AudioRecord): JSONObject {
        val entries = JSONArray()
        val available = runCatching {
            record.activeMicrophones.forEach { mic ->
                entries.put(JSONObject().put("group", mic.group).put("index", mic.indexInTheGroup)
                    .put("type", mic.type).put("location", mic.location).put("directionality", mic.directionality))
            }
        }.isSuccess
        return JSONObject().put("available", available).put("count", if (available) entries.length() else -1)
            .put("entries", entries)
    }

    private fun show(text: String) = compose.runOnIdle { prompt.value = text }

    private companion object {
        const val TAG = "ThorAirflowProbe"
        val SOURCES = setOf(MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
    }
}

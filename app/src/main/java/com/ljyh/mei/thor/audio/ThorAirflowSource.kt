package com.ljyh.mei.thor.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.ljyh.mei.BuildConfig
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.Locale

/**
 * Off until the Activity explicitly calls start after permission and user opt-in.
 * PCM occupies one reusable short buffer; no files, audio focus, network, or permission UI.
 */
class ThorAirflowSource(
    context: Context,
    private val lifecycleOwner: LifecycleOwner? = null,
) : Closeable, DefaultLifecycleObserver {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistenceSupervisor = SupervisorJob()
    private val persistenceScope = CoroutineScope(persistenceSupervisor + Dispatchers.IO)
    private val calibrationStore = ThorAirflowCalibrationStore(this.context)
    private val audioManager = this.context.getSystemService(AudioManager::class.java)
    private val isThorHardware = isThorMicrophoneHardware(Build.MANUFACTURER, Build.MODEL)
    private val sourceCandidates = airflowSourceCandidates(Build.MANUFACTURER, Build.MODEL,
        audioManager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
    private var calibrationSource = sourceCandidates.first()
    private val sourceCalibrations = mutableMapOf<Int, ThorAirflowCalibration>()
    private val detector = ThorAirflowDetector().apply {
        applyCalibration(airflowDefaultCalibration(calibrationSource, isThorHardware))
    }
    private val signalLock = Any()
    private val mutableStrength = MutableStateFlow(0f)
    private val mutableStatus = MutableStateFlow(ThorAirflowStatus.Stopped)
    private val mutableError = MutableStateFlow<String?>(null)
    private val mutableCalibration = MutableStateFlow(detector.calibration)
    private val mutableStep = MutableStateFlow<ThorAirflowCalibrationStep?>(null)
    private val mutableProgress = MutableStateFlow(0f)
    private val mutableNextStep = MutableStateFlow(ThorAirflowCalibrationStep.Quiet)
    private val mutableCalibrationFeedback = MutableStateFlow<String?>(null)
    val strength: StateFlow<Float> = mutableStrength.asStateFlow()
    val status: StateFlow<ThorAirflowStatus> = mutableStatus.asStateFlow()
    val error: StateFlow<String?> = mutableError.asStateFlow()
    val calibration: StateFlow<ThorAirflowCalibration> = mutableCalibration.asStateFlow()
    val calibrationStep: StateFlow<ThorAirflowCalibrationStep?> = mutableStep.asStateFlow()
    val calibrationProgress: StateFlow<Float> = mutableProgress.asStateFlow()
    val nextCalibrationStep: StateFlow<ThorAirflowCalibrationStep> = mutableNextStep.asStateFlow()
    val calibrationFeedback: StateFlow<String?> = mutableCalibrationFeedback.asStateFlow()
    @Volatile private var recording: AudioRecord? = null
    private var reader: Job? = null
    private var closed = false
    @Volatile private var calibrationRevision = 0
    private var persistenceJob: Job? = null

    init {
        lifecycleOwner?.lifecycle?.addObserver(this)
    }

    /** No permission is requested here. False leaves ordinary playback and controls untouched. */
    @Synchronized
    fun start(): Boolean {
        if (closed) return false
        if (recording != null) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mutableStatus.value = ThorAirflowStatus.PermissionRequired
            mutableError.value = "启用吹气需要麦克风权限"
            return false
        }
        if (lifecycleOwner != null && !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            mutableStatus.value = ThorAirflowStatus.Stopped
            return false
        }
        var next: AudioRecord? = null
        try {
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "设备不支持麦克风采样格式" }
            val prepared = prepareRecording(minimum)
            next = prepared
            recording = prepared
            val source = prepared.audioSource
            if (calibrationSource != source) mutableNextStep.value = ThorAirflowCalibrationStep.Quiet
            calibrationSource = source
            val revision = ++calibrationRevision
            // Never run even one frame using a different input path's calibration.
            val initial = sourceCalibrations[source] ?: airflowDefaultCalibration(source, isThorHardware)
            synchronized(signalLock) { detector.applyCalibration(initial) }
            mutableCalibration.value = initial
            mutableStrength.value = 0f
            mutableStep.value = null
            mutableProgress.value = 0f
            mutableError.value = null
            mutableCalibrationFeedback.value = null
            mutableStatus.value = ThorAirflowStatus.Listening
            val current = prepared
            val job = scope.launch {
                restoreCalibration(current, source, revision)
                // Enabling is immediately usable. Calibration starts only on an explicit action;
                // otherwise the user's first blow could be permanently learned as ambient noise.
                if (recording === current) read(current, source)
            }
            reader = job
            // Completion also covers cancellation before the coroutine first executes.
            job.invokeOnCompletion { runCatching { current.release() } }
            return true
        } catch (error: Exception) {
            runCatching { next?.release() }
            recording = null
            mutableStrength.value = 0f
            mutableStatus.value = if (error is SecurityException) ThorAirflowStatus.PermissionRequired else ThorAirflowStatus.Unavailable
            mutableError.value = error.message ?: "麦克风暂不可用"
            return false
        }
    }

    @Synchronized
    fun stop() {
        val previous = recording
        recording = null
        // AudioRecord.stop releases a blocking read; release runs only after the reader completes.
        runCatching { previous?.stop() }
        reader?.cancel()
        reader = null
        synchronized(signalLock) { detector.cancel() }
        mutableStrength.value = 0f
        mutableStep.value = null
        mutableProgress.value = 0f
        mutableCalibrationFeedback.value = null
        mutableStatus.value = ThorAirflowStatus.Stopped
    }

    @Synchronized
    fun calibrate(step: ThorAirflowCalibrationStep = ThorAirflowCalibrationStep.Quiet, durationMs: Long = 1_500): Boolean {
        if (closed || recording == null) return false
        synchronized(signalLock) {
            calibrationRevision++
            detector.calibrate(step, SystemClock.elapsedRealtime(), durationMs)
        }
        mutableStrength.value = 0f
        mutableStep.value = step
        mutableProgress.value = 0f
        mutableCalibrationFeedback.value = null
        mutableStatus.value = ThorAirflowStatus.Calibrating
        return true
    }

    @Synchronized
    fun applyCalibration(value: ThorAirflowCalibration): Boolean {
        if (closed) return false
        val accepted = synchronized(signalLock) {
            detector.applyCalibration(value).also { if (it) calibrationRevision++ }
        }
        if (accepted) {
            sourceCalibrations[calibrationSource] = value
            mutableCalibration.value = value
            mutableStep.value = null
            mutableProgress.value = 0f
            mutableStrength.value = 0f
            persistCalibration(calibrationSource, value)
        }
        return accepted
    }

    override fun onPause(owner: LifecycleOwner) = stop()
    override fun onStop(owner: LifecycleOwner) = stop()
    override fun onDestroy(owner: LifecycleOwner) = close()

    @Synchronized
    override fun close() {
        if (closed) return
        stop()
        closed = true
        lifecycleOwner?.lifecycle?.removeObserver(this)
        scope.cancel()
        // Already accepted edits finish even if the Activity is destroyed immediately afterwards.
        persistenceSupervisor.complete()
    }

    /** Try the next source only when construction/start fails, never because the room is quiet. */
    private fun prepareRecording(minimum: Int): AudioRecord {
        val builtIns = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.filter { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }.orEmpty()
        var lastFailure: Exception? = null
        for (source in sourceCandidates) {
            var candidate: AudioRecord? = null
            try {
                val current = AudioRecord.Builder().setAudioSource(source)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minimum * 2, FRAME_SAMPLES * 4)).build()
                candidate = current
                check(current.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
                val preferredAccepted = builtIns.firstOrNull()?.let { current.setPreferredDevice(it) } ?: false
                current.startRecording()
                check(current.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风未开始采集" }
                if (BuildConfig.DEBUG) Log.d("ThorAirflowRoute",
                    "event=start source=$source builtinCount=${builtIns.size} preferredAccepted=$preferredAccepted " +
                        "route=${current.routedDevice?.type ?: 0} mute=${audioManager?.isMicrophoneMute == true}")
                return current
            } catch (error: Exception) {
                runCatching { candidate?.stop() }
                runCatching { candidate?.release() }
                if (BuildConfig.DEBUG) Log.d("ThorAirflowRoute", "event=initialization_failed source=$source")
                if (error is SecurityException) throw error
                lastFailure = error
            }
        }
        throw lastFailure ?: IllegalStateException("麦克风暂不可用")
    }

    private suspend fun restoreCalibration(current: AudioRecord, source: Int, revision: Int) {
        if (synchronized(this) { sourceCalibrations.containsKey(source) }) return
        try {
            val saved = calibrationStore.load(source) ?: return
            synchronized(this) {
                // Stop/reconnect and an accepted user calibration outrank delayed DataStore IO.
                if (!closed && recording === current && calibrationRevision == revision) {
                    synchronized(signalLock) { detector.applyCalibration(saved) }
                    sourceCalibrations[source] = saved
                    mutableCalibration.value = saved
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (BuildConfig.DEBUG) Log.w("ThorAirflow", "Unable to restore source calibration; using source default")
        }
    }

    private suspend fun read(current: AudioRecord, source: Int) {
        val samples = ShortArray(FRAME_SAMPLES)
        var lastDiagnosticMs = 0L
        var lastRouteId: Int? = null // Compared only in memory, never included in diagnostics.
        try {
            while (scope.isActive && recording === current) {
                val count = current.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (recording !== current) break
                val now = SystemClock.elapsedRealtime()
                val silenced = current.activeRecordingConfiguration?.isClientSilenced == true
                val muted = audioManager?.isMicrophoneMute == true
                if (BuildConfig.DEBUG && current.routedDevice?.id != lastRouteId) {
                    lastRouteId = current.routedDevice?.id
                    logRecordingRoute(current)
                }
                if (count < 0) {
                    if (BuildConfig.DEBUG) Log.d("ThorAirflowSignal", "event=read_error source=$source code=$count silenced=$silenced mute=$muted")
                    throw IllegalStateException("麦克风采集已中断（$count）")
                }
                if (silenced || muted) {
                    synchronized(this@ThorAirflowSource) {
                        if (recording === current) {
                            synchronized(signalLock) { detector.cancel() }
                            mutableStrength.value = 0f
                            mutableStep.value = null
                            mutableProgress.value = 0f
                            mutableStatus.value = ThorAirflowStatus.Unavailable
                            mutableError.value = if (muted) "系统麦克风已关闭" else "麦克风暂被系统或其它录音占用"
                        }
                    }
                    if (BuildConfig.DEBUG && now - lastDiagnosticMs >= 200) {
                        lastDiagnosticMs = now
                        Log.d("ThorAirflowSignal", "event=silenced source=$source count=$count route=${current.routedDevice?.type ?: 0} silenced=$silenced mute=$muted strength=0")
                    }
                    if (count == 0) delay(10)
                    continue
                }
                if (count == 0) {
                    if (BuildConfig.DEBUG && now - lastDiagnosticMs >= 200) {
                        lastDiagnosticMs = now
                        Log.d("ThorAirflowSignal", "event=empty source=$source count=0 route=${current.routedDevice?.type ?: 0} silenced=$silenced mute=$muted")
                    }
                    delay(10)
                    continue
                }
                val (reading, revision) = synchronized(signalLock) {
                    detector.process(samples, count, SystemClock.elapsedRealtime()) to calibrationRevision
                }
                synchronized(this@ThorAirflowSource) {
                    if (recording === current && calibrationRevision == revision) {
                        val completedStep = mutableStep.value
                        mutableStrength.value = reading.strength
                        mutableCalibration.value = reading.calibration
                        mutableStep.value = reading.step
                        mutableProgress.value = reading.progress
                        mutableStatus.value = if (reading.step == null) ThorAirflowStatus.Listening else ThorAirflowStatus.Calibrating
                        mutableError.value = null
                        if (reading.calibrationAccepted == true) {
                            sourceCalibrations[source] = reading.calibration
                            persistCalibration(source, reading.calibration)
                            if (completedStep != null) mutableNextStep.value =
                                ThorAirflowCalibrationStep.entries[(completedStep.ordinal + 1) % ThorAirflowCalibrationStep.entries.size]
                        }
                        reading.calibrationError?.let { mutableCalibrationFeedback.value = it }
                    }
                }
                if (BuildConfig.DEBUG && now - lastDiagnosticMs >= 200) {
                    lastDiagnosticMs = now
                    // Scalars only: no PCM, speech, device address, or account information.
                    Log.d("ThorAirflowSignal", String.format(Locale.US,
                        "rms=%.6f gate=%.6f crest=%.2f strength=%.3f source=%d route=%d step=%s silenced=%b mute=%b",
                        reading.rms, reading.gate, reading.crest, reading.strength,
                        source, current.routedDevice?.type ?: 0, reading.step?.name ?: "none", silenced, muted))
                }
            }
        } catch (error: Exception) {
            synchronized(this@ThorAirflowSource) {
                if (recording === current) {
                    recording = null
                    mutableStrength.value = 0f
                    mutableStep.value = null
                    mutableProgress.value = 0f
                    mutableStatus.value = ThorAirflowStatus.Unavailable
                    mutableError.value = error.message ?: "麦克风采集已中断"
                }
            }
        } finally {
            samples.fill(0)
            synchronized(this@ThorAirflowSource) {
                if (recording === current) {
                    recording = null
                    mutableStrength.value = 0f
                }
            }
        }
    }

    /** Numeric hardware metadata only; no route IDs, addresses, or names. */
    private fun logRecordingRoute(current: AudioRecord) {
        val microphones = runCatching { current.activeMicrophones }.getOrNull()
        val groups = microphones?.joinToString(";") { "${it.group},${it.indexInTheGroup},${it.type}" } ?: "unknown"
        val configuration = current.activeRecordingConfiguration
        val route = current.routedDevice
        val preferredMatches = route != null && current.preferredDevice?.id == route.id
        Log.d("ThorAirflowRoute", "event=route source=${current.audioSource} route=${current.routedDevice?.type ?: 0} " +
            "preferredMatchesActual=$preferredMatches " +
            "micCount=${microphones?.size ?: -1} groups=$groups silenced=${configuration?.isClientSilenced == true} " +
            "mute=${audioManager?.isMicrophoneMute == true}")
    }

    /** Serialize accepted tuples and capture the originating source before dispatching IO. */
    private fun persistCalibration(source: Int, value: ThorAirflowCalibration) {
        val previous = persistenceJob
        persistenceJob = persistenceScope.launch {
            previous?.join()
            try { calibrationStore.save(source, value) }
            catch (error: Exception) { Log.w("ThorAirflow", "Unable to save airflow calibration", error) }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 640
    }
}

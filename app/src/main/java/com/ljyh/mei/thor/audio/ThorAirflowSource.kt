package com.ljyh.mei.thor.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.ljyh.mei.BuildConfig
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
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
    private val calibrationRestored = CompletableDeferred<Unit>()
    private val detector = ThorAirflowDetector()
    private val signalLock = Any()
    private val mutableStrength = MutableStateFlow(0f)
    private val mutableStatus = MutableStateFlow(ThorAirflowStatus.Stopped)
    private val mutableError = MutableStateFlow<String?>(null)
    private val mutableCalibration = MutableStateFlow(ThorAirflowCalibration())
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
        scope.launch {
            try {
                val saved = calibrationStore.load()
                synchronized(this@ThorAirflowSource) {
                    // A user's new calibration takes precedence over a delayed initial read.
                    if (!closed && calibrationRevision == 0 && saved != null) {
                        synchronized(signalLock) { detector.applyCalibration(saved) }
                        mutableCalibration.value = saved
                    }
                }
            } catch (error: Exception) {
                if (isActive) Log.w("ThorAirflow", "Unable to restore airflow calibration", error)
            } finally { calibrationRestored.complete(Unit) }
        }
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
            val audioManager = context.getSystemService(AudioManager::class.java)
            val unprocessed = audioManager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
            val prepared = AudioRecord.Builder()
                .setAudioSource(if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum * 2, FRAME_SAMPLES * 4))
                .build()
            next = prepared
            check(prepared.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            prepared.startRecording()
            check(prepared.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风未开始采集" }
            recording = prepared
            mutableError.value = null
            mutableCalibrationFeedback.value = null
            mutableStatus.value = ThorAirflowStatus.Listening
            val current = prepared
            val job = scope.launch {
                calibrationRestored.await()
                // Enabling is immediately usable. Calibration starts only on an explicit action;
                // otherwise the user's first blow could be permanently learned as ambient noise.
                read(current)
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
            mutableCalibration.value = value
            mutableStep.value = null
            mutableProgress.value = 0f
            mutableStrength.value = 0f
            persistCalibration(value)
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

    private suspend fun read(current: AudioRecord) {
        val samples = ShortArray(FRAME_SAMPLES)
        var lastDiagnosticMs = 0L
        try {
            while (scope.isActive && recording === current) {
                val count = current.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (recording !== current) break
                if (count < 0) throw IllegalStateException("麦克风采集已中断（$count）")
                if (count == 0) { delay(10); continue }
                if (current.activeRecordingConfiguration?.isClientSilenced == true) {
                    synchronized(this@ThorAirflowSource) {
                        if (recording === current) {
                            synchronized(signalLock) { detector.cancel() }
                            mutableStrength.value = 0f
                            mutableStep.value = null
                            mutableProgress.value = 0f
                            mutableStatus.value = ThorAirflowStatus.Unavailable
                            mutableError.value = "麦克风暂被系统或其它录音占用"
                        }
                    }
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
                            persistCalibration(reading.calibration)
                            if (completedStep != null) mutableNextStep.value =
                                ThorAirflowCalibrationStep.entries[(completedStep.ordinal + 1) % ThorAirflowCalibrationStep.entries.size]
                        }
                        reading.calibrationError?.let { mutableCalibrationFeedback.value = it }
                    }
                }
                val now = SystemClock.elapsedRealtime()
                if (BuildConfig.DEBUG && now - lastDiagnosticMs >= 200) {
                    lastDiagnosticMs = now
                    // Scalars only: no PCM, speech, device address, or account information.
                    Log.d("ThorAirflowSignal", String.format(Locale.US,
                        "rms=%.6f gate=%.6f crest=%.2f strength=%.3f source=%d route=%d step=%s",
                        reading.rms, reading.gate, reading.crest, reading.strength,
                        current.audioSource, current.routedDevice?.type ?: 0, reading.step?.name ?: "none"))
                }
            }
        } catch (error: Exception) {
            synchronized(this@ThorAirflowSource) {
                if (recording === current) {
                    recording = null
                    mutableStrength.value = 0f
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

    /** Serialize accepted tuples so an older IO job cannot overwrite a newer calibration. */
    private fun persistCalibration(value: ThorAirflowCalibration) {
        val previous = persistenceJob
        persistenceJob = persistenceScope.launch {
            previous?.join()
            try { calibrationStore.save(value) }
            catch (error: Exception) { Log.w("ThorAirflow", "Unable to save airflow calibration", error) }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 640
    }
}

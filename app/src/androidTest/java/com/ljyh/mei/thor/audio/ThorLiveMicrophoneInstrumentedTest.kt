package com.ljyh.mei.thor.audio

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.constants.AppAppearanceKey
import com.ljyh.mei.constants.AutoMixEnabledKey
import com.ljyh.mei.constants.IsShuffleModeKey
import com.ljyh.mei.constants.LoopPlaybackKey
import com.ljyh.mei.constants.MusicQualityKey
import com.ljyh.mei.constants.ReduceTransparencyKey
import com.ljyh.mei.constants.RepeatModeKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.playback.CacheManager
import com.ljyh.mei.playback.MusicService
import com.ljyh.mei.playback.playbackOrderIndices
import com.ljyh.mei.playback.queue.PlaylistQueueSource
import com.ljyh.mei.playback.queueEntryId
import com.ljyh.mei.playback.withQueueEntryId
import com.ljyh.mei.thor.ThorActivity
import com.ljyh.mei.thor.ThorShellViewModel
import com.ljyh.mei.thor.data.ThorAccountTicket
import com.ljyh.mei.thor.data.ThorMusicRepository
import com.ljyh.mei.thor.debug.ThorLiveCatalogEntryPoint
import com.ljyh.mei.thor.state.ThorOverlay
import com.ljyh.mei.thor.test.ThorLiveEnvironment
import com.ljyh.mei.utils.dataStore
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual ThorActivity -> original service -> AudioRecord policy, without catalog/search.
 * Only a generated quiet WAV is saved; captured microphone samples never leave the production
 * detector's in-memory buffer. The additional manual session requires its own explicit opt-in;
 * the automated policy case proves capture suspension, not acoustic blow recognition.
 */
@OptIn(UnstableApi::class)
@LargeTest
@RunWith(AndroidJUnit4::class)
class ThorLiveMicrophoneInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext

    @Test(timeout = 180_000)
    fun realServicePlaybackStopsTheActualMicrophoneAndPauseRestartsItTwice() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit live and physical opt-in are required",
            arguments.getString("thor.live") == "true" && arguments.getString("thor.physical") == "true")
        val report = JSONObject().put("schemaVersion", 1).put("environment", ThorLiveEnvironment.reportName)
            .put("status", "running").put("checks", JSONObject()).put("counts", JSONObject())
        val fixture = Fixture(report)
        var failure: String? = null
        try {
            ThorLiveEnvironment.requireRequestedDevice(context)
            live(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                "record_audio_must_already_be_granted")
            fixture.open()
            fixture.verifyTwoPlaybackCycles()
        } catch (error: Throwable) {
            failure = (error as? LiveFailure)?.code ?: "${fixture.stage}_failed"
        } finally {
            val cleaned = fixture.close()
            if (failure == null && !cleaned) failure = "cleanup_not_confirmed"
            report.put("status", if (failure == null) "passed" else "failed")
                .put("cleanup_ok", cleaned).put("checkedAtEpochMs", System.currentTimeMillis())
            if (failure != null) report.put("failureCode", failure)
            File(context.cacheDir, "thor-live-microphone.json").writeText(report.toString(2))
        }
        if (failure != null) throw AssertionError("thor_live_microphone_failed:$failure")
    }

    /** User-assisted acoustic input only; model/touch feel is evaluated separately by the user. */
    @Test(timeout = 300_000)
    fun realUserBreathAndTouchSession() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit live, physical and manual opt-in are required",
            arguments.getString("thor.live") == "true" && arguments.getString("thor.physical") == "true" &&
                arguments.getString("thor.liveManual") == "true")
        ThorLiveEnvironment.requireRequestedDevice(context)
        live(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            "record_audio_must_already_be_granted")
        val reportFile = File(context.cacheDir, "thor-live-manual.json")
        val finish = File(context.cacheDir, "thor-live-manual-finish")
        live(!finish.exists() || finish.delete(), "old_manual_marker_not_removed")
        live(!reportFile.exists() || reportFile.delete(), "old_manual_report_not_removed")
        val report = JSONObject().put("schemaVersion", 1).put("environment", ThorLiveEnvironment.reportName)
            .put("status", "running").put("stage", "preparing").put("checks", JSONObject()).put("counts", JSONObject())
        fun publish() {
            val temporary = File(context.cacheDir, "thor-live-manual.json.tmp")
            temporary.writeText(report.toString(2))
            live(temporary.renameTo(reportFile), "manual_report_write_failed")
        }
        publish()
        val fixture = Fixture(report)
        var failure: String? = null
        try {
            fixture.open()
            fixture.prepareManualSession()
            val started = SystemClock.elapsedRealtime()
            report.put("stage", "ready").put("startedEpochMs", System.currentTimeMillis())
                .put("maximumStrength", 0.0).put("zeroAtFinish", false).put("caseMotionNotEvaluated", true)
            publish()
            var samples = 0
            var actualMicSamples = 0
            var positive = 0
            var maximum = 0f
            var lastStrength = 0f
            var lastPublish = started
            var finished = false
            while (SystemClock.elapsedRealtime() - started < 180_000) {
                val sample = fixture.manualSample()
                samples++
                lastStrength = sample.strength
                if (sample.actualMic) {
                    actualMicSamples++
                    maximum = maxOf(maximum, sample.strength)
                    if (sample.strength >= .15f) positive++
                }
                report.getJSONObject("counts").put("samples", samples).put("actualMicSamples", actualMicSamples)
                    .put("positiveSamples", positive).put("audioSource", MediaRecorder.AudioSource.MIC)
                report.put("maximumStrength", maximum.toDouble()).put("lastStrength", lastStrength.toDouble())
                    .put("zeroAtFinish", lastStrength == 0f)
                if (finish.isFile) { finished = true; break }
                val now = SystemClock.elapsedRealtime()
                if (now - lastPublish >= 500) { publish(); lastPublish = now }
                SystemClock.sleep(100)
            }
            live(finished, "manual_finish_marker_timeout")
            live(actualMicSamples > 0, "manual_real_mic_not_observed")
            live(positive > 0 && maximum >= .15f, "manual_effective_airflow_not_observed")
            report.getJSONObject("checks").put("manualFinishMarkerReceived", true)
                .put("actualMicObserved", true).put("effectiveStrengthObserved", true)
            report.put("stage", "completed")
        } catch (error: Throwable) {
            failure = (error as? LiveFailure)?.code ?: "${fixture.stage}_manual_session_failed"
        } finally {
            val cleaned = fixture.close()
            val markerDeleted = !finish.exists() || finish.delete()
            report.getJSONObject("checks").put("finishMarkerRemoved", markerDeleted)
            if (failure == null && (!cleaned || !markerDeleted)) failure = "manual_cleanup_not_confirmed"
            report.put("status", if (failure == null) "passed" else "failed")
                .put("cleanup_ok", cleaned && markerDeleted).put("checkedAtEpochMs", System.currentTimeMillis())
            if (failure != null) report.put("failureCode", failure).put("stage", "failed")
            publish()
        }
        if (failure != null) throw AssertionError("thor_live_manual_failed:$failure")
    }

    private data class ManualSample(val strength: Float, val actualMic: Boolean)

    private inner class Fixture(private val report: JSONObject) {
        var stage = "initialization"
            private set
        private lateinit var repository: ThorMusicRepository
        private lateinit var ticket: ThorAccountTicket
        private lateinit var service: MusicService
        private lateinit var activity: ThorActivity
        private lateinit var model: ThorShellViewModel
        private lateinit var source: ThorAirflowSource
        private val audio = context.getSystemService(AudioManager::class.java)
        private val recordingField = ThorAirflowSource::class.java.getDeclaredField("recording").apply { isAccessible = true }
        private var scenario: ActivityScenario<ThorActivity>? = null
        private var savedQueue: SavedQueue? = null
        private var savedPreferences: List<Pair<Preferences.Key<*>, Any?>>? = null
        private var protectedCache = emptyList<Triple<String, Long, Long>>()
        private var wave: File? = null
        private var fixtureEntryId: String? = null
        private var keepScreenOnBefore: Boolean? = null
        private var manualMode = false
        private var bound = false
        private val connected = CountDownLatch(1)
        private val reference = AtomicReference<MusicService?>()
        private val observedSessions = linkedSetOf<Int>()
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                reference.set((binder as? MusicService.MusicBinder)?.service)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }

        fun open() {
            stage = "live_account_preflight"
            val entry = EntryPointAccessors.fromApplication(context, ThorLiveCatalogEntryPoint::class.java)
            repository = entry.thorMusicRepository()
            val account = io { withTimeout(15_000) { repository.awaitCurrentAccountProjection() } }
            live(account.signedIn, "login_required")
            ticket = repository.captureAccountTicket(account) ?: fail("account_changed_before_microphone_test")
            val profile = io { withTimeout(45_000) { repository.withAccountTicket(ticket) { entry.meloXRepository().accountProfile() } } }
            live(profile.id.toString() == account.userId, "live_profile_mismatch")
            mark("liveAccountVerified")

            stage = "snapshot_service_and_preferences"
            bound = context.bindService(Intent(context, MusicService::class.java), connection, Context.BIND_AUTO_CREATE)
            live(bound && connected.await(15, TimeUnit.SECONDS), "service_connection_failed")
            service = reference.get() ?: fail("service_binder_unavailable")
            val restoring = MusicService::class.java.getDeclaredField("isRestoringPlayback").apply { isAccessible = true }
            await("original_queue_restore_not_finished", 20_000) { !restoring.getBoolean(service) }
            onMain {
                savedQueue = SavedQueue.capture(service)
                service.cancelUnavailableRecovery()
                service.player.pause()
                service.queueManager.cancelServerShuffle()
            }
            io {
                context.dataStore.edit { prefs ->
                    savedPreferences = preferenceKeys.map { it to prefs[it] }
                    prefs[AirflowKey] = false
                    prefs[ReduceMotionKey] = true
                    prefs[AutoMixEnabledKey] = false
                }
            }
            val cache = CacheManager.getSimpleCache(context)
            protectedCache = cache.keys.flatMap { key -> cache.getCachedSpans(key).map { Triple(key, it.position, it.length) } }

            stage = "foreground_thor"
            scenario = ActivityScenario.launch<ThorActivity>(Intent(context, ThorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            scenario!!.onActivity { owner ->
                activity = owner
                keepScreenOnBefore = owner.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
                owner.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                model = ViewModelProvider(owner)[ThorShellViewModel::class.java]
                source = ThorActivity::class.java.getDeclaredField("airflow").apply { isAccessible = true }
                    .get(owner) as ThorAirflowSource
            }
            val handled = ThorShellViewModel::class.java.getDeclaredField("handledAccountGeneration").apply { isAccessible = true }
            await("thor_not_ready_for_microphone", 20_000) {
                activity.lifecycle.currentState == Lifecycle.State.RESUMED && model.playback.state.value.connected &&
                    model.library.value.account.generation == account.generation &&
                    handled.getLong(model) == account.generation && model.preferences.value.reduceMotion &&
                    !model.preferences.value.airflowEnabled
            }
            ensureAccount()

            stage = "prepare_generated_local_audio"
            val runId = UUID.randomUUID().toString()
            wave = File(context.cacheDir, "thor-mic-policy-$runId.wav")
            writeQuietWave(checkNotNull(wave))
            val metadata = MediaMetadata(-SystemClock.elapsedRealtime().coerceAtLeast(1), "Thor microphone policy fixture", "",
                emptyList(), 30_000, MediaMetadata.Album(-1, "Local acceptance clip"), isPodcast = true)
            // Empty (the standard MediaItem default) deliberately keeps this non-catalog clip
            // out of AutomaticCacheController's song playback_count table. Its occurrence still
            // has a unique queue entry ID; no library/database fixture needs insertion or deletion.
            val item = MediaItem.Builder().setMediaId("")
                .setUri(Uri.fromFile(wave)).setTag(metadata)
                .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(metadata.title).build())
                .build().withQueueEntryId("thor-mic-policy-entry-$runId")
            fixtureEntryId = item.queueEntryId
            // The original service/renderer/output play a real file MediaSource. Avoid registering
            // fake library rows just to satisfy the online resolver's media-ID lookup. The clip is
            // tagged as a non-song program so ordinary history/catalog reporters ignore it.
            val mediaSource = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context)).createMediaSource(item)
            onMain {
                service.cancelUnavailableRecovery()
                service.player.stop()
                service.queueManager.cancelServerShuffle()
                service.queueManager.restoreFmMode(false)
                service.queueManager.restorePlaylistSource(null)
                service.player.shuffleModeEnabled = false
                service.player.repeatMode = Player.REPEAT_MODE_OFF
                service.queueTitle = "Thor microphone policy acceptance"
                service.player.activeDeck.setMediaSource(mediaSource)
                service.player.playWhenReady = false
                service.player.prepare()
            }
            await("generated_audio_not_ready", 15_000) {
                service.player.playbackState == Player.STATE_READY && !service.player.isPlaying &&
                    service.player.currentMediaItem?.queueEntryId == item.queueEntryId
            }
            mark("realServiceLocalWavReady")
            onMain { model.updatePreferences { it.copy(airflowEnabled = true) } }
            await("airflow_preference_not_enabled") { model.preferences.value.airflowEnabled }
        }

        fun prepareManualSession() {
            stage = "manual_session_setup"
            awaitRealMic("manual_initial_capture_not_ready").also { observedSessions += it.audioSessionId }
            // No further ActivityScenario.onActivity/waitForIdle calls once the real scene moves.
            manualMode = true
            onMain {
                model.showPlayer()
                model.updatePreferences { it.copy(reduceMotion = false, airflowEnabled = true) }
            }
            await("manual_player_has_no_current_model", 10_000) {
                model.navigation.value.overlay == ThorOverlay.Player && model.playback.state.value.current != null &&
                    model.playback.state.value.currentEntryId == fixtureEntryId && !model.playback.state.value.playing &&
                    model.preferences.value.airflowEnabled && !model.preferences.value.reduceMotion &&
                    activity.lifecycle.currentState == Lifecycle.State.RESUMED
            }
            mark("actualCurrentPlayerModelPresent")
            mark("manualSceneMotionEnabled")
            stage = "awaiting_user_finish"
        }

        fun manualSample(): ManualSample = onMain {
            ensureAccount()
            live(activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
                model.navigation.value.overlay == ThorOverlay.Player && model.playback.state.value.current != null &&
                model.playback.state.value.currentEntryId == fixtureEntryId && !service.player.isPlaying &&
                model.preferences.value.airflowEnabled && !model.preferences.value.reduceMotion,
                "manual_scene_changed_before_finish")
            val record = recordingField.get(source) as? AudioRecord
            val actualMic = record != null && record.state == AudioRecord.STATE_INITIALIZED &&
                record.recordingState == AudioRecord.RECORDSTATE_RECORDING && record.audioSource == MediaRecorder.AudioSource.MIC &&
                audio.activeRecordingConfigurations.any {
                    it.clientAudioSessionId == record.audioSessionId && it.clientAudioSource == MediaRecorder.AudioSource.MIC
                }
            if (actualMic) observedSessions += record!!.audioSessionId
            val strength = source.strength.value.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            ManualSample(strength, actualMic)
        }

        fun verifyTwoPlaybackCycles() {
            var recording = awaitRealMic("initial_paused_capture")
            observedSessions += recording.audioSessionId
            mark("pausedCaptureUsesRealMic")
            repeat(2) { cycle ->
                stage = "playback_cycle_${cycle + 1}"
                val originalSession = recording.audioSessionId
                onMain { service.player.play() }
                await("real_service_not_playing") { service.player.playbackState == Player.STATE_READY && service.player.isPlaying }
                val start = onMain { service.player.currentPosition }
                await("real_service_position_did_not_advance", 10_000) {
                    service.player.isPlaying && service.player.currentPosition >= start + 1_000
                }
                await("playback_did_not_stop_actual_capture") { stopped(originalSession) }
                val until = SystemClock.elapsedRealtime() + 2_000
                while (SystemClock.elapsedRealtime() < until) {
                    ensureAccount()
                    live(onMain { service.player.isPlaying && model.preferences.value.airflowEnabled && stopped(originalSession) },
                        "capture_restarted_during_continuing_playback")
                    SystemClock.sleep(100)
                }
                mark("cycle${cycle + 1}RecordingStoppedForTwoSeconds")
                onMain { service.player.pause() }
                await("real_service_pause_not_projected") { !service.player.isPlaying && !model.playback.state.value.playing }
                val next = awaitRealMic("pause_did_not_restart_actual_capture")
                live(next !== recording && next.audioSessionId != originalSession, "pause_did_not_create_new_capture_session")
                recording = next
                observedSessions += next.audioSessionId
                mark("cycle${cycle + 1}PauseRestartsActualMic")
            }
            live(observedSessions.size == 3, "capture_sessions_were_not_independent")
            report.getJSONObject("counts").put("playbackCycles", 2).put("recordingSessionsObserved", observedSessions.size)
                .put("audioSource", MediaRecorder.AudioSource.MIC)
            ensureAccount()
            mark("sameAccountAfterCycles")
        }

        private fun awaitRealMic(code: String): AudioRecord {
            var accepted: AudioRecord? = null
            await(code, 15_000) {
                val current = recordingField.get(source) as? AudioRecord
                val valid = current != null && current.state == AudioRecord.STATE_INITIALIZED &&
                    current.recordingState == AudioRecord.RECORDSTATE_RECORDING &&
                    current.audioSource == MediaRecorder.AudioSource.MIC && audio.activeRecordingConfigurations.any {
                        it.clientAudioSessionId == current.audioSessionId && it.clientAudioSource == MediaRecorder.AudioSource.MIC
                    }
                if (valid) accepted = current
                valid
            }
            return accepted ?: fail(code)
        }

        private fun stopped(previousSession: Int) = recordingField.get(source) == null && source.strength.value == 0f &&
            audio.activeRecordingConfigurations.none { it.clientAudioSessionId == previousSession }

        fun close(): Boolean {
            var clean = true
            fun cleanup(code: String, action: () -> Unit) {
                try { action(); mark(code) }
                catch (_: Throwable) { clean = false; report.getJSONObject("checks").put(code, false) }
            }
            cleanup("realActivityClosedAndMicReleased") {
                if (manualMode && ::model.isInitialized && onMain { !activity.isDestroyed }) {
                    // End perpetual manual frames before Scenario.close's internal lifecycle wait.
                    onMain { model.updatePreferences { it.copy(reduceMotion = true, airflowEnabled = false) } }
                    await("manual_scene_did_not_stop_for_cleanup") {
                        model.preferences.value.reduceMotion && !model.preferences.value.airflowEnabled
                    }
                }
                if (::activity.isInitialized) onMain {
                    if (!activity.isDestroyed) keepScreenOnBefore?.let { before ->
                        if (before) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        live((activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0) == before,
                            "keep_screen_on_flag_not_restored")
                    }
                }
                scenario?.close()
                scenario = null
                if (::source.isInitialized) await("activity_close_left_recording_active") {
                    recordingField.get(source) == null && audio.activeRecordingConfigurations.none { it.clientAudioSessionId in observedSessions }
                }
            }
            cleanup("originalQueueRestored") {
                savedQueue?.let { saved -> onMain {
                    service.cancelUnavailableRecovery()
                    service.player.pause()
                    service.player.stop()
                    service.queueManager.cancelServerShuffle()
                    saved.restore(service)
                }
                    onMain { }; onMain { }
                    onMain { saved.verify(service); service.schedulePlaybackSnapshot() }
                }
            }
            cleanup("originalNullablePreferencesRestored") {
                savedPreferences?.let { saved -> io {
                    context.dataStore.edit { prefs ->
                        @Suppress("UNCHECKED_CAST")
                        saved.forEach { (key, value) ->
                            val typed = key as Preferences.Key<Any>
                            if (value == null) prefs.remove(typed) else prefs[typed] = value
                        }
                    }
                    val current = context.dataStore.data.first()
                    live(saved.all { (key, value) -> current[key] == value }, "nullable_preferences_not_restored")
                } }
            }
            cleanup("originalAccountUnchanged") { if (::ticket.isInitialized) ensureAccount() }
            cleanup("preexistingAudioCacheRetained") {
                if (savedQueue != null) {
                    val cache = CacheManager.getSimpleCache(context)
                    live(protectedCache.all { (key, offset, length) -> cache.isCached(key, offset, length) }, "preexisting_cache_bytes_missing")
                }
            }
            cleanup("generatedWavDeleted") { wave?.let { live(!it.exists() || it.delete(), "generated_wav_not_deleted") } }
            cleanup("serviceBindingReleased") { if (bound) { context.unbindService(connection); bound = false } }
            return clean
        }

        private fun ensureAccount() = live(repository.isTicketCurrent(ticket), "account_changed_during_microphone_test")
        private fun mark(key: String) { report.getJSONObject("checks").put(key, true) }
    }

    /** Complete original playback context remains in memory and never enters an evidence file. */
    private data class SavedQueue(
        val items: List<MediaItem>, val index: Int, val positionMs: Long, val order: List<Int>,
        val shuffle: Boolean, val repeat: Int, val ready: Boolean, val idle: Boolean, val title: String?,
        val fm: Boolean, val similarIds: List<String>?, val autoplay: Boolean, val autoplayStartIndex: Int?,
        val playlistSource: PlaylistQueueSource?,
    ) {
        fun restore(service: MusicService) {
            val player = service.player
            player.shuffleModeEnabled = false
            if (items.isEmpty()) player.clearMediaItems() else player.activeDeck.setMediaItems(items, index, positionMs)
            player.setPlaybackOrder(order)
            service.queueTitle = title
            service.queueManager.restoreFmMode(fm, similarIds, autoplay)
            service.queueManager.restoreQueueAutoplayStartIndex(autoplayStartIndex)
            service.queueManager.restorePlaylistSource(playlistSource)
            player.repeatMode = repeat
            player.shuffleModeEnabled = shuffle
            if (!idle && items.isNotEmpty()) player.prepare()
            player.playWhenReady = ready
        }
        fun verify(service: MusicService) {
            val player = service.player
            live(items == List(player.mediaItemCount, player::getMediaItemAt), "original_media_items_not_restored")
            live(order == player.playbackOrderIndices(true), "original_queue_order_not_restored")
            live(player.repeatMode == repeat && player.shuffleModeEnabled == shuffle && player.playWhenReady == ready,
                "original_queue_modes_not_restored")
            if (items.isNotEmpty()) live(player.currentMediaItemIndex == index && abs(player.currentPosition - positionMs) < 1_000,
                "original_queue_position_not_restored")
            val manager = service.queueManager
            live(service.queueTitle == title && manager.isFmMode == fm && manager.similarFmSourceIds == similarIds &&
                manager.queueAutoplayEnabled.value == autoplay && manager.playlistSource == playlistSource &&
                manager.queueAutoplayStartEntryId.value == autoplayStartIndex?.let { items.getOrNull(it)?.queueEntryId },
                "original_queue_context_not_restored")
        }
        companion object {
            fun capture(service: MusicService): SavedQueue {
                val p = service.player
                val m = service.queueManager
                return SavedQueue(List(p.mediaItemCount, p::getMediaItemAt), p.currentMediaItemIndex, p.currentPosition,
                    p.playbackOrderIndices(true), p.shuffleModeEnabled, p.repeatMode, p.playWhenReady,
                    p.playbackState == Player.STATE_IDLE, service.queueTitle, m.isFmMode, m.similarFmSourceIds,
                    m.queueAutoplayEnabled.value, (0 until p.mediaItemCount).firstOrNull {
                        p.getMediaItemAt(it).queueEntryId == m.queueAutoplayStartEntryId.value }, m.playlistSource)
            }
        }
    }

    private fun await(code: String, timeout: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(40)
        }
        fail(code)
    }
    private fun <T> onMain(action: () -> T): T {
        val result = AtomicReference<T>()
        val failed = AtomicReference<Throwable>()
        instrumentation.runOnMainSync { try { result.set(action()) } catch (error: Throwable) { failed.set(error) } }
        failed.get()?.let { throw it }
        return result.get()
    }
    private fun <T> io(action: suspend () -> T): T = runBlocking(Dispatchers.IO) { withTimeout(15_000) { action() } }

    private companion object {
        val AirflowKey = booleanPreferencesKey("thor.airflowEnabled")
        val ReduceMotionKey = booleanPreferencesKey("thor.reduceMotion")
        val preferenceKeys: List<Preferences.Key<*>> = listOf(
            stringPreferencesKey("thor.theme"), AppAppearanceKey, ReduceMotionKey, ReduceTransparencyKey, AirflowKey,
            stringPreferencesKey("thor.background.mode"), stringPreferencesKey("thor.background.intensity"),
            AutoMixEnabledKey, IsShuffleModeKey, RepeatModeKey, MusicQualityKey, LoopPlaybackKey,
        )
        class LiveFailure(val code: String) : RuntimeException(code)
        fun fail(code: String): Nothing = throw LiveFailure(code)
        fun live(condition: Boolean, code: String) { if (!condition) fail(code) }
        fun writeQuietWave(file: File) {
            val rate = 16_000
            val frames = rate * 30
            val bytes = frames * 2
            val buffer = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(bytes)
            repeat(frames) { frame -> buffer.putShort((sin(2 * PI * 220 * frame / rate) * 24).toInt().toShort()) }
            file.writeBytes(buffer.array())
        }
    }
}

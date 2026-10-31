package com.ljyh.mei.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.ljyh.mei.constants.AutoMixEnabledKey
import com.ljyh.mei.constants.IsShuffleModeKey
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.constants.MusicQualityKey
import com.ljyh.mei.constants.NoAudioSourceKey
import com.ljyh.mei.constants.RepeatModeKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.SongPlaybackAvailability
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.model.isExplicitlyUnavailable
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.network.NeteaseSessionType
import com.ljyh.mei.data.network.neteaseSessionType
import com.ljyh.mei.playback.queue.PlaylistQueueSource
import com.ljyh.mei.thor.ThorActivity
import com.ljyh.mei.thor.ThorShellViewModel
import com.ljyh.mei.thor.debug.ThorPhysicalAccountEntryPoint
import com.ljyh.mei.thor.state.ThorOverlay
import com.ljyh.mei.thor.test.ThorLiveEnvironment
import com.ljyh.mei.utils.dataStore
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit opt-in, current-session acceptance on the requested Thor environment.
 * Physical Thor additionally requires -e thor.physical true; the shared device gate
 * rejects a mismatch before account access. The default remains the dedicated emulator.
 * First run the live catalog test, then pass -e thor.livePlayback true. Ordinary CI skips
 * before Hilt, DataStore or HTTP access. Missing/stale catalog and actual network failures
 * fail an opted-in run; natural playback/permission cases use no fake URL, resolver or
 * injected player error. Runtime cases seed Unknown metadata to bypass catalog prefiltering. The
 * last three intentionally retain the older fault
 * injection regressions, each with its own real, prepared six-entry online precondition.
 *
 * Playback uses the installed service, decoder, output and normal cache. Nothing deletes
 * existing cached bytes or changes account/library data. Playback can update normal local
 * listening history and the service's normal playback-history reporting still applies.
 * Assertion messages omit account identifiers, catalog names, HTTP URLs and error bodies.
 */
@LargeTest
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ThorLivePlaybackInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test(timeout = 240_000)
    fun onlinePlaybackAdvancesPausesResumesSeeksAndRealTriggersFollowQueueOrder() = withLiveFixture { fixture ->
        val songs = fixture.playableSongs()
        songs.forEach(fixture::verifyFullHttpSource)
        fixture.seed(listOf(songs[0], songs[1], songs[0]), play = true)
        fixture.awaitProgress(0)
        onMain { fixture.service.player.pause() }
        val pausedAt = onMain { fixture.service.player.currentPosition }
        SystemClock.sleep(700)
        onMain {
            assertFalse("pause_intent_retained=false", fixture.service.player.playWhenReady)
            assertTrue("paused_position_stable=false", abs(fixture.service.player.currentPosition - pausedAt) < 100)
        }
        onMain { fixture.service.player.play() }
        fixture.awaitProgress(0)
        onMain { fixture.service.player.seekTo(10_000L) }
        await("real_seek_applied") {
            fixture.service.player.currentMediaItemIndex == 0 && fixture.service.player.currentPosition in 9_500L..15_000L
        }

        fixture.openThor()
        val identities = onMain { fixture.entryIds() }
        fixture.verifyShoulderInputs()
        onMain { fixture.service.player.play() }
        fixture.press(KeyEvent.KEYCODE_BUTTON_R2)
        fixture.awaitProgress(1)
        // This test checks the existing previous-entry action, independent of the user's
        // optional "restart progressed current track" setting.
        onMain { fixture.service.player.seekTo(0L) }
        fixture.press(KeyEvent.KEYCODE_BUTTON_L2)
        fixture.awaitProgress(0)
        onMain {
            fixture.service.player.setPlaybackOrder(listOf(0, 2, 1))
            fixture.service.player.shuffleModeEnabled = true
        }
        fixture.press(KeyEvent.KEYCODE_BUTTON_R2)
        fixture.awaitProgress(2)
        onMain { fixture.service.player.seekTo(0L) }
        fixture.press(KeyEvent.KEYCODE_BUTTON_L2)
        fixture.awaitProgress(0)
        fixture.verifyAnalogTriggerLatch(identities)
        onMain { assertTrue("trigger_keeps_queue_identity=false", identities == fixture.entryIds()) }
    }

    @Test(timeout = 240_000)
    fun serverDeniedSourceWithSkippingDisabledPausesAndRetainsTheCurrentEntry() = withLiveFixture { fixture ->
        val blocked = fixture.blockedSong()
        val playable = fixture.playableSongs().first()
        fixture.verifyFullHttpSource(playable)
        fixture.setSkipping(false)
        fixture.seed(listOf(blocked, playable), play = false)
        val deniedEntry = onMain { fixture.service.player.currentMediaItem?.queueEntryId }
        val errors = fixture.observeRealErrors()
        onMain { fixture.service.player.play(); fixture.service.player.prepare() }
        await("server_denial_pauses_current", 60_000) {
            errors.denied > 0 && !fixture.service.player.playWhenReady && fixture.service.playbackFailure.value != null
        }
        SystemClock.sleep(1_000)
        onMain {
            assertTrue("skip_disabled_retains_entry=false", fixture.service.player.currentMediaItem?.queueEntryId == deniedEntry)
            assertTrue("skip_disabled_retains_queue=false", fixture.service.player.mediaItemCount == 2)
            assertTrue("skip_disabled_no_auto_seek=false", errors.seekTargets.isEmpty())
        }
    }

    @Test(timeout = 240_000)
    fun actualServerDeniedSourceProbesPastDeniedCandidateAndCommitsOnlyThePlayableEntry() = withLiveFixture { fixture ->
        val blocked = fixture.blockedSong()
        val playable = fixture.playableSongs().first()
        fixture.verifyFullHttpSource(playable)
        fixture.setSkipping(true)
        // Direct service seeding deliberately models a runtime Unknown verdict. Production
        // list/queue insertion would otherwise remove the catalog-known denied item. The
        // server, resolver, actual error and candidate scan remain entirely real.
        fixture.seed(listOf(blocked, blocked, playable), play = false)
        val ids = onMain { fixture.entryIds() }
        val errors = fixture.observeRealErrors()
        onMain { fixture.service.player.play(); fixture.service.player.prepare() }
        fixture.awaitProgress(2, timeoutMs = 75_000)
        onMain {
            assertTrue("real_source_denial_observed=false", errors.denied > 0)
            assertTrue("failed_candidate_became_current=false", ids[1] !in errors.seekTargets)
            assertTrue("only_ready_candidate_committed=false", errors.seekTargets == listOf(ids[2]))
            assertTrue("runtime_recovery_keeps_queue=false", ids == fixture.entryIds())
            assertTrue("successful_recovery_no_terminal_notice=false", fixture.service.playbackFailure.value == null)
        }
    }

    @Test(timeout = 180_000)
    fun repeatAllWithOnlyServerDeniedEntriesStopsWithinTheCampaignAndEmitsOneFinalFailure() = withLiveFixture { fixture ->
        val blocked = fixture.blockedSong()
        fixture.setSkipping(true)
        fixture.seed(List(3) { blocked }, play = false)
        onMain { fixture.service.player.repeatMode = Player.REPEAT_MODE_ALL }
        val ids = onMain { fixture.entryIds() }
        val errors = fixture.observeRealErrors()
        val failures = fixture.observeFailureIds()
        onMain { fixture.service.player.play(); fixture.service.player.prepare() }
        await("all_denied_campaign_terminates", 70_000) {
            errors.denied > 0 && !fixture.service.player.playWhenReady && failures.size == 1
        }
        val recoveryMs = onMain { SystemClock.elapsedRealtime() - errors.firstErrorAtMs }
        assertTrue("all_denied_campaign_exceeded_budget=false", recoveryMs <= 17_000)
        SystemClock.sleep(800)
        onMain {
            assertTrue("all_denied_retains_current=false", fixture.service.player.currentMediaItem?.queueEntryId == ids.first())
            assertTrue("all_denied_retains_queue=false", ids == fixture.entryIds())
            assertTrue("all_denied_repeat_mode_changed=false", fixture.service.player.repeatMode == Player.REPEAT_MODE_ALL)
            assertTrue("all_denied_never_commits_candidate=false", errors.seekTargets.isEmpty())
            assertTrue("all_denied_error_flood=false", errors.total == 1 && failures.size == 1)
            assertTrue("all_denied_aggregate_failure_missing=false", fixture.service.playbackFailure.value?.id == failures.single())
            assertFalse("all_denied_remains_paused=false", fixture.service.player.playWhenReady)
        }
        Log.i("ThorLivePlayback", "all_denied_stopped=true final_failure_count=1 recovery_ms=$recoveryMs")
    }

    @Test(timeout = 240_000)
    fun independentPausedQueuePreconditionRunsTheExistingRepeatedFailureRegression() {
        requireOriginalOptIn("issue59Recovery")
        withLiveFixture { fixture ->
            fixture.preparePausedSixEntryQueue()
            MusicServiceQualityRecoveryTest().pausedRepeatedSourceFailuresNeverStartCandidateRecoveryOrChangeTheQueue()
        }
    }

    @Test(timeout = 240_000)
    fun independentOnlinePreconditionRunsTheExistingInjectedDecoderRecovery() {
        requireOriginalOptIn("issue38Recovery")
        withLiveFixture { fixture ->
            fixture.preparePausedSixEntryQueue(requireMultipleQualities = true)
            MusicServiceQualityRecoveryTest().injectedDecoderFailureRecreatesTheCurrentPausedSourceAndPreservesItsQueue()
        }
    }

    @Test(timeout = 300_000)
    fun independentOnlinePreconditionRunsTheExistingInjectedStallAndQualityFallbackRecovery() {
        requireOriginalOptIn("issue57Recovery")
        withLiveFixture { fixture ->
            fixture.preparePausedSixEntryQueue(requireMultipleQualities = true)
            MusicServiceQualityRecoveryTest().stalledSourceRetriesItsQualityThenFallsBackWithoutDeletingCachedBytes()
        }
    }

    private fun requireOriginalOptIn(name: String) {
        // Only the global gate skips ordinary CI. Explicit live runs must report a missing
        // required flag as a failure instead of silently counting a skipped legacy case.
        assumeTrue(InstrumentationRegistry.getArguments().getString("thor.livePlayback") == "true")
        assertTrue("required_recovery_opt_in_missing=false", InstrumentationRegistry.getArguments().getString(name) == "true")
    }

    private fun withLiveFixture(block: (Fixture) -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("thor.livePlayback") == "true")
        ThorLiveEnvironment.requireRequestedDevice(context)
        val fixture = Fixture()
        var primaryFailure: Throwable? = null
        try {
            fixture.open()
            block(fixture)
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            try { fixture.close() } catch (cleanup: Throwable) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private inner class Fixture {
        lateinit var service: MusicService
        private val connected = CountDownLatch(1)
        private val reference = AtomicReference<MusicService?>()
        private var bound = false
        private var snapshot: SavedQueue? = null
        private var savedPreferences: SavedPreferences? = null
        private var scenario: ActivityScenario<ThorActivity>? = null
        private var activity: ThorActivity? = null
        private var accountType = NeteaseSessionType.None
        private var accountId: String? = null
        private lateinit var playableIds: List<Long>
        private lateinit var unavailableIds: List<Long>
        private val listeners = mutableListOf<Player.Listener>()
        private val observationJobs = mutableListOf<Job>()
        private var protectedCache = emptyList<Triple<String, Long, Long>>()
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                reference.set((binder as? MusicService.MusicBinder)?.service)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }

        fun open() {
            val catalogFile = File(context.cacheDir, "thor-live-catalog.json")
            assertTrue("live_catalog_missing=false", catalogFile.isFile)
            val catalog = runCatching { JsonParser.parseString(catalogFile.readText()).asJsonObject }.getOrNull()
            assertTrue("live_catalog_valid=false", catalog != null)
            checkNotNull(catalog)
            assertTrue("live_catalog_not_passed=false", catalog.get("status")?.asString == "passed")
            assertTrue("live_catalog_wrong_environment=false", catalog.get("environment")?.asString == ThorLiveEnvironment.reportName)
            assertTrue("live_catalog_schema=false", catalog.get("schemaVersion")?.asInt == 1)
            assertTrue("live_catalog_stale=false", System.currentTimeMillis() - catalog.get("checkedAtEpochMs").asLong in 0L..7_200_000L)
            assertTrue("live_catalog_account_unverified=false", catalog.get("accountVerified")?.asBoolean == true)
            playableIds = catalog.getAsJsonArray("playableCandidates").map { it.asJsonObject.get("songId").asLong }.distinct()
            unavailableIds = catalog.getAsJsonArray("unavailableCandidates").map { it.asJsonObject.get("songId").asLong }.distinct()
            assertTrue("live_candidates_missing=false", playableIds.size >= 2 && unavailableIds.isNotEmpty() &&
                (playableIds + unavailableIds).all { it > 0 })
            val identity = network("stored_identity", 10_000) {
                val p = context.dataStore.data.first()
                p.neteaseSessionType() to p[UserIdKey]
            }
            accountType = identity.first
            accountId = identity.second
            assertTrue("installed_account_missing=false", accountType != NeteaseSessionType.None && !accountId.isNullOrBlank())
            assertTrue("catalog_session_type_changed=false", catalog.get("sessionType")?.asString == accountType.name)
            val profile = network("live_profile", 45_000) {
                EntryPointAccessors.fromApplication(context.applicationContext, ThorPhysicalAccountEntryPoint::class.java)
                    .meloXRepository().accountProfile()
            }
            assertTrue("live_profile_same_account=false", profile.id.toString() == accountId)

            bound = context.bindService(Intent(context, MusicService::class.java), connection, Context.BIND_AUTO_CREATE)
            assertTrue("service_bind=false", bound)
            assertTrue("service_connected=false", connected.await(15, TimeUnit.SECONDS))
            service = checkNotNull(reference.get())
            val restoring = MusicService::class.java.getDeclaredField("isRestoringPlayback").apply { isAccessible = true }
            await("saved_queue_restore_finished", 20_000) { !restoring.getBoolean(service) }
            onMain {
                snapshot = SavedQueue.capture(service)
                service.cancelUnavailableRecovery()
                service.player.pause()
                service.queueManager.cancelServerShuffle()
                service.queueManager.restoreFmMode(false)
                service.queueManager.restorePlaylistSource(null)
            }
            network("preferences_snapshot", 10_000) {
                context.dataStore.edit { p ->
                    savedPreferences = SavedPreferences(p[NoAudioSourceKey], p[AutoMixEnabledKey],
                        p[IsShuffleModeKey], p[RepeatModeKey], p[MusicQualityKey], p[LiveReduceMotionKey])
                    p[AutoMixEnabledKey] = false
                    p[LiveReduceMotionKey] = true
                }
            }
            val controllerField = MusicService::class.java.getDeclaredField("autoMixController").apply { isAccessible = true }
            val configurationField = AutoMixController::class.java.getDeclaredField("configuration").apply { isAccessible = true }
            await("automix_preference_consumed") {
                !(configurationField.get(controllerField.get(service)) as AutoMixRuntimeConfiguration).enabled
            }
            val cache = CacheManager.getSimpleCache(context)
            protectedCache = cache.keys.flatMap { key -> cache.getCachedSpans(key).map { Triple(key, it.position, it.length) } }
            // Android 15+ requires a foreground app/service for audio focus. Keep the real
            // Thor host visible for every live case, not only the input-routing case.
            openThor()
        }

        fun playableSongs(): List<MediaMetadata> = details(playableIds.take(2)).also { songs ->
            assertTrue("live_playable_details=false", songs.size == 2 && songs.all { !it.isExplicitlyUnavailable && it.duration > 30_000 })
        }

        fun blockedSong(): MediaMetadata {
            val song = details(unavailableIds.take(1)).single()
            assertTrue("live_catalog_no_longer_denied=false", song.isExplicitlyUnavailable)
            service.mediaUriProvider.invalidate(song.id.toString())
            val refusal = runBlocking(Dispatchers.IO) {
                runCatching { withTimeout(60_000) { service.mediaUriProvider.resolveMediaSource(song.id.toString(), quality()) } }.exceptionOrNull()
            }
            // A timeout, DNS/HTTP transport error or missing detail is not copyright evidence.
            assertTrue("live_full_source_server_refusal=false", refusal is SourceNotFoundException)
            Log.i("ThorLivePlayback", "catalog_denied=true full_source_refused=true runtime_seed=Unknown")
            return song.copy(availability = SongPlaybackAvailability.Unknown, availabilityReason = null)
        }

        private fun details(ids: List<Long>): List<MediaMetadata> = network("live_song_details", 30_000) {
            val response = service.apiService.getSongDetail(GetSongDetails(ids.joinToString(",")))
            check(response.code == 200)
            ids.map { id ->
                val track = response.songs.first { it.id == id }
                track.toMediaMetadata(response.privileges.orEmpty().firstOrNull { it.id == id })
            }
        }

        private suspend fun quality(): String = context.dataStore.data.first()[MusicQualityKey]
            ?.let(::normalizePlaybackQuality) ?: MusicQuality.EXHIGH.text

        fun verifyFullHttpSource(song: MediaMetadata) {
            val source = network("live_full_http_source", 60_000) {
                service.mediaUriProvider.resolveMediaSource(song.id.toString(), quality())
            }
            assertTrue("online_source_expected=false", source.cacheKey != null && source.uri.scheme in setOf("http", "https"))
            val opened = network("live_http_read", 8_000) {
                runInterruptible {
                    val dataSource = CacheManager.getRecoveryProbeDataSourceFactory(context).createDataSource()
                    try {
                        dataSource.open(DataSpec.Builder().setUri(source.uri).setLength(1).build())
                        dataSource.read(ByteArray(1), 0, 1) > 0
                    } finally { runCatching { dataSource.close() } }
                }
            }
            assertTrue("live_http_bytes_read=false", opened)
            Log.i("ThorLivePlayback", "full_source=true http_read=true")
        }

        fun setSkipping(enabled: Boolean) {
            network("set_skip_preference", 10_000) { context.dataStore.edit { it[NoAudioSourceKey] = enabled } }
            val field = MusicService::class.java.getDeclaredField("skipUnavailableSources").apply { isAccessible = true }
            await("skip_preference_consumed") { field.getBoolean(service) == enabled }
        }

        fun seed(songs: List<MediaMetadata>, play: Boolean) = onMain {
            service.cancelUnavailableRecovery()
            val player = service.player
            player.stop()
            player.shuffleModeEnabled = false
            player.repeatMode = Player.REPEAT_MODE_OFF
            service.queueTitle = "Thor live playback acceptance"
            player.setMediaItems(songs.map(MediaMetadata::toMediaItem), 0, 0L)
            assertTrue("seeded_occurrence_identities=false", entryIds().all { it != null } && entryIds().distinct().size == songs.size)
            player.playWhenReady = play
            if (play) player.prepare()
        }

        fun entryIds(): List<String?> = List(service.player.mediaItemCount) { service.player.getMediaItemAt(it).queueEntryId }

        fun awaitProgress(index: Int, timeoutMs: Long = 45_000) {
            await("live_ready_playing", timeoutMs) {
                service.player.currentMediaItemIndex == index && service.player.playbackState == Player.STATE_READY && service.player.isPlaying
            }
            val start = onMain { service.player.currentPosition }
            await("live_position_advanced", 12_000) {
                service.player.currentMediaItemIndex == index && service.player.isPlaying && service.player.currentPosition >= start + 1_200
            }
            onMain { Log.i("ThorLivePlayback", "ready=true playing=true progress_ms=${service.player.currentPosition - start}") }
        }

        fun observeRealErrors(): RealErrors = RealErrors().also { errors ->
            listeners += errors
            onMain { service.player.addListener(errors) }
        }

        fun observeFailureIds(): List<Long> {
            val events = mutableListOf<Long>()
            onMain {
                val baseline = service.playbackFailure.value?.id ?: 0L
                observationJobs += service.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    service.playbackFailure.collect { failure ->
                        if (failure != null && failure.id > baseline && events.lastOrNull() != failure.id) events += failure.id
                    }
                }
            }
            return events
        }

        private fun model() = ViewModelProvider(checkNotNull(activity))[ThorShellViewModel::class.java]

        fun verifyShoulderInputs() {
            if (onMain { model().navigation.value.overlay != ThorOverlay.Player }) press(KeyEvent.KEYCODE_BUTTON_START)
            await("controller_player_overlay") { model().navigation.value.overlay == ThorOverlay.Player }
            onMain { service.player.pause(); service.player.seekTo(10_000L) }
            await("controller_paused_seek_anchor") { !service.player.playWhenReady && service.player.currentPosition == 10_000L }
            val entry = onMain { service.player.currentMediaItem?.queueEntryId }
            press(KeyEvent.KEYCODE_BUTTON_R1)
            await("right_shoulder_tap_plus15") { abs(service.player.currentPosition - 25_000L) < 100 }
            press(KeyEvent.KEYCODE_BUTTON_L1)
            await("left_shoulder_tap_minus15") { abs(service.player.currentPosition - 10_000L) < 100 }
            for ((key, direction) in listOf(KeyEvent.KEYCODE_BUTTON_R1 to 1, KeyEvent.KEYCODE_BUTTON_L1 to -1)) {
                val anchor = onMain { service.player.currentPosition }
                val downAt = onMain {
                    SystemClock.uptimeMillis().also { dispatchKeyOnMain(key, true, it) }
                }
                var released = false
                try {
                    await("shoulder_hold_enters_preview") { model().seekPreview.value != null }
                    val initial = onMain { checkNotNull(model().seekPreview.value) }
                    await("shoulder_hold_repeats_preview") {
                        model().seekPreview.value?.let { (it - initial) * direction >= 1_500L } == true
                    }
                    val target = onMain {
                        assertTrue("hold_preview_did_seek_early=false", service.player.currentPosition == anchor)
                        assertTrue("hold_preview_changed_entry=false", service.player.currentMediaItem?.queueEntryId == entry)
                        val finalPreview = checkNotNull(model().seekPreview.value)
                        dispatchKeyOnMain(key, false, downAt)
                        released = true
                        finalPreview
                    }
                    await("shoulder_release_applies_preview") { model().seekPreview.value == null && abs(service.player.currentPosition - target) < 100 }
                } finally {
                    if (!released) onMain { dispatchKeyOnMain(key, false, downAt) }
                }
            }
            onMain {
                assertFalse("shoulder_preview_changed_paused_intent=false", service.player.playWhenReady)
                assertTrue("shoulder_controls_kept_entry=false", service.player.currentMediaItem?.queueEntryId == entry)
            }
            Log.i("ThorLivePlayback", "native_chain_software_injection=true shoulder_tap_hold_release=true")
        }

        fun verifyAnalogTriggerLatch(identities: List<String?>) {
            onMain { service.player.shuffleModeEnabled = false; service.player.seekTo(0, 0L) }
            awaitProgress(0)
            val device = InputDevice.getDeviceIds().asSequence().mapNotNull(InputDevice::getDevice)
                .filter { it.sources and InputDevice.SOURCE_CLASS_JOYSTICK != 0 }
                .sortedByDescending { it.name.contains("odin", ignoreCase = true) }
                .firstOrNull { candidate -> listOf(MotionEvent.AXIS_GAS, MotionEvent.AXIS_RTRIGGER).any { axis ->
                    candidate.getMotionRange(axis)?.let { it.min <= 0f && it.max >= 1f } == true
                } }
            val deviceId = device?.id ?: -1
            val axis = listOf(MotionEvent.AXIS_GAS, MotionEvent.AXIS_RTRIGGER).firstOrNull {
                device?.getMotionRange(it) != null
            } ?: MotionEvent.AXIS_RTRIGGER
            fun send(value: Float) = onMain {
                val now = SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1,
                    arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN }),
                    arrayOf(MotionEvent.PointerCoords().apply { setAxisValue(axis, value) }),
                    0, 0, 1f, 1f, deviceId, 0, InputDevice.SOURCE_JOYSTICK, 0)
                try { assertTrue("activity_analog_event_consumed=false", checkNotNull(activity).dispatchGenericMotionEvent(event)) }
                finally { event.recycle() }
            }
            try {
                send(0f); send(.9f)
                awaitProgress(1)
                for (value in listOf(.9f, .9f, .2f, .9f)) { send(value); SystemClock.sleep(40) }
                SystemClock.sleep(300)
                onMain { assertTrue("analog_unreleased_stroke_repeated=false", service.player.currentMediaItem?.queueEntryId == identities[1]) }
                send(0f); send(.9f)
                awaitProgress(2)
                onMain { assertTrue("analog_stroke_replaced_queue=false", identities == entryIds()) }
                Log.i("ThorLivePlayback", "native_chain_software_injection=true analog_rearm=true physical_axis_capability=${device != null}")
            } finally { send(0f) }
        }

        fun openThor() {
            if (scenario != null) return
            scenario = ActivityScenario.launch<ThorActivity>(Intent(context, ThorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            scenario!!.onActivity { current ->
                current.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity = current
            }
            val handledAccount = ThorShellViewModel::class.java.getDeclaredField("handledAccountGeneration").apply { isAccessible = true }
            await("thor_bridge_connected", 15_000) {
                val model = ViewModelProvider(checkNotNull(activity))[ThorShellViewModel::class.java]
                model.playback.state.value.connected && model.library.value.account.userId == accountId &&
                    handledAccount.getLong(model) == model.library.value.account.generation && model.preferences.value.reduceMotion
            }
            // Let the lower Presentation's composition install its command collector.
            await("thor_lower_attached", 10_000) {
                android.view.inspector.WindowInspector.getGlobalWindowViews().any { view ->
                    view.isAttachedToWindow && view.isLaidOut && view.findViewTreeLifecycleOwner() === activity &&
                        view.display?.mode?.let {
                            maxOf(it.physicalWidth, it.physicalHeight) == 1240 && minOf(it.physicalWidth, it.physicalHeight) == 1080
                        } == true
                }
            }
        }

        fun press(key: Int) = onMain {
            val now = SystemClock.uptimeMillis()
            dispatchKeyOnMain(key, true, now)
            dispatchKeyOnMain(key, false, now)
        }

        private fun dispatchKeyOnMain(key: Int, down: Boolean, downAt: Long) {
            assertTrue("activity_key_consumed=false", checkNotNull(activity).dispatchKeyEvent(KeyEvent(downAt, SystemClock.uptimeMillis(),
                if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, key, 0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD)))
        }

        fun preparePausedSixEntryQueue(requireMultipleQualities: Boolean = false) {
            val songs = playableSongs()
            songs.forEach(::verifyFullHttpSource)
            if (requireMultipleQualities) {
                val qualities = network("live_quality_precondition", 60_000) {
                    service.mediaUriProvider.getAvailableMusicQualities(songs.first().id.toString(), supportDolby = false)
                }
                assertTrue("real_quality_fallback_precondition_missing=false", qualities.size >= 2)
            }
            seed(List(6) { songs[it % songs.size] }, play = false)
            onMain { service.player.prepare() }
            await("real_online_paused_ready", 45_000) {
                service.player.playbackState == Player.STATE_READY && !service.player.playWhenReady && service.currentMusicQuality.value != null
            }
        }

        fun close() {
            try {
                // Dispose both windows while the temporary reduce-motion setting is still
                // active; continuous decoration must not hold lifecycle/idle cleanup open.
                scenario?.close()
                scenario = null
                activity = null
                if (::service.isInitialized && snapshot != null) onMain {
                    observationJobs.forEach { it.cancel() }
                    listeners.forEach(service.player::removeListener)
                    service.cancelUnavailableRecovery()
                    service.player.pause()
                    service.player.stop()
                    service.queueManager.cancelServerShuffle()
                    snapshot?.restore(service)
                }
                // Drain player callbacks and their queued preference collectors without
                // waiting for global UI idleness. Source preparation may continue normally.
                if (snapshot != null) { onMain { }; onMain { } }
                savedPreferences?.let { saved -> network("restore_preferences", 10_000) {
                    context.dataStore.edit(saved::restore)
                } }
                if (::service.isInitialized) {
                    onMain {
                        service.queueManager.cancelServerShuffle()
                        snapshot?.verify(service.player)
                        service.schedulePlaybackSnapshot()
                    }
                    val cache = CacheManager.getSimpleCache(context)
                    assertTrue("preexisting_cache_bytes_retained=false", protectedCache.all { (key, position, length) -> cache.isCached(key, position, length) })
                }
                if (accountId != null) network("same_account_after_playback", 10_000) {
                    val p = context.dataStore.data.first()
                    assertTrue("account_changed_during_playback=false", p.neteaseSessionType() == accountType && p[UserIdKey] == accountId)
                }
            } finally {
                try { scenario?.close() } finally { if (bound) context.unbindService(connection) }
            }
        }
    }

    private class RealErrors : Player.Listener {
        var denied = 0
        var total = 0
        var firstErrorAtMs = 0L
        val seekTargets = mutableListOf<String?>()
        override fun onPlayerError(error: PlaybackException) {
            total++
            if (firstErrorAtMs == 0L) firstErrorAtMs = SystemClock.elapsedRealtime()
            val sourceDenied = generateSequence<Throwable>(error) { it.cause }.take(16).any { it is SourceNotFoundException }
            if (sourceDenied) denied++
            Log.i("ThorLivePlayback", "real_player_error_code=${error.errorCode} full_source_denied=$sourceDenied")
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) seekTargets += newPosition.mediaItem?.queueEntryId
        }
    }

    private data class SavedQueue(
        val items: List<MediaItem>, val index: Int, val positionMs: Long, val order: List<Int>,
        val shuffle: Boolean, val repeat: Int, val playWhenReady: Boolean, val wasIdle: Boolean,
        val title: String?, val fm: Boolean, val similarIds: List<String>?, val autoplay: Boolean,
        val autoplayStartIndex: Int?, val playlistSource: PlaylistQueueSource?,
    ) {
        fun restore(service: MusicService) {
            val p = service.player
            p.shuffleModeEnabled = false
            if (items.isEmpty()) p.clearMediaItems() else p.activeDeck.setMediaItems(items, index, positionMs)
            p.setPlaybackOrder(order)
            service.queueTitle = title
            service.queueManager.restoreFmMode(fm, similarIds, autoplay)
            service.queueManager.restoreQueueAutoplayStartIndex(autoplayStartIndex)
            service.queueManager.restorePlaylistSource(playlistSource)
            p.repeatMode = repeat
            p.shuffleModeEnabled = shuffle
            if (!wasIdle && items.isNotEmpty()) p.prepare()
            p.playWhenReady = playWhenReady
        }
        fun verify(p: StableDeckPlayer) {
            assertTrue("original_queue_restored=false", items.map { it.queueEntryId } == List(p.mediaItemCount) { p.getMediaItemAt(it).queueEntryId })
            assertTrue("original_media_restored=false", items.map { it.mediaId } == List(p.mediaItemCount) { p.getMediaItemAt(it).mediaId })
            assertTrue("original_order_restored=false", order == p.playbackOrderIndices(true))
            assertTrue("original_modes_restored=false", p.shuffleModeEnabled == shuffle && p.repeatMode == repeat && p.playWhenReady == playWhenReady)
            if (items.isNotEmpty()) assertTrue("original_position_restored=false", p.currentMediaItemIndex == index && abs(p.currentPosition - positionMs) < 1_000)
        }
        companion object {
            fun capture(s: MusicService): SavedQueue {
                val p = s.player
                return SavedQueue(List(p.mediaItemCount, p::getMediaItemAt), p.currentMediaItemIndex, p.currentPosition,
                    p.playbackOrderIndices(true), p.shuffleModeEnabled, p.repeatMode, p.playWhenReady, p.playbackState == Player.STATE_IDLE,
                    s.queueTitle, s.queueManager.isFmMode, s.queueManager.similarFmSourceIds, s.queueManager.queueAutoplayEnabled.value,
                    (0 until p.mediaItemCount).firstOrNull { p.getMediaItemAt(it).queueEntryId == s.queueManager.queueAutoplayStartEntryId.value },
                    s.queueManager.playlistSource)
            }
        }
    }

    private data class SavedPreferences(val skip: Boolean?, val autoMix: Boolean?, val shuffle: Boolean?, val repeat: Int?,
        val quality: String?, val reduceMotion: Boolean?) {
        fun restore(p: MutablePreferences) {
            p.restore(NoAudioSourceKey, skip)
            p.restore(AutoMixEnabledKey, autoMix)
            p.restore(IsShuffleModeKey, shuffle)
            p.restore(RepeatModeKey, repeat)
            p.restore(MusicQualityKey, quality)
            p.restore(LiveReduceMotionKey, reduceMotion)
        }
    }

    private fun <T> network(stage: String, timeoutMs: Long, block: suspend () -> T): T {
        val result = runCatching { runBlocking(Dispatchers.IO) { withTimeout(timeoutMs) { block() } } }
        assertTrue("${stage}_ok=false", result.isSuccess)
        return result.getOrThrow()
    }

    private fun await(stage: String, timeoutMs: Long = 10_000, predicate: () -> Boolean) = runBlocking {
        val passed = runCatching { withTimeout(timeoutMs) { while (!onMain(predicate)) delay(40) } }.isSuccess
        assertTrue("${stage}_within_bound=false", passed)
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            try { result.set(block()) } catch (error: Throwable) { failure.set(error) }
        }
        failure.get()?.let { throw it }
        return result.get()
    }
}

private val LiveReduceMotionKey = booleanPreferencesKey("thor.reduceMotion")

private fun <T> MutablePreferences.restore(key: Preferences.Key<T>, value: T?) {
    if (value == null) remove(key) else this[key] = value
}

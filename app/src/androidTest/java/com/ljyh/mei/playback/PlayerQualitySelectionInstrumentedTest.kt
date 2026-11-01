package com.ljyh.mei.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.ljyh.mei.constants.AutoCacheEnabledKey
import com.ljyh.mei.constants.AutoMixEnabledKey
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.constants.MusicQualityKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.SongUrl
import com.ljyh.mei.data.model.Tracks
import com.ljyh.mei.data.model.api.GetSongUrlV1
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.di.repository.SongRepository
import com.ljyh.mei.playback.queue.PlaylistQueueSource
import com.ljyh.mei.thor.debug.ThorLiveCatalogEntryPoint
import com.ljyh.mei.thor.test.ThorLiveEnvironment
import com.ljyh.mei.utils.dataStore
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual Service/Connection/Provider/decoder with isolated synthetic API levels and a quiet WAV.
 * The API fixture verifies requested-versus-reported quality, not the WAV's acoustic fidelity.
 * No catalog request, account mutation or fixture row is sent to the user's database.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlayerQualitySelectionInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun fallbackIsPerSongAndExplicitSelectionPreservesTheActiveQueuePositionAndIntent() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("thor.qualitySelection") == "true")
        ThorLiveEnvironment.requireRequestedDevice(context)
        val fixture = Fixture()
        try {
            fixture.open()
            val service = fixture.service
            val connection = fixture.playerConnection
            await("fallback_source_not_ready") {
                service.player.playbackState == Player.STATE_READY &&
                    service.player.currentMediaItem?.queueEntryId == fixture.entries[0] &&
                    connection.currentMusicQuality.value == MusicQuality.EXHIGH
            }
            assertEquals("Fallback cannot rewrite the global target", MusicQuality.LOSSLESS.name, preference())
            await("requested_flow_did_not_keep_lossless") { connection.requestedMusicQuality.value == MusicQuality.LOSSLESS }
            assertFalse(onMain { connection.isPlayingLocalSource.value })
            assertTrue(fixture.requests.any { it.first == fixture.ids[0] && it.second == "lossless" })

            onMain { service.player.seekTo(1, 1_250); service.player.prepare() }
            await("next_song_did_not_return_to_requested_quality") {
                service.player.playbackState == Player.STATE_READY &&
                    service.player.currentMediaItem?.queueEntryId == fixture.entries[1] &&
                    connection.currentMusicQuality.value == MusicQuality.LOSSLESS
            }
            assertEquals(MusicQuality.LOSSLESS.name, preference())
            assertTrue("The next song must start from the user's target, not the previous fallback",
                fixture.requests.any { it.first == fixture.ids[1] && it.second == "lossless" })

            val pausedPosition = onMain {
                assertFalse(service.player.playWhenReady)
                service.player.currentPosition.also { connection.changeQuality(MusicQuality.HIRES) }
            }
            await("explicit_paused_quality_did_not_apply") {
                service.player.playbackState == Player.STATE_READY &&
                    connection.requestedMusicQuality.value == MusicQuality.HIRES &&
                    connection.currentMusicQuality.value == MusicQuality.HIRES
            }
            assertEquals(MusicQuality.HIRES.name, preference())
            onMain {
                fixture.verifyFixtureQueue()
                assertFalse("A paused selection must stay paused", service.player.playWhenReady)
                assertTrue("Changing quality must retain position", abs(service.player.currentPosition - pausedPosition) <= 500)
                service.player.play()
            }
            await("quiet_fixture_did_not_play") { service.player.isPlaying && service.player.currentPosition > pausedPosition + 150 }
            val playingPosition = onMain {
                service.player.currentPosition.also { connection.changeQuality(MusicQuality.EXHIGH) }
            }
            await("explicit_playing_quality_did_not_apply") {
                service.player.playbackState == Player.STATE_READY &&
                    connection.requestedMusicQuality.value == MusicQuality.EXHIGH &&
                    connection.currentMusicQuality.value == MusicQuality.EXHIGH && service.player.isPlaying
            }
            assertEquals(MusicQuality.EXHIGH.name, preference())
            onMain {
                fixture.verifyFixtureQueue()
                assertTrue("A playing selection must remain playing", service.player.playWhenReady)
                assertTrue("Changing quality cannot restart the song", service.player.currentPosition >= playingPosition - 250)
                assertTrue("Changing quality cannot jump forward", service.player.currentPosition <= playingPosition + 1_500)
                assertTrue(connection.availableMusicQualities.value.contains(MusicQuality.EXHIGH))
                service.player.pause()
            }
        } finally {
            fixture.close()
        }
    }

    private inner class Fixture {
        lateinit var service: MusicService
        lateinit var playerConnection: PlayerConnection
        private val connected = CountDownLatch(1)
        private val serviceRef = AtomicReference<MusicService>()
        private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var bound = false
        private var saved: SavedQueue? = null
        private var preferences: Map<Preferences.Key<*>, Any?>? = null
        private var originalProvider: MediaUriProvider? = null
        private var originalAutomaticCache: AutomaticCacheController? = null
        private var database: AppDatabase? = null
        private var wave: File? = null
        private val runId = UUID.randomUUID().toString()
        private val firstId = 4_000_000_000_000_000_000L + UUID.randomUUID().mostSignificantBits.ushr(2)
        val ids = listOf(firstId.toString(), (firstId + 1).toString())
        val entries = listOf("quality-fixture-$runId-a", "quality-fixture-$runId-b")
        val requests = ConcurrentLinkedQueue<Pair<String, String>>()
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                serviceRef.set((binder as MusicService.MusicBinder).service)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }

        fun open() {
            bound = context.bindService(Intent(context, MusicService::class.java), connection, Context.BIND_AUTO_CREATE)
            assertTrue("MusicService must bind", bound)
            assertTrue("MusicService bind timed out", connected.await(10, TimeUnit.SECONDS))
            service = serviceRef.get()
            val restoring = MusicService::class.java.getDeclaredField("isRestoringPlayback").apply { isAccessible = true }
            await("original_service_restore_not_complete", 20_000) { !restoring.getBoolean(service) }
            // The cache's configured minimum is 256 MiB. Refuse before any fixture mutation if
            // our bounded files could make its LRU evict pre-existing user audio.
            assertTrue("insufficient_safe_cache_headroom",
                CacheManager.getSimpleCache(context).cacheSpace + 8 * 1024 * 1024 < 256L * 1024 * 1024)
            val current = io { context.dataStore.data.first() }
            val keys: List<Preferences.Key<*>> = listOf(MusicQualityKey, AutoMixEnabledKey, AutoCacheEnabledKey)
            preferences = keys.associateWith { current[it] }
            database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            wave = File(context.cacheDir, "quality-selection-$runId.wav").also(::writeWave)
            val fakeApi = api()
            val provider = MediaUriProvider(fakeApi, SongRepository(checkNotNull(database).songDao()), context)
            val entry = EntryPointAccessors.fromApplication(context, ThorLiveCatalogEntryPoint::class.java)
            onMain {
                saved = SavedQueue.capture(service)
                originalProvider = service.mediaUriProvider
                originalAutomaticCache = service.automaticCacheController
                service.player.pause()
                service.player.stop()
                service.cancelUnavailableRecovery()
                service.resetPlaybackSourcesForQualityChange()
                service.queueManager.cancelServerShuffle()
                service.queueManager.restoreFmMode(false)
                service.queueManager.restorePlaylistSource(null)
                provider.onSourceResolved = service.mediaUriProvider.onSourceResolved
                service.mediaUriProvider = provider
                // Even a slow emulator cannot insert a synthetic playback_count into user DB.
                service.automaticCacheController = AutomaticCacheController(context, entry.playlistRepository(), checkNotNull(database))
                service.player.volume = 0f
            }
            io { context.dataStore.edit {
                it[MusicQualityKey] = MusicQuality.LOSSLESS.name
                it[AutoMixEnabledKey] = false
                it[AutoCacheEnabledKey] = false
            } }
            onMain {
                playerConnection = PlayerConnection(context, service.MusicBinder(), checkNotNull(database), owner)
                val items = ids.mapIndexed { index, id ->
                    val metadata = MediaMetadata(id.toLong(), "Quality fixture", "", emptyList(), 30_000,
                        MediaMetadata.Album(-1, "Synthetic audio"), isPodcast = true)
                    MediaItem.Builder().setMediaId(id).setCustomCacheKey(id).setUri(Uri.parse("https://example.invalid/quality-placeholder"))
                        .setTag(metadata).setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(metadata.title).build())
                        .build().withQueueEntryId(entries[index])
                }
                service.player.shuffleModeEnabled = false
                service.player.repeatMode = Player.REPEAT_MODE_ALL
                service.player.activeDeck.setMediaItems(items, 0, 1_250)
                service.player.setPlaybackOrder(listOf(1, 0))
                service.queueTitle = "Quality selection fixture"
                service.player.playWhenReady = false
                service.player.prepare()
            }
        }

        private fun api(): ApiService {
            val gson = Gson()
            return Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, arguments ->
                when (method.name) {
                    "getSongDetail" -> gson.fromJson("""{"code":200,"songs":[],"privileges":[]}""", Tracks::class.java)
                    "getSongUrlV1" -> {
                        val request = arguments!![0] as GetSongUrlV1
                        val id = request.ids.removePrefix("[").removeSuffix("]")
                        check(id in ids) { "Unexpected synthetic source identity" }
                        requests += id to request.level
                        val actual = if (id == ids[0]) "exhigh" else request.level
                        val source = """{"code":200,"data":[{"id":$id,"code":200,"level":"$actual",
                            "url":"${Uri.fromFile(checkNotNull(wave))}","md5":"$runId-$id-$actual",
                            "size":${checkNotNull(wave).length()},"expi":300,"freeTrialInfo":null}]}"""
                        gson.fromJson(source, SongUrl::class.java)
                    }
                    else -> error("Unexpected synthetic API operation")
                }
            } as ApiService
        }

        fun verifyFixtureQueue() {
            val player = service.player
            assertTrue("Quality changes must retain both source identities", ids == List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId })
            assertTrue("Quality changes must retain occurrence identities", entries == List(player.mediaItemCount) { player.getMediaItemAt(it).queueEntryId })
            assertEquals(1, player.currentMediaItemIndex)
            assertEquals(listOf(1, 0), player.playbackOrderIndices(true))
            assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
            assertFalse(player.shuffleModeEnabled)
            assertEquals("Quality selection fixture", service.queueTitle)
        }

        fun close() {
            val failures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) { try { action() } catch (failure: Throwable) { failures += failure } }
            cleanup {
                if (::playerConnection.isInitialized) onMain { playerConnection.dispose() }
                owner.cancel()
            }
            cleanup {
                if (::service.isInitialized && saved != null) onMain {
                    service.player.pause()
                    service.player.stop()
                    service.cancelUnavailableRecovery()
                    service.resetRejectedPlaybackSources()
                }
            }
            cleanup {
                preferences?.let { old -> io {
                    context.dataStore.edit { prefs -> old.forEach { (key, value) ->
                        @Suppress("UNCHECKED_CAST") val typed = key as Preferences.Key<Any>
                        if (value == null) prefs.remove(typed) else prefs[typed] = value
                    } }
                    val after = context.dataStore.data.first()
                    assertTrue("The exact nullable original preferences must be restored", old.all { (key, value) -> after[key] == value })
                } }
            }
            cleanup {
                saved?.let { original -> onMain {
                    service.mediaUriProvider.onSourceResolved = null
                    service.mediaUriProvider = checkNotNull(originalProvider)
                    service.automaticCacheController = checkNotNull(originalAutomaticCache)
                    original.restore(service)
                    original.verify(service)
                    service.schedulePlaybackSnapshot()
                } }
            }
            cleanup {
                val cache = CacheManager.getSimpleCache(context)
                ids.forEach { id -> cache.keys.filter { it.startsWith(playbackCacheKeyPrefix(id)) && it.contains(runId) }.forEach(cache::removeResource) }
            }
            cleanup { wave?.let { assertTrue("The fixture WAV must be deleted", !it.exists() || it.delete()) } }
            cleanup { database?.close() }
            cleanup { if (bound) context.unbindService(connection) }
            if (failures.isNotEmpty()) throw AssertionError("Quality fixture cleanup failed", failures.first()).apply {
                failures.drop(1).forEach(::addSuppressed)
            }
        }
    }

    private data class SavedQueue(
        val items: List<MediaItem>, val index: Int, val position: Long, val order: List<Int>,
        val shuffle: Boolean, val repeat: Int, val ready: Boolean, val idle: Boolean, val title: String?,
        val fm: Boolean, val similar: List<String>?, val autoplay: Boolean, val autoplayIndex: Int?,
        val source: PlaylistQueueSource?, val volume: Float,
    ) {
        fun restore(service: MusicService) {
            val p = service.player
            p.shuffleModeEnabled = false
            if (items.isEmpty()) p.clearMediaItems() else p.activeDeck.setMediaItems(items, index, position)
            p.setPlaybackOrder(order)
            service.queueManager.restoreFmMode(fm, similar, autoplay)
            service.queueManager.restoreQueueAutoplayStartIndex(autoplayIndex)
            service.queueManager.restorePlaylistSource(source)
            service.queueTitle = title
            p.repeatMode = repeat
            p.shuffleModeEnabled = shuffle
            p.volume = volume
            if (!idle && items.isNotEmpty()) p.prepare()
            p.playWhenReady = ready
        }
        fun verify(service: MusicService) {
            val p = service.player
            assertTrue("The original queue must be restored", items == List(p.mediaItemCount, p::getMediaItemAt))
            assertTrue("The original order must be restored", order == p.playbackOrderIndices(true))
            assertTrue("The original modes must be restored", p.repeatMode == repeat && p.shuffleModeEnabled == shuffle && p.playWhenReady == ready)
            if (items.isNotEmpty()) assertTrue("The original position must be restored", p.currentMediaItemIndex == index && abs(p.currentPosition - position) <= 1_000)
            assertTrue("The original queue source must be restored", service.queueTitle == title &&
                service.queueManager.playlistSource == source && service.queueManager.isFmMode == fm &&
                service.queueManager.similarFmSourceIds == similar && service.queueManager.queueAutoplayEnabled.value == autoplay)
            assertEquals(volume, p.volume, .001f)
        }
        companion object {
            fun capture(service: MusicService): SavedQueue {
                val p = service.player
                val q = service.queueManager
                return SavedQueue(List(p.mediaItemCount, p::getMediaItemAt), p.currentMediaItemIndex, p.currentPosition,
                    p.playbackOrderIndices(true), p.shuffleModeEnabled, p.repeatMode, p.playWhenReady,
                    p.playbackState == Player.STATE_IDLE, service.queueTitle, q.isFmMode, q.similarFmSourceIds,
                    q.queueAutoplayEnabled.value, (0 until p.mediaItemCount).firstOrNull {
                        p.getMediaItemAt(it).queueEntryId == q.queueAutoplayStartEntryId.value }, q.playlistSource, p.volume)
            }
        }
    }

    private fun writeWave(file: File) {
        val rate = 16_000
        val payload = ByteArray(rate * 30 * 2)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + payload.size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(payload.size).array()
        file.outputStream().use { it.write(header); it.write(payload) }
    }
    private fun preference() = io { context.dataStore.data.first()[MusicQualityKey] }
    private fun await(message: String, timeout: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(30)
        }
        fail(message)
    }
    private fun <T> onMain(action: () -> T): T {
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync { try { result.set(action()) } catch (error: Throwable) { failure.set(error) } }
        failure.get()?.let { throw it }
        return result.get()
    }
    private fun <T> io(action: suspend () -> T): T = runBlocking(Dispatchers.IO) { withTimeout(15_000) { action() } }
}

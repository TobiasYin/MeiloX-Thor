package com.ljyh.mei.playback

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real deck/entry transitions, a deterministic fake resolver and generated local silence only. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackRecoveryScanInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 45_000)
    fun failedCandidatesNeverBecomeVisibleAndOnlyThePlayableEntryIsCommitted() = withDecks { fixture ->
        val probes = CopyOnWriteArrayList<String>()
        val transitions = CopyOnWriteArrayList<Int>()
        val interruptions = AtomicInteger()
        val result = CompletableDeferred<PlaybackRecoveryResult>()
        instrumentation.runOnMainSync {
            assertFalse("The original failed deck is not playing", fixture.player.isPlaying)
            assertTrue("An error must retain the user's intent to continue", fixture.player.playWhenReady)
            fixture.player.onRecoveryInterrupted = { interruptions.incrementAndGet() }
            fixture.player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) transitions += fixture.player.currentMediaItemIndex
                }
            })
            fixture.scope.launch {
                result.complete(scanPlaybackRecoveryCandidates(fixture.candidates, fixture.campaign,
                    isCurrent = { fixture.stillCurrent() },
                    probe = { probes += it.mediaId; it == fixture.items[2] },
                    commit = { candidate ->
                        fixture.player.withInternalQueueUpdate {
                            fixture.player.seekToDefaultPosition(fixture.items.indexOf(candidate.value))
                            fixture.player.prepare()
                            fixture.player.playWhenReady = true
                        }
                        true
                    }))
            }
        }
        assertEquals(PlaybackRecoveryResult.Recovered("recovery-entry-2"), await(result))
        assertEquals(listOf(fixture.items[1].mediaId, fixture.items[2].mediaId), probes.toList())
        awaitCondition { fixture.player.playbackState == Player.STATE_READY }
        instrumentation.runOnMainSync {
            assertEquals(2, fixture.player.currentMediaItemIndex)
            assertEquals("recovery-entry-2", fixture.player.currentMediaItem?.queueEntryId)
            assertEquals(listOf(2), transitions.toList())
            assertNull(fixture.player.playerError)
            assertEquals("The one internal commit must not cancel its own campaign", 0, interruptions.get())
        }
    }

    @Test(timeout = 45_000)
    fun allUnavailableCandidatesLeaveTheOriginalEntrySelectedAndOnlyThenPause() = withDecks { fixture ->
        val result = CompletableDeferred<PlaybackRecoveryResult>()
        val commits = AtomicInteger()
        instrumentation.runOnMainSync {
            fixture.scope.launch {
                result.complete(scanPlaybackRecoveryCandidates(fixture.candidates, fixture.campaign,
                    { fixture.stillCurrent() }, { false }, { commits.incrementAndGet(); true }))
            }
        }
        assertEquals(PlaybackRecoveryResult.Exhausted, await(result))
        instrumentation.runOnMainSync {
            fixture.player.withInternalQueueUpdate { handleUnavailablePlaybackSource(fixture.player) }
            assertEquals(0, fixture.player.currentMediaItemIndex)
            assertEquals("recovery-entry-0", fixture.player.currentMediaItem?.queueEntryId)
            assertFalse(fixture.player.playWhenReady)
            assertEquals(0, commits.get())
        }
    }

    @Test(timeout = 90_000)
    fun pauseStopSeekAndQueueEditsImmediatelyCancelTheProbeBeforeItCanCommit() {
        for (operation in listOf("pause", "stop", "seek", "queue")) withDecks { fixture ->
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val commits = AtomicInteger()
            lateinit var job: Job
            instrumentation.runOnMainSync {
                fixture.player.onRecoveryInterrupted = { job.cancel() }
                job = fixture.scope.launch {
                    scanPlaybackRecoveryCandidates(fixture.candidates, fixture.campaign,
                        { fixture.stillCurrent() }, { started.complete(Unit); release.await(); true },
                        { commits.incrementAndGet(); true })
                }
            }
            await(started)
            instrumentation.runOnMainSync {
                when (operation) {
                    "pause" -> fixture.player.pause()
                    "stop" -> fixture.player.stop()
                    "seek" -> fixture.player.seekToDefaultPosition(1)
                    "queue" -> fixture.player.removeMediaItem(2)
                }
                assertTrue("$operation must cancel synchronously before the resolver returns", job.isCancelled)
                release.complete(Unit)
            }
            runBlocking { withTimeout(5_000) { job.join() } }
            assertEquals("$operation must prevent every automatic commit", 0, commits.get())
        }
    }

    private class Fixture(val player: StableDeckPlayer, val items: List<MediaItem>) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val campaign = PlaybackRecoveryCampaign(nowMs = SystemClock::elapsedRealtime).apply { markFailed("recovery-entry-0") }
        val candidates = items.drop(1).map { PlaybackRecoveryCandidate(checkNotNull(it.queueEntryId), it) }
        private val epoch = player.queueIdentityEpoch
        fun stillCurrent() = player.playWhenReady && player.queueIdentityEpoch == epoch &&
            player.currentMediaItem?.queueEntryId == "recovery-entry-0"
    }

    private fun withDecks(check: (Fixture) -> Unit) {
        assumeTrue("Run only on the dedicated emulator", Build.HARDWARE in setOf("ranchu", "goldfish") ||
            Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("sdk_gphone", true))
        val context = instrumentation.targetContext
        val silence = File(context.cacheDir, "thor-recovery-${UUID.randomUUID()}.wav")
        val missing = File(context.cacheDir, "thor-recovery-missing-${UUID.randomUUID()}.wav")
        val sampleBytes = 16_000 * 2 * 4
        val wave = ByteBuffer.allocate(44 + sampleBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + sampleBytes)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(sampleBytes)
        }.array()
        silence.writeBytes(wave)
        var fixture: Fixture? = null
        try {
            instrumentation.runOnMainSync {
                val first = ExoPlayer.Builder(context).build()
                val second = ExoPlayer.Builder(context).build()
                val player = StableDeckPlayer(first, second, AudioAttributes.DEFAULT, false)
                val items = (0..2).map { index -> MediaItem.Builder().setMediaId("${910_000 + index}")
                    .setUri(Uri.fromFile(if (index == 2) silence else missing)).build()
                    .withQueueEntryId("recovery-entry-$index") }
                player.setMediaItems(items, 0, 0L)
                player.playWhenReady = true
                player.prepare()
                fixture = Fixture(player, items)
            }
            awaitCondition { checkNotNull(fixture).player.playerError != null }
            check(checkNotNull(fixture))
        } finally {
            instrumentation.runOnMainSync {
                fixture?.let { it.scope.cancel(); it.player.onRecoveryInterrupted = null; it.player.release() }
            }
            silence.delete()
        }
    }

    private fun <T> await(deferred: CompletableDeferred<T>): T = runBlocking { withTimeout(5_000) { deferred.await() } }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(20)
        }
        fail("The real local deck did not reach its expected state within five seconds")
    }
}

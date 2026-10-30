package com.ljyh.mei.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.thor.playback.ThorQueueUndoTracker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory unprepared decks: no service, account, network, decoder or audio playback. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class StableDeckQueueIdentityInstrumentedTest {
    @Test fun preallocatedIdsSurviveRealSetAndAddWhileConflictsBecomeNewOccurrences() = withPlayer { player ->
        val first = item("same-song", "first")
        val second = item("same-song", "second")
        var epoch = 0L
        onMain { player.setMediaItems(listOf(first, second)) }
        idle()
        onMain {
            assertEquals(listOf("first", "second"), player.entryIds())
            epoch = player.queueIdentityEpoch
            player.addMediaItems(listOf(item("same-song", "append"), item("same-song", "append"), item("same-song", "first"), item("same-song")))
        }
        idle()
        onMain {
            val actual = player.entryIds()
            assertEquals(listOf("first", "second", "append"), actual.take(3))
            assertEquals(6, actual.toSet().size)
            assertTrue(actual.all { !it.isNullOrBlank() })
            assertEquals(epoch, player.queueIdentityEpoch)
            assertEquals("first", player.currentMediaItem?.queueEntryId)
        }
    }

    @Test fun aFullReplacementRetainsValidIdsButInvalidatesOldQueueEpoch() = withPlayer { player ->
        var epoch = 0L
        onMain { player.setMediaItems(listOf(item("song", "stable"))) }
        idle()
        onMain {
            epoch = player.queueIdentityEpoch
            player.setMediaItems(listOf(item("song", "stable"), item("song", "stable")))
        }
        idle()
        onMain {
            assertEquals("stable", player.getMediaItemAt(0).queueEntryId)
            assertNotEquals("stable", player.getMediaItemAt(1).queueEntryId)
            assertTrue(player.queueIdentityEpoch > epoch)
            epoch = player.queueIdentityEpoch
            player.replaceMediaItems(0, player.mediaItemCount, listOf(item("new-song", "stable")))
        }
        idle()
        onMain {
            assertEquals(listOf("stable"), player.entryIds())
            assertTrue(player.queueIdentityEpoch > epoch)
        }
    }

    @Test fun metadataRefreshKeepsItsOccurrenceAndPartialReplacementRejectsRetainedCollision() = withPlayer { player ->
        var epoch = 0L
        onMain { player.setMediaItems(listOf(item("a", "a"), item("b", "b"), item("c", "c"))) }
        idle()
        onMain {
            epoch = player.queueIdentityEpoch
            player.replaceMediaItem(1, item("b", "incoming-metadata-id"))
        }
        idle()
        onMain {
            assertEquals(listOf("a", "b", "c"), player.entryIds())
            assertEquals(epoch, player.queueIdentityEpoch)
            player.replaceMediaItem(1, item("different-song", "a"))
        }
        idle()
        onMain {
            assertEquals("a", player.getMediaItemAt(0).queueEntryId)
            assertEquals("c", player.getMediaItemAt(2).queueEntryId)
            assertEquals(3, player.entryIds().toSet().size)
        }
    }

    @Test fun realNormalizedInsertionUndoPreservesPlayedAAndOnlyRemovesPendingBWithoutSeekingCurrent() = withPlayer { player ->
        val tracker = ThorQueueUndoTracker()
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { tracker.observeCurrent(mediaItem?.queueEntryId) }
        }
        onMain {
            tracker.beginEpoch()
            player.addListener(listener)
            player.setMediaItems(listOf(item("original", "original")))
        }
        idle()
        val inserted = normalizeQueueMediaItems(listOf(item("same-song", "insert-a"), item("same-song", "insert-b")), setOf("original"))
        val insertedIds = inserted.mapNotNull { it.queueEntryId }.toSet()
        val undoEpoch = tracker.epoch
        onMain { player.addMediaItems(inserted); player.seekTo(1, 123L) }
        idle()
        // After listening to A the user returned to the original. B remains pending.
        onMain { player.seekTo(0, 2_345L); player.playWhenReady = false }
        idle()
        onMain {
            val current = player.currentMediaItem?.queueEntryId
            val position = player.currentPosition
            val targets = requireNotNull(tracker.insertionUndoTargets(undoEpoch, insertedIds, current))
            assertEquals(setOf("insert-b"), targets)
            for (index in player.mediaItemCount - 1 downTo 0) {
                if (player.getMediaItemAt(index).queueEntryId in targets) player.removeMediaItem(index)
            }
            assertEquals(listOf("original", "insert-a"), player.entryIds())
            assertEquals(current, player.currentMediaItem?.queueEntryId)
            assertEquals(position, player.currentPosition)
            assertFalse(player.playWhenReady)
            player.removeListener(listener)
        }
    }

    private fun item(song: String, entry: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId(song).setUri("https://example.invalid/not-requested/$song").build()
        .let { if (entry == null) it else it.withQueueEntryId(entry) }

    private fun Player.entryIds() = (0 until mediaItemCount).map { getMediaItemAt(it).queueEntryId }

    private fun withPlayer(block: (StableDeckPlayer) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var player: StableDeckPlayer
        onMain {
            val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
            player = StableDeckPlayer(ExoPlayer.Builder(instrumentation.targetContext).build(),
                ExoPlayer.Builder(instrumentation.targetContext).build(), attributes, handlesAudioFocus = false)
        }
        try { block(player) } finally { onMain { player.release() } }
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }
    private fun idle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()
}

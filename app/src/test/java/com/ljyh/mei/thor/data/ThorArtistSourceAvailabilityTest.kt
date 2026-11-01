package com.ljyh.mei.thor.data

import com.google.gson.Gson
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.SongPlaybackAvailability
import com.ljyh.mei.data.model.SongUrl
import com.ljyh.mei.data.repository.LibraryAccount
import com.ljyh.mei.data.repository.SongSourceSessionChangedException
import com.ljyh.mei.thor.model.ThorCatalogPage
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.toThorItem
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ThorArtistSourceAvailabilityTest {
    private val gson = Gson()

    @Test fun explicitTrialAtEveryPlayableQualityDisablesOnlyThatSongWithoutChangingItsMetadata() = runBlocking {
        val original = song(65800).toThorItem().copy(owned = true, collected = true, description = "Album detail")
        val calls = mutableListOf<Pair<List<String>, String>>()
        val result = resolveThorArtistSourceAvailability(listOf(original), "exhigh", {}) { ids, quality ->
            calls += ids to quality
            response(source(65800, trial = true, level = "standard"))
        }.single()
        assertEquals(listOf(listOf("65800") to "exhigh", listOf("65800") to "standard"), calls)
        val updated = result.tracks.single()
        assertEquals(SongPlaybackAvailability.Unavailable, updated.availability)
        assertEquals("当前账号仅可试听", updated.availabilityReason)
        assertEquals(original.tracks.single(), updated.copy(availability = SongPlaybackAvailability.Unknown, availabilityReason = null))
        assertEquals(original, result.copy(tracks = original.tracks))
    }

    @Test fun aCompleteLowerQualitySourceWinsOverTheHigherQualityTrial() = runBlocking {
        val calls = mutableListOf<String>()
        val result = resolveThorArtistSourceAvailability(listOf(song(7).toThorItem()), "exhigh", {}) { _, quality ->
            calls += quality
            response(source(7, trial = quality == "exhigh", level = quality))
        }
        assertEquals(listOf("exhigh", "standard"), calls)
        assertEquals(SongPlaybackAvailability.Playable, result.single().tracks.single().availability)
        assertNull(result.single().tracks.single().availabilityReason)
    }

    @Test fun reorderedLongIdsAndDuplicateRowsKeepTheirOriginalOrderAndPagination() = runBlocking {
        val first = 3_325_135_834L
        val second = 3_323_827_648L
        val page = ThorCatalogPage(listOf(song(first).toThorItem(), song(second).toThorItem(), song(first).toThorItem()), nextOffset = 80)
        val calls = mutableListOf<List<String>>()
        val updated = page.copy(items = resolveThorArtistSourceAvailability(page.items, "standard", {}) { ids, _ ->
            calls += ids
            response(source(second, trial = true), source(first, trial = true), source(first), source(99))
        })
        assertEquals(listOf(listOf(first.toString(), second.toString())), calls)
        assertEquals(page.items.map { it.key }, updated.items.map { it.key })
        assertEquals(listOf(SongPlaybackAvailability.Playable, SongPlaybackAvailability.Unavailable, SongPlaybackAvailability.Playable),
            updated.items.map { it.tracks.single().availability })
        assertEquals(80, updated.nextOffset)
        assertTrue(updated.hasMore)
    }

    @Test fun missingIdsUrlsUnknownFormatsAndSourceBusinessErrorsDoNotBecomePermissionDenials() = runBlocking {
        val original = (1L..5L).map { song(it).toThorItem() }
        val result = resolveThorArtistSourceAvailability(original, "standard", {}) { _, _ ->
            response(source(1, trial = true, url = false), source(2, trial = true, level = "future-codec"),
                source(3, trial = true, code = 403), source(4, url = false), source(99, trial = true))
        }
        assertEquals(original, result)
    }

    @Test fun networkOrOuterBusinessFailuresDoNotConvertAnEarlierTrialIntoADenial() = runBlocking {
        for (networkFailure in listOf(true, false)) {
            val original = listOf(song(7).toThorItem())
            val result = resolveThorArtistSourceAvailability(original, "exhigh", {}) { _, quality ->
                if (quality == "exhigh") response(source(7, trial = true))
                else if (networkFailure) throw IOException("Synthetic transport failure")
                else SongUrl(code = 503, data = emptyList())
            }
            assertEquals(original, result)
        }
    }

    @Test fun missingRightsAndFeeLabelsSupplyNoSourceEvidenceOnTheirOwn() = runBlocking {
        val original = listOf(song(7).toThorItem())
        val raw = gson.fromJson("""{"code":200,"data":[{"id":7,"code":200,"fee":1,"payed":0}]}""", SongUrl::class.java)
        val result = resolveThorArtistSourceAvailability(original, "standard", {}) { _, _ -> raw }
        assertEquals(original, result)
    }

    @Test fun existingPermissionVerdictsLocalSongsAndPodcastProgramsRemainUntouched() = runBlocking {
        val original = listOf(
            song(1).copy(availability = SongPlaybackAvailability.Playable),
            song(2).copy(availability = SongPlaybackAvailability.Unavailable, availabilityReason = "Catalog denial"),
            song(3).copy(isLocal = true), song(4).copy(isPodcast = true), song(-5),
        ).map { it.toThorItem() }
        var calls = 0
        val result = resolveThorArtistSourceAvailability(original, "standard", {}) { _, _ ->
            calls++
            response()
        }
        assertEquals(0, calls)
        assertSame(original, result)
    }

    @Test fun largePagesUseBoundedBatchesAndDoNotProbeAlreadyResolvedSongsAgain() = runBlocking {
        val original = (1L..51L).map { song(it).toThorItem() }
        val calls = mutableListOf<Pair<List<String>, String>>()
        val result = resolveThorArtistSourceAvailability(original, "exhigh", {}) { ids, quality ->
            calls += ids to quality
            response(*ids.map { source(it.toLong()) }.toTypedArray())
        }
        assertEquals(listOf(50, 1), calls.map { it.first.size })
        assertEquals(listOf("exhigh", "exhigh"), calls.map { it.second })
        assertEquals((1L..51L).toList(), result.map { it.tracks.single().id })
        assertTrue(result.all { it.tracks.single().availability == SongPlaybackAvailability.Playable })
    }

    @Test fun cancellationAndTypedSessionChangesAlwaysEscapeTheBestEffortProbe() = runBlocking {
        for (failure in listOf(CancellationException("Cancelled catalog read"), SongSourceSessionChangedException())) {
            val caught = runCatching {
                resolveThorArtistSourceAvailability(listOf(song(7).toThorItem()), "standard", {}) { _, _ -> throw failure }
            }.exceptionOrNull()
            assertSame(failure, caught)
        }
    }

    @Test fun switchingAccountsWhileAResponseIsInFlightPreventsItsPublication() = runBlocking {
        val fence = ThorAccountFence(Dispatchers.Unconfined)
        val ticket = fence.changeAccount(LibraryAccount("7", "synthetic-initial-account"))
        var published = false
        val failure = runCatching {
            fence.withTicket(ticket) {
                resolveThorArtistSourceAvailability(listOf(song(7).toThorItem()), "standard",
                    { fence.checkCurrent(ticket) }) { _, _ ->
                    fence.changeAccount(LibraryAccount("8", "synthetic-next-account"))
                    response(source(7, trial = true))
                }
                published = true
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(published)
    }

    private fun song(id: Long) = MediaMetadata(id, "Song $id", "https://example.invalid/art", listOf(
        MediaMetadata.Artist(3684, "Artist")), 180_000, MediaMetadata.Album(42, "Album"), tns = "Translation")

    private fun response(vararg sources: SongUrl.Data) = SongUrl(200, sources.toList())

    private fun source(id: Long, trial: Boolean = false, level: String = "standard", url: Boolean = true, code: Int = 200): SongUrl.Data =
        gson.fromJson("""{"id":$id,"code":$code,"level":"$level","url":${if (url) "\"https://example.invalid/audio\"" else "null"},
            "freeTrialInfo":${if (trial) "{\"start\":0,\"end\":30}" else "null"}}""", SongUrl.Data::class.java)
}

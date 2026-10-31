package com.ljyh.mei.thor.state

import com.ljyh.mei.thor.model.*
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Real browser state machine, with deterministic transport boundaries and no Android or account I/O. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThorArtistBrowserTest {
    @Test fun rapidlySwitchingSectionsRetainsEachIndependentResponseAndDoesNotShowTheWrongRows() = runTest {
        val hot = CompletableDeferred<ThorArtistProfile>()
        val songs = CompletableDeferred<ThorCatalogPage>()
        val albums = CompletableDeferred<ThorCatalogPage>()
        var profileCalls = 0
        var songCalls = 0
        var albumCalls = 0
        val browser = ThorArtistBrowser(backgroundScope,
            profile = { profileCalls++; hot.await() },
            songs = { _, offset -> assertEquals(0, offset); songCalls++; songs.await() },
            albums = { _, offset -> assertEquals(0, offset); albumCalls++; albums.await() },
        )
        browser.open(artist(1))
        runCurrent()
        browser.select(ThorArtistSection.Songs)
        runCurrent()
        browser.select(ThorArtistSection.Albums)
        runCurrent()
        assertEquals(ThorArtistSection.entries.toSet(), browser.current.value!!.loading)

        hot.complete(ThorArtistProfile(artist(1).copy(description = "Profile"), listOf(song(11))))
        songs.complete(ThorCatalogPage(listOf(song(21)), nextOffset = 30))
        runCurrent()
        assertEquals(ThorArtistSection.Albums, browser.current.value!!.section)
        assertTrue(browser.current.value!!.items.isEmpty())
        assertEquals(setOf(ThorArtistSection.Albums), browser.current.value!!.loading)

        albums.complete(ThorCatalogPage(listOf(album(31)), nextOffset = 10))
        runCurrent()
        assertEquals(listOf(album(31)), browser.current.value!!.items)
        browser.select(ThorArtistSection.Songs)
        runCurrent()
        assertEquals(listOf(song(21)), browser.current.value!!.items)
        assertEquals(30, browser.current.value!!.nextOffsets[ThorArtistSection.Songs])
        browser.select(ThorArtistSection.HotSongs)
        runCurrent()
        assertEquals(listOf(song(11)), browser.current.value!!.items)
        assertEquals("Profile", browser.current.value!!.item.description)
        assertFalse(browser.current.value!!.hasMore)
        assertEquals(listOf(1, 1, 1), listOf(profileCalls, songCalls, albumCalls))
    }

    @Test fun pagingKeepsServerOrderAndRawContinuationWhileRefreshReplacesTheOldLogicalSet() = runTest {
        val calls = mutableListOf<Int>()
        var firstPageCalls = 0
        val browser = ThorArtistBrowser(backgroundScope,
            profile = { ThorArtistProfile(it, emptyList()) },
            songs = { _, offset ->
                calls += offset
                when (offset) {
                    0 -> if (firstPageCalls++ == 0) ThorCatalogPage(listOf(song(7), song(3)), nextOffset = 3)
                        else ThorCatalogPage(listOf(song(9), song(7)), nextOffset = 2)
                    3 -> ThorCatalogPage(listOf(song(3), song(5)), nextOffset = 6)
                    else -> error("Unexpected continuation")
                }
            },
            albums = { _, _ -> ThorCatalogPage(emptyList()) },
        )
        browser.open(artist(1))
        runCurrent()
        browser.select(ThorArtistSection.Songs)
        runCurrent()
        browser.loadMore()
        runCurrent()
        assertEquals(listOf(song(7), song(3), song(5)), browser.current.value!!.items)
        assertEquals(6, browser.current.value!!.nextOffsets[ThorArtistSection.Songs])
        assertEquals(listOf(0, 3), calls)

        browser.refresh()
        runCurrent()
        assertEquals(listOf(song(9), song(7)), browser.current.value!!.items)
        assertEquals(2, browser.current.value!!.nextOffsets[ThorArtistSection.Songs])
        assertEquals(listOf(0, 3, 0), calls)
        assertNull(browser.current.value!!.error)
    }

    @Test fun anUncancellableOldAccountResponseCannotPopulateTheNewAccountsSameArtist() = runTest {
        lateinit var oldResponse: Continuation<ThorArtistProfile>
        var calls = 0
        val browser = ThorArtistBrowser(backgroundScope,
            profile = { item ->
                if (calls++ == 0) suspendCoroutine { oldResponse = it }
                else ThorArtistProfile(item.copy(description = "Current account"), listOf(song(22)))
            },
            songs = { _, _ -> ThorCatalogPage(emptyList()) },
            albums = { _, _ -> ThorCatalogPage(emptyList()) },
        )
        browser.open(artist(1))
        runCurrent()
        browser.invalidate()
        runCurrent()
        assertNull(browser.current.value)
        browser.open(artist(1))
        runCurrent()
        // suspendCoroutine deliberately ignores Job.cancel: the generation fence must reject it.
        oldResponse.resume(ThorArtistProfile(artist(1).copy(description = "Old account"), listOf(song(11))))
        runCurrent()
        assertEquals("Current account", browser.current.value!!.item.description)
        assertEquals(listOf(song(22)), browser.current.value!!.items)
        assertTrue(browser.current.value!!.loading.isEmpty())
        assertTrue(browser.current.value!!.errors.isEmpty())
        assertEquals(2, calls)
    }

    @Test fun aSameSectionRefreshWinsEvenWhenTheCancelledRequestCompletesLast() = runTest {
        lateinit var oldResponse: Continuation<ThorCatalogPage>
        var calls = 0
        val browser = ThorArtistBrowser(backgroundScope,
            profile = { ThorArtistProfile(it, emptyList()) },
            songs = { _, _ -> if (calls++ == 0) suspendCoroutine { oldResponse = it }
                else ThorCatalogPage(listOf(song(9)), nextOffset = 20) },
            albums = { _, _ -> ThorCatalogPage(emptyList()) },
        )
        browser.open(artist(1))
        runCurrent()
        browser.select(ThorArtistSection.Songs)
        runCurrent()
        assertTrue(browser.current.value!!.isLoading)
        browser.refresh()
        runCurrent()
        oldResponse.resume(ThorCatalogPage(listOf(song(1)), nextOffset = 100))
        runCurrent()
        assertEquals(listOf(song(9)), browser.current.value!!.items)
        assertEquals(20, browser.current.value!!.nextOffsets[ThorArtistSection.Songs])
        assertFalse(browser.current.value!!.isLoading)
        assertNull(browser.current.value!!.error)
        assertEquals(2, calls)
    }

    @Test fun returningToAnArtistRestoresItsSectionAndCachedContinuationWithoutAnotherRequest() = runTest {
        val profileCalls = mutableListOf<Long>()
        val songCalls = mutableListOf<Pair<Long, Int>>()
        val browser = ThorArtistBrowser(backgroundScope,
            profile = { profileCalls += (it.key as ThorItemKey.Artist).id; ThorArtistProfile(it, listOf(song(10))) },
            songs = { id, offset -> songCalls += id to offset; ThorCatalogPage(listOf(song(20)), nextOffset = 40) },
            albums = { _, _ -> ThorCatalogPage(emptyList()) },
        )
        browser.open(artist(1))
        runCurrent()
        browser.select(ThorArtistSection.Songs)
        runCurrent()
        val cached = browser.current.value
        browser.open(artist(2))
        runCurrent()
        browser.open(artist(1))
        runCurrent()
        assertEquals(cached, browser.current.value)
        assertEquals(listOf(1L, 2L), profileCalls)
        assertEquals(listOf(1L to 0), songCalls)
    }

    @Test fun aFailedMorePageKeepsPreviousRowsAndRetriesTheSameOffsetWithoutStartingOver() = runTest {
        val offsets = mutableListOf<Int>()
        var moreCalls = 0
        val browser = ThorArtistBrowser(backgroundScope,
            profile = { ThorArtistProfile(it, emptyList()) },
            songs = { _, offset ->
                offsets += offset
                if (offset == 0) ThorCatalogPage(listOf(song(1), song(2)), nextOffset = 2)
                else if (moreCalls++ == 0) throw IOException("Synthetic transport failure")
                else ThorCatalogPage(listOf(song(2), song(3)))
            },
            albums = { _, _ -> ThorCatalogPage(emptyList()) },
        )
        browser.open(artist(1))
        runCurrent()
        browser.select(ThorArtistSection.Songs)
        runCurrent()
        browser.loadMore()
        runCurrent()
        assertEquals(listOf(song(1), song(2)), browser.current.value!!.items)
        assertTrue(browser.current.value!!.hasMore)
        assertFalse(browser.current.value!!.isLoading)
        assertNotNull(browser.current.value!!.error)

        browser.loadMore()
        runCurrent()
        assertEquals(listOf(song(1), song(2), song(3)), browser.current.value!!.items)
        assertNull(browser.current.value!!.error)
        assertFalse(browser.current.value!!.hasMore)
        browser.loadMore()
        runCurrent()
        assertEquals(listOf(0, 2, 2), offsets)
    }

    private fun artist(id: Long) = ThorItem(ThorItemKey.Artist(id), "Artist $id")
    private fun song(id: Long) = ThorItem(ThorItemKey.Song(id), "Song $id")
    private fun album(id: Long) = ThorItem(ThorItemKey.Album(id), "Album $id")
}

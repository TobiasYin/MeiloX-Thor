package com.ljyh.mei.thor

import android.app.Presentation
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.PixelCopy
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.playback.ThorLivePlaybackInstrumentedTest
import com.ljyh.mei.thor.model.*
import com.ljyh.mei.thor.state.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual signed search, visible mixed/type lists and existing Artist/Media3 routes on Thor. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ThorSearchArtistsLiveInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 360_000)
    fun actualMixedSearchOpensArtistSongsAndTypeFiltersKeepSingleSongPlaybackAndReturnPosition() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("thor.searchArtists") == "true")
        val report = JSONObject().put("environment", "AYN_Thor_physical").put("status", "running")
        var stage = "setup"
        try {
            ThorLivePlaybackInstrumentedTest().withLiveFixture { fixture ->
                val vm = main { ViewModelProvider(fixture.ownerActivity)[ThorShellViewModel::class.java] }
                val knownSong = fixture.playableSongs().first()
                val artist = knownSong.artists.first { it.id > 0 && it.name.isNotBlank() }
                main {
                    while (!vm.navigation.value.isRoot) vm.back()
                    vm.push(ThorPage.Search)
                }
                stage = "visible_artist_search"
                compose.onNodeWithTag("thor-search-input").performTextReplacement(artist.name)
                compose.onNodeWithTag("thor-search-submit").performTouchInput { click(center) }
                await("mixed_results") { vm.search.value.searched && !vm.search.value.loading && vm.search.value.items.isNotEmpty() }
                assertNull(vm.search.value.error)
                assertFalse("real_artist_endpoint_must_succeed", ThorSearchCategory.Artists in vm.search.value.failedCategories)
                val mixed = vm.search.value.visibleItems
                val artistKey = ThorItemKey.Artist(artist.id)
                val artistIndex = mixed.indexOfFirst { it.key == artistKey }
                assertTrue("an_actual_artist_must_be_discoverable_in_the_first_rows", artistIndex in 0..3)
                assertTrue("promoted_aliases_must_not_hide_songs", mixed.take(4).any { it.key is ThorItemKey.Song })
                assertTrue(mixed.any { it.key is ThorItemKey.Song })
                report.put("mixedResultCount", mixed.size).put("artistPositionInMixedResults", artistIndex)
                val entry = vm.searchEntry.value
                await("mixed_first_focus") { vm.preview.value?.key == mixed.first().key }
                repeat(artistIndex) { fixture.press(KeyEvent.KEYCODE_DPAD_DOWN) }
                await("mixed_artist_focus") { vm.preview.value?.key == artistKey }
                val allScene = thorSearchResultsScene(entry, ThorSearchCategory.All)
                val allFocus = vm.rememberedFocus(allScene)
                fixture.press(KeyEvent.KEYCODE_BUTTON_A)
                await("artist_from_mixed") { (vm.navigation.value.page as? ThorPage.Artist)?.item?.key == artistKey &&
                    vm.artistBrowse.value?.let { it.item.key == artistKey && !it.isLoading && it.items.isNotEmpty() } == true }
                assertTrue(vm.artistBrowse.value!!.items.all { it.key is ThorItemKey.Song && it.tracks.isNotEmpty() })
                report.put("artistSongCount", vm.artistBrowse.value!!.items.size)
                fixture.press(KeyEvent.KEYCODE_BUTTON_B)
                await("mixed_return") { vm.navigation.value.page == ThorPage.Search && vm.preview.value?.key == artistKey }
                assertEquals(entry, vm.searchEntry.value)
                assertEquals(allFocus, vm.rememberedFocus(allScene))
                assertEquals(ThorSearchCategory.All, vm.search.value.category)
                report.put("mixedArtistReturnRestored", true)

                stage = "artist_type_list"
                compose.onNodeWithTag("search-category:Artists").performTouchInput { click(center) }
                await("artist_category") { vm.search.value.category == ThorSearchCategory.Artists && vm.search.value.visibleItems.isNotEmpty() }
                val artists = vm.search.value.visibleItems
                assertTrue(artists.all { it.key is ThorItemKey.Artist })
                assertEquals(entry, vm.searchEntry.value)
                await("artist_type_first_focus") { vm.preview.value?.key == artists.first().key }
                compose.onNodeWithTag("search-category:Artists").assertIsSelected().assertIsNotFocused()
                snapshotArtists(fixture.ownerActivity)
                fixture.press(KeyEvent.KEYCODE_BUTTON_A)
                await("artist_type_open") { (vm.navigation.value.page as? ThorPage.Artist)?.item?.key == artists.first().key }
                fixture.press(KeyEvent.KEYCODE_BUTTON_B)
                await("artist_type_return") { vm.navigation.value.page == ThorPage.Search && vm.search.value.category == ThorSearchCategory.Artists &&
                    vm.preview.value?.key == artists.first().key }
                report.put("artistTypeCount", artists.size).put("artistTypeReturnRestored", true)

                stage = "type_shortcuts_and_next_query"
                fixture.press(KeyEvent.KEYCODE_DPAD_LEFT)
                await("songs_type") { vm.search.value.category == ThorSearchCategory.Songs }
                assertEquals(entry, vm.searchEntry.value)
                compose.onNodeWithTag("search-category:Songs").assertIsSelected().assertIsNotFocused()
                compose.onNodeWithTag("thor-search-input").performTextReplacement(knownSong.title)
                compose.onNodeWithTag("thor-search-submit").performTouchInput { click(center) }
                await("song_query") { vm.searchEntry.value > entry && !vm.search.value.loading && vm.search.value.visibleItems.isNotEmpty() }
                assertEquals(ThorSearchCategory.Songs, vm.search.value.category)
                val songs = vm.search.value.visibleItems
                assertTrue(songs.all { it.key is ThorItemKey.Song })
                val target = songs.indexOfFirst { it.key == ThorItemKey.Song(knownSong.id) }
                assertTrue("known_live_song_must_be_returned_by_its_title", target >= 0)
                fixture.verifyFullHttpSource(knownSong)
                await("songs_first_focus") { vm.preview.value?.key == songs.first().key }
                repeat(target) { fixture.press(KeyEvent.KEYCODE_DPAD_DOWN) }
                await("target_song_focus") { vm.preview.value?.key == songs[target].key }
                val songScene = thorSearchResultsScene(vm.searchEntry.value, ThorSearchCategory.Songs)
                val songFocus = vm.rememberedFocus(songScene)
                stage = "single_song_A"
                fixture.press(KeyEvent.KEYCODE_BUTTON_A)
                // The binder saves the actual viewport synchronously on activation. An
                // untouched first row legitimately has no earlier scroll-memory entry.
                val activatedScroll = checkNotNull(vm.rememberedListScroll(songScene))
                await("single_song_player") { vm.navigation.value.overlay == ThorOverlay.Player && vm.playback.state.value.current?.id == knownSong.id }
                assertEquals("A in song-filtered search must not enqueue every search result", 1, vm.playback.state.value.queue.size)
                fixture.awaitProgress(0)
                fixture.press(KeyEvent.KEYCODE_BUTTON_B)
                await("filtered_song_return") { vm.navigation.value.overlay == null && vm.navigation.value.page == ThorPage.Search &&
                    vm.preview.value?.key == songs[target].key }
                assertEquals(ThorSearchCategory.Songs, vm.search.value.category)
                assertEquals(songFocus, vm.rememberedFocus(songScene))
                assertEquals(activatedScroll, vm.rememberedListScroll(songScene))
                report.put("newQueryRetainedType", true).put("singleSongQueueCount", 1)
                    .put("selectedSongActuallyProgressed", true).put("filteredSongReturnRestored", true)
            }
            report.put("status", "passed").put("queueRestored", true)
        } catch (failure: Throwable) {
            report.put("status", "failed").put("stage", stage)
            throw failure
        } finally {
            File(instrumentation.targetContext.cacheDir, "thor-search-artists-live.json").writeText(report.toString(2))
        }
    }

    private fun await(label: String, timeout: Long = 60_000, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < until) {
            compose.waitForIdle()
            if (main(condition)) return
            SystemClock.sleep(35)
        }
        error("search_artists_timeout_$label")
    }
    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
    private fun snapshotArtists(activity: ThorActivity) {
        compose.waitForIdle()
        val window = main {
            val host = activity.javaClass.getDeclaredField("displayHost").apply { isAccessible = true }.get(activity)
            checkNotNull((host.javaClass.getDeclaredField("presentation").apply { isAccessible = true }.get(host) as Presentation).window)
        }
        val bitmap = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1); val result = AtomicReference<Int>()
        main { PixelCopy.request(window, bitmap, { result.set(it); latch.countDown() }, Handler(Looper.getMainLooper())) }
        assertTrue(latch.await(6, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result.get())
        val directory = File(instrumentation.targetContext.cacheDir, "thor-search-evidence").apply { mkdirs() }
        File(directory, "artists-lower.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

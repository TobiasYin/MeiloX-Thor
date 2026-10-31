package com.ljyh.mei.thor

import android.app.Presentation
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.Window
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.datastore.preferences.core.edit
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.playback.ThorLivePlaybackInstrumentedTest
import com.ljyh.mei.constants.AppAppearance
import com.ljyh.mei.constants.AppAppearanceKey
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.thor.model.*
import com.ljyh.mei.thor.state.*
import com.ljyh.mei.thor.debug.ThorLiveCatalogEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Installed account, real signed endpoints, actual dual windows and production key dispatch. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ThorDiscoveryLiveInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 240_000)
    fun actualArtistAreaTypeOffsetAndArtistSearchUseTheCurrentSession() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("thor.discovery") == "true")
        val report = JSONObject().put("environment", "AYN_Thor_physical").put("status", "running")
        try {
            ThorLivePlaybackInstrumentedTest().withLiveFixture { fixture ->
                val vm = main { ViewModelProvider(fixture.ownerActivity)[ThorShellViewModel::class.java] }
                val filters = vm.catalogFilters.value
                try {
                    val entry = EntryPointAccessors.fromApplication(instrumentation.targetContext.applicationContext,
                        ThorLiveCatalogEntryPoint::class.java)
                    val repository = entry.thorMusicRepository()
                    val ticket = checkNotNull(repository.captureAccountTicket(vm.library.value.account))
                    val expected = runBlocking(Dispatchers.IO) { repository.withAccountTicket(ticket) {
                        entry.thorCatalogRepository().page(ThorCollection.Artists,
                            ThorCatalogFilters(artistArea = 7, artistType = 1), expectedSession = ticket.account?.sessionFingerprint)
                    } }
                    assertTrue("real_filtered_artist_endpoint_required", expected.items.size >= 2)
                    main { vm.setArtistArea(7); vm.setArtistType(1) }
                    await("filtered_artist_data") { vm.catalogFilters.value.artistArea == 7 && vm.catalogFilters.value.artistType == 1 &&
                        ThorCollection.Artists !in vm.library.value.loading &&
                        (vm.library.value[ThorCollection.Artists].map { it.key } == expected.items.map { it.key } ||
                            vm.library.value.errors[ThorCollection.Artists] != null) }
                    assertNull("real_filtered_artist_request_failed", vm.library.value.errors[ThorCollection.Artists])
                    val firstPage = vm.library.value[ThorCollection.Artists]
                    assertTrue(firstPage.size >= 2 && firstPage.all { it.key is ThorItemKey.Artist })
                    report.put("filteredArtistCount", firstPage.size).put("area", 7).put("type", 1)
                    if (ThorCollection.Artists in vm.library.value.hasMore) {
                        main { vm.loadMore(ThorCollection.Artists) }
                        await("filtered_artist_offset") { ThorCollection.Artists !in vm.library.value.loadingMore &&
                            (vm.library.value[ThorCollection.Artists].size > firstPage.size || vm.library.value.errors[ThorCollection.Artists] != null) }
                        val after = vm.library.value[ThorCollection.Artists]
                        assertNull(vm.library.value.errors[ThorCollection.Artists])
                        assertEquals(firstPage.map { it.key }, after.take(firstPage.size).map { it.key })
                        report.put("directoryOffsetAppended", after.size - firstPage.size)
                    }
                    val result = runBlocking(Dispatchers.IO) { repository.search(firstPage.first().title) }
                    assertTrue("real_artist_search_result_required", result.any { it.key == firstPage.first().key })
                    report.put("artistSearchMatchedDirectoryIdentity", true)
                } finally {
                    main { vm.setArtistArea(filters.artistArea); vm.setArtistType(filters.artistType) }
                }
            }
            report.put("status", "passed").put("queueRestored", true)
        } catch (failure: Throwable) {
            report.put("status", "failed")
            throw failure
        } finally {
            File(instrumentation.targetContext.cacheDir, "thor-discovery-filters-live.json").writeText(report.toString(2))
        }
    }

    @Test(timeout = 480_000)
    fun actualCatalogsArtistDiscographyAndDailyPlaybackKeepTheHomeListPosition() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("thor.discovery") == "true")
        val report = JSONObject().put("environment", "AYN_Thor_physical").put("status", "running")
        var stage = "setup"
        try {
            ThorLivePlaybackInstrumentedTest().withLiveFixture { fixture ->
                val vm = main { ViewModelProvider(fixture.ownerActivity)[ThorShellViewModel::class.java] }
                val originalFilters = vm.catalogFilters.value
                val originalCategory = vm.homeCategory.value
                val originalAppearance = runBlocking { instrumentation.targetContext.dataStore.data.first()[AppAppearanceKey] }
                try {
                    main {
                        while (!vm.navigation.value.isRoot) vm.back()
                        vm.selectTab(ThorTab.Home)
                    }
                    fun category(target: ThorHomeCategory): List<ThorItem> {
                        main { vm.selectHomeCategory(target); vm.refresh(target.collection) }
                        await("category_${target.name}") {
                            target.collection !in vm.library.value.loading &&
                                (vm.library.value[target.collection].isNotEmpty() || vm.library.value.errors[target.collection] != null)
                        }
                        assertTrue("catalog_${target.name}_failed", vm.library.value.errors[target.collection] == null)
                        return vm.library.value[target.collection].also {
                            assertTrue("catalog_${target.name}_empty", it.isNotEmpty())
                            report.put("${target.name.lowercase()}Count", it.size)
                        }
                    }

                    stage = "recommended"
                    category(ThorHomeCategory.Recommended)
                    for (tab in ThorHomeCategory.entries) compose.onNode(hasText(tab.title) and hasClickAction())
                        .assertExists().assertIsNotFocused()

                    stage = "playlist_cursor"
                    val playlists = category(ThorHomeCategory.Playlists)
                    assertTrue(playlists.all { it.key is ThorItemKey.Playlist })
                    if (ThorCollection.RecommendedPlaylists in vm.library.value.hasMore) {
                        await("playlist_first_focus") { vm.preview.value?.key == playlists.first().key }
                        repeat(playlists.size) { fixture.press(KeyEvent.KEYCODE_DPAD_DOWN) }
                        await("playlist_pagination_focus") {
                            vm.rememberedFocus("root:Home:Playlists")?.endsWith(":more:${playlists.size}") == true
                        }
                        fixture.press(KeyEvent.KEYCODE_BUTTON_A)
                        await("playlist_cursor") {
                            ThorCollection.RecommendedPlaylists !in vm.library.value.loadingMore &&
                                (vm.library.value[ThorCollection.RecommendedPlaylists].size > playlists.size ||
                                    vm.library.value.errors[ThorCollection.RecommendedPlaylists] != null)
                        }
                        val appended = vm.library.value[ThorCollection.RecommendedPlaylists]
                        assertTrue("playlist_pagination_failed", vm.library.value.errors[ThorCollection.RecommendedPlaylists] == null)
                        assertEquals(playlists.map { it.key }, appended.take(playlists.size).map { it.key })
                        assertTrue("playlist_cursor_must_add_distinct_rows", appended.size > playlists.size)
                        await("pagination_hands_focus_to_first_new_row") { vm.preview.value?.key == appended[playlists.size].key }
                        report.put("playlistCursorAppended", appended.size - playlists.size)
                        report.put("paginationControllerFocusHandedToFirstNewRow", true)
                    }
                    main { vm.setPlaylistCategory("华语") }
                    await("playlist_filter_started") { ThorCollection.RecommendedPlaylists in vm.library.value.loading }
                    await("playlist_filter") {
                        ThorCollection.RecommendedPlaylists !in vm.library.value.loading &&
                            vm.library.value[ThorCollection.RecommendedPlaylists].isNotEmpty()
                    }
                    assertTrue(vm.library.value.errors[ThorCollection.RecommendedPlaylists] == null)
                    report.put("playlistFilterLoaded", true)

                    stage = "official_charts"
                    val charts = category(ThorHomeCategory.Charts)
                    assertTrue(charts.all { it.key is ThorItemKey.Playlist })
                    val themePixels = mutableListOf<Int>()
                    for ((value, appearance) in listOf(AppAppearance.Light to ThorAppearance.Day, AppAppearance.Dark to ThorAppearance.Night)) {
                        runBlocking { instrumentation.targetContext.dataStore.edit { it[AppAppearanceKey] = value.name } }
                        await("charts_theme_${appearance.name}") { vm.preferences.value.appearance == appearance }
                        compose.onNodeWithTag("home-category:Charts").assertIsSelected().assertIsNotFocused()
                        val host = main { fixture.ownerActivity.javaClass.getDeclaredField("displayHost")
                            .apply { isAccessible = true }.get(fixture.ownerActivity) }
                        val lower = main { host.javaClass.getDeclaredField("presentation").apply { isAccessible = true }.get(host) as Presentation }
                        themePixels += snapshot(checkNotNull(lower.window), "home-charts-${appearance.name.lowercase()}.png")
                    }
                    assertTrue("actual_day_and_night_pixels_must_differ", themePixels[0] != themePixels[1])
                    report.put("dayAndNightCatalogsRendered", true)
                    main { vm.open(charts.first()) }
                    await("chart_tracks") { !vm.detailLoading.value && (vm.navigation.value.page as? ThorPage.Detail)?.item?.tracks?.isNotEmpty() == true }
                    report.put("chartTrackCount", (vm.navigation.value.page as ThorPage.Detail).item.tracks.size)
                    fixture.press(KeyEvent.KEYCODE_BUTTON_B)
                    assertEquals(ThorHomeCategory.Charts, vm.homeCategory.value)

                    stage = "artist_directory"
                    val artists = category(ThorHomeCategory.Artists)
                    assertTrue(artists.all { it.key is ThorItemKey.Artist })
                    await("artist_first_focus") { vm.preview.value?.key == artists.first().key }
                    fixture.press(KeyEvent.KEYCODE_DPAD_DOWN)
                    await("artist_second_focus") { vm.preview.value?.key == artists[1].key }
                    val selected = vm.preview.value!!.key
                    fixture.press(KeyEvent.KEYCODE_BUTTON_A)
                    await("artist_hot_songs") { vm.navigation.value.page is ThorPage.Artist &&
                        vm.artistBrowse.value?.let { it.item.key == selected && !it.isLoading && it.items.isNotEmpty() } == true }
                    report.put("artistHotSongCount", vm.artistBrowse.value!!.items.size)
                    assertEquals(selected, (vm.navigation.value.page as ThorPage.Artist).item.key)

                    stage = "artist_songs_pages"
                    fixture.press(KeyEvent.KEYCODE_DPAD_RIGHT)
                    await("artist_all_songs") { vm.artistBrowse.value?.let { it.section == ThorArtistSection.Songs && !it.isLoading && it.items.isNotEmpty() } == true }
                    val firstSongs = vm.artistBrowse.value!!.items
                    report.put("artistFirstSongPageCount", firstSongs.size)
                    if (vm.artistBrowse.value!!.hasMore) {
                        main { vm.loadMoreArtist() }
                        await("artist_next_songs") { vm.artistBrowse.value?.let {
                            !it.isLoading && (it.items.size > firstSongs.size || it.error != null)
                        } == true }
                        val state = vm.artistBrowse.value!!
                        assertNull("artist_pagination_failed", state.error)
                        assertEquals(firstSongs.map { it.key }, state.items.take(firstSongs.size).map { it.key })
                        assertTrue(state.items.size > firstSongs.size)
                        report.put("artistSongPageAppended", state.items.size - firstSongs.size)
                    }

                    stage = "artist_albums"
                    fixture.press(KeyEvent.KEYCODE_DPAD_RIGHT)
                    await("artist_albums") { vm.artistBrowse.value?.let { it.section == ThorArtistSection.Albums && !it.isLoading && it.items.isNotEmpty() } == true }
                    val album = vm.artistBrowse.value!!.items.first()
                    assertTrue(album.key is ThorItemKey.Album)
                    report.put("artistAlbumCount", vm.artistBrowse.value!!.items.size)
                    await("artist_album_focus") { vm.preview.value?.key == album.key }
                    fixture.press(KeyEvent.KEYCODE_BUTTON_A)
                    await("album_tracks") { !vm.detailLoading.value && (vm.navigation.value.page as? ThorPage.Detail)?.item?.let { it.key == album.key && it.tracks.isNotEmpty() } == true }
                    fixture.press(KeyEvent.KEYCODE_BUTTON_B)
                    await("artist_return") { vm.navigation.value.page is ThorPage.Artist && vm.artistBrowse.value?.section == ThorArtistSection.Albums }
                    fixture.press(KeyEvent.KEYCODE_BUTTON_B)
                    await("directory_return_focus") { vm.navigation.value.page == ThorPage.Root(ThorTab.Home) && vm.preview.value?.key == selected }
                    assertEquals(ThorHomeCategory.Artists, vm.homeCategory.value)
                    report.put("artistDirectoryPositionRestored", true)

                    stage = "home_direction_shortcut"
                    fixture.press(KeyEvent.KEYCODE_DPAD_LEFT)
                    await("left_changes_category") { vm.homeCategory.value == ThorHomeCategory.Charts }
                    fixture.press(KeyEvent.KEYCODE_DPAD_RIGHT)
                    await("right_changes_category") { vm.homeCategory.value == ThorHomeCategory.Artists }
                    for (tab in ThorHomeCategory.entries) compose.onNode(hasText(tab.title) and hasClickAction()).assertIsNotFocused()
                    report.put("tabsExcludedFromFocus", true)
                    fixture.press(KeyEvent.KEYCODE_BUTTON_R1)
                    await("primary_tab_discover") { vm.navigation.value.tab == ThorTab.Discover }
                    fixture.press(KeyEvent.KEYCODE_BUTTON_L1)
                    await("primary_tab_home") { vm.navigation.value.page == ThorPage.Root(ThorTab.Home) }
                    assertEquals(ThorHomeCategory.Artists, vm.homeCategory.value)
                    report.put("shouldersKeepPrimaryTabContract", true)

                    stage = "daily_recommendations"
                    main { vm.openCollection(ThorCollection.DailySongs) }
                    await("daily_songs") { ThorCollection.DailySongs !in vm.library.value.loading &&
                        (vm.library.value[ThorCollection.DailySongs].isNotEmpty() || vm.library.value.errors[ThorCollection.DailySongs] != null) }
                    assertTrue("daily_recommendations_failed", vm.library.value.errors[ThorCollection.DailySongs] == null)
                    val daily = vm.library.value[ThorCollection.DailySongs]
                    assertTrue(daily.isNotEmpty() && daily.all { it.key is ThorItemKey.Song && it.tracks.isNotEmpty() })
                    report.put("dailySongCount", daily.size)

                    // Find a real full source before asking the UI to play it; availability flags alone
                    // are not used as proof that this installed account can obtain the audio.
                    stage = "daily_actual_playback"
                    val index = daily.indices.firstOrNull { index ->
                        val item = daily[index]
                        !item.isPlaybackUnavailable && runCatching { fixture.verifyFullHttpSource(item.tracks.single()) }.isSuccess
                    } ?: error("daily_full_source_required")
                    await("daily_first_focus") { vm.preview.value?.key == daily.first().key }
                    repeat(index) { fixture.press(KeyEvent.KEYCODE_DPAD_DOWN) }
                    await("daily_selected_focus") { vm.preview.value?.key == daily[index].key }
                    fixture.press(KeyEvent.KEYCODE_BUTTON_A)
                    await("daily_player") { vm.navigation.value.overlay == ThorOverlay.Player && vm.playback.state.value.current?.id == daily[index].tracks.single().id }
                    fixture.awaitProgress(main { fixture.service.player.currentMediaItemIndex })
                    assertEquals(Player.STATE_READY, main { fixture.service.player.playbackState })
                    report.put("actualSelectedSongProgressed", true)
                    fixture.press(KeyEvent.KEYCODE_BUTTON_B)
                    await("daily_return_position") { vm.navigation.value.page == ThorPage.Collection(ThorCollection.DailySongs) &&
                        vm.navigation.value.overlay == null && vm.preview.value?.key == daily[index].key }
                    report.put("dailyPositionRestored", true)
                } finally {
                    runBlocking { instrumentation.targetContext.dataStore.edit {
                        if (originalAppearance == null) it.remove(AppAppearanceKey) else it[AppAppearanceKey] = originalAppearance
                    } }
                    main {
                        while (!vm.navigation.value.isRoot) vm.back()
                        vm.selectTab(ThorTab.Home)
                        vm.setPlaylistCategory(originalFilters.playlistCategory)
                        vm.setArtistArea(originalFilters.artistArea)
                        vm.setArtistType(originalFilters.artistType)
                        vm.selectHomeCategory(originalCategory)
                    }
                }
            }
            report.put("status", "passed").put("queueRestored", true)
        } catch (failure: Throwable) {
            report.put("status", "failed").put("stage", stage)
            throw failure
        } finally {
            File(instrumentation.targetContext.cacheDir, "thor-discovery-live.json").writeText(report.toString(2))
        }
    }

    private fun await(label: String, timeout: Long = 60_000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            // A ComposeTestRule owns these windows' frame clocks. HTTP completion alone
            // cannot advance focus restores/scrolls waiting for withFrameNanos.
            compose.waitForIdle()
            if (main(condition)) return
            SystemClock.sleep(35)
        }
        error("discovery_timeout_$label")
    }
    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun snapshot(window: Window, name: String): Int {
        compose.waitForIdle()
        val (width, height) = main { window.decorView.width to window.decorView.height }
        assertEquals(1240, width); assertEquals(1080, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val result = AtomicReference<Int>()
        val latch = CountDownLatch(1)
        val committed = CountDownLatch(1)
        main {
            window.decorView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            window.decorView.invalidate()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertTrue("actual_lower_window_commit_required", committed.await(6, TimeUnit.SECONDS))
        main { PixelCopy.request(window, bitmap, { result.set(it); latch.countDown() }, Handler(Looper.getMainLooper())) }
        assertTrue(latch.await(6, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result.get())
        val directory = File(instrumentation.targetContext.cacheDir, "thor-discovery-evidence").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val sample = bitmap.getPixel(30, height - 30)
        bitmap.recycle()
        return sample
    }
}

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
import com.ljyh.mei.data.model.SongPlaybackAvailability
import com.ljyh.mei.playback.ThorLivePlaybackInstrumentedTest
import com.ljyh.mei.thor.debug.ThorLiveCatalogEntryPoint
import com.ljyh.mei.thor.model.*
import com.ljyh.mei.thor.state.*
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ThorArtistSourceLiveInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test(timeout = 300_000)
    fun linJunJieTrialOnlyRowsAreMarkedAndCannotReplacePlayingMusicWhileFullArtistSourcesPlay() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("thor.artistSources") == "true")
        val report = JSONObject().put("environment", "AYN_Thor_physical").put("status", "running")
        var stage = "setup"
        try {
            ThorLivePlaybackInstrumentedTest().withLiveFixture { fixture ->
                val vm = main { ViewModelProvider(fixture.ownerActivity)[ThorShellViewModel::class.java] }
                val known = fixture.playableSongs().first()
                fixture.verifyFullHttpSource(known)
                fixture.seed(listOf(known), play = true); fixture.awaitProgress(0)
                main { while (!vm.navigation.value.isRoot) vm.back(); vm.open(ThorItem(ThorItemKey.Artist(3684), "林俊杰")) }
                stage = "lin_jun_jie_preflight"
                await("lin_jun_jie") { vm.artistBrowse.value?.let { it.item.key == ThorItemKey.Artist(3684) && !it.isLoading && it.items.isNotEmpty() } == true }
                val rows = vm.artistBrowse.value!!.items
                val entry = EntryPointAccessors.fromApplication(instrumentation.targetContext.applicationContext, ThorLiveCatalogEntryPoint::class.java)
                val repo = entry.thorMusicRepository()
                val ticket = checkNotNull(repo.captureAccountTicket(vm.library.value.account))
                val sampled = rows.take(3).flatMap { it.tracks }
                val response = runBlocking(Dispatchers.IO) { repo.withAccountTicket(ticket) {
                    entry.playlistRepository().getRawSongUrlV1(sampled.map { it.id.toString() }, "standard", ticket.expectedSessionFingerprint)
                } }
                assertEquals(200, response.code)
                val trialIds = response.data.filter { it.freeTrialInfo != null && !it.url.isNullOrBlank() }.map { it.id }.toSet()
                assertTrue("Current real account must reproduce the reported trial-only case", trialIds.isNotEmpty())
                for (row in rows.filter { (it.key as? ThorItemKey.Song)?.id in trialIds }) {
                    assertTrue(row.isPlaybackUnavailable)
                    assertEquals("当前账号仅可试听", row.tracks.single().availabilityReason)
                }
                report.put("linJunJieLoaded", rows.size).put("confirmedTrialRows", trialIds.size)
                    .put("linJunJieUnavailableCount", rows.count { it.isPlaybackUnavailable })
                    .put("linJunJiePlayableCount", rows.count { it.tracks.single().availability == SongPlaybackAvailability.Playable })
                val trialIndex = rows.indexOfFirst { (it.key as? ThorItemKey.Song)?.id in trialIds }
                await("lin_jun_jie_first_focus") { vm.preview.value?.key == rows.first().key }
                repeat(trialIndex) { fixture.press(KeyEvent.KEYCODE_DPAD_DOWN) }
                await("trial_focus") { vm.preview.value?.key == rows[trialIndex].key }
                captureLower(fixture.ownerActivity)
                val before = vm.playback.state.value.currentEntryId
                val position = main { fixture.service.player.currentPosition }
                fixture.press(KeyEvent.KEYCODE_BUTTON_A)
                compose.waitForIdle()
                assertNull("An explicitly trial-only choice must not enter Player", vm.navigation.value.overlay)
                assertEquals(before, vm.playback.state.value.currentEntryId)
                await("existing_music_keeps_playing") { fixture.service.player.isPlaying && fixture.service.player.currentPosition > position + 300 }
                report.put("trialChoiceKeptExistingMusic", true)

                stage = "full_artist_control"
                val artist = known.artists.first { it.id > 0 }
                main { vm.back(); vm.open(ThorItem(ThorItemKey.Artist(artist.id), artist.name)) }
                await("full_artist") { vm.artistBrowse.value?.let { it.item.key == ThorItemKey.Artist(artist.id) && !it.isLoading && it.items.any { row -> row.tracks.single().id == known.id } } == true }
                val fullRows = vm.artistBrowse.value!!.items
                val index = fullRows.indexOfFirst { it.tracks.single().id == known.id }
                assertFalse(fullRows[index].isPlaybackUnavailable)
                await("full_artist_first_focus") { vm.preview.value?.key == fullRows.first().key }
                repeat(index) { fixture.press(KeyEvent.KEYCODE_DPAD_DOWN) }
                await("full_artist_target_focus") { vm.preview.value?.key == fullRows[index].key }
                fixture.press(KeyEvent.KEYCODE_BUTTON_A)
                await("actual_new_full_entry") { vm.navigation.value.overlay == ThorOverlay.Player && vm.playback.state.value.current?.id == known.id &&
                    vm.playback.state.value.currentEntryId != before }
                fixture.awaitProgress(main { fixture.service.player.currentMediaItemIndex })
                report.put("fullArtistSourceNewEntryProgressed", true)
            }
            report.put("status", "passed").put("queueRestored", true)
        } catch (failure: Throwable) { report.put("status", "failed").put("stage", stage); throw failure }
        finally { File(instrumentation.targetContext.cacheDir, "thor-artist-sources-live.json").writeText(report.toString(2)) }
    }
    private fun captureLower(activity: ThorActivity) {
        compose.waitForIdle()
        val window = main {
            val host = activity.javaClass.getDeclaredField("displayHost").apply { isAccessible = true }.get(activity)
            val presentation = host.javaClass.getDeclaredField("presentation").apply { isAccessible = true }.get(host) as Presentation
            checkNotNull(presentation.window)
        }
        val (width, height) = main { window.decorView.width to window.decorView.height }
        assertEquals(1240, width); assertEquals(1080, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val result = AtomicReference<Int>()
        val copied = CountDownLatch(1)
        val committed = CountDownLatch(1)
        main {
            window.decorView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            window.decorView.invalidate()
        }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        assertTrue(committed.await(6, TimeUnit.SECONDS))
        main { PixelCopy.request(window, bitmap, { result.set(it); copied.countDown() }, Handler(Looper.getMainLooper())) }
        assertTrue(copied.await(6, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result.get())
        val directory = File(instrumentation.targetContext.cacheDir, "thor-artist-source-evidence").apply { mkdirs() }
        File(directory, "lin-jun-jie-lower.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun await(label: String, timeout: Long = 60_000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { compose.waitForIdle(); if (main(condition)) return; SystemClock.sleep(35) }
        error("artist_source_timeout_$label")
    }
    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>(); instrumentation.runOnMainSync { result.set(block()) }; return result.get()
    }
}

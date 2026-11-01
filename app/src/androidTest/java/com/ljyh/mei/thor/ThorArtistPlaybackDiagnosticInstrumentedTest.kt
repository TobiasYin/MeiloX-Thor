package com.ljyh.mei.thor

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.constants.MusicQualityKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.api.GetSongUrlV1
import com.ljyh.mei.playback.ThorLivePlaybackInstrumentedTest
import com.ljyh.mei.thor.debug.ThorLiveCatalogEntryPoint
import com.ljyh.mei.thor.model.*
import com.ljyh.mei.utils.dataStore
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic reads/real source attempts; never prints a URL, credential or account identity. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ThorArtistPlaybackDiagnosticInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test(timeout = 360_000)
    fun compareArtistMetadataAndActualSourcesAgainstCanonicalSongDetails() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("thor.artistDiagnostic") == "true")
        val report = JSONObject().put("environment", "AYN_Thor_physical").put("status", "running")
        val results = JSONArray(); report.put("artists", results)
        val file = File(instrumentation.targetContext.cacheDir, "thor-artist-playback-diagnostic.json")
        try {
            ThorLivePlaybackInstrumentedTest().withLiveFixture { fixture ->
                val vm = main { ViewModelProvider(fixture.ownerActivity)[ThorShellViewModel::class.java] }
                val entry = EntryPointAccessors.fromApplication(instrumentation.targetContext.applicationContext, ThorLiveCatalogEntryPoint::class.java)
                val repo = entry.thorMusicRepository()
                val known = fixture.playableSongs().first()
                fixture.verifyFullHttpSource(known)
                report.put("knownCanonical", describe(known))
                val ticket = checkNotNull(repo.captureAccountTicket(vm.library.value.account))
                val catalog = io { repo.withAccountTicket(ticket) {
                    entry.thorCatalogRepository().page(ThorCollection.Artists, ThorCatalogFilters(), expectedSession = ticket.expectedSessionFingerprint)
                } }
                val knownArtist = known.artists.first { it.id > 0 }
                val searchedArtists = io { repo.search(knownArtist.name) }.filter { it.key is ThorItemKey.Artist }.take(2)
                val artists = (listOf(ThorItem(ThorItemKey.Artist(knownArtist.id), knownArtist.name)) + searchedArtists + catalog.items.take(1)).distinctBy { it.key }
                val quality = io { instrumentation.targetContext.dataStore.data.first()[MusicQualityKey] ?: "exhigh" }
                for (artist in artists) {
                    val profile = io { repo.artistProfile(artist) }
                    val all = io { repo.artistSongs((artist.key as ThorItemKey.Artist).id, 0) }
                    val artistReport = JSONObject().put("artistId", (artist.key as ThorItemKey.Artist).id)
                        .put("hotCount", profile.hotSongs.size).put("allCount", all.items.size)
                    results.put(artistReport)
                    val candidates = (profile.hotSongs.take(3) + all.items.take(2) +
                        profile.hotSongs.filter { it.tracks.firstOrNull()?.id == known.id }).distinctBy { it.key }
                    val tracks = JSONArray(); artistReport.put("tracks", tracks)
                    for (item in candidates) {
                        val raw = item.tracks.single()
                        val canonical = io { repo.load(item).tracks.single() }
                        val row = JSONObject().put("raw", describe(raw)).put("canonical", describe(canonical))
                        tracks.put(row)
                        try {
                            val response = io { fixture.service.apiService.getSongUrlV1(GetSongUrlV1(ids = "[${raw.id}]", level = quality)) }
                            val actual = response.data.firstOrNull { it.id == raw.id }
                            row.put("urlResponse", JSONObject().put("code", response.code).put("dataCode", actual?.code ?: -999)
                                .put("urlPresent", !actual?.url.isNullOrBlank()).put("trial", actual?.freeTrialInfo != null)
                                .put("level", actual?.level ?: "").put("bitrate", actual?.br ?: 0))
                        } catch (failure: Throwable) { row.put("urlRequestFailure", failure.javaClass.simpleName) }
                        try {
                            val source = io { withTimeout(15_000) { fixture.service.mediaUriProvider.resolveMediaSource(raw.id.toString(), quality) } }
                            row.put("fullSourceResolved", true).put("httpSource", source.uri.scheme in setOf("http", "https"))
                                .put("contentLength", source.contentLength ?: -1)
                        } catch (failure: Throwable) {
                            row.put("fullSourceResolved", false).put("sourceFailure", failure.javaClass.simpleName)
                        }
                        file.writeText(report.toString(2))
                    }
                    // The known song, when present, discriminates a broken artist path from
                    // ordinary denied candidates. Observe a new queue entry, not an old READY.
                    val candidate = candidates.firstOrNull { it.tracks.single().id == known.id }
                        ?: candidates.firstOrNull { it.tracks.single().availability.name != "Unavailable" }
                    if (candidate != null) {
                        val oldEntry = vm.playback.state.value.currentEntryId
                        val accepted = main { vm.playback.play(listOf(candidate.tracks.single()), 0, "Artist playback diagnosis") }
                        artistReport.put("bridgeAccepted", accepted)
                        if (accepted) {
                            val until = SystemClock.uptimeMillis() + 12_000
                            var advanced = false
                            while (SystemClock.uptimeMillis() < until) {
                                val state = main { vm.playback.state.value }
                                if (state.current?.id == candidate.tracks.single().id && state.currentEntryId != oldEntry && state.playing && state.positionMs > 400) {
                                    advanced = true; break
                                }
                                SystemClock.sleep(70)
                            }
                            artistReport.put("actualNewEntryProgressed", advanced)
                            artistReport.put("playerErrorCode", main { fixture.service.player.playerError?.errorCode ?: 0 })
                        }
                        artistReport.put("notice", vm.notice.value?.message ?: "")
                    }
                    file.writeText(report.toString(2))
                }
            }
            report.put("status", "diagnostic_complete").put("queueRestored", true)
        } finally { file.writeText(report.toString(2)) }
    }
    private fun describe(song: MediaMetadata) = JSONObject().put("id", song.id).put("duration", song.duration)
        .put("albumId", song.album.id).put("artistIds", JSONArray(song.artists.map { it.id }))
        .put("isLocal", song.isLocal).put("isPodcast", song.isPodcast).put("availability", song.availability.name)
        .put("coverPresent", !song.coverUrl.isNullOrBlank())
        .put("reason", song.availabilityReason ?: "")
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { withTimeout(60_000) { block() } }
    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>(); instrumentation.runOnMainSync { result.set(block()) }; return result.get()
    }
}

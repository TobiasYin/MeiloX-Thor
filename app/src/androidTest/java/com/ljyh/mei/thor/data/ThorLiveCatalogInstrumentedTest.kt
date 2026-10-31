package com.ljyh.mei.thor.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.SongPlaybackAvailability
import com.ljyh.mei.data.model.SongPlaybackPrivilege
import com.ljyh.mei.data.model.api.toSongMediaMetadata
import com.ljyh.mei.data.model.mergedSongPlaybackPrivilege
import com.ljyh.mei.data.model.playbackAvailability
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.model.toPlaybackPrivilege
import com.ljyh.mei.data.network.NeteaseSessionType
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.neteaseSessionType
import com.ljyh.mei.thor.debug.ThorLiveCatalogEntryPoint
import com.ljyh.mei.thor.model.ThorCollection
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.test.ThorLiveEnvironment
import com.ljyh.mei.utils.dataStore
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicitly opt-in, read-only verification against the installed application's current session.
 * No fixture account, catalog response, song, playlist, URL probe, or account mutation is used.
 * Run the profile method first. Only absence of opt-in skips; all opted-in checks must pass.
 * The report is an allowlist assembled from public-search/song-detail fields, not a response dump.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class ThorLiveCatalogInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext

    /** Fast preflight: can run independently before the longer catalog scan. */
    @Test(timeout = 75_000)
    fun liveSessionProfileIsValid() = runBlocking<Unit> {
        requireOptIn()
        ThorLiveEnvironment.requireRequestedDevice(context)
        try {
            verifyLiveProfile(entryPoint())
        } catch (failure: Throwable) {
            throw AssertionError(safeFailure(failure, "login_expired_or_profile_unavailable"))
        }
    }

    @Test(timeout = 420_000)
    fun realCatalogPermissionsAndRepositoryMappingsMatchCurrentSession() = runBlocking<Unit> {
        requireOptIn()
        val reportFile = File(context.cacheDir, REPORT_NAME)
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("environment", ThorLiveEnvironment.reportName)
            .put("checkedAtEpochMs", System.currentTimeMillis())
            .put("accountVerified", false)
            .put("sessionType", "None")
            .put("counts", JSONObject())
        var stage = "initialization"
        // An interrupted/rejected new run cannot leave an old passing whitelist behind.
        writeReport(reportFile, report, "running")
        try {
            withTimeout(360_000) {
                stage = "environment"
                ThorLiveEnvironment.requireRequestedDevice(context)
                val entry = entryPoint()
                stage = "login_expired_or_profile_unavailable"
                val session = verifyLiveProfile(entry)
                val thor = entry.thorMusicRepository()
                val account = withTimeout(15_000) { thor.awaitCurrentAccountProjection() }
                requireLive(account.signedIn && account.userId?.toLongOrNull() == session.id,
                    "account_projection_mismatch")
                report.put("accountVerified", true).put("sessionType", session.type.name)
                fun checkAccount() = requireLive(
                    thor.state.value.account.generation == account.generation &&
                        thor.state.value.account.userId == account.userId &&
                        thor.state.value.account.signedIn,
                    "account_changed_during_live_test",
                )

                stage = "library"
                val libraryCollections = setOf(ThorCollection.LikedSongs, ThorCollection.CollectedAlbums,
                    ThorCollection.OwnedPlaylists, ThorCollection.SavedPlaylists)
                // Subscribe before refresh: stale already-complete state is not accepted as this read.
                val library = coroutineScope {
                    val started = async(start = CoroutineStart.UNDISPATCHED) {
                        thor.state.first { state -> libraryCollections.any { it in state.loading } }
                    }
                    thor.refresh(ThorCollection.OwnedPlaylists)
                    thor.refresh(ThorCollection.CollectedAlbums)
                    withTimeout(15_000) { started.await() }
                    withTimeout(120_000) { thor.state.first { state ->
                        libraryCollections.none { it in state.loading } &&
                            libraryCollections.all { it in state.collections || it in state.errors }
                    } }
                }
                checkAccount()
                requireLive(libraryCollections.none { it in library.errors }, "library_live_refresh_failed")
                val counts = report.getJSONObject("counts")
                // The public projection also contains device favorites/playlists. Count only this
                // real account's remote namespace, and never serialize item labels or private IDs.
                counts.put("likedSongs", library[ThorCollection.LikedSongs].count {
                    (it.key as? ThorItemKey.Song)?.local == false })
                    .put("collectedAlbums", library[ThorCollection.CollectedAlbums].count {
                        (it.key as? ThorItemKey.Album)?.local == false })
                    .put("ownedPlaylists", library[ThorCollection.OwnedPlaylists].count {
                        (it.key as? ThorItemKey.Playlist)?.local == false })
                    .put("savedPlaylists", library[ThorCollection.SavedPlaylists].count {
                        (it.key as? ThorItemKey.Playlist)?.local == false })

                stage = "search"
                val search = entry.searchRepository()
                val publicCandidates = linkedMapOf<Long, MediaMetadata>()
                val details = linkedMapOf<Long, Candidate>()
                var searchCount = 0
                var thorSearchChecked = false
                var firstQueryWithSongs: String? = null
                // The names are public catalog search seeds, not accounts or fixed rights assertions.
                // Rights are read afresh from this session; no hardcoded song ID is deemed playable.
                val queries = listOf("陈奕迅", "周杰伦", "林俊杰", "五月天", "Adele", "Taylor Swift", "坂本龍一", "纯音乐")
                for (query in queries) {
                    val response = request("search") { search.search(query, 1, 20) }
                    checkAccount()
                    requireLive(response.code == 200, "search_live_response_failed_code_${response.code}")
                    val metadata = response.result.toSongMediaMetadata()
                    val privileges = response.result.privileges.orEmpty().associateBy { it.id }
                    response.result.songs.orEmpty().forEach { song ->
                        val expected = mergedSongPlaybackPrivilege(song.id, song.privilege, privileges[song.id])
                            .playbackAvailability()
                        requireLive(metadata.firstOrNull { it.id == song.id }?.availability == expected,
                            "search_permission_mapping_mismatch")
                    }
                    searchCount += metadata.size
                    metadata.filter { it.id > 0 && !it.isLocal && !it.isPodcast }.forEach { publicCandidates[it.id] = it }
                    if (metadata.isNotEmpty() && firstQueryWithSongs == null) firstQueryWithSongs = query
                    if (!thorSearchChecked && metadata.isNotEmpty()) {
                        val thorResults = withTimeout(60_000) { thor.search(query) }
                        checkAccount()
                        val songsById = thorResults.filter { it.key is ThorItemKey.Song }
                            .flatMap { it.tracks }.associateBy { it.id }
                        val overlap = metadata.filter { it.id in songsById }
                        requireLive(overlap.isNotEmpty(), "thor_search_no_matching_live_song")
                        overlap.forEach { requireLive(songsById[it.id]?.availability == it.availability,
                            "thor_search_permission_mapping_mismatch") }
                        thorSearchChecked = true
                    }

                    stage = "song_detail"
                    val pending = publicCandidates.keys.filterNot { it in details }.take(20)
                    if (pending.isNotEmpty()) {
                        val responseDetails = request("song_detail") {
                            entry.playerRepository().getSongDetail(pending.joinToString(","))
                        }
                        checkAccount()
                        requireLive(responseDetails.code == 200, "song_detail_live_response_failed")
                        val privilegesById = responseDetails.privileges.orEmpty().associateBy { it.id }
                        val mapped = responseDetails.toMediaMetadata().associateBy { it.id }
                        responseDetails.songs.filter { it.id in publicCandidates }.forEach { song ->
                            val evidence = mergedSongPlaybackPrivilege(song.id, song.privilege,
                                privilegesById[song.id]?.toPlaybackPrivilege(), song.st)
                            val track = mapped[song.id] ?: failLive("song_detail_missing_mapped_track")
                            requireLive(track.availability == evidence.playbackAvailability(),
                                "song_detail_permission_mapping_mismatch")
                            details[song.id] = Candidate(track, evidence)
                        }
                    }
                    // At most 160 public search candidates/eight batched detail requests are scanned.
                    if (details.values.count(Candidate::isLongPlayable) >= 2 &&
                        details.values.any { it.track.availability == SongPlaybackAvailability.Unavailable }) break
                    stage = "search"
                }
                // Search intentionally omits some withdrawn recordings (and may return covers
                // instead). Public catalog IDs are only discovery seeds, never fixed rights
                // assertions: every verdict below still comes from this account's live detail.
                // One bounded batch avoids treating an entirely playable search page as a bug.
                if (details.values.none { it.track.availability == SongPlaybackAvailability.Unavailable }) {
                    stage = "public_catalog_detail"
                    val publicIds = listOf(186016L, 185087L, 185098L, 185903L, 185928L, 186001L,
                        347230L, 386538L).filterNot { it in details }
                    if (publicIds.isNotEmpty()) {
                        val responseDetails = request("public_catalog_detail") {
                            entry.playerRepository().getSongDetail(publicIds.joinToString(","))
                        }
                        checkAccount()
                        requireLive(responseDetails.code == 200, "public_catalog_detail_live_response_failed")
                        val privilegesById = responseDetails.privileges.orEmpty().associateBy { it.id }
                        val mapped = responseDetails.toMediaMetadata().associateBy { it.id }
                        responseDetails.songs.filter { it.id in publicIds }.forEach { song ->
                            val evidence = mergedSongPlaybackPrivilege(song.id, song.privilege,
                                privilegesById[song.id]?.toPlaybackPrivilege(), song.st)
                            val track = mapped[song.id] ?: failLive("public_catalog_detail_missing_track")
                            requireLive(track.availability == evidence.playbackAvailability(),
                                "public_catalog_detail_permission_mapping_mismatch")
                            details[song.id] = Candidate(track, evidence)
                        }
                        counts.put("additionalPublicDetails", publicIds.size)
                    }
                }
                requireLive(thorSearchChecked, "live_search_returned_no_songs")
                counts.put("searchSongs", searchCount).put("songDetails", details.size)
                val playable = details.values.filter(Candidate::isLongPlayable).take(3)
                val unavailable = details.values.filter { it.track.availability == SongPlaybackAvailability.Unavailable }.take(3)
                requireLive(playable.size >= 2, "insufficient_live_explicitly_playable_candidates")
                requireLive(unavailable.isNotEmpty(), "no_explicitly_unavailable_candidate_in_bounded_live_scan")

                stage = "thor_song_load"
                // Real detail evidence crosses the same Thor boundary used by direct list playback.
                (playable.take(2) + unavailable.take(1)).forEach { candidate ->
                    val loaded = withTimeout(45_000) { thor.load(candidate.track.toThorItemForTest()) }
                    checkAccount()
                    val mapped = loaded.tracks.firstOrNull { it.id == candidate.track.id }
                    requireLive(mapped?.availability == candidate.track.availability,
                        "thor_song_load_permission_mapping_mismatch")
                }

                stage = "album_detail"
                val albumId = playable.firstOrNull { it.track.album.id > 0 }?.track?.album?.id
                    ?: failLive("public_candidate_has_no_album")
                val album = request("album_detail") { entry.playlistRepository().getAlbumDetail(albumId.toString()) }
                checkAccount()
                requireLive(album.code == 200 && album.songs.isNotEmpty(), "album_detail_live_response_failed")
                val albumMetadata = album.toMediaMetadata()
                val albumPrivileges = album.privileges.orEmpty().associateBy { it.id }
                album.songs.forEach { song ->
                    val expected = mergedSongPlaybackPrivilege(song.id, song.privilege?.toPlaybackPrivilege(),
                        albumPrivileges[song.id], song.st).playbackAvailability()
                    requireLive(albumMetadata.firstOrNull { it.id == song.id }?.availability == expected,
                        "album_permission_mapping_mismatch")
                }
                val thorAlbum = withTimeout(45_000) { thor.load(ThorItem(ThorItemKey.Album(albumId), "")) }
                checkAccount()
                verifySameMapping(albumMetadata, thorAlbum.tracks, "thor_album")
                counts.put("albumTracks", albumMetadata.size)

                stage = "playlist_detail"
                val playlistSearch = request("playlist_search") {
                    search.search(firstQueryWithSongs ?: "纯音乐", 1000, 30)
                }
                checkAccount()
                requireLive(playlistSearch.code == 200, "playlist_search_live_response_failed")
                val publicPlaylist = playlistSearch.result.playlists.orEmpty()
                    .filter { it.trackCount in 1..150 }.minByOrNull { it.trackCount }
                    ?: failLive("no_bounded_public_playlist_candidate")
                val playlist = request("playlist_detail") {
                    entry.playlistRepository().getPlaylistDetail(publicPlaylist.id.toString())
                }
                checkAccount()
                requireLive(playlist.code == 200 && playlist.playlist.tracks.isNotEmpty(),
                    "playlist_detail_live_response_failed")
                val playlistMetadata = playlist.toMediaMetadata()
                val playlistPrivileges = playlist.privileges.orEmpty().associateBy { it.id }
                playlist.playlist.tracks.forEach { song ->
                    val expected = mergedSongPlaybackPrivilege(song.id, song.privilege,
                        playlistPrivileges[song.id]?.toPlaybackPrivilege(), song.st).playbackAvailability()
                    requireLive(playlistMetadata.firstOrNull { it.id == song.id }?.availability == expected,
                        "playlist_permission_mapping_mismatch")
                }
                val thorPlaylist = withTimeout(60_000) {
                    thor.load(ThorItem(ThorItemKey.Playlist(publicPlaylist.id.toString()), ""))
                }
                checkAccount()
                // Compare the initial real detail subset by ID; Thor can additionally complete pages.
                verifySameMapping(playlistMetadata, thorPlaylist.tracks, "thor_playlist")
                requireLive(thorPlaylist.tracks.isNotEmpty(), "thor_playlist_no_tracks")
                counts.put("playlistTracks", thorPlaylist.tracks.size)

                stage = "final_profile"
                val after = verifyLiveProfile(entry)
                requireLive(after.id == session.id && after.type == session.type, "account_changed_during_live_test")
                checkAccount()
                report.put("checkedAtEpochMs", System.currentTimeMillis())
                writeReport(reportFile, report, "passed", playable, unavailable)
            }
        } catch (failure: Throwable) {
            val code = safeFailure(failure, "${stage}_request_failed")
            report.put("failureCode", code).put("checkedAtEpochMs", System.currentTimeMillis())
            writeReport(reportFile, report, "failed")
            // Never attach the backend exception/cause, whose message could contain private data.
            throw AssertionError("thor_live_catalog_failed:$code")
        }
    }

    private fun requireOptIn() = assumeTrue("Explicit thor.live=true is required before account access",
        InstrumentationRegistry.getArguments().getString("thor.live") == "true")

    private fun entryPoint() = EntryPointAccessors.fromApplication(context, ThorLiveCatalogEntryPoint::class.java)

    private data class VerifiedSession(val id: Long, val type: NeteaseSessionType)

    private suspend fun verifyLiveProfile(entry: ThorLiveCatalogEntryPoint): VerifiedSession {
        // Only the public stored account ID/type are inspected; cookie/device/SDK fields are not read.
        val before = withTimeout(10_000) { context.dataStore.data.first() }
        val id = before[UserIdKey]?.toLongOrNull()
        val type = before.neteaseSessionType()
        requireLive(type != NeteaseSessionType.None && id != null && id > 0, "login_required")
        val profile = try { withTimeout(45_000) { entry.meloXRepository().accountProfile() } }
            catch (_: Exception) { failLive("login_expired_or_profile_unavailable") }
        requireLive(profile.id > 0 && profile.id == id, "live_profile_does_not_match_installed_session")
        val after = withTimeout(10_000) { context.dataStore.data.first() }
        requireLive(after[UserIdKey]?.toLongOrNull() == id && after.neteaseSessionType() == type,
            "account_changed_during_live_test")
        return VerifiedSession(id!!, type)
    }

    private suspend fun <T> request(label: String, operation: suspend () -> Resource<T>): T {
        val response = withTimeout(45_000) { operation() }
        return when (response) {
            is Resource.Success -> response.data
            else -> failLive("${label}_live_request_failed")
        }
    }

    private fun verifySameMapping(expected: List<MediaMetadata>, actual: List<MediaMetadata>, label: String) {
        val byId = actual.associateBy { it.id }
        expected.forEach { requireLive(byId[it.id]?.availability == it.availability,
            "${label}_permission_mapping_mismatch") }
    }

    private data class Candidate(val track: MediaMetadata, val privilege: SongPlaybackPrivilege) {
        fun isLongPlayable() = track.availability == SongPlaybackAvailability.Playable && track.duration > 30_000
        fun toSafeJson() = JSONObject().put("songId", track.id).put("title", track.title)
            .put("artists", JSONArray(track.artists.map { it.name }))
            .put("durationMs", track.duration).put("sourceType", "song-detail")
            .put("availability", track.availability.name)
            .put("privilege", JSONObject().put("st", privilege.st ?: JSONObject.NULL)
                .put("pl", privilege.pl ?: JSONObject.NULL)
                .put("playMaxbr", privilege.playMaxbr ?: JSONObject.NULL)
                .put("playMaxBrLevel", privilege.playMaxBrLevel ?: JSONObject.NULL))
    }

    private suspend fun writeReport(file: File, report: JSONObject, status: String,
        playable: List<Candidate> = emptyList(), unavailable: List<Candidate> = emptyList()) =
        withContext(Dispatchers.IO) {
            report.put("status", status)
                .put("playableCandidates", JSONArray(playable.map(Candidate::toSafeJson)))
                .put("unavailableCandidates", JSONArray(unavailable.map(Candidate::toSafeJson)))
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(report.toString(2))
            check(temporary.renameTo(file)) { "thor_live_report_write_failed" }
        }

    private fun MediaMetadata.toThorItemForTest() = ThorItem(ThorItemKey.Song(id), "")
    private class LiveFailure(val code: String) : RuntimeException(code)
    private fun failLive(code: String): Nothing = throw LiveFailure(code)
    private fun requireLive(condition: Boolean, code: String) { if (!condition) failLive(code) }
    private fun safeFailure(failure: Throwable, fallback: String) = (failure as? LiveFailure)?.code ?: fallback

    private companion object { const val REPORT_NAME = "thor-live-catalog.json" }
}

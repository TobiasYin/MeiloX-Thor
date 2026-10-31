package com.ljyh.mei.thor.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.SongPlaybackAvailability
import com.ljyh.mei.data.model.UserPlaylist
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.thor.debug.ThorLiveCatalogEntryPoint
import com.ljyh.mei.thor.model.ThorAccountState
import com.ljyh.mei.thor.model.ThorCollection
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.model.ThorOperationResult
import com.ljyh.mei.thor.test.ThorLiveEnvironment
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real, reversible cloud operations. Requires both thor.live=true and thor.liveMutations=true.
 * Existing user playlists are never edited. Only the one uniquely named playlist created by this
 * run may be populated, renamed, or deleted. Public reports contain no account/private object IDs.
 * The private cleanup journal stays in application cache and is removed after confirmed cleanup.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class ThorLiveManagementInstrumentedTest {
    @Test(timeout = 600_000)
    fun reversibleCloudManagementReadsBackFromServerAndCleansItsOwnPlaylist() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit live and mutation opt-in are required",
            arguments.getString("thor.live") == "true" &&
                arguments.getString("thor.liveMutations") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val reportFile = File(context.cacheDir, "thor-live-management.json")
        val report = JSONObject().put("schemaVersion", 1).put("status", "running")
            .put("environment", ThorLiveEnvironment.reportName).put("checkedAtEpochMs", System.currentTimeMillis())
            .put("accountVerified", false).put("cleanup_ok", false).put("steps", JSONObject())
        writeJson(reportFile, report)
        var run: ManagementRun? = null
        var failureCode: String? = null
        try {
            withTimeout(420_000) {
                ThorLiveEnvironment.requireRequestedDevice(context)
                val entry = EntryPointAccessors.fromApplication(context, ThorLiveCatalogEntryPoint::class.java)
                val thor = entry.thorMusicRepository()
                val account = withTimeout(15_000) { thor.awaitCurrentAccountProjection() }
                requireLive(account.signedIn, "login_required")
                val ticket = thor.captureAccountTicket(account) ?: failLive("account_changed_before_test")
                run = ManagementRun(context, entry, thor, account, ticket, report)
                run!!.execute()
            }
        } catch (failure: Throwable) {
            failureCode = (failure as? LiveFailure)?.code ?: "${run?.stage ?: "initialization"}_request_failed"
        } finally {
            // Cleanup has its own finite budget even if the main test was cancelled/timed out.
            val cleanupOk = withContext(NonCancellable) {
                try { withTimeout(120_000) { run?.cleanup() ?: true } }
                catch (_: Throwable) { false }
            }
            report.put("cleanup_ok", cleanupOk).put("checkedAtEpochMs", System.currentTimeMillis())
                .put("status", if (failureCode == null && cleanupOk) "passed" else "failed")
                .put("journalRetained", run?.journalFile?.exists() == true)
            if (failureCode != null) report.put("failureCode", failureCode)
            else if (!cleanupOk) report.put("failureCode", "cleanup_not_confirmed")
            writeJson(reportFile, report)
            assertTrue("thor_live_management_failed:${failureCode ?: "cleanup_not_confirmed"};cleanup_ok=$cleanupOk",
                failureCode == null && cleanupOk)
        }
    }

    private class ManagementRun(
        private val context: Context,
        private val entry: ThorLiveCatalogEntryPoint,
        private val thor: ThorMusicRepository,
        private val account: ThorAccountState,
        private val ticket: ThorAccountTicket,
        private val report: JSONObject,
    ) {
        private val runId = UUID.randomUUID().toString()
        // A UUID-derived suffix remains unique while staying below common 40-character limits.
        private val originalName = "Thor QA 临时测试-${runId.replace("-", "").take(20)}"
        private val renamedName = "$originalName-R"
        private val startedAt = System.currentTimeMillis()
        val journalFile = File(context.cacheDir, "thor-live-management-cleanup-$runId.json")
        var stage = "profile"
            private set
        private val player get() = entry.playerRepository()
        private val playlists get() = entry.playlistRepository()
        private val collections get() = entry.libraryCollectionsRepository()
        private val remoteAccount get() = ticket.account ?: failLive("login_required")
        private var likeSong: MediaMetadata? = null
        private var likedBefore: Boolean? = null
        private var likeAttempted = false
        private var album: ThorItem? = null
        private var albumCollectedBefore: Boolean? = null
        private var albumAttempted = false
        private var creationAttempted = false
        private var created: ThorItemKey.Playlist? = null
        private var deletionConfirmed = false
        private var baselinePlaylistIds = emptySet<Long>()

        suspend fun execute() {
            val profile = frozen { entry.meloXRepository().accountProfile() }
            requireLive(profile.id.toString() == account.userId, "live_profile_mismatch")
            report.put("accountVerified", true)
            val catalogFile = File(context.cacheDir, "thor-live-catalog.json")
            requireLive(catalogFile.isFile, "live_catalog_report_required")
            val catalog = withContext(Dispatchers.IO) { JSONObject(catalogFile.readText()) }
            requireLive(catalog.optInt("schemaVersion") == 1 && catalog.optString("status") == "passed" &&
                catalog.optBoolean("accountVerified") && catalog.optString("environment") == ThorLiveEnvironment.reportName &&
                System.currentTimeMillis() - catalog.optLong("checkedAtEpochMs") in 0..3_600_000L,
                "fresh_passing_live_catalog_required")
            val candidates = catalog.optJSONArray("playableCandidates") ?: failLive("catalog_candidates_missing")
            val candidateIds = (0 until candidates.length()).map { candidates.getJSONObject(it).getLong("songId") }
                .filter { it > 0 }.distinct().take(3)
            requireLive(candidateIds.size >= 3, "three_public_catalog_candidates_required")
            stage = "load_current_session_candidates"
            val catalogTracks = candidateIds.map { id ->
                val item = frozen { thor.load(ThorItem(ThorItemKey.Song(id), "")) }
                val track = item.tracks.firstOrNull { it.id == id } ?: failLive("live_candidate_missing")
                requireLive(!track.isLocal && !track.isPodcast && track.availability == SongPlaybackAvailability.Playable,
                    "live_candidate_no_longer_playable")
                track
            }
            val tracks = catalogTracks.take(2)
            stage = "select_untouched_fixtures"
            // Restoring a true flag by removing and adding it can reorder an existing user list.
            // Only add a previously absent item, then remove that new item via Undo.
            for (track in catalogTracks) {
                val liked = frozen {
                    resource(player.checkSongLike(track.id, ticket.expectedSessionFingerprint), "like_state_read_failed")
                }
                if (!liked) { likeSong = track; likedBefore = false; break }
            }
            requireLive(likeSong != null, "fixture_no_unliked_candidate")
            for (albumId in catalogTracks.map { it.album.id }.filter { it > 0 }.distinct()) {
                val collected = frozen { collections.albumSubscription(remoteAccount, albumId) }
                if (!collected) {
                    album = ThorItem(ThorItemKey.Album(albumId), "")
                    albumCollectedBefore = false
                    break
                }
            }
            requireLive(album != null, "fixture_no_uncollected_candidate")

            stage = "original_states"
            baselinePlaylistIds = readOwnedPlaylists().mapTo(hashSetOf()) { it.id }
            requireLive(readOwnedPlaylists().none { it.name == originalName || it.name == renamedName },
                "unique_test_name_collision")
            journal("prepared")

            stage = "like"
            requireLive(!readLike(), "fixture_no_longer_unliked")
            likeAttempted = true
            journal("like_write_pending")
            val likeResult = operation { thor.toggleLike(likeSong!!) }
            awaitServer("like_server_readback") { readLike() == !likedBefore!! }
            passed("like_server_readback")
            operation { thor.undo(likeResult.undoToken ?: failLive("like_undo_missing")) }
            awaitServer("like_undo_server_readback") { readLike() == likedBefore }
            passed("like_undo_server_readback")
            journal("like_restored")

            stage = "album_collection"
            refreshAlbumProjection()
            requireLive(!readAlbumCollection(), "fixture_no_longer_uncollected")
            requireLive(thor.state.value[ThorCollection.CollectedAlbums].any { it.key == album!!.key } == albumCollectedBefore,
                "album_canonical_state_does_not_match_server")
            albumAttempted = true
            journal("album_write_pending")
            val albumResult = operation { thor.setCollected(album!!.copy(collected = albumCollectedBefore!!), !albumCollectedBefore!!) }
            awaitServer("album_server_readback") { readAlbumCollection() == !albumCollectedBefore!! }
            passed("album_server_readback")
            operation { thor.undo(albumResult.undoToken ?: failLive("album_undo_missing")) }
            awaitServer("album_undo_server_readback") { readAlbumCollection() == albumCollectedBefore }
            passed("album_undo_server_readback")
            journal("album_restored")

            stage = "playlist_create"
            creationAttempted = true
            journal("playlist_create_pending")
            // The actual Thor UI creation API is exercised, with no private/fake alternate path.
            val creation = frozen { thor.createPlaylist(originalName, local = false) }
            created = creation.createdPlaylist
            journal("playlist_create_returned")
            requireLive(creation.success && created != null && created?.local == false, "thor_playlist_create_failed")
            requireLive(created!!.id.toLongOrNull()?.let { it > 0 && it !in baselinePlaylistIds } == true,
                "playlist_id_was_not_created_by_test")
            val firstSnapshot = readTestPlaylist()
            requireLive(firstSnapshot.playlist.name == originalName && firstSnapshot.playlist.trackIds.isEmpty(),
                "created_playlist_initial_state_mismatch")
            awaitServer("playlist_create_server_readback") { readOwnedPlaylists().any { it.id.toString() == created!!.id } }
            passed("playlist_create_server_readback")

            stage = "playlist_add"
            operation { thor.addToPlaylist(created!!, tracks) }
            val order = tracks.map { it.id }
            var readbacks = 0
            awaitServer("playlist_add_server_readback") {
                val snapshot = readTestPlaylist().playlist
                val actual = snapshot.trackIds.map { it.id }
                // Whitelist only shape/order comparisons: never expose the private test list,
                // either title, account, raw HTTP response, or the individual track identifiers.
                report.put("playlistAddReadback", JSONObject().put("attempts", ++readbacks)
                    .put("trackCount", snapshot.trackCount).put("trackIdCount", actual.size)
                    .put("expected_count", order.size).put("actualMatchesOrder", actual == order)
                    .put("actualMatchesReverseOrder", actual == order.reversed())
                    .put("sameMembership", actual.size == order.size && actual.toSet() == order.toSet()))
                actual == order
            }
            passed("playlist_add_server_readback")
            stage = "playlist_duplicate_add"
            operation { thor.addToPlaylist(created!!, tracks) }
            requireLive(readTestPlaylist().playlist.trackIds.map { it.id } == order,
                "duplicate_add_changed_server_membership")
            passed("duplicate_add_did_not_duplicate")

            stage = "playlist_append_existing"
            val third = catalogTracks[2]
            val appended = operation { thor.addToPlaylist(created!!, listOf(third, tracks.first(), third)) }
            awaitServer("playlist_existing_append_order_readback") {
                readTestPlaylist().playlist.trackIds.map { it.id } == order + third.id
            }
            passed("playlist_existing_append_order_readback")
            operation { thor.undo(appended.undoToken ?: failLive("existing_append_undo_missing")) }
            awaitServer("playlist_existing_append_undo_readback") {
                readTestPlaylist().playlist.trackIds.map { it.id } == order
            }
            passed("playlist_existing_append_undo_readback")

            stage = "playlist_remove"
            val removed = operation { thor.removeFromPlaylist(created!!, tracks.take(1)) }
            awaitServer("playlist_remove_server_readback") {
                readTestPlaylist().playlist.trackIds.map { it.id } == order.drop(1)
            }
            passed("playlist_remove_server_readback")
            operation { thor.undo(removed.undoToken ?: failLive("remove_undo_missing")) }
            awaitServer("playlist_remove_undo_order_readback") { readTestPlaylist().playlist.trackIds.map { it.id } == order }
            passed("playlist_remove_undo_order_readback")

            stage = "playlist_rename"
            val renamed = operation { thor.renamePlaylist(created!!, renamedName) }
            awaitServer("playlist_rename_server_readback") { readTestPlaylist().playlist.name == renamedName }
            passed("playlist_rename_server_readback")
            operation { thor.undo(renamed.undoToken ?: failLive("rename_undo_missing")) }
            awaitServer("playlist_rename_undo_readback") { readTestPlaylist().playlist.name == originalName }
            passed("playlist_rename_undo_readback")

            stage = "playlist_delete"
            operation { thor.deletePlaylist(created!!) }
            awaitServer("playlist_delete_server_readback") { readOwnedPlaylists().none { it.id.toString() == created!!.id } }
            deletionConfirmed = true
            passed("playlist_delete_server_readback")
            report.put("counts", JSONObject().put("publicTracksUsed", catalogTracks.size).put("temporaryPlaylistsCreated", 1)
                .put("temporaryPlaylistsDeleted", 1))
            journal("flow_complete")
        }

        suspend fun cleanup(): Boolean {
            if (!thor.isTicketCurrent(ticket)) {
                journal("cleanup_blocked_account_changed")
                return false
            }
            var restoredLike = true
            var restoredAlbum = true
            var removedPlaylist = true
            if (likeAttempted && likedBefore != null) {
                restoredLike = try {
                    if (readLike() != likedBefore) operation { thor.toggleLike(likeSong!!) }
                    awaitServer("cleanup_like_not_restored") { readLike() == likedBefore }
                    true
                } catch (_: Throwable) { false }
            }
            if (albumAttempted && albumCollectedBefore != null) {
                restoredAlbum = try {
                    if (readAlbumCollection() != albumCollectedBefore)
                        operation { thor.setCollected(album!!, albumCollectedBefore!!) }
                    awaitServer("cleanup_album_not_restored") { readAlbumCollection() == albumCollectedBefore }
                    true
                } catch (_: Throwable) { false }
            }
            if (creationAttempted) {
                removedPlaylist = try {
                    // If create reached the server but its response was cancelled, recover only the
                    // unique name absent from the pre-test snapshot, never an existing user list.
                    if (created == null) {
                        val matches = readOwnedPlaylists().filter {
                            it.id !in baselinePlaylistIds && (it.name == originalName || it.name == renamedName)
                        }
                        requireLive(matches.size <= 1, "cleanup_ambiguous_test_playlist")
                        created = matches.singleOrNull()?.let { ThorItemKey.Playlist(it.id.toString()) }
                        journal("cleanup_recovered_creation")
                    }
                    val key = created
                    if (key != null && !deletionConfirmed) {
                        val stillExists = readOwnedPlaylists().any { it.id.toString() == key.id }
                        if (stillExists) {
                            val detail = readTestPlaylist()
                            requireLive(detail.playlist.name == originalName || detail.playlist.name == renamedName,
                                "cleanup_test_playlist_was_changed_externally")
                            operation { thor.deletePlaylist(key) }
                        }
                    }
                    awaitServer("cleanup_playlist_still_exists") {
                        readOwnedPlaylists().none { playlist ->
                            playlist.id.toString() == created?.id || playlist.name == originalName || playlist.name == renamedName
                        }
                    }
                    deletionConfirmed = true
                    true
                } catch (_: Throwable) { false }
            }
            val ok = restoredLike && restoredAlbum && removedPlaylist && thor.isTicketCurrent(ticket)
            report.put("cleanup", JSONObject().put("likeRestored", restoredLike)
                .put("albumRestored", restoredAlbum).put("temporaryPlaylistAbsent", removedPlaylist))
            journal(if (ok) "cleanup_confirmed" else "cleanup_incomplete")
            if (ok) withContext(Dispatchers.IO) { journalFile.delete() }
            return ok
        }

        private suspend fun readLike(): Boolean = frozen {
            resource(player.checkSongLike(likeSong!!.id, ticket.expectedSessionFingerprint), "like_state_read_failed")
        }

        private suspend fun readAlbumCollection(): Boolean = frozen {
            collections.albumSubscription(remoteAccount, (album!!.key as ThorItemKey.Album).id)
        }

        private suspend fun readOwnedPlaylists(): List<UserPlaylist.Playlist> = frozen {
            collections.allPlaylists(remoteAccount).filter { it.creator.userId.toString() == account.userId }
        }

        private suspend fun readTestPlaylist(): PlaylistDetail = frozen {
            val key = created ?: failLive("test_playlist_not_created")
            requireLive(!key.local && key.id.toLongOrNull()?.let { it > 0 && it !in baselinePlaylistIds } == true,
                "refusing_existing_playlist")
            playlists.loadPlaylistForOrder(key.id, remoteAccount.userId.toLong(), ticket.expectedSessionFingerprint)
        }

        private suspend fun refreshAlbumProjection() = coroutineScope {
            val started = async(start = CoroutineStart.UNDISPATCHED) {
                thor.state.first { ThorCollection.CollectedAlbums in it.loading }
            }
            frozen { thor.refresh(ThorCollection.CollectedAlbums) }
            withTimeout(10_000) { started.await() }
            val result = withTimeout(45_000) { thor.state.first { ThorCollection.CollectedAlbums !in it.loading } }
            requireLive(ThorCollection.CollectedAlbums !in result.errors, "album_projection_refresh_failed")
        }

        private suspend fun operation(action: suspend () -> ThorOperationResult): ThorOperationResult = frozen {
            action().also { requireLive(it.success, "${stage}_operation_rejected") }
        }

        private suspend fun <T> frozen(action: suspend () -> T): T {
            requireLive(thor.captureAccountTicket(account) === ticket, "account_changed_during_management_test")
            return withTimeout(45_000) { thor.withAccountTicket(ticket, action) }
        }

        private suspend fun awaitServer(code: String, predicate: suspend () -> Boolean) {
            withTimeout(30_000) {
                repeat(6) { attempt ->
                    if (predicate()) return@withTimeout
                    if (attempt < 5) delay(700)
                }
                failLive(code)
            }
        }

        private fun passed(id: String) { report.getJSONObject("steps").put(id, true) }

        private suspend fun journal(status: String) {
            // This private cache-only journal is deliberately not included in the public report.
            val value = JSONObject().put("schemaVersion", 1).put("runId", runId).put("startedAtEpochMs", startedAt)
                .put("status", status).put("uniqueName", originalName).put("renamedName", renamedName)
                .put("playlistId", created?.id ?: JSONObject.NULL).put("creationAttempted", creationAttempted)
                .put("deletionConfirmed", deletionConfirmed).put("likeSongId", likeSong?.id ?: JSONObject.NULL)
                .put("likedBefore", likedBefore ?: JSONObject.NULL).put("likeAttempted", likeAttempted)
                .put("albumId", (album?.key as? ThorItemKey.Album)?.id ?: JSONObject.NULL)
                .put("albumCollectedBefore", albumCollectedBefore ?: JSONObject.NULL).put("albumAttempted", albumAttempted)
            writeJson(journalFile, value)
        }
    }

    private companion object {
        class LiveFailure(val code: String) : RuntimeException(code)
        fun failLive(code: String): Nothing = throw LiveFailure(code)
        fun requireLive(condition: Boolean, code: String) { if (!condition) failLive(code) }
        fun <T> resource(value: Resource<T>, code: String): T = when (value) {
            is Resource.Success -> value.data
            else -> failLive(code)
        }
        suspend fun writeJson(file: File, value: JSONObject) = withContext(Dispatchers.IO) {
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(value.toString(2))
            check(temporary.renameTo(file)) { "live_management_cache_write_failed" }
        }
    }
}

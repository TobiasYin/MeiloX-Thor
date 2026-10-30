package com.ljyh.mei.thor.data

import com.google.gson.Gson
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.api.BaseMessageResponse
import com.ljyh.mei.data.model.api.BaseResponse
import com.ljyh.mei.data.model.api.CreatePlaylistResult
import com.ljyh.mei.data.model.api.ManipulateTrackResult
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.EApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.network.neteaseSessionFingerprint
import com.ljyh.mei.data.repository.PlaylistRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic transports only: these tests cannot create or mutate a real account. */
class ThorPlaylistSessionGuardTest {
    @Test fun wrongSessionRejectsCreateAndDeleteBeforeSending() = runBlocking {
        val fixture = Fixture()
        assertTrue(fixture.repository.createPlaylist("Draft", false, expectedSession = "stale") is Resource.Error)
        assertTrue(fixture.repository.deletePlaylist("99", "stale") is Resource.Error)
        assertEquals(0, fixture.createCalls)
        assertEquals(0, fixture.deleteCalls)
    }

    @Test fun createPassesExpectedSessionAndRejectsChangedAccountOnReturn() = runBlocking {
        val fixture = Fixture()
        val expected = neteaseSessionFingerprint(fixture.session)
        fixture.changeDuringCreate = true
        assertTrue(fixture.repository.createPlaylist("Draft", false, expectedSession = expected) is Resource.Error)
        assertEquals(1, fixture.createCalls)
        assertEquals(expected, fixture.lastExpectedSession)
    }

    @Test fun renameChecksOwnershipBeforeWriting() = runBlocking {
        val fixture = Fixture()
        val expected = neteaseSessionFingerprint(fixture.session)
        assertTrue(fixture.repository.renamePlaylist("99", "New", 7, expected) is Resource.Error)
        assertEquals(0, fixture.renameCalls)
        assertTrue(fixture.repository.renamePlaylist("99", "New", 8, expected) is Resource.Success)
        assertEquals(1, fixture.renameCalls)
        assertEquals(expected, fixture.lastExpectedSession)
    }

    @Test fun accountChangeDuringOwnershipReadPreventsRename() = runBlocking {
        val fixture = Fixture()
        val expected = neteaseSessionFingerprint(fixture.session)
        fixture.changeDuringDetail = true
        assertTrue(fixture.repository.renamePlaylist("99", "New", 8, expected) is Resource.Error)
        assertEquals(0, fixture.renameCalls)
    }

    @Test fun anExplicitOldSessionRejectsTrackMutationBeforeSending() = runBlocking {
        val fixture = Fixture()
        val expected = neteaseSessionFingerprint(fixture.session)
        fixture.session = "another-synthetic-session"
        assertTrue(fixture.repository.manipulateTrack("add", "99", "7", expectedSession = expected) is Resource.Error)
        assertEquals(0, fixture.trackCalls)
    }

    @Test fun trackMutationCarriesTheCallerSessionAndRejectsAChangedReturn() = runBlocking {
        val fixture = Fixture()
        val expected = neteaseSessionFingerprint(fixture.session)
        fixture.changeDuringTrack = true
        assertTrue(fixture.repository.manipulateTrack("del", "99", "7", expectedSession = expected) is Resource.Error)
        assertEquals(1, fixture.trackCalls)
        assertEquals(expected, fixture.lastExpectedSession)
    }

    @Test fun trackMutationWithoutAnExplicitSessionKeepsExistingCallersWorking() = runBlocking {
        val fixture = Fixture()
        val expected = neteaseSessionFingerprint(fixture.session)
        assertTrue(fixture.repository.manipulateTrack("add", "99", "7") is Resource.Success)
        assertEquals(1, fixture.trackCalls)
        assertEquals(expected, fixture.lastExpectedSession)
    }

    private class Fixture {
        var session = "synthetic-account-session"
        var changeDuringCreate = false
        var changeDuringDetail = false
        var changeDuringTrack = false
        var createCalls = 0
        var deleteCalls = 0
        var renameCalls = 0
        var trackCalls = 0
        var lastExpectedSession: String? = null
        private val gson = Gson()
        private val api: ApiService = proxy(ApiService::class.java) { method, args ->
            when (method) {
                "createPlaylist" -> {
                    createCalls++
                    lastExpectedSession = args[1] as? String
                    if (changeDuringCreate) session = "another-synthetic-session"
                    gson.fromJson("""{"code":200,"id":99,"playlist":{"id":99}}""", CreatePlaylistResult::class.java)
                }
                "deletePlaylist" -> {
                    deleteCalls++
                    lastExpectedSession = args[1] as? String
                    gson.fromJson("""{"code":200,"message":"ok"}""", BaseMessageResponse::class.java)
                }
                "getPlaylistDetail" -> {
                    if (changeDuringDetail) session = "another-synthetic-session"
                    gson.fromJson(
                        """{"code":200,"playlist":{"id":99,"name":"Old","creator":{"userId":8},"trackIds":[],"tracks":[]}}""",
                        PlaylistDetail::class.java,
                    )
                }
                "renamePlaylist" -> {
                    renameCalls++
                    lastExpectedSession = args[1] as? String
                    gson.fromJson("""{"code":200}""", BaseResponse::class.java)
                }
                "manipulateTracks" -> {
                    trackCalls++
                    lastExpectedSession = args[1] as? String
                    if (changeDuringTrack) session = "another-synthetic-session"
                    gson.fromJson("""{"code":200}""", ManipulateTrackResult::class.java)
                }
                else -> error("Unexpected synthetic transport call: $method")
            }
        }
        val repository = PlaylistRepository(
            api, proxy(WeApiService::class.java), proxy(EApiService::class.java),
            freshCheckToken = { "unused" }, subscriptionAccount = { session },
        )
    }
}

private fun <T> proxy(type: Class<T>, call: (String, Array<out Any?>) -> Any? = { name, _ -> error(name) }): T =
    type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, arguments ->
        call(method.name, arguments ?: emptyArray())
    })

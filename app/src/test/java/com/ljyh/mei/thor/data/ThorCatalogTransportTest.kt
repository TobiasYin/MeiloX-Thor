package com.ljyh.mei.thor.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.DeviceIdKey
import com.ljyh.mei.constants.NeteaseMusicAKey
import com.ljyh.mei.constants.NeteaseSessionTypeKey
import com.ljyh.mei.data.network.NeteaseAndroidClientProfile
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.data.network.neteaseSessionFingerprint
import com.ljyh.mei.data.repository.LibraryAccount
import com.ljyh.mei.di.NeteaseInterceptor
import com.ljyh.mei.thor.model.*
import com.ljyh.mei.utils.encrypt.decryptEApi
import java.io.IOException
import korlibs.encoding.unhex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Actual catalog request construction and Retrofit/interceptor envelopes, entirely offline. */
class ThorCatalogTransportTest {
    @Test fun playlistPagingSendsTheAcceptedCategoryAndLongCursorAsWeapiWithoutAnIdentityOverride() = runBlocking {
        val requests = mutableListOf<ThorCatalogRequest>()
        val repository = ThorCatalogRepository { request ->
            requests += request
            json("""{"code":200,"more":false,"playlists":[]}""")
        }
        repository.page(ThorCollection.RecommendedPlaylists, ThorCatalogFilters(playlistCategory = "日语"),
            cursor = 1_730_426_400_999, expectedSession = "opaque-ticket")
        val request = requests.single()
        assertEquals("https://music.163.com/weapi/playlist/highquality/list", request.url)
        assertEquals("weapi", request.crypto)
        assertEquals("日语", request.body["cat"])
        assertEquals(1_730_426_400_999L, request.body["lasttime"])
        assertEquals(true, request.body["total"])
        assertEquals("opaque-ticket", request.expectedSession)
        assertFalse(request.body.keys.any { it.contains("cookie", true) || it.contains("session", true) })
        assertEquals("ThorCatalogRequest", request.toString())
    }

    @Test fun hotAndFilteredArtistDirectoriesUseTheirRealDistinctContracts() = runBlocking {
        val requests = mutableListOf<ThorCatalogRequest>()
        val repository = ThorCatalogRepository { request -> requests += request; json("""{"code":200,"artists":[],"more":false}""") }
        repository.page(ThorCollection.Artists, ThorCatalogFilters(), offset = 40)
        repository.page(ThorCollection.Artists, ThorCatalogFilters(artistArea = 8, artistType = 2), offset = 80)
        assertEquals("/api/artist/top", requests[0].path)
        assertEquals(40, requests[0].body["offset"])
        assertFalse(requests[0].body.containsKey("area"))
        assertEquals("/api/v1/artist/list", requests[1].path)
        assertEquals(8, requests[1].body["area"])
        assertEquals(2, requests[1].body["type"])
        assertEquals(-1, requests[1].body["initial"])
        assertEquals(80, requests[1].body["offset"])
        assertTrue(requests.all { it.crypto == "weapi" })
    }

    @Test fun noAccountDailyRequestDoesNotReachTransportAndNon200OrMissingCodesCannotBecomeAnEmptySuccess() = runBlocking {
        var requests = 0
        val repository = ThorCatalogRepository { requests++; json("""{"code":200,"data":{"dailySongs":[]}}""") }
        assertTrue(runCatching { repository.page(ThorCollection.DailySongs, ThorCatalogFilters()) }.isFailure)
        assertEquals(0, requests)
        assertTrue(repository.page(ThorCollection.DailySongs, ThorCatalogFilters(), expectedSession = "accepted").items.isEmpty())
        assertEquals(1, requests)
        for (response in listOf("""{"code":405,"list":[]}""", """{"list":[]}""")) {
            var attempts = 0
            val rejected = ThorCatalogRepository { attempts++; json(response) }
            assertTrue(runCatching { rejected.page(ThorCollection.Toplists, ThorCatalogFilters()) }.isFailure)
            assertEquals(1, attempts)
        }
    }

    @Test fun artistProfileAndPageRequestsKeepTheirIdentityAndAdvanceTheRequestedOffsets() = runBlocking {
        val requests = mutableListOf<ThorCatalogRequest>()
        val repository = ThorCatalogRepository { request ->
            requests += request
            when (request.path) {
                "/api/artist/head/info/get" -> json("""{"code":200,"data":{"artist":{"id":7,"name":"QA Artist","cover":"https://example.invalid/cover","briefDesc":"Biography","musicSize":6}}}""")
                "/api/v1/artist/7" -> json("""{"code":200,"hotSongs":[]}""")
                "/api/v1/artist/songs" -> json("""{"code":200,"songs":[],"more":false}""")
                "/api/artist/albums/7" -> json("""{"code":200,"hotAlbums":[{"id":12,"name":"QA Album","artists":[{"name":"QA Artist"}],"picUrl":"https://example.invalid/album","size":4}],"more":true}""")
                else -> error("Unexpected synthetic route")
            }
        }
        val profile = repository.artistProfile(ThorItem(ThorItemKey.Artist(7), "Old label"), "accepted")
        assertEquals(ThorItemKey.Artist(7), profile.item.key)
        assertEquals("Biography", profile.item.description)
        assertEquals("https://example.invalid/cover", profile.item.artworkUri)
        assertEquals(6, profile.item.trackCount)
        repository.artistSongs(7, 80, "accepted")
        val albums = repository.artistAlbums(7, 40, "accepted")
        assertEquals(41, albums.nextOffset)
        assertEquals(ThorItemKey.Album(12), albums.items.single().key)
        assertEquals("QA Artist", albums.items.single().subtitle)
        assertEquals(80, requests.first { it.path == "/api/v1/artist/songs" }.body["offset"])
        assertEquals("true", requests.first { it.path == "/api/v1/artist/songs" }.body["private_cloud"])
        assertEquals(40, requests.first { it.path == "/api/artist/albums/7" }.body["offset"])
        assertTrue(requests.all { it.expectedSession == "accepted" })
        assertEquals("eapi", requests.first { it.path == "/api/artist/head/info/get" }.crypto)
        assertEquals("weapi", requests.first { it.path == "/api/v1/artist/7" }.crypto)
    }

    @Test fun changingAccountDuringARealCatalogSuspensionRejectsTheLateResultAndKeepsTheOriginalTicket() = runBlocking {
        val fence = ThorAccountFence(Dispatchers.Unconfined)
        val first = fence.changeAccount(LibraryAccount("7", "synthetic-first"))
        val started = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()
        var acceptedRows = 0
        val repository = ThorCatalogRepository { request ->
            assertEquals(first.expectedSessionFingerprint, request.expectedSession)
            started.complete(Unit)
            complete.await()
            json("""{"code":200,"list":[{"id":19,"name":"Late chart"}]}""")
        }
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { fence.withTicket(first) {
                repository.page(ThorCollection.Toplists, ThorCatalogFilters(), expectedSession = first.expectedSessionFingerprint)
            }.also { acceptedRows += it.items.size } }
        }
        started.await()
        fence.changeAccount(LibraryAccount("8", "synthetic-second"))
        complete.complete(Unit)
        assertTrue(pending.await().isFailure)
        assertEquals(0, acceptedRows)
    }

    @Test fun actualEapiTransportSignsTheOfficialToplistPathWithTheSameMobileSessionAndConsumesTheFenceHeader() = runBlocking {
        val fixture = WireFixture()
        val original = fixture.preferences.asMap().toMap()
        val result = fixture.repository.page(ThorCollection.Toplists, ThorCatalogFilters(),
            expectedSession = neteaseSessionFingerprint("synthetic-session"))
        assertEquals(ThorItemKey.Playlist("19"), result.items.single().key)
        val wire = fixture.requests.single()
        assertEquals("interfacepc.music.163.com", wire.url.host)
        assertEquals("/eapi/toplist", wire.url.encodedPath)
        val form = wire.body as FormBody
        val params = (0 until form.size).first { form.name(it) == "params" }.let(form::value)
        val decoded = decryptEApi(params.unhex).split("-36cd479b6b5-", limit = 3)
        assertEquals("/api/toplist", decoded[0])
        val payload = json(decoded[1])
        assertFalse(payload.has("cookie"))
        assertFalse(payload.has("os"))
        assertTrue(wire.header("Cookie").orEmpty().contains("MUSIC_U=synthetic-session"))
        assertTrue(wire.header("Cookie").orEmpty().contains("os=android"))
        assertNull(wire.header("X-Netease-Expected-Session"))
        assertNull(wire.header("X-Netease-Crypto"))
        assertEquals(original, fixture.preferences.asMap())
    }

    @Test fun aSessionChangeBeforeActualSendRejectsWithoutSendingOrRecapturingAnotherAccount() = runBlocking {
        val fixture = WireFixture()
        val accepted = neteaseSessionFingerprint("synthetic-session")
        fixture.preferences[CookieKey] = "MUSIC_U=synthetic-different-session"
        val outcome = runCatching {
            fixture.repository.page(ThorCollection.Toplists, ThorCatalogFilters(), expectedSession = accepted)
        }
        assertTrue(outcome.exceptionOrNull() is IOException)
        assertTrue(fixture.requests.isEmpty())
    }

    private class WireFixture {
        val preferences = mutablePreferencesOf(CookieKey to "synthetic-session", DeviceIdKey to "fixture-device",
            NeteaseSessionTypeKey to "Mobile", NeteaseMusicAKey to "synthetic-mobile-a")
        val requests = mutableListOf<Request>()
        private val client = OkHttpClient.Builder().addInterceptor(NeteaseInterceptor(
            accountPreferences = { preferences },
            androidConfigProvider = { NeteaseAndroidClientProfile.eapiConfig("13", "Fixture", "QA", "1920x1080") },
        )).addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .body("""{"code":200,"list":[{"id":19,"name":"QA Chart"}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        private val service = Retrofit.Builder().baseUrl("https://interface.music.163.com/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(MeloXDirectService::class.java)
        val repository = ThorCatalogRepository(service, service)
    }

    private fun json(source: String): JsonObject = JsonParser.parseString(source).asJsonObject
}

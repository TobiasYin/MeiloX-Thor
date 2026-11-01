package com.ljyh.mei.data.repository

import com.google.gson.Gson
import com.ljyh.mei.data.model.SongUrl
import com.ljyh.mei.data.model.api.GetSongUrlV1
import com.ljyh.mei.data.network.NETEASE_EXPECTED_SESSION_HEADER
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.EApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.network.neteaseSessionFingerprint
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.http.Header

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistRawSongSourceTest {
    @Test fun rawRequestRetainsTrialDeniedAndUnexpectedRowsAndUsesTheAcceptedSession() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fixture = Fixture(dispatcher, backgroundScope)
        val response = sources()
        fixture.onRequest = {
            assertSame(dispatcher, currentCoroutineContext()[ContinuationInterceptor])
            response
        }
        val session = neteaseSessionFingerprint(fixture.account)
        val result = fixture.repository.getRawSongUrlV1(
            listOf(" 7 ", "007", "8", "2147483789", "0", "-4", "local_1", "bad", ""),
            "standard", session,
        )

        assertSame(response, result)
        assertEquals(listOf(7L, 8L, 999L), result.data.map { it.id })
        assertNotNull(result.data[0].freeTrialInfo)
        assertEquals(404, result.data[1].code)
        assertNull(result.data[1].url)
        assertEquals("[7,8,2147483789]", fixture.calls.single().body.ids)
        assertEquals("standard", fixture.calls.single().body.level)
        assertEquals(session, fixture.calls.single().session)
    }

    @Test fun wrongOrMissingSessionCannotSendAnAuthenticatedRequest() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler), backgroundScope)
        for (session in listOf(null, neteaseSessionFingerprint("another-session"))) {
            val result = runCatching { fixture.repository.getRawSongUrlV1(listOf("7"), "standard", session) }
            assertTrue(result.exceptionOrNull() is SongSourceSessionChangedException)
        }
        assertTrue(fixture.calls.isEmpty())
    }

    @Test fun anonymousRequestRequiresTheAccountToRemainSignedOut() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler), backgroundScope)
        fixture.account = ""
        assertSame(fixture.response, fixture.repository.getRawSongUrlV1(listOf("7"), "standard", null))
        assertNull(fixture.calls.single().session)

        fixture.onRequest = { fixture.account = "new-session"; fixture.response }
        val result = runCatching { fixture.repository.getRawSongUrlV1(listOf("7"), "standard", null) }
        assertTrue(result.exceptionOrNull() is SongSourceSessionChangedException)
    }

    @Test fun accountSwitchDuringTheRequestRejectsItsReturnedTrialEvidence() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler), backgroundScope)
        val release = CompletableDeferred<Unit>()
        fixture.onRequest = { release.await(); fixture.response }
        val session = neteaseSessionFingerprint(fixture.account)
        val result = async { runCatching { fixture.repository.getRawSongUrlV1(listOf("7"), "standard", session) } }
        runCurrent()
        assertEquals(1, fixture.calls.size)
        fixture.account = "replacement-session"
        release.complete(Unit)
        assertTrue(result.await().exceptionOrNull() is SongSourceSessionChangedException)
    }

    @Test fun sessionChangeAlsoReclassifiesAnInterceptorIOExceptionAsAStaleRead() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler), backgroundScope)
        fixture.onRequest = {
            fixture.account = "replacement-session"
            throw IOException("transport rejected session")
        }
        val session = neteaseSessionFingerprint(fixture.account)
        val result = runCatching { fixture.repository.getRawSongUrlV1(listOf("7"), "standard", session) }
        assertTrue(result.exceptionOrNull() is SongSourceSessionChangedException)
    }

    @Test fun businessAndTransportErrorsRemainErrorsAndDoNotBecomeAnEmptySourceList() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler), backgroundScope)
        val session = neteaseSessionFingerprint(fixture.account)
        fixture.onRequest = { SongUrl(code = 405, data = emptyList()) }
        val business = runCatching { fixture.repository.getRawSongUrlV1(listOf("7"), "standard", session) }
        assertTrue(business.exceptionOrNull() is IOException)
        assertTrue(business.exceptionOrNull()!!.message!!.contains("405"))

        val failure = IOException("network unavailable")
        fixture.onRequest = { throw failure }
        val transport = runCatching { fixture.repository.getRawSongUrlV1(listOf("7"), "standard", session) }
        assertPropagated(failure, transport.exceptionOrNull())
    }

    @Test fun callerCancellationIsNotSwallowedOrConvertedToAvailabilityEvidence() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler), backgroundScope)
        val release = CompletableDeferred<Unit>()
        fixture.onRequest = { release.await(); fixture.response }
        var returned = false
        val caller = launch {
            fixture.repository.getRawSongUrlV1(listOf("7"), "standard", neteaseSessionFingerprint(fixture.account))
            returned = true
        }
        runCurrent()
        assertEquals(1, fixture.calls.size)
        caller.cancelAndJoin()
        assertTrue(caller.isCancelled)
        assertFalse(returned)

        val cancellation = CancellationException("cancelled request")
        fixture.onRequest = { throw cancellation }
        val result = runCatching {
            fixture.repository.getRawSongUrlV1(listOf("7"), "standard", neteaseSessionFingerprint(fixture.account))
        }
        assertPropagated(cancellation, result.exceptionOrNull())
    }

    @Test fun aRawBatchIsBoundedToFiftyDistinctPositiveIds() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler), backgroundScope)
        val session = neteaseSessionFingerprint(fixture.account)
        val ids = (1..50).map(Int::toString)
        fixture.repository.getRawSongUrlV1(ids + ids, "standard", session)
        assertEquals("[${ids.joinToString(",")}]", fixture.calls.single().body.ids)
        val tooMany = runCatching { fixture.repository.getRawSongUrlV1(ids + "51", "standard", session) }
        assertTrue(tooMany.exceptionOrNull() is IllegalArgumentException)
        assertEquals(1, fixture.calls.size)
    }

    @Test fun anEmptyPositiveIdSetDoesNotSendARequestButStillChecksTheSession() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler), backgroundScope)
        val session = neteaseSessionFingerprint(fixture.account)
        val response = fixture.repository.getRawSongUrlV1(listOf("0", "-1", "local_7"), "standard", session)
        assertEquals(200, response.code)
        assertTrue(response.data.isEmpty())
        fixture.account = "replacement-session"
        val stale = runCatching { fixture.repository.getRawSongUrlV1(emptyList(), "standard", session) }
        assertTrue(stale.exceptionOrNull() is SongSourceSessionChangedException)
        assertTrue(fixture.calls.isEmpty())
    }

    @Test fun songUrlTransportCarriesTheExpectedSessionHeader() {
        val method = ApiService::class.java.methods.single { it.name == "getSongUrlV1" }
        val header = method.parameterAnnotations[1].filterIsInstance<Header>().single()
        assertEquals(NETEASE_EXPECTED_SESSION_HEADER, header.value)
    }

    private data class Request(val body: GetSongUrlV1, val session: String?)

    private class Fixture(dispatcher: CoroutineDispatcher, scope: CoroutineScope) {
        var account = "test-session"
        val response = sources()
        val calls = mutableListOf<Request>()
        var onRequest: suspend () -> SongUrl = { response }
        private val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) {
                _, method, arguments ->
            check(method.name == "getSongUrlV1") { "Unexpected API request: ${method.name}" }
            val args = checkNotNull(arguments)
            calls += Request(args[0] as GetSongUrlV1, args[1] as? String)
            suspendResponse(args.last(), onRequest)
        } as ApiService
        private val weapi = Proxy.newProxyInstance(WeApiService::class.java.classLoader, arrayOf(WeApiService::class.java)) {
                _, method, _ -> error("Unexpected WEAPI request: ${method.name}")
        } as WeApiService
        private val eapi = Proxy.newProxyInstance(EApiService::class.java.classLoader, arrayOf(EApiService::class.java)) {
                _, method, _ -> error("Unexpected EAPI request: ${method.name}")
        } as EApiService
        val repository = PlaylistRepository(api, weapi, eapi, freshCheckToken = { error("Unexpected token request") },
            subscriptionAccount = { account }, subscriptionScope = scope, ioDispatcher = dispatcher)
    }

    private companion object {
        private fun assertPropagated(expected: Throwable, actual: Throwable?) {
            // Coroutine stack-trace recovery can copy an exception across suspension boundaries.
            // It preserves its type/message and attaches the original throwable as a cause.
            assertNotNull(actual)
            assertEquals(expected.javaClass, actual!!.javaClass)
            assertEquals(expected.message, actual.message)
            assertTrue("The original failure must remain in the propagated cause chain",
                generateSequence(actual) { it.cause }.take(32).any { it === expected })
        }

        fun sources(): SongUrl = Gson().fromJson("""{"code":200,"data":[
            {"id":7,"code":200,"url":"https://example.invalid/trial","level":"standard","freeTrialInfo":{"start":0,"end":30000}},
            {"id":8,"code":404,"url":null,"level":"standard"},
            {"id":999,"code":200,"url":"https://example.invalid/full","level":"standard","freeTrialInfo":null}
        ]}""", SongUrl::class.java)

        @Suppress("UNCHECKED_CAST")
        fun suspendResponse(continuation: Any?, action: suspend () -> SongUrl): Any {
            action.startCoroutine(continuation as Continuation<SongUrl>)
            return COROUTINE_SUSPENDED
        }
    }
}

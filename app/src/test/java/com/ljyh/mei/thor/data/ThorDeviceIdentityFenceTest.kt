package com.ljyh.mei.thor.data

import com.ljyh.mei.data.repository.LibraryAccount
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ThorDeviceIdentityFenceTest {
    private val account = LibraryAccount("7", "synthetic-mobile-session")

    @Test fun aStaleAcceptedOperationCannotStartDevicePreparation() = runBlocking {
        val fence = ThorAccountFence(Dispatchers.Unconfined)
        val ticket = fence.changeAccount(account)
        fence.changeAccount(LibraryAccount("8", "synthetic-new-session"))
        var prepares = 0
        assertTrue(runCatching { awaitThorDeviceIdentity(fence, ticket) { prepares++ } }.isFailure)
        assertEquals(0, prepares)
    }

    @Test fun returningToSameAccountDuringPreparationDoesNotReleaseOldNetworkLoad() = runBlocking {
        withTimeout(10_000) {
            val fence = ThorAccountFence(Dispatchers.Unconfined)
            val ticket = fence.changeAccount(account)
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var networkCalls = 0
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    awaitThorDeviceIdentity(fence, ticket) { expectedSession ->
                        assertEquals(account.sessionFingerprint, expectedSession)
                        entered.complete(Unit)
                        finish.await()
                    }
                    networkCalls++
                }
            }
            entered.await()
            fence.changeAccount(LibraryAccount("8", "synthetic-new-session"))
            fence.changeAccount(account)
            finish.complete(Unit)
            assertTrue(pending.await().isFailure)
            assertEquals(0, networkCalls)
        }
    }

    @Test fun bootstrapFailurePreventsNetworkAndTheSameAccountCanRetry() = runBlocking {
        val fence = ThorAccountFence(Dispatchers.Unconfined)
        val ticket = fence.changeAccount(account)
        var networkCalls = 0
        assertTrue(runCatching {
            awaitThorDeviceIdentity(fence, ticket) { throw IOException("Synthetic bootstrap failure") }
            networkCalls++
        }.isFailure)
        assertEquals(0, networkCalls)
        assertTrue(fence.isCurrent(ticket))
        awaitThorDeviceIdentity(fence, ticket) { expected -> assertEquals(account.sessionFingerprint, expected) }
        networkCalls++
        assertEquals(1, networkCalls)
    }
}

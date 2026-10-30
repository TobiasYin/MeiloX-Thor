package com.ljyh.mei.data.repository

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.DeviceIdKey
import com.ljyh.mei.constants.NeteaseCsrfKey
import com.ljyh.mei.constants.NeteaseMusicAKey
import com.ljyh.mei.constants.NeteaseRefreshTokenKey
import com.ljyh.mei.constants.NeteaseSecurityIdentityKey
import com.ljyh.mei.constants.NeteaseSessionTypeKey
import com.ljyh.mei.constants.NeteaseUrsAppIdKey
import com.ljyh.mei.constants.SDeviceIdKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.data.network.NeteaseSessionType
import com.ljyh.mei.data.network.neteaseSessionFingerprint
import com.ljyh.mei.data.network.requireExpectedNeteaseSession
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production startup gate; all credentials/identities here are synthetic. */
class NeteaseDeviceIdentityBootstrapTest {
    private class Fixture {
        val values = mutablePreferencesOf(
            CookieKey to "synthetic-mobile-session",
            UserIdKey to "7",
            NeteaseSessionTypeKey to NeteaseSessionType.Mobile.name,
            NeteaseMusicAKey to "synthetic-music-a",
            NeteaseCsrfKey to "synthetic-csrf",
            NeteaseRefreshTokenKey to "synthetic-refresh",
            NeteaseUrsAppIdKey to "synthetic-urs-id",
        )
        val expected get() = neteaseSessionFingerprint(checkNotNull(values[CookieKey]))
        var attempts = 0
        fun completeRegistration() {
            values[DeviceIdKey] = "synthetic-target-device"
            values[SDeviceIdKey] = "synthetic-target-registration"
            values[NeteaseSecurityIdentityKey] = "official-9.5.70-v4"
        }
        fun gate(prepare: suspend () -> Unit = { completeRegistration() }) = NeteaseDeviceIdentityBootstrap(
            readPreferences = { values },
            writePending = { expectedSession, pending ->
                values.requireExpectedNeteaseSession(expectedSession)
                if (pending) values[NeteaseDeviceBootstrapPendingKey] = true
                else values.remove(NeteaseDeviceBootstrapPendingKey)
            },
            prepare = { expectedSession ->
                values.requireExpectedNeteaseSession(expectedSession)
                attempts++
                prepare()
            },
        )
    }

    @Test fun freshMobileIdentityIsPreparedBeforeNetworkWithoutChangingAnyAccountKey() = runBlocking {
        val fixture = Fixture()
        val accountBefore = fixture.values.asMap().toMap()
        val order = mutableListOf<String>()
        val gate = fixture.gate {
            assertEquals(true, fixture.values[NeteaseDeviceBootstrapPendingKey])
            assertNull(fixture.values[DeviceIdKey])
            order += "target-identity"
            fixture.completeRegistration()
        }
        gate.awaitReady(fixture.expected)
        order += "network"
        assertEquals(listOf("target-identity", "network"), order)
        assertEquals(1, fixture.attempts)
        assertNull(fixture.values[NeteaseDeviceBootstrapPendingKey])
        for ((key, value) in accountBefore) assertEquals(value, fixture.values.asMap()[key])
    }

    @Test fun existingIdentityDoesNotPrewarmOrRegisterEvenForLegacyInstallations() = runBlocking {
        val fixture = Fixture()
        fixture.values[DeviceIdKey] = "synthetic-existing-device"
        val before = fixture.values.asMap().toMap()
        repeat(3) { fixture.gate().awaitReady(fixture.expected) }
        assertEquals(0, fixture.attempts)
        assertEquals(before, fixture.values.asMap())
    }

    @Test fun guestAndWebSessionsKeepTheirExistingNetworkPathWithoutMobileRegistration() = runBlocking {
        for (type in listOf(NeteaseSessionType.Web, NeteaseSessionType.None)) {
            val fixture = Fixture()
            fixture.values[NeteaseSessionTypeKey] = type.name
            if (type == NeteaseSessionType.None) fixture.values.remove(CookieKey)
            val before = fixture.values.asMap().toMap()
            fixture.gate().awaitReady(if (type == NeteaseSessionType.None) null else fixture.expected)
            assertEquals(0, fixture.attempts)
            assertEquals(before, fixture.values.asMap())
        }
    }

    @Test fun parallelStartupLoadsWaitForOneRegistration() = runBlocking {
        withTimeout(10_000) {
            val fixture = Fixture()
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var networkCalls = 0
            val gate = fixture.gate {
                entered.complete(Unit)
                finish.await()
                fixture.completeRegistration()
            }
            val loads = List(4) { async(start = CoroutineStart.UNDISPATCHED) {
                gate.awaitReady(fixture.expected)
                networkCalls++
            } }
            entered.await()
            assertEquals(1, fixture.attempts)
            assertEquals(0, networkCalls)
            assertTrue(loads.none { it.isCompleted })
            finish.complete(Unit)
            loads.awaitAll()
            assertEquals(1, fixture.attempts)
            assertEquals(4, networkCalls)
        }
    }

    @Test fun aFailedRegistrationRemainsRetryableAfterDeviceIdWasPersistedAndProcessRecreated() = runBlocking {
        val fixture = Fixture()
        val expected = fixture.expected
        var networkCalls = 0
        val first = fixture.gate {
            fixture.values[DeviceIdKey] = "synthetic-generated-before-failure"
            throw IOException("Synthetic registration failure")
        }
        assertTrue(runCatching { first.awaitReady(expected); networkCalls++ }.isFailure)
        assertEquals(0, networkCalls)
        assertEquals(true, fixture.values[NeteaseDeviceBootstrapPendingKey])
        assertEquals(NeteaseSessionType.Mobile.name, fixture.values[NeteaseSessionTypeKey])
        assertEquals(expected, fixture.expected)
        // New gate models a new repository/process; retry relies on durable state, not RAM.
        fixture.gate().awaitReady(expected)
        networkCalls++
        assertEquals(2, fixture.attempts)
        assertEquals(1, networkCalls)
        assertNull(fixture.values[NeteaseDeviceBootstrapPendingKey])
    }

    @Test fun anIncompleteSuccessfulCallbackDoesNotReleaseNetworkLoads() = runBlocking {
        val fixture = Fixture()
        var networkCalls = 0
        val gate = fixture.gate { fixture.values[DeviceIdKey] = "synthetic-device-only" }
        assertTrue(runCatching { gate.awaitReady(fixture.expected); networkCalls++ }.isFailure)
        assertEquals(0, networkCalls)
        assertEquals(true, fixture.values[NeteaseDeviceBootstrapPendingKey])
    }

    @Test fun switchingSessionsDuringRegistrationRejectsTheOldLoadAndRetainsNewSession() = runBlocking {
        val fixture = Fixture()
        val oldExpected = fixture.expected
        var networkCalls = 0
        val gate = fixture.gate {
            fixture.completeRegistration()
            fixture.values[CookieKey] = "synthetic-new-mobile-session"
            fixture.values[UserIdKey] = "8"
        }
        assertTrue(runCatching { gate.awaitReady(oldExpected); networkCalls++ }.isFailure)
        assertEquals(0, networkCalls)
        assertEquals("synthetic-new-mobile-session", fixture.values[CookieKey])
        assertEquals("8", fixture.values[UserIdKey])
        assertEquals(NeteaseSessionType.Mobile.name, fixture.values[NeteaseSessionTypeKey])
        assertEquals(true, fixture.values[NeteaseDeviceBootstrapPendingKey])
        fixture.gate().awaitReady(fixture.expected)
        assertNull(fixture.values[NeteaseDeviceBootstrapPendingKey])
    }

    @Test fun cancellationKeepsPendingAndReleasesTheGateForRetry() = runBlocking {
        withTimeout(10_000) {
            val fixture = Fixture()
            val entered = CompletableDeferred<Unit>()
            val neverFinish = CompletableDeferred<Unit>()
            val gate = fixture.gate {
                if (fixture.attempts == 1) {
                    fixture.values[DeviceIdKey] = "synthetic-generated-before-cancel"
                    entered.complete(Unit)
                    neverFinish.await()
                }
                fixture.completeRegistration()
            }
            val first = launch(start = CoroutineStart.UNDISPATCHED) { gate.awaitReady(fixture.expected) }
            entered.await()
            first.cancelAndJoin()
            assertEquals(true, fixture.values[NeteaseDeviceBootstrapPendingKey])
            gate.awaitReady(fixture.expected)
            assertEquals(2, fixture.attempts)
            assertNull(fixture.values[NeteaseDeviceBootstrapPendingKey])
        }
    }
}

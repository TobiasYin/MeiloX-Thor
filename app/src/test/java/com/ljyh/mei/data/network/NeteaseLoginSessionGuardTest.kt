package com.ljyh.mei.data.network

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.NeteaseCsrfKey
import com.ljyh.mei.constants.UserIdKey
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Artificial session values only: no account, stored credentials, HTTP or login service. */
class NeteaseLoginSessionGuardTest {
    @Test
    fun signedOutIsAnExplicitFingerprintAndMatchesAnExplicitEmptyCookie() {
        val absent = emptyPreferences().captureNeteaseLoginSession()
        val empty = preferencesOf(CookieKey to "").captureNeteaseLoginSession()
        assertEquals(absent, empty)
        assertTrue(absent.isNotEmpty())
        emptyPreferences().requireExpectedNeteaseLoginSession(absent)
    }

    @Test
    fun theSameSessionAllowsAnAcceptedLoginCommit() {
        val current = preferencesOf(CookieKey to "synthetic-session-a", UserIdKey to "qa-a").toMutablePreferences()
        val expected = current.captureNeteaseLoginSession()
        current.requireExpectedNeteaseLoginSession(expected)
        current[CookieKey] = "synthetic-verified-session"
        current[UserIdKey] = "qa-verified"
        assertEquals("synthetic-verified-session", current[CookieKey])
        assertEquals("qa-verified", current[UserIdKey])
    }

    @Test
    fun aLoginStartedWhileSignedOutCannotOverwriteAnAccountAddedMeanwhile() {
        val expected = emptyPreferences().captureNeteaseLoginSession()
        val current = preferencesOf(CookieKey to "synthetic-session-b", UserIdKey to "qa-b").toMutablePreferences()
        assertThrows(IOException::class.java) {
            current.requireExpectedNeteaseLoginSession(expected)
            current[CookieKey] = "synthetic-stale-login"
            current[UserIdKey] = "qa-stale"
        }
        assertEquals("synthetic-session-b", current[CookieKey])
        assertEquals("qa-b", current[UserIdKey])
    }

    @Test
    fun aChangedAccountRejectsTheLateCommitBeforeAnyIdentityOrCsrfWrite() {
        val expected = preferencesOf(CookieKey to "synthetic-session-a").captureNeteaseLoginSession()
        val current = preferencesOf(CookieKey to "synthetic-session-b", UserIdKey to "qa-b", NeteaseCsrfKey to "qa-csrf-b")
            .toMutablePreferences()
        assertThrows(IOException::class.java) {
            current.requireExpectedNeteaseLoginSession(expected)
            current[CookieKey] = "synthetic-stale-session-a"
            current[UserIdKey] = "qa-a"
            current[NeteaseCsrfKey] = "qa-csrf-a"
        }
        assertEquals("synthetic-session-b", current[CookieKey])
        assertEquals("qa-b", current[UserIdKey])
        assertEquals("qa-csrf-b", current[NeteaseCsrfKey])
    }

    @Test
    fun loggingOutWhileALoginIsPendingCannotResurrectTheEarlierSession() {
        val expected = preferencesOf(CookieKey to "synthetic-session-a").captureNeteaseLoginSession()
        val signedOut = emptyPreferences().toMutablePreferences()
        assertThrows(IOException::class.java) {
            signedOut.requireExpectedNeteaseLoginSession(expected)
            signedOut[CookieKey] = "synthetic-stale-session-a"
            signedOut[UserIdKey] = "qa-a"
        }
        assertNull(signedOut[CookieKey])
        assertNull(signedOut[UserIdKey])
    }

    @Test
    fun profileMetadataChangesDoNotInvalidateTheSameAuthenticationSession() {
        val original = preferencesOf(CookieKey to "synthetic-session-a", UserIdKey to "qa-old-profile")
        val expected = original.captureNeteaseLoginSession()
        val refreshed = original.toMutablePreferences().apply { this[UserIdKey] = "qa-refreshed-profile" }
        refreshed.requireExpectedNeteaseLoginSession(expected)
        assertEquals(expected, refreshed.captureNeteaseLoginSession())
    }
}

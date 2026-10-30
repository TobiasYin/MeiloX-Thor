package com.ljyh.mei.thor.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.data.network.NeteaseSessionType
import com.ljyh.mei.data.network.neteaseSessionType
import com.ljyh.mei.thor.debug.ThorPhysicalAccountEntryPoint
import com.ljyh.mei.utils.dataStore
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in, read-only server verification of the installed app's retained account.
 * Enable only for the intended physical session with -e thor.physicalAccount true.
 * No fixture session, profile cache, login, device registration, or library mutation is used.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class ThorPhysicalAccountInstrumentedTest {
    @Test(timeout = 90_000)
    fun retainedAccountMatchesTheLiveServerProfile() = runBlocking<Unit> {
        // This gate precedes Hilt access, DataStore reads, and all network calls on ordinary CI.
        assumeTrue(
            "Explicit physical-account verification is required",
            InstrumentationRegistry.getArguments().getString("thor.physicalAccount") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val before = runCatching { withTimeout(10_000) { context.dataStore.data.first() } }.getOrNull()
        assertTrue("stored_account_readable=false", before != null)
        val storedType = before!!.neteaseSessionType()
        val storedId = before[UserIdKey]?.toLongOrNull()
        assertTrue("stored_account_present=false", storedType != NeteaseSessionType.None && (storedId ?: 0) > 0)

        val repository = runCatching {
            EntryPointAccessors.fromApplication(context, ThorPhysicalAccountEntryPoint::class.java)
                .meloXRepository()
        }.getOrNull()
        assertTrue("repository_available=false", repository != null)

        // Call the no-argument path so the real repository uses the installed session's transport.
        // Discard failure causes: assertion reports must never contain server account data.
        val profile = runCatching { withTimeout(45_000) { repository!!.accountProfile() } }.getOrNull()
        assertTrue("profile_ok=false", profile != null && profile.id > 0)
        assertTrue("same_account=false", profile!!.id == storedId)

        val after = runCatching { withTimeout(10_000) { context.dataStore.data.first() } }.getOrNull()
        assertTrue("stored_account_readable=false", after != null)
        assertTrue(
            "same_account=false",
            after!!.neteaseSessionType() == storedType && after[UserIdKey]?.toLongOrNull() == storedId,
        )
    }
}

package com.ljyh.mei.di

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.DeviceIdKey
import com.ljyh.mei.constants.NeteaseSessionTypeKey
import com.ljyh.mei.constants.NeteaseUrsAppIdKey
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NeteaseInterceptorDeviceIdentityTest {
    @Test
    fun missingOrBlankMobileIdentityFailsThroughCallbackWithoutEscapingDispatcher() {
        for (deviceId in listOf(null, "", " \t")) {
            val preferences = mutablePreferencesOf(CookieKey to "synthetic-mobile-session", NeteaseSessionTypeKey to "Mobile")
            if (deviceId != null) preferences[DeviceIdKey] = deviceId
            val callback = CountDownLatch(1)
            val callbackError = AtomicReference<IOException?>()
            val escaped = AtomicReference<Throwable?>()
            val responseCount = AtomicInteger()
            val transportCount = AtomicInteger()
            val workers = CopyOnWriteArrayList<Thread>()
            // OkHttp can notify onFailure and then rethrow a RuntimeException on its worker.
            // Observe the actual dispatcher task boundary, not merely the callback's type.
            val executor = Executors.newSingleThreadExecutor { task ->
                Thread({
                    try {
                        task.run()
                    } catch (error: Throwable) {
                        escaped.compareAndSet(null, error)
                    }
                }, "identity-regression-dispatcher").apply { isDaemon = true }.also { workers.add(it) }
            }
            val client = OkHttpClient.Builder()
                .dispatcher(Dispatcher(executor))
                .addInterceptor(NeteaseInterceptor { preferences })
                .addInterceptor {
                    transportCount.incrementAndGet()
                    throw IOException("Unexpected transport: an incomplete identity must fail closed")
                }.build()
            try {
                client.newCall(accountRequest()).enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        callbackError.set(error)
                        callback.countDown()
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { responseCount.incrementAndGet() }
                        callback.countDown()
                    }
                })
                assertTrue("Incomplete mobile session must complete through onFailure", callback.await(5, TimeUnit.SECONDS))
            } finally {
                executor.shutdownNow()
                assertTrue("Dispatcher must finish", executor.awaitTermination(5, TimeUnit.SECONDS))
                workers.forEach { it.join(5_000) }
                client.connectionPool.evictAll()
            }
            val error = callbackError.get()
            assertNotNull("Expected a recoverable network failure", error)
            assertTrue(error!!.message.orEmpty().contains("device identity"))
            assertFalse(error.message.orEmpty().contains("synthetic-mobile-session"))
            assertNull("A runtime failure must not be wrapped and rethrown", error.cause)
            assertNull("No exception may escape the OkHttp dispatcher task", escaped.get())
            assertEquals(0, responseCount.get())
            assertEquals(0, transportCount.get())
            assertEquals("Mobile", preferences[NeteaseSessionTypeKey])
            assertEquals(deviceId, preferences[DeviceIdKey])
        }
    }

    @Test
    fun legacyMobileIdentityRequirementRemainsStrictAndRecoverable() {
        val preferences = mutablePreferencesOf(CookieKey to "synthetic-legacy-session", NeteaseUrsAppIdKey to "synthetic-urs-app")
        val transportCount = AtomicInteger()
        val client = OkHttpClient.Builder()
            .addInterceptor(NeteaseInterceptor { preferences })
            .addInterceptor {
                transportCount.incrementAndGet()
                throw IOException("Unexpected transport")
            }.build()
        try {
            client.newCall(accountRequest()).execute().close()
            fail("Legacy mobile sessions still require their own device identity")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("device identity"))
            assertFalse(error.message.orEmpty().contains("synthetic-legacy-session"))
            assertNull(error.cause)
        } finally {
            client.connectionPool.evictAll()
        }
        assertEquals(0, transportCount.get())
        assertNull(preferences[DeviceIdKey])
        assertNull(preferences[NeteaseSessionTypeKey])
    }

    private fun accountRequest() = Request.Builder()
        .url("https://example.invalid/api/w/nuser/account/get")
        .post("{}".toRequestBody())
        .build()
}

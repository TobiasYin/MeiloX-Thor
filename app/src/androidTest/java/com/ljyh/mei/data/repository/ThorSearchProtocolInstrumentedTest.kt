package com.ljyh.mei.data.repository

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.data.network.NeteaseSessionType
import com.ljyh.mei.data.network.neteaseSessionType
import com.ljyh.mei.thor.debug.ThorPhysicalAccountEntryPoint
import com.ljyh.mei.thor.debug.ThorSearchProtocolEntryPoint
import com.ljyh.mei.thor.test.ThorLiveEnvironment
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.log.NetworkLogInterceptor
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Opt-in read diagnostics, not a search-success acceptance test. No automatic fallback,
 * cookie/OS override, alternate account, anonymous request, retry or risk challenge bypass.
 * Run with thor.searchProtocol=true and the shared requested-device flag.
 *
 * Variant order: 0 bare API /search/get; 1 WEAPI /search/get; 2 EAPI on the existing
 * interface host; 3 the maintained EAPI interfacepc host /search/get; 4 interfacepc
 * /cloudsearch/pc + total=true. The encrypted path belongs to the protocol; this test
 * never replaces the saved session or its device identity. A completion means five
 * measurements, even if all business codes reject the request.
 *
 * Primary endpoint and host references (not a claim that these work for this account):
 * https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/master/module/search.js
 * https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/master/module/cloudsearch.js
 * https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/master/util/config.json
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class ThorSearchProtocolInstrumentedTest {
    @Test(timeout = 180_000)
    fun sameSessionSearchProtocolsReportOnlySafeCodesAndCounts() = runBlocking<Unit>(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("thor.searchProtocol") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        ThorLiveEnvironment.requireRequestedDevice(context)
        val before = withTimeout(10_000) { context.dataStore.data.first() }
        val userId = before[UserIdKey]
        val sessionType = before.neteaseSessionType()
        assertTrue("search_probe_account_present=false", !userId.isNullOrBlank() && sessionType != NeteaseSessionType.None)
        val profile = runCatching { withTimeout(30_000) {
            EntryPointAccessors.fromApplication(context, ThorPhysicalAccountEntryPoint::class.java)
                .meloXRepository().accountProfile()
        } }.getOrNull()
        assertTrue("search_probe_profile_same_account=false", profile?.id?.toString() == userId)

        val variants = listOf(
            Variant("https://interface.music.163.com/api/search/get", "api"),
            Variant("https://music.163.com/weapi/search/get", "weapi"),
            Variant("https://interface.music.163.com/api/search/get", "eapi"),
            Variant("https://interfacepc.music.163.com/api/search/get", "eapi"),
            Variant("https://interfacepc.music.163.com/api/cloudsearch/pc", "eapi", total = true),
        )
        val allowedHosts = setOf("interface.music.163.com", "interfacepc.music.163.com", "music.163.com")
        val allowedPaths = setOf("/api/search/get", "/weapi/search/get", "/eapi/search/get", "/eapi/cloudsearch/pc")
        val existing = EntryPointAccessors.fromApplication(context, ThorSearchProtocolEntryPoint::class.java).okHttpClient()
        val client = existing.newBuilder().callTimeout(12, TimeUnit.SECONDS).connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false)
            .apply { interceptors().removeAll { it is HttpLoggingInterceptor || it is NetworkLogInterceptor } }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                if (request.method != "POST" || request.url.host !in allowedHosts || request.url.encodedPath !in allowedPaths) {
                    throw IOException("Search probe permits only its five read endpoints")
                }
                // Keep server tracking/session refresh responses out of this diagnostic's
                // shared interceptor. No real account or security identity is rewritten.
                chain.proceed(request).newBuilder().removeHeader("Set-Cookie")
                    .removeHeader("x-encr-ssid").removeHeader("x-encr-sskey").build()
            }.build()
        val transport = Retrofit.Builder().baseUrl("https://interface.music.163.com/")
            .client(client).addConverterFactory(GsonConverterFactory.create()).build().create(SearchProbeTransport::class.java)
        val file = File(context.cacheDir, "thor-search-protocol.json")
        val measurements = JSONArray()
        val report = JSONObject().put("schemaVersion", 1).put("environment", ThorLiveEnvironment.reportName)
            .put("checkedAtEpochMs", System.currentTimeMillis()).put("measurements", measurements).put("status", "running")
        file.writeText(report.toString())
        for ((index, variant) in variants.withIndex()) {
            val identity = withTimeout(10_000) { context.dataStore.data.first() }
            assertTrue("search_probe_same_account=false", identity[UserIdKey] == userId && identity.neteaseSessionType() == sessionType)
            val body = linkedMapOf<String, Any>("s" to "陈奕迅", "type" to 1, "limit" to 3, "offset" to 0)
            if (variant.total) body["total"] = true
            val started = System.nanoTime()
            val response = runCatching { withTimeout(15_000) { transport.post(variant.url, body, variant.crypto) } }.getOrNull()
            val json = response?.body()
            val message = listOf("msg", "message").mapNotNull { name ->
                json?.get(name)?.takeIf { it.isJsonPrimitive }?.asString
            }.joinToString(" ")
            val code = runCatching { json?.get("code")?.asInt }.getOrNull() ?: 0
            val count = json?.get("result")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("songs")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0
            runCatching { response?.errorBody()?.close() }
            val row = JSONObject().put("variant", index).put("httpCode", response?.code() ?: 0).put("code", code)
                .put("songCount", count).put("transportError", response == null)
                .put("operationFrequent", Regex("频繁|频次|frequen|too many", RegexOption.IGNORE_CASE).containsMatchIn(message))
                .put("loginRequired", code in setOf(301, 302) || Regex("未登录|请.*登录|login|log in", RegexOption.IGNORE_CASE).containsMatchIn(message))
                .put("invalidParameter", Regex("参数|parameter|invalid argument", RegexOption.IGNORE_CASE).containsMatchIn(message))
                .put("clientUpgradeRequired", Regex("版本.*低|升级|更新.*版本|upgrade", RegexOption.IGNORE_CASE).containsMatchIn(message))
                .put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
            measurements.put(row)
            file.writeText(report.toString())
            Log.i("ThorSearchProtocol", row.toString())
            if (index < variants.lastIndex) delay(500)
        }
        val after = withTimeout(10_000) { context.dataStore.data.first() }
        assertTrue("search_probe_same_account_after=false", after[UserIdKey] == userId && after.neteaseSessionType() == sessionType)
        report.put("status", "completed").put("sameAccount", true)
        file.writeText(report.toString())
    }

    private data class Variant(val url: String, val crypto: String, val total: Boolean = false)

    private interface SearchProbeTransport {
        @POST suspend fun post(@Url url: String, @Body body: Map<String, @JvmSuppressWildcards Any>,
            @Header("X-Netease-Crypto") crypto: String): Response<JsonObject>
    }
}

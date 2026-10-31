package com.ljyh.mei.data.repository

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.DeviceIdKey
import com.ljyh.mei.constants.NeteaseCsrfKey
import com.ljyh.mei.constants.NeteaseMusicAKey
import com.ljyh.mei.constants.NeteaseSessionTypeKey
import com.ljyh.mei.data.network.NeteaseAndroidClientProfile
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.di.NeteaseInterceptor
import com.ljyh.mei.utils.encrypt.decryptEApi
import java.security.MessageDigest
import korlibs.encoding.unhex
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Real Retrofit -> NeteaseInterceptor -> AES wire envelope, with no network or real session. */
class SearchEncryptedTransportTest {
    @Test
    fun mobileSearchSignsTheExactEndpointAndPreservesQueryTypesAndInstalledIdentity() = runBlocking {
        val fixture = Fixture(mobile = true)
        val query = "  陈奕迅 / QA + 你好  "
        for (type in listOf(1, 10, 100, 1000)) {
            assertTrue(fixture.repository.search(query, type, 20) is Resource.Success)
            val request = fixture.requests.last()
            val payload = decryptedPayload(request)
            assertEquals("/eapi/cloudsearch/pc", request.url.encodedPath)
            assertEquals("interfacepc.music.163.com", request.url.host)
            assertEquals(query, payload["s"].asString)
            assertEquals(type.toString(), payload["type"].toString())
            assertEquals("20", payload["limit"].toString())
            assertEquals("0", payload["offset"].toString())
            assertTrue(payload["total"].asBoolean)
            assertEquals("{}", payload["header"].asString)
            assertFalse(payload["e_r"].asBoolean)
            assertEquals("android", cookie(request, "os"))
            assertEquals("fixture-device", cookie(request, "deviceId"))
            assertEquals("fixture-session", cookie(request, "MUSIC_U"))
            assertEquals("fixture-mobile-a", cookie(request, "MUSIC_A"))
            assertEquals("fixture-device", request.header("x-deviceId"))
            assertEquals("fixture-session", request.header("x-music-u"))
            assertEquals("android", request.header("x-os"))
            assertTrue(request.header("User-Agent")!!.startsWith(NeteaseAndroidClientProfile.USER_AGENT_PREFIX))
            assertNull(request.header("X-Netease-Crypto"))
            assertNull(request.header("X-Netease-Cookie-OS"))
            assertFalse(payload.has("cookieOs"))
        }
        assertEquals(fixture.originalPreferences, fixture.preferences.asMap())
    }

    @Test
    fun webSearchKeepsTheExistingDesktopEapiProfileWithoutChangingTheWebSession() = runBlocking {
        val fixture = Fixture(mobile = false)
        assertTrue(fixture.repository.search("QA", 1, 12) is Resource.Success)
        val request = fixture.requests.single()
        val payload = decryptedPayload(request)
        assertEquals("pc", cookie(request, "os"))
        assertEquals("fixture-session", cookie(request, "MUSIC_U"))
        assertNull(cookie(request, "MUSIC_A"))
        assertEquals("pc", payload["header"].asJsonObject["os"].asString)
        assertNull(request.header("x-os"))
        assertEquals(0, fixture.androidProfileReads)
        assertEquals(fixture.originalPreferences, fixture.preferences.asMap())
    }

    @Test
    fun podcastKeepsItsPreviouslyVerifiedPlainDesktopCookieContract() = runBlocking {
        val fixture = Fixture(mobile = true)
        assertTrue(fixture.repository.search("HOYO", 1009, 12) is Resource.Success)
        val request = fixture.requests.single()
        assertEquals("/api/search/get/", request.url.encodedPath)
        val form = request.body as FormBody
        assertEquals("HOYO", formValue(form, "s"))
        assertEquals("1009", formValue(form, "type"))
        assertEquals("12", formValue(form, "limit"))
        assertEquals("0", formValue(form, "offset"))
        assertNull(formValue(form, "params"))
        assertEquals("pc", cookie(request, "os"))
        assertEquals("fixture-session", cookie(request, "MUSIC_U"))
        assertNull(request.header("X-Netease-Crypto"))
        assertNull(request.header("X-Netease-Cookie-OS"))
        assertEquals(fixture.originalPreferences, fixture.preferences.asMap())
    }

    @Test
    fun businessRejectionIsNotRetriedWithAnotherIdentityOrHiddenAsSuccessCode200() = runBlocking {
        val fixture = Fixture(mobile = true, response = """{"code":405}""")
        val result = fixture.repository.search("QA", 1, 20)
        assertTrue(result is Resource.Success)
        assertEquals(405, (result as Resource.Success).data.code)
        assertEquals(1, fixture.requests.size)
        assertEquals("android", cookie(fixture.requests.single(), "os"))
        assertEquals(fixture.originalPreferences, fixture.preferences.asMap())
    }

    private fun decryptedPayload(request: Request): JsonObject {
        val form = request.body as FormBody
        assertEquals(listOf("params"), (0 until form.size).map(form::name))
        val envelope = decryptEApi(checkNotNull(formValue(form, "params")).unhex)
            .split("-36cd479b6b5-", limit = 3)
        assertEquals(3, envelope.size)
        assertEquals("/api/cloudsearch/pc", envelope[0])
        val digest = MessageDigest.getInstance("MD5")
            .digest("nobody${envelope[0]}use${envelope[1]}md5forencrypt".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(digest, envelope[2])
        return JsonParser.parseString(envelope[1]).asJsonObject
    }

    private fun formValue(form: FormBody, name: String): String? =
        (0 until form.size).firstOrNull { form.name(it) == name }?.let(form::value)

    private fun cookie(request: Request, name: String): String? = request.header("Cookie")
        ?.split("; ")?.firstOrNull { it.substringBefore('=') == name }?.substringAfter('=')

    private class Fixture(mobile: Boolean, response: String = """{"code":200,"result":{},"trp":{"rules":[]}}""") {
        val preferences = mutablePreferencesOf(CookieKey to "fixture-session", DeviceIdKey to "fixture-device",
            NeteaseSessionTypeKey to if (mobile) "Mobile" else "Web").apply {
            if (mobile) {
                this[NeteaseMusicAKey] = "fixture-mobile-a"
                this[NeteaseCsrfKey] = "fixture-csrf"
            }
        }
        val originalPreferences = preferences.asMap().toMap()
        val requests = mutableListOf<Request>()
        var androidProfileReads = 0
        val repository = SearchRepository(Retrofit.Builder()
            .baseUrl("https://interface.music.163.com/")
            .client(OkHttpClient.Builder()
                .addInterceptor(NeteaseInterceptor(
                    accountPreferences = { preferences },
                    androidConfigProvider = {
                        androidProfileReads++
                        NeteaseAndroidClientProfile.eapiConfig("13", "Fixture", "QA", "1920x1080")
                    },
                ))
                .addInterceptor { chain ->
                    requests += chain.request()
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                        .message("synthetic transport").body(response.toResponseBody("application/json".toMediaType())).build()
                }.build())
            .addConverterFactory(GsonConverterFactory.create()).build().create(ApiService::class.java))
    }
}

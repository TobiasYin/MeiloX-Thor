package com.ljyh.mei.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class CacheManagerRecoveryTest {
    private fun cache(length: Long, cachedBytes: Long, onRange: () -> Unit = {}): Cache {
        val metadata = Proxy.newProxyInstance(ContentMetadata::class.java.classLoader,
            arrayOf(ContentMetadata::class.java)) { _, method, _ ->
            check(method.name == "get") { "Unexpected metadata call: ${method.name}" }
            length
        } as ContentMetadata
        return Proxy.newProxyInstance(Cache::class.java.classLoader, arrayOf(Cache::class.java)) { _, method, _ ->
            when (method.name) {
                "getContentMetadata" -> metadata
                "getCachedBytes" -> { onRange(); cachedBytes }
                else -> error("A completeness check must not modify cached media: ${method.name}")
            }
        } as Cache
    }

    @Test fun zeroOrUnknownLengthMustNotBeAcceptedAsAnOfflinePlayableCandidate() {
        for (length in listOf(0L, -1L)) {
            assertFalse(CacheManager.isContentFullyCached(cache(length, 0L) {
                error("An empty/unknown resource has no complete byte range")
            }, "qa"))
        }
    }

    @Test fun realCompleteCacheRemainsPlayableOfflineWhilePartialCacheNeedsAnUpstreamCheck() {
        assertTrue(CacheManager.isContentFullyCached(cache(1_024L, 1_024L), "qa"))
        assertFalse(CacheManager.isContentFullyCached(cache(1_024L, 1_023L), "qa"))
    }
}

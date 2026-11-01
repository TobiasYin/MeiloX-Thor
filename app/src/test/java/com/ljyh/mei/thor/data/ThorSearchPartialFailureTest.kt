package com.ljyh.mei.thor.data

import com.google.gson.Gson
import com.ljyh.mei.data.model.SongPlaybackAvailability
import com.ljyh.mei.data.model.api.SearchResult
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.thor.model.*
import org.junit.Assert.*
import org.junit.Test

/** Actual typed transport outcomes and public-catalog JSON, without UI or real requests. */
class ThorSearchPartialFailureTest {
    private val gson = Gson()

    @Test fun exactRequestTypesPreserveTheirFailedCategoryWhileOtherMetadataRemainsUsable() {
        assertEquals(listOf(1 to ThorSearchCategory.Songs, 10 to ThorSearchCategory.Albums,
            100 to ThorSearchCategory.Artists, 1000 to ThorSearchCategory.Playlists), thorSearchRequests)
        val result = mergeThorSearchResponses(emptyList(), listOf(
            ThorSearchCategory.Songs to success("""{"code":200,"result":{"songs":[$song]}}"""),
            ThorSearchCategory.Albums to success("""{"code":405,"result":{}}"""),
            ThorSearchCategory.Artists to Resource.Error("synthetic transport failure"),
            ThorSearchCategory.Playlists to success("""{"code":200,"result":{"playlists":[
                {"id":301,"name":"QA Playlist","creator":{"userId":7,"nickname":"QA Curator"},
                 "coverImgUrl":"https://example.invalid/playlist","trackCount":4}
            ]}}"""),
        ), "7")
        assertEquals(listOf(ThorItemKey.Song(101), ThorItemKey.Playlist("301")), result.items.map { it.key })
        assertEquals(setOf(ThorSearchCategory.Albums, ThorSearchCategory.Artists), result.failedCategories)
        val track = result.items.first().tracks.single()
        assertEquals("QA Song", track.title)
        assertEquals(183_456L, track.duration)
        assertEquals("QA Artist", track.artists.single().name)
        assertEquals("https://example.invalid/cover", track.coverUrl)
        assertEquals(SongPlaybackAvailability.Playable, track.availability)
        assertTrue(result.items.last().owned)
    }

    @Test fun localHitsNeverHideAnyFailedRemoteCategoryEvenWhenEveryRemoteRequestFails() {
        val local = ThorItem(ThorItemKey.Song(101, local = true), "QA Local")
        val failed = allFailures()
        val result = mergeThorSearchResponses(listOf(local), failed, null)
        assertEquals(listOf(local), result.items)
        assertEquals(thorSearchRequests.map { it.second }.toSet(), result.failedCategories)
    }

    @Test fun aSuccessfulEmptyCategoryRemainsPartialWhileAllFailuresWithoutLocalHitsStillThrow() {
        assertThrows(IllegalStateException::class.java) { mergeThorSearchResponses(emptyList(), allFailures(), null) }
        val responses = allFailures().map { (category, resource) ->
            category to if (category == ThorSearchCategory.Artists) success("""{"code":200,"result":{"artists":[]}}""") else resource
        }
        val result = mergeThorSearchResponses(emptyList(), responses, null)
        assertTrue(result.items.isEmpty())
        assertEquals(setOf(ThorSearchCategory.Songs, ThorSearchCategory.Albums, ThorSearchCategory.Playlists), result.failedCategories)
        assertFalse(ThorSearchCategory.Artists in result.failedCategories)
    }

    @Test fun artistSuccessKeepsArtistIdentityArtworkAndAliasesWhenTheSongRequestFails() {
        val result = mergeThorSearchResponses(emptyList(), listOf(
            ThorSearchCategory.Songs to Resource.Error("synthetic failure"),
            ThorSearchCategory.Albums to success("""{"code":200,"result":{"albums":[]}}"""),
            ThorSearchCategory.Artists to success("""{"code":200,"result":{"artists":[
                {"id":201,"name":"QA Artist","alias":["QA Alias"],"picUrl":null,"img1v1Url":"https://example.invalid/artist"}
            ]}}"""),
            ThorSearchCategory.Playlists to success("""{"code":200,"result":{"playlists":[]}}"""),
        ), null)
        val artist = result.items.single()
        assertEquals(ThorItemKey.Artist(201), artist.key)
        assertEquals("QA Artist", artist.title)
        assertEquals("QA Alias", artist.subtitle)
        assertEquals("https://example.invalid/artist", artist.artworkUri)
        assertTrue(artist.tracks.isEmpty())
        assertEquals(setOf(ThorSearchCategory.Songs), result.failedCategories)
    }

    @Test fun successfulResponsesKeepLocalAndOnlineNamespacesAndDeduplicateOnlyIdenticalKeys() {
        val local = ThorItem(ThorItemKey.Song(101, local = true), "QA Local")
        val remote = success("""{"code":200,"result":{"songs":[$song]}}""")
        val result = mergeThorSearchResponses(listOf(local, local), listOf(
            ThorSearchCategory.Songs to remote,
            ThorSearchCategory.Albums to remote,
            ThorSearchCategory.Artists to success("""{"code":200,"result":{}}"""),
            ThorSearchCategory.Playlists to success("""{"code":200,"result":{}}"""),
        ), null)
        assertEquals(listOf(ThorItemKey.Song(101, local = true), ThorItemKey.Song(101)), result.items.map { it.key })
        assertTrue(result.failedCategories.isEmpty())
        val absentResult = mergeThorSearchResponses(listOf(local), listOf(
            ThorSearchCategory.Artists to success("""{"code":200,"result":null}"""),
        ), null)
        assertEquals(setOf(ThorSearchCategory.Artists), absentResult.failedCategories)
    }

    private fun allFailures(): List<Pair<ThorSearchCategory, Resource<SearchResult>>> = listOf(
        ThorSearchCategory.Songs to Resource.Error("synthetic network failure"),
        ThorSearchCategory.Albums to success("""{"code":405,"result":{}}"""),
        ThorSearchCategory.Artists to success("""{"code":500,"result":{}}"""),
        ThorSearchCategory.Playlists to Resource.Error("synthetic network failure"),
    )

    private fun success(source: String): Resource.Success<SearchResult> =
        Resource.Success(gson.fromJson(source, SearchResult::class.java))

    private val song = """{
        "id":101,"name":"QA Song","dt":183456,"st":0,
        "ar":[{"id":201,"name":"QA Artist","alias":null}],
        "al":{"id":301,"name":"QA Album","picUrl":"https://example.invalid/cover"},
        "privilege":{"id":101,"pl":320000}
    }"""
}

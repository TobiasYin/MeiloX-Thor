package com.ljyh.mei.thor.data

import com.google.gson.Gson
import com.ljyh.mei.data.model.api.SearchResult
import com.ljyh.mei.data.model.api.toSongMediaMetadata
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.thor.model.*

internal val thorSearchRequests = listOf(
    1 to ThorSearchCategory.Songs,
    10 to ThorSearchCategory.Albums,
    100 to ThorSearchCategory.Artists,
    1000 to ThorSearchCategory.Playlists,
)

/** A successful category, even with zero hits, is distinct from a rejected or failed request. */
internal fun mergeThorSearchResponses(
    localMatches: List<ThorItem>,
    responses: List<Pair<ThorSearchCategory, Resource<SearchResult>>>,
    accountUserId: String?,
    gson: Gson = Gson(),
): ThorSearchResponse {
    val failed = linkedSetOf<ThorSearchCategory>()
    var successfulCategories = 0
    val online = responses.flatMap { (category, response) ->
        require(category != ThorSearchCategory.All)
        val result: SearchResult.Result? = (response as? Resource.Success<SearchResult>)?.data?.takeIf { it.code == 200 }?.result
        if (result == null) {
            failed += category
            emptyList()
        } else {
            successfulCategories++
            result.toSongMediaMetadata().map { it.toThorItem() } +
                result.albums.orEmpty().map { album -> ThorItem(ThorItemKey.Album(album.id), album.name,
                    album.artists.joinToString(" / ") { it.name }, album.picUrl, album.description.orEmpty(), album.size) } +
                result.artists.orEmpty().mapNotNull { artist ->
                    thorCatalogArtistItem(gson.toJsonTree(artist).asJsonObject)
                } +
                result.playlists.orEmpty().map { playlist -> ThorItem(ThorItemKey.Playlist(playlist.id.toString()),
                    playlist.name, playlist.creator.nickname, playlist.coverImgUrl, trackCount = playlist.trackCount,
                    owned = accountUserId == playlist.creator.userId.toString()) }
        }
    }
    if (successfulCategories == 0 && localMatches.isEmpty() && failed.isNotEmpty()) {
        error("搜索失败，请检查网络后重试")
    }
    return ThorSearchResponse((localMatches + online).distinctBy { it.key }, failed)
}

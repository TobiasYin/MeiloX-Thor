package com.ljyh.mei.thor.data

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.SongPlaybackPrivilege
import com.ljyh.mei.data.model.mergedSongPlaybackPrivilege
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.model.withPlaybackPrivilege
import com.ljyh.mei.data.network.NETEASE_EXPECTED_SESSION_HEADER
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.thor.model.*
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Read-only signed catalog operations. The caller owns the accepted account/generation ticket. */
@Singleton
class ThorCatalogRepository internal constructor(
    private val fetch: suspend (ThorCatalogRequest) -> JsonObject,
) {
    @Inject constructor(
        @Named("MeloXEapi") eapi: MeloXDirectService,
        @Named("MeloXWeapi") weapi: MeloXDirectService,
    ) : this({ request ->
        (if (request.crypto == "eapi") eapi else weapi).post(
            request.url, request.body, cryptoMode = request.crypto,
            headers = request.expectedSession?.let { mapOf(NETEASE_EXPECTED_SESSION_HEADER to it) }.orEmpty(),
        )
    })

    suspend fun page(collection: ThorCollection, filters: ThorCatalogFilters, offset: Int = 0,
        cursor: Long = 0, expectedSession: String? = null): ThorCatalogPage = when (collection) {
        ThorCollection.RecommendedPlaylists -> parseThorRecommendedPlaylists(request(
            "/api/personalized/playlist", mapOf("limit" to PAGE_SIZE, "total" to true, "n" to 1_000),
            "eapi", expectedSession))
        ThorCollection.Discover -> parseThorCatalogPlaylists(request(
            "/api/playlist/highquality/list", mapOf("cat" to filters.playlistCategory, "limit" to PAGE_SIZE,
                "lasttime" to cursor, "total" to true), "weapi", expectedSession), cursor)
        ThorCollection.Toplists -> parseThorCatalogToplists(request("/api/toplist", emptyMap(), "eapi", expectedSession))
        ThorCollection.Artists -> {
            val hot = filters.artistArea == -1 && filters.artistType == -1
            val body = linkedMapOf<String, Any>("offset" to offset, "limit" to PAGE_SIZE, "total" to true)
            if (!hot) body.putAll(mapOf("area" to filters.artistArea, "type" to filters.artistType, "initial" to -1))
            parseThorCatalogArtists(request(if (hot) "/api/artist/top" else "/api/v1/artist/list",
                body, "weapi", expectedSession), offset)
        }
        ThorCollection.DailySongs -> {
            check(expectedSession != null) { "登录后查看每日推荐" }
            val response = request("/api/v3/discovery/recommend/songs", emptyMap(), "weapi", expectedSession)
            val data = response.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: error("每日推荐读取失败")
            parseThorCatalogSongs(data, "dailySongs", null, false)
        }
        else -> error("不支持的目录")
    }

    suspend fun artistProfile(item: ThorItem, expectedSession: String?): ThorArtistProfile = coroutineScope {
        val id = (item.key as? ThorItemKey.Artist)?.id ?: error("歌手已不存在")
        require(id > 0)
        val head = async { request("/api/artist/head/info/get", mapOf("id" to id), "eapi", expectedSession) }
        val hot = async { request("/api/v1/artist/" + id, emptyMap(), "weapi", expectedSession) }
        val detail = head.await().get("data")?.catalogObject()
            ?.get("artist")?.catalogObject() ?: error("歌手资料读取失败")
        val profile = thorCatalogArtistItem(detail) ?: error("歌手资料读取失败")
        check(profile.key == item.key) { "歌手资料已变化" }
        ThorArtistProfile(profile, parseThorCatalogSongs(hot.await(), "hotSongs", null, false).items)
    }

    suspend fun artistSongs(id: Long, offset: Int, expectedSession: String?): ThorCatalogPage {
        require(id > 0 && offset >= 0)
        val response = request("/api/v1/artist/songs", mapOf("id" to id, "offset" to offset,
            "limit" to PAGE_SIZE, "order" to "hot", "private_cloud" to "true", "work_type" to 1), "eapi", expectedSession)
        return parseThorCatalogSongs(response, "songs", offset, response.catalogBoolean("more"))
    }

    suspend fun artistAlbums(id: Long, offset: Int, expectedSession: String?): ThorCatalogPage {
        require(id > 0 && offset >= 0)
        val response = request("/api/artist/albums/" + id, mapOf("offset" to offset, "limit" to PAGE_SIZE,
            "total" to true), "weapi", expectedSession)
        val raw = response.catalogArray("hotAlbums")
        val items = raw.mapNotNull { element -> element.catalogObject()?.let { album ->
            val albumId = album.catalogLong("id")?.takeIf { it > 0 } ?: return@let null
            val title = album.catalogString("name") ?: return@let null
            ThorItem(ThorItemKey.Album(albumId), title,
                album.catalogArray("artists").mapNotNull { it.catalogObject()?.catalogString("name") }.joinToString(" / "),
                album.catalogString("picUrl") ?: album.catalogString("blurPicUrl"),
                album.catalogString("description").orEmpty(), album.catalogLong("size")?.toInt())
        } }
        return ThorCatalogPage(items, nextOffset = thorCatalogNextOffset(offset, raw.size, response.catalogBoolean("more")))
    }

    private suspend fun request(path: String, body: Map<String, Any>, crypto: String, expectedSession: String?): JsonObject {
        val response = fetch(ThorCatalogRequest(path, body, crypto, expectedSession))
        check(response.catalogLong("code") == 200L) { "目录读取失败，请重试" }
        return response
    }

    private companion object { const val PAGE_SIZE = 40 }
}

/** Maintained API module endpoint/protocol pairs; no cookie identity or OS override. */
internal class ThorCatalogRequest(val path: String, val body: Map<String, Any>, val crypto: String, val expectedSession: String?) {
    val url get() = if (crypto == "eapi") "https://interfacepc.music.163.com" + path
        else "https://music.163.com" + path.replaceFirst("/api/", "/weapi/")
    override fun toString() = "ThorCatalogRequest"
}

internal fun thorCatalogNextOffset(offset: Int, rawCount: Int, more: Boolean): Int? {
    require(offset >= 0 && rawCount >= 0)
    return if (more && rawCount > 0) Math.addExact(offset, rawCount) else null
}

internal fun thorCatalogNextCursor(previous: Long, next: Long, rawCount: Int, more: Boolean): Long? {
    require(previous >= 0 && rawCount >= 0)
    if (!more || rawCount == 0) return null
    check(next > 0 && (previous == 0L || next < previous)) { "分页游标未推进，请刷新重试" }
    return next
}

internal fun parseThorCatalogPlaylists(response: JsonObject, previousCursor: Long): ThorCatalogPage {
    val raw = response.catalogArray("playlists")
    val items = raw.mapNotNull { element -> element.catalogObject()?.let { playlist ->
        val id = playlist.catalogLong("id")?.takeIf { it > 0 } ?: return@let null
        val title = playlist.catalogString("name") ?: return@let null
        ThorItem(ThorItemKey.Playlist(id.toString()), title,
            playlist.get("creator")?.catalogObject()?.catalogString("nickname").orEmpty(),
            playlist.catalogString("coverImgUrl"), playlist.catalogString("description").orEmpty(),
            playlist.catalogLong("trackCount")?.toInt())
    } }
    return ThorCatalogPage(items, nextCursor = thorCatalogNextCursor(previousCursor,
        response.catalogLong("lasttime") ?: 0L, raw.size, response.catalogBoolean("more")))
}

/** Recommendation batches can refresh, but the endpoint does not define a continuation token. */
internal fun parseThorRecommendedPlaylists(response: JsonObject): ThorCatalogPage {
    val raw = response.get("result")?.takeIf { it.isJsonArray }?.asJsonArray
        ?: error("推荐歌单读取失败，请重试")
    val items = raw.mapNotNull { element -> element.catalogObject()?.let { playlist ->
        val id = playlist.catalogLong("id")?.takeIf { it > 0 } ?: return@let null
        val title = playlist.catalogString("name") ?: return@let null
        ThorItem(ThorItemKey.Playlist(id.toString()), title,
            playlist.get("creator")?.catalogObject()?.catalogString("nickname")
                ?: playlist.catalogString("copywriter").orEmpty(),
            playlist.catalogString("picUrl") ?: playlist.catalogString("coverImgUrl"),
            playlist.catalogString("copywriter") ?: playlist.catalogString("description").orEmpty(),
            playlist.catalogLong("trackCount")?.toInt())
    } }
    return ThorCatalogPage(items)
}

internal fun parseThorCatalogToplists(response: JsonObject): ThorCatalogPage = ThorCatalogPage(
    response.catalogArray("list").mapNotNull { element -> element.catalogObject()?.let { list ->
        val id = list.catalogLong("id")?.takeIf { it > 0 } ?: return@let null
        val title = list.catalogString("name") ?: return@let null
        ThorItem(ThorItemKey.Playlist(id.toString()), title, list.catalogString("updateFrequency").orEmpty(),
            list.catalogString("coverImgUrl"), list.catalogString("description").orEmpty(),
            list.catalogLong("trackCount")?.toInt())
    } })

internal fun parseThorCatalogArtists(response: JsonObject, offset: Int): ThorCatalogPage {
    val raw = response.catalogArray("artists")
    return ThorCatalogPage(raw.mapNotNull { it.catalogObject()?.let(::thorCatalogArtistItem) },
        nextOffset = thorCatalogNextOffset(offset, raw.size, response.catalogBoolean("more")))
}

internal fun thorCatalogArtistItem(artist: JsonObject): ThorItem? {
    val id = artist.catalogLong("id")?.takeIf { it > 0 } ?: return null
    val title = artist.catalogString("name") ?: return null
    return ThorItem(ThorItemKey.Artist(id), title,
        artist.catalogArray("alias").mapNotNull { it.takeIf(JsonElement::isJsonPrimitive)?.asString }.joinToString(" / "),
        artist.catalogString("picUrl") ?: artist.catalogString("img1v1Url") ?: artist.catalogString("cover") ?: artist.catalogString("avatar"),
        artist.catalogString("briefDesc").orEmpty(), artist.catalogLong("musicSize")?.toInt())
}

internal fun parseThorCatalogSongs(response: JsonObject, field: String, offset: Int?, more: Boolean): ThorCatalogPage {
    val gson = Gson()
    val raw = response.catalogArray(field)
    val privileges = response.catalogArray("privileges").mapNotNull { element ->
        element.catalogObject()?.let { gson.fromJson(it, SongPlaybackPrivilege::class.java) }
    }.associateBy { it.id }
    val items = raw.mapNotNull { element ->
        val value = element.catalogObject() ?: return@mapNotNull null
        val id = value.catalogLong("id")?.takeIf { it > 0 } ?: return@mapNotNull null
        val track = gson.fromJson(value, PlaylistDetail.Playlist.Track::class.java)
        track.toMediaMetadata().withPlaybackPrivilege(mergedSongPlaybackPrivilege(id,
            value.get("privilege")?.catalogObject()?.let { gson.fromJson(it, SongPlaybackPrivilege::class.java) },
            privileges[id], value.catalogLong("st")?.toInt())).toThorItem()
    }
    return ThorCatalogPage(items, nextOffset = offset?.let { thorCatalogNextOffset(it, raw.size, more) })
}

private fun JsonElement.catalogObject(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.catalogArray(name: String): List<JsonElement> = get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
private fun JsonObject.catalogString(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)
private fun JsonObject.catalogLong(name: String): Long? = catalogString(name)?.toLongOrNull()
private fun JsonObject.catalogBoolean(name: String): Boolean = catalogString(name)?.toBooleanStrictOrNull() ?: false

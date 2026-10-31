package com.ljyh.mei.thor.model

/** Home categories are independent from the four primary destinations and list focus. */
enum class ThorHomeCategory(val title: String, val collection: ThorCollection) {
    Recommended("推荐", ThorCollection.Home),
    Playlists("歌单", ThorCollection.RecommendedPlaylists),
    Charts("榜单", ThorCollection.Toplists),
    Artists("歌手", ThorCollection.Artists),
}

data class ThorCatalogFilters(
    val playlistCategory: String = "全部",
    val artistArea: Int = -1,
    val artistType: Int = -1,
)

data class ThorCatalogPage(
    val items: List<ThorItem>,
    val nextOffset: Int? = null,
    val nextCursor: Long? = null,
) {
    val hasMore get() = nextOffset != null || nextCursor != null
}

data class ThorArtistProfile(val item: ThorItem, val hotSongs: List<ThorItem>)

enum class ThorArtistSection(val title: String) { HotSongs("热门"), Songs("全部歌曲"), Albums("专辑") }

data class ThorArtistBrowseState(
    val item: ThorItem,
    val section: ThorArtistSection = ThorArtistSection.HotSongs,
    val pages: Map<ThorArtistSection, List<ThorItem>> = emptyMap(),
    val nextOffsets: Map<ThorArtistSection, Int> = emptyMap(),
    val loading: Set<ThorArtistSection> = emptySet(),
    val errors: Map<ThorArtistSection, String> = emptyMap(),
) {
    val items get() = pages[section].orEmpty()
    val hasMore get() = nextOffsets[section] != null
    val isLoading get() = section in loading
    val error get() = errors[section]
    val sceneKey get() = "artist:${(item.key as ThorItemKey.Artist).id}:${section.name}"
}

package com.ljyh.mei.thor.model

enum class ThorSearchCategory(val title: String) {
    All("综合"), Songs("歌曲"), Artists("歌手"), Albums("专辑"), Playlists("歌单"),
}

data class ThorSearchResponse(
    val items: List<ThorItem>,
    val failedCategories: Set<ThorSearchCategory> = emptySet(),
)

fun ThorItem.searchCategory(): ThorSearchCategory? = when (key) {
    is ThorItemKey.Song, is ThorItemKey.QueueEntry -> ThorSearchCategory.Songs
    is ThorItemKey.Artist -> ThorSearchCategory.Artists
    is ThorItemKey.Album -> ThorSearchCategory.Albums
    is ThorItemKey.Playlist -> ThorSearchCategory.Playlists
    is ThorItemKey.Destination -> null
}

/** Preserve relevance within each response, while making every returned resource discoverable. */
internal fun thorMixedSearchResults(items: List<ThorItem>, query: String): List<ThorItem> {
    val unique = items.distinctBy { it.key }
    val term = query.trim()
    val artists = unique.filter { it.key is ThorItemKey.Artist }
    // Search aliases can repeat a popular singer on many unrelated artist records. Promote
    // only the best exact identity; never let those aliases hide all songs from the first page.
    val bestArtist = if (term.isEmpty()) null else artists.firstOrNull { it.title.equals(term, ignoreCase = true) }
        ?: artists.firstOrNull { item -> item.subtitle.split(" / ").any { it.trim().equals(term, ignoreCase = true) } }
    val exactArtists = listOfNotNull(bestArtist)
    val promoted = exactArtists.mapTo(hashSetOf()) { it.key }
    val order = if (exactArtists.isEmpty()) listOf(ThorSearchCategory.Songs, ThorSearchCategory.Artists,
        ThorSearchCategory.Albums, ThorSearchCategory.Playlists)
        else listOf(ThorSearchCategory.Songs, ThorSearchCategory.Albums, ThorSearchCategory.Playlists, ThorSearchCategory.Artists)
    val groups = order
        .map { category -> unique.filter { it.key !in promoted && it.searchCategory() == category } }
    return buildList {
        addAll(exactArtists)
        repeat(groups.maxOfOrNull { it.size } ?: 0) { index ->
            groups.forEach { group -> group.getOrNull(index)?.let(::add) }
        }
        addAll(unique.filter { it.searchCategory() == null })
    }
}

internal fun thorVisibleSearchResults(items: List<ThorItem>, category: ThorSearchCategory): List<ThorItem> =
    if (category == ThorSearchCategory.All) items else items.filter { it.searchCategory() == category }

/** The default scene remains compatible with existing search return/focus memory. */
internal fun thorSearchResultsScene(entry: Long, category: ThorSearchCategory): String =
    "search:results:$entry" + if (category == ThorSearchCategory.All) "" else ":${category.name}"

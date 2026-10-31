package com.ljyh.mei.thor.state

import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.thor.model.ThorCollection
import com.ljyh.mei.thor.model.ThorLibraryState
import com.ljyh.mei.thor.model.ThorArtistBrowseState
import com.ljyh.mei.thor.model.ThorArtistSection

internal data class ThorPagePlayback(val tracks: List<MediaMetadata>, val title: String)

/** The same visible pages expose bulk buttons and accept L3/R3; containers are never flattened. */
internal fun thorPagePlayback(navigation: ThorNavigation, library: ThorLibraryState,
    artist: ThorArtistBrowseState? = null): ThorPagePlayback? {
    if (navigation.overlay != null) return null
    val target = when (val page = navigation.page) {
        is ThorPage.Detail -> ThorPagePlayback(page.item.tracks.toList(), page.item.title)
        is ThorPage.Collection -> if (page.collection in setOf(ThorCollection.LocalSongs, ThorCollection.LikedSongs, ThorCollection.Downloads, ThorCollection.History, ThorCollection.DailySongs)) {
            ThorPagePlayback(library[page.collection].flatMap { it.tracks }, page.collection.title)
        } else null
        is ThorPage.Artist -> artist?.takeIf { it.item.key == page.item.key && it.section != ThorArtistSection.Albums }
            ?.let { ThorPagePlayback(it.items.flatMap { row -> row.tracks }, "${it.item.title} · ${it.section.title}") }
        else -> null
    }
    return target?.takeIf { it.tracks.isNotEmpty() }
}

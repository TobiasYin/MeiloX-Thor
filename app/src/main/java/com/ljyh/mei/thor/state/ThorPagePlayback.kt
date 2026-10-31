package com.ljyh.mei.thor.state

import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.thor.model.ThorCollection
import com.ljyh.mei.thor.model.ThorLibraryState

internal data class ThorPagePlayback(val tracks: List<MediaMetadata>, val title: String)

/** The same visible pages expose bulk buttons and accept L3/R3; containers are never flattened. */
internal fun thorPagePlayback(navigation: ThorNavigation, library: ThorLibraryState): ThorPagePlayback? {
    if (navigation.overlay != null) return null
    val target = when (val page = navigation.page) {
        is ThorPage.Detail -> ThorPagePlayback(page.item.tracks.toList(), page.item.title)
        is ThorPage.Collection -> if (page.collection in setOf(ThorCollection.LocalSongs, ThorCollection.LikedSongs, ThorCollection.Downloads, ThorCollection.History)) {
            ThorPagePlayback(library[page.collection].flatMap { it.tracks }, page.collection.title)
        } else null
        else -> null
    }
    return target?.takeIf { it.tracks.isNotEmpty() }
}

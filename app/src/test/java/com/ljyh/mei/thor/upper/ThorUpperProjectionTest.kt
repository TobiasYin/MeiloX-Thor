package com.ljyh.mei.thor.upper

import com.ljyh.mei.thor.model.ThorCollection
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.state.ThorPage
import com.ljyh.mei.thor.state.ThorTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThorUpperProjectionTest {
    private val current = ThorItem(ThorItemKey.Song(1), "Current")
    private val preview = ThorItem(ThorItemKey.Album(2), "Preview")

    @Test fun settingsWithoutABrowsingPreviewBorrowTheCurrentCover() {
        for (page in listOf(ThorPage.Controls, ThorPage.Appearance)) {
            assertSame(current, thorUpperProjection(page, false, null, current))
        }
    }

    @Test fun settingsWithoutMusicShowOnlyTheirOwnNonMusicalDestination() {
        val cases = listOf(
            Triple(ThorPage.Controls, "controls", "手柄与吹气"),
            Triple(ThorPage.Appearance, "appearance", "外观"),
        )
        for ((page, id, title) in cases) {
            val projected = requireNotNull(thorUpperProjection(page, false, null, null))
            assertEquals(ThorItemKey.Destination(id), projected.key)
            assertEquals(title, projected.title)
            assertTrue(projected.tracks.isEmpty())
            assertNull(projected.trackCount)
            assertNull(projected.artworkUri)
        }
    }

    @Test fun anExistingPreviewKeepsPriorityOnSettingsPages() {
        for (page in listOf(ThorPage.Controls, ThorPage.Appearance)) {
            assertSame(preview, thorUpperProjection(page, false, preview, current))
        }
    }

    @Test fun ordinaryBrowsingDoesNotFallBackToThePlayingSong() {
        val pages = listOf(ThorPage.Root(ThorTab.Home), ThorPage.Root(ThorTab.My),
            ThorPage.Collection(ThorCollection.LikedSongs), ThorPage.Detail(preview), ThorPage.Search)
        for (page in pages) {
            assertNull(thorUpperProjection(page, false, null, current))
            assertNull(thorUpperProjection(page, false, null, null))
            assertSame(preview, thorUpperProjection(page, false, preview, current))
        }
    }

    @Test fun playbackOverlaysAlwaysShowTheCurrentSongRatherThanTheUnderlyingPreview() {
        for (page in listOf(ThorPage.Controls, ThorPage.Appearance, ThorPage.Search)) {
            assertSame(current, thorUpperProjection(page, true, preview, current))
            assertNull(thorUpperProjection(page, true, preview, null))
        }
    }
}

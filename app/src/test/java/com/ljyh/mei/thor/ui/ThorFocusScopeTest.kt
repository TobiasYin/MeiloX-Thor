package com.ljyh.mei.thor.ui

import androidx.compose.ui.geometry.Rect
import com.ljyh.mei.thor.model.ThorDirection
import org.junit.Assert.*
import org.junit.Test

class ThorFocusScopeTest {
    private val keys = listOf("first", "second", "last")
    private val rows = keys.mapIndexed { index, key -> key to ThorFocusCandidate(Rect(0f, index * 60f, 100f, index * 60f + 48f)) }.toMap()

    @Test fun aVerticalListNeverWrapsOrExitsThroughHorizontalOrBoundaryMoves() {
        val scope = ThorFocusScope("list", keys)
        assertNull(thorScopeTarget(scope, "first", ThorDirection.Up, rows))
        assertNull(thorScopeTarget(scope, "last", ThorDirection.Down, rows))
        for (key in keys) for (direction in listOf(ThorDirection.Left, ThorDirection.Right)) {
            assertNull(thorScopeTarget(scope, key, direction, rows))
        }
        assertEquals("second", thorScopeTarget(scope, "first", ThorDirection.Down, rows))
        assertEquals("second", thorScopeTarget(scope, "last", ThorDirection.Up, rows))
    }

    @Test fun aHorizontalOperationRegionIsClosedVerticallyAndSkipsDisabledControls() {
        val scope = ThorFocusScope("controls", keys, ThorFocusLayout.Horizontal)
        val candidates = rows + ("second" to rows.getValue("second").copy(enabled = false))
        assertEquals("last", thorScopeTarget(scope, "first", ThorDirection.Right, candidates))
        assertEquals("first", thorScopeTarget(scope, "last", ThorDirection.Left, candidates))
        assertNull(thorScopeTarget(scope, "last", ThorDirection.Right, candidates))
        assertNull(thorScopeTarget(scope, "first", ThorDirection.Up, candidates))
        assertNull(thorScopeTarget(scope, "last", ThorDirection.Down, candidates))
    }

    @Test fun chromeAndForeignSceneKeysCannotEnterTheCandidateSet() {
        val scope = ThorFocusScope("list", keys)
        val candidates = rows + mapOf(
            "header" to ThorFocusCandidate(Rect(0f, -50f, 100f, -2f)),
            "footer" to ThorFocusCandidate(Rect(0f, 180f, 100f, 228f)),
            "mini" to ThorFocusCandidate(Rect(120f, 60f, 220f, 108f)),
        )
        assertNull(thorScopeTarget(scope, "first", ThorDirection.Up, candidates))
        assertNull(thorScopeTarget(scope, "last", ThorDirection.Down, candidates))
        assertEquals("first", thorScopeTarget(scope, "header", ThorDirection.Down, candidates))
    }

    @Test fun spatialSearchUsesOnlyTheExplicitRegionAndPrefersAlignedControls() {
        val candidates = mapOf(
            "play" to ThorFocusCandidate(Rect(100f, 100f, 180f, 180f)),
            "previous" to ThorFocusCandidate(Rect(10f, 110f, 70f, 170f)),
            "next" to ThorFocusCandidate(Rect(210f, 110f, 270f, 170f)),
            "progress" to ThorFocusCandidate(Rect(10f, 20f, 270f, 68f)),
            "diagonal" to ThorFocusCandidate(Rect(290f, 10f, 338f, 58f)),
            "header" to ThorFocusCandidate(Rect(100f, 70f, 180f, 95f)),
            "footer" to ThorFocusCandidate(Rect(100f, 190f, 180f, 238f)),
        )
        val scope = ThorFocusScope("player", listOf("play", "previous", "next", "progress", "diagonal"), ThorFocusLayout.Spatial)
        assertEquals("previous", thorScopeTarget(scope, "play", ThorDirection.Left, candidates))
        assertEquals("next", thorScopeTarget(scope, "play", ThorDirection.Right, candidates))
        assertEquals("progress", thorScopeTarget(scope, "play", ThorDirection.Up, candidates))
        assertNull(thorScopeTarget(scope, "play", ThorDirection.Down, candidates))
    }

    @Test fun spatialSearchIgnoresUnlaidOutAndDisabledEntries() {
        val candidates = rows + mapOf(
            "hidden" to ThorFocusCandidate(Rect.Zero),
            "disabled" to ThorFocusCandidate(Rect(0f, -60f, 100f, -12f), enabled = false),
        )
        val scope = ThorFocusScope("controls", listOf("first", "hidden", "disabled"), ThorFocusLayout.Spatial)
        assertNull(thorScopeTarget(scope, "first", ThorDirection.Up, candidates))
        assertNull(thorScopeTarget(scope, "first", ThorDirection.Down, candidates))
    }

    @Test fun freshRestoreDeletedKeysAndFormerChromeAllReturnToTheFirstRow() {
        assertEquals(0, thorRestoredListIndex(keys, null))
        assertEquals(1, thorRestoredListIndex(keys, "second"))
        assertEquals(0, thorRestoredListIndex(keys, "removed"))
        assertEquals(0, thorRestoredListIndex(keys, "header:search"))
        assertEquals(2, thorRestoredListIndex(keys, "first", currentIndex = 2))
        assertEquals(0, thorRestoredListIndex(keys, null, currentIndex = 99))
    }

    @Test fun emptyAndRepeatedKeysDoNotCreateAnExitOrACycle() {
        assertNull(thorScopeTarget(ThorFocusScope("empty", emptyList()), null, ThorDirection.Down, rows))
        val scope = ThorFocusScope("duplicates", listOf("first", "first", "last"))
        assertEquals("last", thorScopeTarget(scope, "first", ThorDirection.Down, rows))
        assertNull(thorScopeTarget(scope, "last", ThorDirection.Down, rows))
    }
}

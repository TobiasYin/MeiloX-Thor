package com.ljyh.mei.ui.component.player.component

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadableLyricsProgressTest {
    @Test fun glyphSpansNeverSplitSupplementaryChineseOrEmojiSurrogates() {
        assertEquals(listOf(0, 1, 3, 5, 6), readableGlyphOffsets("A𠮷🎵中"))
        assertEquals(listOf(0), readableGlyphOffsets(""))
    }

    @Test fun timedRevealClampsAndHandlesZeroDurationSyllables() {
        assertEquals(0.0, readableKaraokeProgress(90, 100, 200, 4), 0.0)
        assertEquals(2.0, readableKaraokeProgress(150, 100, 200, 4), 0.0)
        assertEquals(4.0, readableKaraokeProgress(300, 100, 200, 4), 0.0)
        assertEquals(4.0, readableKaraokeProgress(100, 100, 100, 4), 0.0)
        assertEquals(0.0, readableKaraokeProgress(100, 100, 200, 0), 0.0)
    }

    @Test fun largeTimestampDifferencesDoNotOverflowIntArithmetic() {
        assertEquals(2.0, readableKaraokeProgress(Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, 2), 0.0)
    }
}

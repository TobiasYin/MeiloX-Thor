package com.ljyh.mei.thor.upper

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import org.junit.Assert.*
import org.junit.Test

class ThorLyricProgressTest {
    @Test fun realSyllableTimesKeepTheirGapsAndDoNotSpreadWordsEvenlyAcrossTheLine() {
        val timeline = thorLyricTimeline(karaoke(listOf(KaraokeSyllable("你", 100, 200),
            KaraokeSyllable("好", 700, 1_000))), true)
        assertEquals(ThorLyricTiming.WordTimed, timeline.timing)
        assertEquals("你好", timeline.text)
        assertEquals(.5f, thorLyricSpanProgress(150, timeline.spans[0]), .0001f)
        assertEquals(1f, thorLyricSpanProgress(500, timeline.spans[0]), 0f)
        assertEquals(0f, thorLyricSpanProgress(500, timeline.spans[1]), 0f)
        assertEquals(.5f, thorLyricSpanProgress(850, timeline.spans[1]), .0001f)
    }

    @Test fun lrcHasOneContinuousLineProgressAndNeverInventsWordTimestamps() {
        val timeline = thorLyricTimeline(SyncedLine("A long line with several words", null, 500, 2_500), false)
        assertEquals(ThorLyricTiming.LineProgress, timeline.timing)
        assertEquals(1, timeline.spans.size)
        assertEquals(0, timeline.spans.single().startOffset)
        assertEquals(timeline.text.length, timeline.spans.single().endOffset)
        assertEquals(.5f, thorLyricSpanProgress(1_500, timeline.spans.single()), .0001f)
    }

    @Test fun explicitLineVariantDoesNotExposeUnderlyingSyllablesAsRealWordTiming() {
        val line = karaoke(listOf(KaraokeSyllable("Word ", 100, 300), KaraokeSyllable("two", 900, 1_000)))
        assertEquals(ThorLyricTiming.LineProgress, thorLyricTimeline(line, false).timing)
        assertEquals(1, thorLyricTimeline(line, false).spans.size)
    }

    @Test fun whollyMissingWordDurationsFallBackToTheKnownLineWindow() {
        val line = karaoke(listOf(KaraokeSyllable("One", 0, 0), KaraokeSyllable(" two", 0, 0)))
        val timeline = thorLyricTimeline(line, true)
        assertEquals(ThorLyricTiming.LineProgress, timeline.timing)
        assertEquals(.5f, thorLyricSpanProgress(550, timeline.spans.single()), .0001f)
    }

    @Test fun utf16RangesKeepSupplementaryCjkEmojiAndCombiningTextIntact() {
        val timeline = thorLyricTimeline(karaoke(listOf(KaraokeSyllable("𠮷🎵", 100, 300),
            KaraokeSyllable("e\u0301中", 400, 800))), true)
        assertEquals("𠮷🎵e\u0301中", timeline.text)
        assertEquals(listOf(0 to 4, 4 to 7), timeline.spans.map { it.startOffset to it.endOffset })
        timeline.spans.forEach { span ->
            assertFalse(span.startOffset > 0 && timeline.text[span.startOffset].isLowSurrogate())
            assertFalse(span.endOffset < timeline.text.length && timeline.text[span.endOffset].isLowSurrogate())
        }
    }

    @Test fun malformedProviderBoundaryInsideASurrogatePairIsMerged() {
        val timeline = thorLyricTimeline(karaoke(listOf(KaraokeSyllable("\uD83C", 100, 200),
            KaraokeSyllable("\uDFB5", 200, 400))), true)
        assertEquals("🎵", timeline.text)
        assertEquals(listOf(ThorLyricSpan(0, 2, 100, 400)), timeline.spans)
    }

    @Test fun samplingPauseAndBackwardSeekIsDeterministicAndNeverLoops() {
        val span = ThorLyricSpan(0, 4, 100, 1_100)
        assertEquals(.75f, thorLyricSpanProgress(850, span), 0f)
        repeat(20) { assertEquals(.75f, thorLyricSpanProgress(850, span), 0f) }
        assertEquals(.25f, thorLyricSpanProgress(350, span), 0f)
        assertEquals(1f, thorLyricSpanProgress(Long.MAX_VALUE, span), 0f)
        assertEquals(0f, thorLyricSpanProgress(Long.MIN_VALUE, span), 0f)
    }

    @Test fun zeroAndReversedDurationsCompleteOnceAtTheLatestBoundary() {
        assertEquals(0f, thorLyricSpanProgress(99, ThorLyricSpan(0, 1, 100, 100)), 0f)
        assertEquals(1f, thorLyricSpanProgress(100, ThorLyricSpan(0, 1, 100, 100)), 0f)
        assertEquals(0f, thorLyricSpanProgress(95, ThorLyricSpan(0, 1, 100, 90)), 0f)
        assertEquals(1f, thorLyricSpanProgress(100, ThorLyricSpan(0, 1, 100, 90)), 0f)
    }

    @Test fun extremeIntTimingsDoNotOverflowAndEmptyContentDoesNotCreatePaintRanges() {
        val span = ThorLyricSpan(0, 1, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
        assertEquals(.5f, thorLyricSpanProgress(0, span), .00001f)
        val empty = thorLyricTimeline(SyncedLine("", null, 0, 1_000), false)
        assertTrue(empty.spans.isEmpty())
    }

    private fun karaoke(syllables: List<KaraokeSyllable>) = KaraokeLine.MainKaraokeLine(
        syllables, null, KaraokeAlignment.Start, 100, 1_000)
}

package com.ljyh.mei.utils.lyric

import com.google.gson.Gson
import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.ui.model.LyricSource
import com.ljyh.mei.ui.model.LyricSourceData
import com.ljyh.mei.ui.model.LyricVariant
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Original two-syllable fixture verifies the automatic path, not merely YRCParser in isolation. */
class AutomaticYrcSelectionTest {
    @Test
    fun automaticYrcKeepsWordTimingWhenBothTranslationFieldsAreAbsentOrNull() {
        for (translationFields in listOf("", ",\"ytlrc\":null,\"tlyric\":null",
            ",\"ytlrc\":{\"lyric\":null},\"tlyric\":{}")) {
            val result = mergeLyrics(listOf(source(translationFields)))
            assertTrue(result.isVerbatim)
            assertEquals(LyricSource.NetEaseCloudMusic, result.source)
            val line = result.lyricLine.lines.single() as KaraokeLine.MainKaraokeLine
            assertEquals(listOf("微", "光"), line.syllables.map { it.content })
            assertEquals(listOf(1000, 1350), line.syllables.map { it.start })
            assertEquals(listOf(1350, 2000), line.syllables.map { it.end })
            assertEquals(1000, line.start)
            assertEquals(2000, line.end)
            assertNull(line.translation)
        }
    }

    @Test
    fun automaticYrcPreservesEverySyllableThroughThePlaybackTimingOffset() {
        val data = mergeLyrics(listOf(source()))
        val adjusted = data.withTimingOffset(250)
        assertTrue(adjusted.isVerbatim)
        assertEquals(LyricSource.NetEaseCloudMusic, adjusted.source)
        val line = adjusted.lyricLine.lines.single() as KaraokeLine.MainKaraokeLine
        assertEquals(listOf("微", "光"), line.syllables.map { it.content })
        assertEquals(listOf(1250, 1600), line.syllables.map { it.start })
        assertEquals(listOf(1600, 2250), line.syllables.map { it.end })
        assertEquals(1000, data.lyricLine.lines.single().start)
    }

    @Test
    fun explicitNeteaseLrcVariantStillReplacesAutomaticWordsWithoutChangingSourceData() {
        val sources = listOf(source())
        val automatic = mergeLyrics(sources)
        assertTrue(automatic.isVerbatim)
        val explicit = buildLyricVariants(sources).single { lyricVariantOf(it) == LyricVariant.NeteaseLrc }
        val selected = selectPreferredLyricUpdate(automatic, explicit, LyricSource.NetEaseCloudMusic,
            sourcesFinished = true, alignmentFinished = true, preferredVariant = LyricVariant.NeteaseLrc)
        assertSame(explicit, selected)
        assertFalse(selected.isVerbatim)
        assertTrue(selected.matchesLyricPreference(LyricSource.NetEaseCloudMusic, LyricVariant.NeteaseLrc))
        assertTrue(automatic.isVerbatim)
        assertEquals(listOf(1000, 1350),
            (automatic.lyricLine.lines.single() as KaraokeLine).syllables.map { it.start })
    }

    @Test
    fun actualLineOnlyResponseDoesNotInventVerbatimTiming() {
        val source = LyricSourceData.NetEase(Gson().fromJson(
            """{"code":200,"lrc":{"lyric":"[00:01.00]微光"}}""", Lyric::class.java))
        val result = mergeLyrics(listOf(source))
        assertEquals(LyricSource.NetEaseCloudMusic, result.source)
        assertFalse(result.isVerbatim)
        assertEquals(1, result.lyricLine.lines.size)
    }

    private fun source(translationFields: String = ""): LyricSourceData.NetEase = LyricSourceData.NetEase(
        Gson().fromJson("""{"code":200,
            "yrc":{"lyric":"[1000,1000](1000,350,0)微(1350,650,0)光"},
            "lrc":{"lyric":"[00:01.00]微光"}$translationFields
        }""", Lyric::class.java),
    )
}

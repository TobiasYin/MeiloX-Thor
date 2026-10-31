package com.ljyh.mei.thor.upper

import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine

/** LineProgress is an honest visual fallback; it does not invent syllable timestamps. */
internal enum class ThorLyricTiming { WordTimed, LineProgress }

internal data class ThorLyricSpan(
    val startOffset: Int,
    val endOffset: Int,
    val startMs: Long,
    val endMs: Long,
)

internal data class ThorLyricTimeline(
    val text: String,
    val timing: ThorLyricTiming,
    val spans: List<ThorLyricSpan>,
)

/** Offsets address the ORIGINAL shaped string in UTF-16; no per-frame text reconstruction. */
internal fun thorLyricTimeline(line: ISyncedLine, verbatim: Boolean): ThorLyricTimeline {
    val text = when (line) {
        is KaraokeLine -> line.syllables.joinToString("") { it.content }
        is SyncedLine -> line.content
        else -> ""
    }
    val timed = verbatim && line is KaraokeLine && line.syllables.any { it.content.isNotEmpty() && it.end > it.start }
    if (!timed) return ThorLyricTimeline(text, ThorLyricTiming.LineProgress,
        if (text.isEmpty()) emptyList() else listOf(ThorLyricSpan(0, text.length, line.start.toLong(), line.end.toLong())))
    var offset = 0
    val spans = mutableListOf<ThorLyricSpan>()
    for (syllable in line.syllables) {
        val end = offset + syllable.content.length
        if (end > offset) {
            val next = ThorLyricSpan(offset, end, syllable.start.toLong(), syllable.end.toLong())
            // Malformed provider segmentation must not split a supplementary Unicode scalar.
            // Normal syllables already end at scalar boundaries, including emoji/CJK ext-B.
            val splitSurrogate = offset in 1 until text.length &&
                text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate()
            if (splitSurrogate && spans.isNotEmpty()) {
                val previous = spans.removeAt(spans.lastIndex)
                spans += previous.copy(endOffset = end, startMs = minOf(previous.startMs, next.startMs),
                    endMs = maxOf(previous.endMs, next.endMs))
            } else spans += next
        }
        offset = end
    }
    return ThorLyricTimeline(text, ThorLyricTiming.WordTimed, spans)
}

/** Pauses and seeks are direct samples, not an extrapolated or looping animation clock. */
internal fun thorLyricSpanProgress(timeMs: Long, span: ThorLyricSpan): Float {
    if (span.endMs <= span.startMs) return if (timeMs >= maxOf(span.startMs, span.endMs)) 1f else 0f
    if (timeMs <= span.startMs) return 0f
    if (timeMs >= span.endMs) return 1f
    return ((timeMs - span.startMs).toDouble() / (span.endMs - span.startMs).toDouble()).toFloat().coerceIn(0f, 1f)
}

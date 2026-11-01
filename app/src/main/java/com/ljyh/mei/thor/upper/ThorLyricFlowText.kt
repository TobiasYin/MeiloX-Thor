package com.ljyh.mei.thor.upper

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import com.ljyh.mei.thor.theme.ThorLyricSheenTokens
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.ResolvedTextDirection
import java.util.concurrent.atomic.AtomicInteger

/** Optional native verification; production supplies no collector and serializes no lyric text. */
internal class ThorLyricFlowDiagnostics {
    val layouts = AtomicInteger()
    val draws = AtomicInteger()
    val frameTicks = AtomicInteger()
    @Volatile var timing: ThorLyricTiming? = null
    @Volatile var decorated: Boolean = false
    @Volatile var sampledPositionMs: Long = 0L
    @Volatile var haloDrawn: Boolean = false
    @Volatile var reflectionDrawn: Boolean = false
}
internal val LocalThorLyricFlowDiagnostics = staticCompositionLocalOf<ThorLyricFlowDiagnostics?> { null }

private data class LyricRun(val bounds: Rect, val rtl: Boolean)
private data class LyricEdge(val x: Float, val bounds: Rect, val rtl: Boolean)

private data class ShapedLyricSpan(val timing: ThorLyricSpan, val runs: List<LyricRun>, val length: Float)

/** Shapes once per style/width change. Text retains its real accessibility/layout semantics. */
@Composable
internal fun ThorLyricFlowText(
    timeline: ThorLyricTimeline,
    style: TextStyle,
    positionProvider: () -> Int,
    frameTick: State<Long>,
    textColor: Color,
    secondaryColor: Color,
    sheen: ThorLyricSheenTokens,
    decorate: Boolean,
    modifier: Modifier = Modifier,
) {
    val diagnostics = LocalThorLyricFlowDiagnostics.current
    val latestPosition by rememberUpdatedState(positionProvider)
    var measured by remember(timeline.text, style) { mutableStateOf<TextLayoutResult?>(null) }
    Text(timeline.text, modifier.drawWithCache {
        val layout = measured
        val shaped = if (layout == null) emptyList() else timeline.spans.map { span ->
            val start = span.startOffset.coerceIn(0, timeline.text.length)
            val end = span.endOffset.coerceIn(start, timeline.text.length)
            val runs = if (start == end) emptyList() else buildList {
                for (line in layout.getLineForOffset(start)..layout.getLineForOffset(end - 1)) {
                    val from = maxOf(start, layout.getLineStart(line))
                    val to = minOf(end, layout.getLineEnd(line, visibleEnd = false))
                    var cursor = from
                    while (cursor < to) {
                        val direction = layout.getBidiRunDirection(cursor)
                        fun scalarEnd(offset: Int): Int = if (offset + 1 < to && timeline.text[offset].isHighSurrogate() &&
                            timeline.text[offset + 1].isLowSurrogate()) offset + 2 else offset + 1
                        var runEnd = scalarEnd(cursor)
                        while (runEnd < to && layout.getBidiRunDirection(runEnd) == direction) runEnd = scalarEnd(runEnd)
                        val bounds = layout.getPathForRange(cursor, runEnd).getBounds()
                        if (bounds.width > .01f && bounds.height > .01f)
                            add(LyricRun(bounds, direction == ResolvedTextDirection.Rtl))
                        cursor = runEnd
                    }
                }
            }
            ShapedLyricSpan(span, runs, runs.sumOf { it.bounds.width.toDouble() }.toFloat())
        }
        val sungMask = Path()
        val softRadius = sheen.softRadius.toPx()
        val ridgeRadius = sheen.ridgeRadius.toPx()
        val haloSpread = sheen.haloSpread.toPx()
        val samples = List(17) { it / 16f }
        val satinStops = samples.map { fraction ->
            fraction to lerp(sheen.warmReflection, sheen.coolReflection, fraction)
                .copy(alpha = sheen.satinOpacity * thorLyricSheenEnvelope(fraction * 2f - 1f))
        }.toTypedArray()
        val ridgeStops = samples.map { fraction ->
            fraction to sheen.ridge.copy(alpha = sheen.ridgeOpacity * thorLyricSheenEnvelope(fraction * 2f - 1f))
        }.toTypedArray()
        val haloStops = samples.map { fraction ->
            fraction to sheen.halo.copy(alpha = sheen.haloOpacity * thorLyricSheenEnvelope(fraction * 2f - 1f))
        }.toTypedArray()
        fun reflection(edge: LyricEdge, radius: Float, stops: Array<Pair<Float, Color>>): Brush {
            val direction = if (edge.rtl) -1f else 1f
            // A small fixed slant gives the pearl ridge depth, without a second phase/clock.
            return Brush.linearGradient(*stops,
                start = Offset(edge.x - radius * direction, edge.bounds.center.y + radius * .10f),
                end = Offset(edge.x + radius * direction, edge.bounds.center.y - radius * .10f))
        }
        onDrawWithContent {
            // These state reads invalidate DRAW only. The shaped text and auxiliary row sizes
            // never change as a word advances. The sample is the actual media clock supplied by
            // the Activity, not a free-running shimmer or synthetic LRC word clock.
            frameTick.value
            val now = latestPosition().toLong()
            diagnostics?.apply {
                draws.incrementAndGet(); timing = timeline.timing
                decorated = decorate && sheen.decorationsAllowed; sampledPositionMs = now
                haloDrawn = false; reflectionDrawn = false
            }
            sungMask.reset()
            var edge: LyricEdge? = null
            for (span in shaped) {
                val progress = thorLyricSpanProgress(now, span.timing)
                var remaining = progress * span.length
                for (run in span.runs) {
                    val width = remaining.coerceIn(0f, run.bounds.width)
                    if (width > 0f) {
                        val area = if (run.rtl) Rect(run.bounds.right - width, run.bounds.top, run.bounds.right, run.bounds.bottom)
                            else Rect(run.bounds.left, run.bounds.top, run.bounds.left + width, run.bounds.bottom)
                        sungMask.addRect(area)
                    }
                    if (progress > 0f && progress < 1f && remaining in 0f..run.bounds.width) {
                        edge = LyricEdge(if (run.rtl) run.bounds.right - width else run.bounds.left + width, run.bounds, run.rtl)
                    }
                    remaining -= run.bounds.width
                }
            }
            val currentEdge = edge
            val decorated = decorate && sheen.decorationsAllowed && currentEdge != null
            if (layout != null && decorated && sheen.haloOpacity > 0f) {
                val activeEdge = checkNotNull(currentEdge)
                // Tiny glyph-contour passes sit behind fully opaque reading ink. Their shader
                // fades to zero at the shoulder; no blur or rectangular x-clip can leave a box.
                clipRect(0f, activeEdge.bounds.top - haloSpread * 2f, size.width, activeEdge.bounds.bottom + haloSpread * 2f) {
                    val brush = reflection(activeEdge, softRadius, haloStops)
                    drawText(layout, brush = brush, topLeft = Offset(0f, -haloSpread))
                    drawText(layout, brush = brush, topLeft = Offset(0f, haloSpread))
                }
                diagnostics?.haloDrawn = true
            }
            if (layout == null) drawContent() else drawText(layout, color = secondaryColor)
            if (layout != null) {
                clipPath(sungMask) { drawText(layout, color = textColor) }
                if (decorated) {
                    val activeEdge = checkNotNull(currentEdge)
                    // Only choose the active wrapped line vertically. Each pass is text-shaped,
                    // with transparent, smooth endpoints instead of a clipped rectangular band.
                    clipRect(0f, activeEdge.bounds.top, size.width, activeEdge.bounds.bottom) {
                        drawText(layout, brush = reflection(activeEdge, softRadius, satinStops))
                        drawText(layout, brush = reflection(activeEdge, ridgeRadius, ridgeStops))
                    }
                    diagnostics?.reflectionDrawn = true
                }
            }
        }
    }, style = style, color = textColor, onTextLayout = {
        measured = it
        diagnostics?.layouts?.incrementAndGet()
    })
}

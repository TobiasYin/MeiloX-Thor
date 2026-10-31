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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicInteger

/** Optional native verification; production supplies no collector and serializes no lyric text. */
internal class ThorLyricFlowDiagnostics {
    val layouts = AtomicInteger()
    val draws = AtomicInteger()
    val frameTicks = AtomicInteger()
    @Volatile var timing: ThorLyricTiming? = null
    @Volatile var decorated: Boolean = false
    @Volatile var sampledPositionMs: Long = 0L
}
internal val LocalThorLyricFlowDiagnostics = staticCompositionLocalOf<ThorLyricFlowDiagnostics?> { null }

private data class LyricRun(val bounds: Rect, val rtl: Boolean)
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
    accentColor: Color,
    decorate: Boolean,
    modifier: Modifier = Modifier,
) {
    val diagnostics = LocalThorLyricFlowDiagnostics.current
    val latestPosition by rememberUpdatedState(positionProvider)
    var measured by remember(timeline.text, style) { mutableStateOf<TextLayoutResult?>(null) }
    val shine = lerp(textColor, accentColor, .16f)
    Text(timeline.text, modifier.drawWithCache {
        val layout = measured
        val shaped = if (layout == null) emptyList() else timeline.spans.map { span ->
            val start = span.startOffset.coerceIn(0, timeline.text.length)
            val end = span.endOffset.coerceIn(start, timeline.text.length)
            val runs = if (start == end) emptyList() else buildList {
                for (line in layout.getLineForOffset(start)..layout.getLineForOffset(end - 1)) {
                    val from = maxOf(start, layout.getLineStart(line))
                    val to = minOf(end, layout.getLineEnd(line, visibleEnd = false))
                    if (from < to) {
                        val bounds = layout.getPathForRange(from, to).getBounds()
                        if (bounds.width > .01f && bounds.height > .01f) add(LyricRun(bounds,
                            layout.getBidiRunDirection(from) == ResolvedTextDirection.Rtl))
                    }
                }
            }
            ShapedLyricSpan(span, runs, runs.sumOf { it.bounds.width.toDouble() }.toFloat())
        }
        val sungMask = Path()
        val bandWidth = 22.dp.toPx()
        val glowRadius = 4.dp.toPx()
        onDrawWithContent {
            // These state reads invalidate DRAW only. The shaped text and auxiliary row sizes
            // never change as a word advances. The sample is the actual media clock supplied by
            // the Activity, not a free-running shimmer or synthetic LRC word clock.
            frameTick.value
            val now = latestPosition().toLong()
            diagnostics?.apply {
                draws.incrementAndGet(); timing = timeline.timing; decorated = decorate; sampledPositionMs = now
            }
            sungMask.reset()
            var edge: Pair<Float, Rect>? = null
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
                        edge = (if (run.rtl) run.bounds.right - width else run.bounds.left + width) to run.bounds
                    }
                    remaining -= run.bounds.width
                }
            }
            val currentEdge = edge
            if (layout != null && decorate && currentEdge != null) {
                val (x, area) = currentEdge
                // A small glyph-local shadow provides depth without blurring the viewport or
                // changing row alpha. Draw it BEFORE opaque ink to preserve text contrast.
                clipRect(x - bandWidth, area.top - glowRadius, x + bandWidth, area.bottom + glowRadius) {
                    drawText(layout, color = shine, alpha = .30f,
                        shadow = Shadow(accentColor.copy(alpha = .45f), Offset.Zero, glowRadius))
                }
            }
            if (layout == null) drawContent() else drawText(layout, color = secondaryColor)
            if (layout != null) {
                clipPath(sungMask) { drawText(layout, color = textColor) }
                if (decorate && currentEdge != null) {
                    val (x, area) = currentEdge
                    clipRect(x - bandWidth, area.top, x + bandWidth, area.bottom) {
                        drawText(layout, brush = Brush.horizontalGradient(listOf(Color.Transparent, shine,
                            textColor, shine, Color.Transparent), startX = x - bandWidth, endX = x + bandWidth))
                    }
                }
            }
        }
    }, style = style, color = textColor, onTextLayout = {
        measured = it
        diagnostics?.layouts?.incrementAndGet()
    })
}

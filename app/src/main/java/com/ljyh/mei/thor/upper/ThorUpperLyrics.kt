package com.ljyh.mei.thor.upper

import android.os.SystemClock
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ljyh.mei.thor.theme.LocalThorTheme
import com.ljyh.mei.ui.component.player.component.lyricAccompanimentOwners
import com.ljyh.mei.ui.component.player.component.lyricFocusLineIndex
import com.ljyh.mei.ui.component.player.component.runInterruptibleLyricJump
import com.ljyh.mei.ui.model.LyricData
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Pixel geometry shared by content padding, its clear focal point and the real alpha mask. */
internal data class ThorLyricViewportLayout(
    val anchorPx: Int,
    val topPaddingPx: Int,
    val bottomPaddingPx: Int,
    val fadeTopPx: Float,
    val fadeBottomPx: Float,
)

internal fun thorLyricViewportLayout(
    heightPx: Int,
    requestedTopFadePx: Float,
    requestedBottomFadePx: Float,
    minimumRowPx: Int,
    anchorFraction: Float = .23f,
): ThorLyricViewportLayout {
    val height = heightPx.coerceAtLeast(0)
    val topFade = requestedTopFadePx.takeIf { it.isFinite() }?.coerceIn(0f, height * .20f) ?: 0f
    val bottomFade = requestedBottomFadePx.takeIf { it.isFinite() }?.coerceIn(0f, height * .20f) ?: 0f
    val fraction = anchorFraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .23f
    val anchor = maxOf((height * fraction).roundToInt(), ceil(topFade).toInt()).coerceAtMost(height)
    val bottom = maxOf(height - anchor - minimumRowPx.coerceAtLeast(0), ceil(bottomFade).toInt())
    return ThorLyricViewportLayout(anchor, anchor, bottom, topFade, bottomFade)
}

internal sealed interface ThorLyricFollowPlan {
    data class Snap(val index: Int) : ThorLyricFollowPlan
    data class AnimateBy(val pixels: Float) : ThorLyricFollowPlan
}

/** Nearby targets use measured pixels, never an estimated average height or a spring correction. */
internal fun thorLyricFollowPlan(
    targetIndex: Int,
    measuredOffsetPx: Int?,
    anchorOffsetPx: Int,
    positioned: Boolean,
    reduceMotion: Boolean,
): ThorLyricFollowPlan = if (!positioned || reduceMotion || measuredOffsetPx == null) {
    ThorLyricFollowPlan.Snap(targetIndex)
} else ThorLyricFollowPlan.AnimateBy((measuredOffsetPx - anchorOffsetPx).toFloat())

/** Independent browsing shares the playback clock only for emphasis; only a row click seeks. */
@Composable
internal fun ThorUpperLyrics(
    data: LyricData,
    positionProvider: () -> Long,
    active: Boolean,
    modifier: Modifier = Modifier,
    onLineSelected: (Long) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    browseState: ThorLyricBrowseState = remember(data.lyricLine) { ThorLyricBrowseState() },
    playing: Boolean = true,
) {
    val theme = LocalThorTheme.current
    val layout = theme.lyricLayout
    val latestPosition by rememberUpdatedState(positionProvider)
    val latestSelection by rememberUpdatedState(onLineSelected)
    val drawFrame = remember { mutableLongStateOf(0L) }
    val flowDiagnostics = LocalThorLyricFlowDiagnostics.current
    LaunchedEffect(active, playing, theme.reduceMotion, flowDiagnostics) {
        if (!active || !playing || theme.reduceMotion) return@LaunchedEffect
        var previousPublished = 0L
        while (isActive) {
            val frame = withFrameNanos { it }
            if (previousPublished == 0L || frame - previousPublished >= 16_000_000L) {
                drawFrame.longValue = frame
                flowDiagnostics?.frameTicks?.incrementAndGet()
                previousPublished = frame
            }
        }
    }
    val lines = data.lyricLine.lines
    val owners = remember(lines) { lyricAccompanimentOwners(lines) }
    val timeProvider = remember { { latestPosition().coerceIn(0L, Int.MAX_VALUE.toLong()).toInt() } }
    val focus by remember(lines) {
        derivedStateOf(structuralEqualityPolicy()) {
            // Notice a real line boundary on the rendering clock as well as bridge ticks.
            // Only an INDEX change invalidates layout; syllable progress remains draw-only.
            drawFrame.longValue
            lyricFocusLineIndex(lines, timeProvider(), owners)
        }
    }
    DisposableEffect(browseState) { onDispose { browseState.onHidden() } }
    LaunchedEffect(browseState, active) {
        if (!active) browseState.onHidden()
    }
    LaunchedEffect(listState, browseState, active) {
        if (!active) return@LaunchedEffect
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> browseState.onDragStarted(SystemClock.uptimeMillis())
                is DragInteraction.Stop, is DragInteraction.Cancel -> browseState.onDragStopped(SystemClock.uptimeMillis())
            }
        }
    }
    LaunchedEffect(listState, browseState, active) {
        if (!active) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            browseState.onScrollChanged(scrolling, SystemClock.uptimeMillis())
        }
    }
    val returnAt = browseState.returnAtMs
    LaunchedEffect(browseState, returnAt, active) {
        if (!active || returnAt == null) return@LaunchedEffect
        while (isActive && browseState.returnAtMs == returnAt) {
            val remaining = returnAt - SystemClock.uptimeMillis()
            if (remaining > 0L) delay(remaining)
            else { browseState.resumeIfIdle(SystemClock.uptimeMillis()); break }
        }
    }
    BoxWithConstraints(modifier.semantics { stateDescription = if (browseState.isBrowsing) "浏览歌词" else "跟随播放" }) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.coerceAtLeast(1)
        val heightPx = constraints.maxHeight.takeIf { it != Constraints.Infinity } ?: 0
        val viewport = with(density) {
            thorLyricViewportLayout(heightPx, layout.edgeFadeTop.toPx(), layout.edgeFadeBottom.toPx(),
                48.dp.roundToPx(), layout.followAnchorFraction)
        }
        val positioned = remember(lines, listState, widthPx, heightPx, density.fontScale, layout) { booleanArrayOf(false) }
        val measurer = rememberTextMeasurer(cacheSize = 32)
        val activeStyle = MaterialTheme.typography.bodyLarge.copy(
            fontSize = theme.typography.lyricCurrent, fontWeight = FontWeight.Medium,
            lineHeight = layout.lineHeight, textDirection = TextDirection.Content)
        val adjacentStyle = MaterialTheme.typography.bodyLarge.copy(
            fontSize = theme.typography.lyricAdjacent, fontWeight = FontWeight.Normal,
            lineHeight = layout.lineHeight, textDirection = TextDirection.Content)
        val translationStyle = MaterialTheme.typography.bodyMedium.copy(
            fontSize = theme.typography.lyricTranslation, lineHeight = layout.lineHeight,
            textDirection = TextDirection.Content)
        val phoneticStyle = MaterialTheme.typography.bodySmall.copy(
            fontSize = 12.sp, lineHeight = layout.lineHeight, textDirection = TextDirection.Content)
        LaunchedEffect(lines, active, theme.reduceMotion, browseState.canFollow, widthPx, heightPx, density.fontScale, layout) {
            if (!active || !browseState.canFollow || heightPx <= 0 || lines.isEmpty()) return@LaunchedEffect
            snapshotFlow { focus }.distinctUntilChanged().collectLatest { index ->
                val target = index.coerceIn(0, lines.lastIndex)
                val completed = runInterruptibleLyricJump(restoreVisibility = {}, jump = {
                    if (!browseState.canFollow) false else {
                        val info = listState.layoutInfo
                        // Item offsets omit beforeContentPadding. Its screen position is
                        // item.offset - viewportStartOffset; topPadding already contains anchor.
                        val anchorOffset = info.viewportStartOffset + viewport.anchorPx
                        val plan = thorLyricFollowPlan(target,
                            info.visibleItemsInfo.firstOrNull { it.index == target }?.offset,
                            anchorOffset, positioned[0], theme.reduceMotion)
                        when (plan) {
                            is ThorLyricFollowPlan.Snap -> listState.scrollToItem(plan.index, 0)
                            is ThorLyricFollowPlan.AnimateBy -> if (abs(plan.pixels) > .5f) {
                                listState.animateScrollBy(plan.pixels, tween(300, easing = LinearOutSlowInEasing))
                            }
                        }
                        true
                    }
                })
                if (completed) positioned[0] = true
            }
        }
        LazyColumn(Modifier.fillMaxSize().focusProperties { canFocus = false }.clipToBounds()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (size.height > 0f) drawRect(Brush.verticalGradient(
                    0f to Color.Transparent,
                    (viewport.fadeTopPx / size.height).coerceIn(0f, .20f) to Color.Black,
                    (1f - viewport.fadeBottomPx / size.height).coerceIn(.80f, 1f) to Color.Black,
                    1f to Color.Transparent,
                ), blendMode = BlendMode.DstIn)
            }
            .pointerInput(browseState, active) {
                if (!active) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    browseState.onTouchStarted(SystemClock.uptimeMillis())
                    try {
                        do { val event = awaitPointerEvent(PointerEventPass.Final) }
                        while (event.changes.any { it.pressed })
                    } finally { browseState.onTouchEnded(SystemClock.uptimeMillis()) }
                }
            }, state = listState,
            userScrollEnabled = active, verticalArrangement = Arrangement.spacedBy(layout.rowGap),
            contentPadding = with(density) { PaddingValues(top = viewport.topPaddingPx.toDp(), bottom = viewport.bottomPaddingPx.toDp()) }) {
            itemsIndexed(lines, key = { index, line -> "${line.start}:${line.end}:$index" }) { index, line ->
                val focused = index == focus || owners[index] == focus
                val words = remember(line) { when (line) {
                    is KaraokeLine -> line.syllables.joinToString("") { it.content }
                    is SyncedLine -> line.content
                    else -> ""
                } }
                val timeline = remember(line, data.isVerbatim) { thorLyricTimeline(line, data.isVerbatim) }
                val translation = when (line) { is KaraokeLine -> line.translation; is SyncedLine -> line.translation; else -> null }
                val phonetic = if (line is KaraokeLine) line.phonetic
                    ?: line.syllables.mapNotNull { it.phonetic }.takeIf { it.isNotEmpty() }?.joinToString("") else null
                val accompaniment = line is KaraokeLine.AccompanimentKaraokeLine
                val primaryStyle = if (focused && !accompaniment) activeStyle else adjacentStyle
                // Reserve this line's maximum real measurement only, not a global fixed row.
                // Font emphasis and auxiliary visibility can no longer move later rows mid-scroll.
                val primaryHeightPx = remember(words, activeStyle, adjacentStyle, accompaniment, widthPx, measurer) {
                    if (words.isEmpty()) 0 else {
                        val constraints = Constraints(maxWidth = widthPx)
                        val adjacentHeight = measurer.measure(words, adjacentStyle, constraints = constraints).size.height
                        if (accompaniment) adjacentHeight else maxOf(adjacentHeight,
                            measurer.measure(words, activeStyle, constraints = constraints).size.height)
                    }
                }
                val backingLines = remember(line, lines) {
                    if (line is KaraokeLine.MainKaraokeLine) line.accompanimentLines.orEmpty().filter { it !in lines } else emptyList()
                }
                Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .focusProperties { canFocus = false }
                    .semantics { selected = focused }
                    .clickable(enabled = active, role = Role.Button, onClickLabel = "从这里播放") {
                        latestSelection(line.start.toLong())
                        browseState.onLineSelected()
                    }.padding(vertical = layout.rowVerticalInset)) {
                    Box(Modifier.fillMaxWidth().height(with(density) { primaryHeightPx.toDp() })) {
                        if (focused) ThorLyricFlowText(
                            timeline = timeline, style = primaryStyle, positionProvider = timeProvider,
                            frameTick = drawFrame, textColor = theme.lyricSheen.ink, secondaryColor = theme.secondary,
                            sheen = theme.lyricSheen, decorate = active && playing && !theme.reduceMotion && !theme.reduceTransparency,
                            modifier = Modifier.fillMaxWidth(),
                        ) else Text(words, Modifier.fillMaxWidth(), style = primaryStyle, color = theme.secondary)
                    }
                    if (!translation.isNullOrBlank() || !phonetic.isNullOrBlank()) {
                        Column(Modifier.padding(top = layout.auxiliaryTopGap), verticalArrangement = Arrangement.spacedBy(layout.auxiliaryGap)) {
                            if (!translation.isNullOrBlank()) Text(translation, style = translationStyle, color = theme.secondary)
                            if (!phonetic.isNullOrBlank()) Text(phonetic, style = phoneticStyle, color = theme.secondary)
                        }
                    }
                    backingLines.forEach { backing ->
                        val showing = focused && timeProvider() in (backing.start - 600)..(backing.end + 600)
                        Column(Modifier.padding(top = layout.auxiliaryTopGap)
                            .graphicsLayer { alpha = if (showing) 1f else 0f }
                            .then(if (showing) Modifier else Modifier.clearAndSetSemantics {}),
                            verticalArrangement = Arrangement.spacedBy(layout.auxiliaryGap)) {
                            Text(backing.syllables.joinToString("") { it.content }, style = adjacentStyle, color = theme.secondary)
                            if (!backing.translation.isNullOrBlank()) Text(backing.translation!!, style = translationStyle, color = theme.secondary)
                        }
                    }
                }
            }
        }
    }
}

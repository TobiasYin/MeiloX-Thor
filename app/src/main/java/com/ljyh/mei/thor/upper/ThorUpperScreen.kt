package com.ljyh.mei.thor.upper

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ljyh.mei.thor.artwork.CaseAmbientPalette
import com.ljyh.mei.thor.artwork.ThorCdCase
import com.ljyh.mei.thor.components.thorCssGradientLine
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.thor.theme.LocalThorTheme
import com.ljyh.mei.thor.theme.ThorTheme
import com.ljyh.mei.ui.component.player.component.lyricAccompanimentOwners
import com.ljyh.mei.ui.component.player.component.lyricFocusLineIndex
import com.ljyh.mei.ui.component.player.component.readableGlyphOffsets
import com.ljyh.mei.ui.component.player.component.readableKaraokeProgress
import com.ljyh.mei.ui.component.player.component.runInterruptibleLyricJump
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Public music projection. The root owns the player, song selection and progress clock. */
@Composable
fun ThorUpperScreen(
    item: ThorItem?,
    playing: Boolean,
    preferences: ThorPreferences,
    airflow: Float,
    lyricData: LyricData? = null,
    positionProvider: () -> Long = { 0L },
    onOpenPlayer: () -> Unit = {},
    modifier: Modifier = Modifier,
    playbackScene: Boolean = false,
    visible: Boolean = true,
    onLyricSelected: (Long) -> Unit = {},
    playbackEntryId: String? = null,
) {
    ThorTheme(preferences) {
        ThorUpperContent(item, playing, preferences, airflow, lyricData, positionProvider, onOpenPlayer, modifier, playbackScene, visible, onLyricSelected, playbackEntryId)
    }
}

@Composable
private fun ThorUpperContent(
    item: ThorItem?, playing: Boolean, preferences: ThorPreferences, airflow: Float,
    lyricData: LyricData?, positionProvider: () -> Long, onOpenPlayer: () -> Unit,
    modifier: Modifier, playbackScene: Boolean, visible: Boolean, onLyricSelected: (Long) -> Unit, playbackEntryId: String?,
) {
    val theme = LocalThorTheme.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var started by remember(lifecycleOwner) { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ -> started = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val active = visible && started
    var ambient by remember(item?.key?.stableId, item?.artworkUri) { mutableStateOf<CaseAmbientPalette?>(null) }
    val hasLyrics = playbackScene && lyricData != null && lyricData.source != LyricSource.Empty &&
        lyricData.source != LyricSource.Loading && !lyricData.isPureMusic && lyricData.lyricLine.lines.isNotEmpty() &&
        !remember(lyricData) { upperLyricsArePlaceholder(lyricData) }
    BoxWithConstraints(modifier.fillMaxSize().background(theme.background)) {
        UpperAmbient(ambient ?: CaseAmbientPalette(theme.ambient, theme.ambient), active,
            playbackScene && playing && !theme.reduceMotion, Modifier.fillMaxSize())
        // Final prototype: 4% horizontal / 13% vertical inset, identical 52/48 grid in every scene.
        Row(Modifier.fillMaxSize().padding(horizontal = maxWidth * .04f, vertical = maxHeight * .13f),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(.52f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                if (item != null) ThorCdCase(
                    artwork = item.artwork, airflow = if (preferences.airflowEnabled) airflow else 0f,
                    visible = active, floating = item.key !is ThorItemKey.Destination && (!playbackScene || playing),
                    onAmbientPalette = { ambient = it }, modifier = Modifier.fillMaxSize(),
                ) else Icon(Icons.Outlined.Album, null, Modifier.size(110.dp), tint = theme.secondary)
            }
            BoxWithConstraints(Modifier.weight(.48f).fillMaxHeight()) {
                if (hasLyrics) {
                    Column(Modifier.fillMaxSize().padding(end = maxWidth * .02f, top = maxHeight * .10f)) {
                        Text(listOfNotNull(item?.title?.takeIf(String::isNotBlank), item?.subtitle?.takeIf(String::isNotBlank)).joinToString(" · "),
                            Modifier.fillMaxWidth().clickable(onClick = onOpenPlayer),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, letterSpacing = 1.3.sp),
                            color = theme.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(20.dp))
                        key(playbackEntryId ?: item?.key?.stableId, lyricData!!.lyricLine) {
                            ThorUpperLyrics(lyricData, positionProvider, active, Modifier.weight(1f).fillMaxWidth(), onLyricSelected)
                        }
                    }
                } else Column(Modifier.fillMaxSize().padding(start = maxWidth * .01f, end = maxWidth * .04f),
                    verticalArrangement = Arrangement.Center) {
                    val eyebrow = when {
                        playbackScene -> if (playing) "正在播放" else "已暂停"
                        item?.key is ThorItemKey.Album -> if (item.collected) "已收藏专辑" else "专辑"
                        item?.key is ThorItemKey.Playlist -> if (item.owned) "创建的歌单" else if (item.collected) "已收藏歌单" else "歌单"
                        item?.key is ThorItemKey.Song -> if (item.key.local) "本地音乐" else "歌曲"
                        else -> null
                    }
                    if (eyebrow != null) {
                        Text(eyebrow, color = theme.secondary, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, letterSpacing = 1.3.sp))
                        Spacer(Modifier.height(14.dp))
                    }
                    Text(item?.title?.takeIf(String::isNotBlank) ?: "音乐库", color = theme.text,
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = theme.typography.display, lineHeight = 1.25.em,
                            letterSpacing = (-.7).sp, fontWeight = FontWeight.Medium), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (!item?.subtitle.isNullOrBlank()) {
                        Spacer(Modifier.height(9.dp))
                        Text(item!!.subtitle, color = theme.secondary, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (!item?.description.isNullOrBlank()) {
                        Spacer(Modifier.height(18.dp))
                        Text(item!!.description, color = theme.text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 1.8.em), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    val metadata = buildList {
                        item?.trackCount?.let { add("$it 首歌曲") }
                        if (playbackScene) when {
                            lyricData?.isPureMusic == true -> add("纯音乐")
                            lyricData?.source == LyricSource.Loading -> add("歌词加载中")
                            else -> add("暂无歌词")
                        }
                    }.joinToString(" · ")
                    if (metadata.isNotBlank()) {
                        Spacer(Modifier.height(13.dp))
                        Text(metadata, color = theme.secondary, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp))
                    }
                }
            }
        }
    }
}

@Composable
private fun UpperAmbient(palette: CaseAmbientPalette, active: Boolean, animate: Boolean, modifier: Modifier) {
    val theme = LocalThorTheme.current
    val phase = remember { mutableFloatStateOf(0f) }
    val motion = remember { floatArrayOf(0f) }
    LaunchedEffect(active, animate, theme.reduceMotion) {
        if (!active || theme.reduceMotion) { motion[0] = 0f; return@LaunchedEffect }
        var previous = 0L
        while (isActive) {
            val now = withFrameNanos { it }
            val dt = if (previous == 0L) 0f else ((now - previous) / 1_000_000_000f).coerceIn(0f, .05f)
            previous = now
            // Let paused music drift to rest without resetting its visible phase.
            motion[0] += ((if (animate) 1f else 0f) - motion[0]) * (1f - exp(-dt / .65f))
            phase.floatValue = (phase.floatValue + dt * .16f * motion[0]) % (Math.PI * 2).toFloat()
            if (!animate && motion[0] < .001f) break
        }
    }
    Canvas(modifier) {
        val clock = phase.floatValue
        val fieldWidth = size.width * .58f
        val opacity = if (theme.reduceTransparency) .35f else 1f
        // CSS's default ellipse uses farthest-corner sizing, then fades to its 67%/62% stop.
        // Both ellipses reach transparent before the field's right boundary; no hard clip seam.
        fun mist(color: Color, alpha: Float, x: Float, y: Float, stop: Float) {
            val center = Offset(fieldWidth * x, size.height * y)
            val rx = max(center.x, fieldWidth - center.x) * sqrt(2f) * stop
            val ry = max(center.y, size.height - center.y) * sqrt(2f) * stop
            if (rx <= 0f || ry <= 0f) return
            withTransform({ scale(1f, ry / rx, center) }) {
                drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha * opacity), Color.Transparent), center, rx), rx, center)
            }
        }
        mist(palette.body, .28f, .44f + sin(clock) * .025f, .65f + sin(clock * .85f) * .018f, .67f)
        mist(palette.glow, .14f, .18f + sin(clock * .73f) * .018f, .17f + sin(clock * .81f) * .025f, .62f)
        if (!theme.reduceTransparency) {
            val line = thorCssGradientLine(size.width, size.height, 135f)
            drawRect(Brush.linearGradient(0f to theme.paneGlint, .38f to Color.Transparent, 1f to Color.Transparent,
                start = Offset(line.startX, line.startY), end = Offset(line.endX, line.endY)))
        }
    }
}

/** Some providers or local LRC files encode their empty-state message as a single timed line. */
private fun upperLyricsArePlaceholder(data: LyricData): Boolean {
    val line = data.lyricLine.lines.singleOrNull() ?: return false
    if (line.start != 0) return false
    val text = when (line) {
        is KaraokeLine -> line.syllables.joinToString("") { it.content }
        is SyncedLine -> line.content
        else -> return false
    }.trim()
    return text in setOf("暂无歌词", "没有歌词", "歌词加载中", "纯音乐", "纯音乐，请欣赏")
}

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
) {
    val theme = LocalThorTheme.current
    val layout = theme.lyricLayout
    val latestPosition by rememberUpdatedState(positionProvider)
    val latestSelection by rememberUpdatedState(onLineSelected)
    var viewport by remember { mutableIntStateOf(0) }
    val lines = data.lyricLine.lines
    val owners = remember(lines) { lyricAccompanimentOwners(lines) }
    val timeProvider = remember { { latestPosition().coerceIn(0L, Int.MAX_VALUE.toLong()).toInt() } }
    val focus by remember(lines) { derivedStateOf { lyricFocusLineIndex(lines, timeProvider(), owners) } }
    val positioned = remember(lines, listState) { booleanArrayOf(false) }
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
        // Recheck the same monotonic clock after waking; an early scheduler callback
        // must neither return before 15s nor leave this unchanged deadline unarmed.
        while (isActive && browseState.returnAtMs == returnAt) {
            val remaining = returnAt - SystemClock.uptimeMillis()
            if (remaining > 0L) delay(remaining)
            else { browseState.resumeIfIdle(SystemClock.uptimeMillis()); break }
        }
    }
    LaunchedEffect(lines, active, theme.reduceMotion, browseState.canFollow) {
        if (!active || !browseState.canFollow) return@LaunchedEffect
        snapshotFlow { focus to viewport }.distinctUntilChanged().collectLatest { (index, height) ->
            if (height <= 0 || lines.isEmpty()) return@collectLatest
            val target = index.coerceIn(0, lines.lastIndex)
            val anchor = -(height * .23f).toInt()
            // Isolate the scroll mutation: a finger winning an animation must not kill follow.
            val completed = runInterruptibleLyricJump(restoreVisibility = {}, jump = {
                if (theme.reduceMotion || !positioned[0]) listState.scrollToItem(target, anchor)
                else listState.animateScrollToItem(target, anchor)
                true
            })
            if (completed) positioned[0] = true
        }
    }
    LazyColumn(modifier.clipToBounds().onSizeChanged { viewport = it.height }
        .semantics { stateDescription = if (browseState.isBrowsing) "浏览歌词" else "跟随播放" }
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
        contentPadding = PaddingValues(bottom = 260.dp)) {
        itemsIndexed(lines, key = { index, line -> "${line.start}:${line.end}:$index" }) { index, line ->
            val focused = index == focus || owners[index] == focus
            val words = remember(line) { when (line) {
                is KaraokeLine -> line.syllables.joinToString("") { it.content }
                is SyncedLine -> line.content
                else -> ""
            } }
            val translation = when (line) { is KaraokeLine -> line.translation; is SyncedLine -> line.translation; else -> null }
            val phonetic = if (line is KaraokeLine) line.phonetic
                ?: line.syllables.mapNotNull { it.phonetic }.takeIf { it.isNotEmpty() }?.joinToString("") else null
            val accompaniment = line is KaraokeLine.AccompanimentKaraokeLine
            val style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = if (focused && !accompaniment) theme.typography.lyricCurrent else theme.typography.lyricAdjacent,
                fontWeight = if (focused && !accompaniment) FontWeight.Medium else FontWeight.Normal,
                lineHeight = layout.lineHeight, textDirection = TextDirection.Content)
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { selected = focused }
                .clickable(enabled = active, role = Role.Button, onClickLabel = "从这里播放") {
                    latestSelection(line.start.toLong())
                    browseState.onLineSelected()
                }.padding(vertical = layout.rowVerticalInset)) {
                if (line is KaraokeLine && data.isVerbatim && focused && !theme.reduceMotion) {
                    val boundaries = remember(line) { line.syllables.map { readableGlyphOffsets(it.content) } }
                    val time = timeProvider()
                    val styled = buildAnnotatedString {
                        line.syllables.forEachIndexed { syllableIndex, syllable ->
                            val start = length
                            append(syllable.content)
                            val offsets = boundaries[syllableIndex]
                            val progress = readableKaraokeProgress(time, syllable.start, syllable.end, offsets.size - 1)
                            for (glyph in 0 until offsets.lastIndex) {
                                val emphasis = (progress - glyph).coerceIn(0.0, 1.0).toFloat()
                                addStyle(SpanStyle(color = lerp(theme.secondary, theme.text, emphasis)), start + offsets[glyph], start + offsets[glyph + 1])
                            }
                        }
                    }
                    Text(styled, style = style, color = theme.text)
                } else Text(words, style = style, color = if (focused) theme.text else theme.secondary)
                if (focused && (!translation.isNullOrBlank() || !phonetic.isNullOrBlank())) {
                    Column(Modifier.padding(top = layout.auxiliaryTopGap), verticalArrangement = Arrangement.spacedBy(layout.auxiliaryGap)) {
                        if (!translation.isNullOrBlank()) Text(translation, style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = theme.typography.lyricTranslation, lineHeight = layout.lineHeight, textDirection = TextDirection.Content), color = theme.secondary)
                        if (!phonetic.isNullOrBlank()) Text(phonetic, style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp, lineHeight = layout.lineHeight, textDirection = TextDirection.Content), color = theme.secondary)
                    }
                }
                if (focused && line is KaraokeLine.MainKaraokeLine) {
                    line.accompanimentLines.orEmpty().filter { backing ->
                        backing !in lines && timeProvider() in (backing.start - 600)..(backing.end + 600)
                    }.forEach { backing ->
                        Column(Modifier.padding(top = layout.auxiliaryTopGap), verticalArrangement = Arrangement.spacedBy(layout.auxiliaryGap)) {
                            Text(backing.syllables.joinToString("") { it.content }, style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = theme.typography.lyricAdjacent, lineHeight = layout.lineHeight), color = theme.secondary)
                            if (!backing.translation.isNullOrBlank()) Text(backing.translation!!, style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = theme.typography.lyricTranslation, lineHeight = layout.lineHeight), color = theme.secondary)
                        }
                    }
                }
            }
        }
    }
}

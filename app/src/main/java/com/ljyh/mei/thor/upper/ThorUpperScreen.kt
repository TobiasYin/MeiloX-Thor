package com.ljyh.mei.thor.upper

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ljyh.mei.thor.artwork.ThorCdCase
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.thor.theme.LocalThorTheme
import com.ljyh.mei.thor.theme.ThorTheme
import com.ljyh.mei.ui.component.player.component.PlayerLyricsView
import com.ljyh.mei.ui.component.player.component.ReadableLyricsPalette
import com.ljyh.mei.ui.component.player.component.lyricAccompanimentOwners
import com.ljyh.mei.ui.component.player.component.lyricFocusLineIndex
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Stateless public music projection. playbackScene is separate from playing: browsing can coexist
 * with background playback, while queue/lyric destinations keep the current song's stage visible.
 * positionProvider reads the root's observable progress state; this component owns no player/ticker.
 */
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
) {
    ThorTheme(preferences) {
        ThorUpperContent(item, playing, preferences, airflow, lyricData, positionProvider, onOpenPlayer, modifier, playbackScene, visible)
    }
}

@Composable
private fun ThorUpperContent(
    item: ThorItem?, playing: Boolean, preferences: ThorPreferences, airflow: Float,
    lyricData: LyricData?, positionProvider: () -> Long, onOpenPlayer: () -> Unit,
    modifier: Modifier, playbackScene: Boolean, visible: Boolean,
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
    var ambient by remember(item?.key?.stableId) { mutableStateOf<Color?>(null) }
    val hasLyrics = playbackScene && lyricData != null && lyricData.source != LyricSource.Empty &&
        lyricData.source != LyricSource.Loading && !lyricData.isPureMusic && lyricData.lyricLine.lines.isNotEmpty()
    BoxWithConstraints(modifier.fillMaxSize().background(theme.background)) {
        UpperAmbient(ambient ?: theme.ambient, active && playbackScene && playing && !theme.reduceMotion, Modifier.fillMaxSize())
        val wide = maxWidth >= 700.dp
        val horizontal = if (wide) 32.dp else 20.dp
        Row(
            Modifier.fillMaxSize().padding(horizontal = horizontal, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(if (wide) 24.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(if (hasLyrics) .44f else .48f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                if (item != null) {
                    ThorCdCase(
                        artwork = item.artwork,
                        airflow = if (preferences.airflowEnabled) airflow else 0f,
                        visible = active,
                        floating = item.key !is ThorItemKey.Destination && (!playbackScene || playing),
                        onAmbientColor = { ambient = it },
                    )
                } else {
                    // Empty libraries have no fictitious album or broken image placeholder.
                    Canvas(Modifier.fillMaxSize().padding(40.dp)) {
                        val side = size.minDimension * .7f
                        drawRoundRect(theme.inset, Offset((size.width - side) / 2, (size.height - side) / 2), androidx.compose.ui.geometry.Size(side, side), androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()))
                    }
                }
            }
            Column(
                Modifier.weight(if (hasLyrics) .56f else .52f).fillMaxHeight(),
                verticalArrangement = if (hasLyrics) Arrangement.Top else Arrangement.Center,
            ) {
                if (hasLyrics) {
                    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onOpenPlayer)) {
                        Text(item?.title.orEmpty(), color = theme.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!item?.subtitle.isNullOrBlank()) Text(item!!.subtitle, color = theme.secondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(12.dp))
                    UpperLyrics(lyricData!!, positionProvider, active, Modifier.weight(1f).fillMaxWidth())
                } else {
                    val title = item?.title ?: "音乐库"
                    Text(title, color = theme.text, style = MaterialTheme.typography.displaySmall.copy(fontSize = if (wide) 36.sp else 30.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (!item?.subtitle.isNullOrBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(item!!.subtitle, color = theme.secondary, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (!item?.description.isNullOrBlank()) {
                        Spacer(Modifier.height(22.dp))
                        Text(item!!.description, color = theme.text, style = MaterialTheme.typography.bodyLarge, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    }
                    val status = when {
                        playbackScene && lyricData?.isPureMusic == true -> "纯音乐"
                        playbackScene && lyricData?.source == LyricSource.Loading -> "歌词加载中"
                        else -> null
                    }
                    val metadata = listOfNotNull(item?.trackCount?.let { "$it 首" }, status).joinToString(" · ")
                    if (metadata.isNotBlank()) {
                        Spacer(Modifier.height(20.dp))
                        Text(metadata, color = theme.secondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun UpperAmbient(color: Color, animate: Boolean, modifier: Modifier) {
    val theme = LocalThorTheme.current
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var previous = 0L
        while (isActive) {
            val now = withFrameNanos { it }
            if (previous != 0L) phase.floatValue = (phase.floatValue + ((now - previous) / 1_000_000_000f).coerceIn(0f, .05f) * .25f) % (Math.PI * 2).toFloat()
            previous = now
        }
    }
    Canvas(modifier) {
        val clock = phase.floatValue
        // Artwork glow is isolated to the model half, never used as a text/focus color.
        val center = Offset(size.width * (.23f + sin(clock) * .025f), size.height * (.53f + cos(clock) * .045f))
        val radius = min(size.height * .66f, size.width * .18f)
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = if (theme.isDark) .24f else .15f), Color.Transparent), center, radius), radius, center)
    }
}

@Composable
private fun UpperLyrics(data: LyricData, positionProvider: () -> Long, active: Boolean, modifier: Modifier) {
    val theme = LocalThorTheme.current
    val latestPosition by rememberUpdatedState(positionProvider)
    val listState = rememberLazyListState()
    val lines = data.lyricLine.lines
    val owners = remember(lines) { lyricAccompanimentOwners(lines) }
    val timeProvider = remember { { latestPosition().coerceIn(0L, Int.MAX_VALUE.toLong()).toInt() } }
    val focus by remember(lines, timeProvider) { derivedStateOf { lyricFocusLineIndex(lines, timeProvider(), owners) } }
    LaunchedEffect(lines, active, theme.reduceMotion) {
        if (!active) return@LaunchedEffect
        var previous = -1
        snapshotFlow { focus }.distinctUntilChanged().collectLatest { index ->
            val target = index.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
            val anchor = -(listState.layoutInfo.viewportEndOffset * .18f).toInt()
            if (theme.reduceMotion || previous < 0 || abs(previous - target) > 5) listState.scrollToItem(target, anchor)
            else listState.animateScrollToItem(target, anchor)
            previous = target
        }
    }
    PlayerLyricsView(
        listState = listState,
        lyrics = data.lyricLine,
        currentPosition = timeProvider,
        onLineClicked = {}, onLinePressed = {},
        isManualScrolling = theme.reduceMotion,
        modifier = modifier,
        listModifier = Modifier.fillMaxSize(),
        normalLineTextStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Medium),
        readableInactiveLineTextStyle = MaterialTheme.typography.titleMedium,
        accompanimentLineTextStyle = MaterialTheme.typography.bodyLarge,
        translationLineTextStyle = MaterialTheme.typography.bodyMedium,
        phoneticTextStyle = MaterialTheme.typography.bodySmall,
        textColor = theme.text,
        blendMode = BlendMode.SrcOver,
        useBlurEffect = false,
        showTranslation = true,
        showPhonetic = true,
        isVerbatim = data.isVerbatim && !theme.reduceMotion,
        offset = 56.dp,
        topFadeHeight = 0.dp, bottomFadeHeight = 0.dp,
        readablePalette = ReadableLyricsPalette(theme.text, theme.secondary, theme.secondary, theme.secondary),
        interactive = false,
    )
}

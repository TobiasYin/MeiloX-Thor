package com.ljyh.mei.thor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.playback.SleepTimer
import com.ljyh.mei.thor.ThorShellViewModel
import com.ljyh.mei.thor.components.ThorActionButton
import com.ljyh.mei.thor.components.ThorSurfaceRole
import com.ljyh.mei.thor.components.thorMaterial
import com.ljyh.mei.thor.menu.ThorMenuAnchor
import com.ljyh.mei.thor.model.ThorMenuSubject
import com.ljyh.mei.thor.model.ThorCollection
import com.ljyh.mei.thor.model.toThorItem
import com.ljyh.mei.thor.playback.ThorPlaybackState
import com.ljyh.mei.thor.theme.LocalThorTheme
import com.ljyh.mei.ui.model.LyricSource
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine

/** This console deliberately does not reuse a list-page dock or mini player. */
@UnstableApi
@Composable
internal fun ThorPlayerPage(
    vm: ThorShellViewModel,
    registry: ThorFocusRegistry,
    playback: ThorPlaybackState,
    onBack: () -> Unit,
) {
    val theme = LocalThorTheme.current
    val library by vm.library.collectAsStateWithLifecycle()
    val seek by vm.seekPreview.collectAsStateWithLifecycle()
    val song = playback.current
    val item = song?.toThorItem()
    val liked = song?.let { current ->
        if (current.isLocal) library[ThorCollection.LikedSongs].any { it.key == item?.key }
        else library.songLikes[current.id] == true
    } == true
    BindThorOptionFocus(vm, registry,
        if (song == null) emptyList() else if (seek != null) listOf("seek:apply") else listOf("player:toggle"),
        preferredKey = if (seek != null) "seek:apply" else "player:toggle")

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp).thorMaterial(ThorSurfaceRole.Header).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThorButton(registry, "player:back", "", icon = Icons.Outlined.ChevronLeft,
                flat = true, contentDescription = "返回", onClick = onBack)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(song?.title ?: "没有正在播放的歌曲", color = theme.text, fontSize = theme.typography.playerTitle, lineHeight = 29.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (song != null) Text(if (playback.error != null) "播放失败，请重试或切换歌曲" else song.artists.joinToString(" / ") { it.name }, color = if (playback.error != null) theme.error else theme.secondary,
                    fontSize = theme.typography.control, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ThorButton(registry, "player:like", "", icon = if (liked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    flat = true, contentDescription = if (liked) "取消喜欢当前歌曲" else "喜欢当前歌曲",
                    selected = liked, enabled = song != null && seek == null, onClick = vm::likeCurrent)
                ThorButton(registry, "player:more", "", icon = Icons.Outlined.MoreHoriz, flat = true, contentDescription = "更多操作",
                    enabled = item != null && seek == null, onClick = {
                        item?.let { frozen ->
                            val bounds = registry.current?.bounds
                            vm.openMenu(ThorMenuSubject(frozen, "player:more", queueEntryId = playback.currentEntryId),
                                ThorMenuAnchor(bounds?.left ?: 0f, bounds?.bottom ?: 64f))
                        }
                    })
            }
        }
        if (song == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                PlayerEmpty(if (playback.connected) "从音乐库选择歌曲开始播放" else "正在连接播放器")
            }
            return@Column
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp),
            contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.fillMaxWidth().thorMaterial(ThorSurfaceRole.Deck).padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val editProgress = { if (seek == null) vm.beginSeek() }
                    ThorActionButton(
                        onClick = editProgress,
                        modifier = Modifier.fillMaxWidth().height(48.dp).thorFocus(registry, "player:progress",
                            enabled = playback.durationMs > 0, onClick = editProgress),
                        enabled = playback.durationMs > 0, selected = seek != null,
                        role = ThorSurfaceRole.Flat, focused = registry.selectedKey == "player:progress",
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        Text(playbackTime(seek ?: playback.positionMs), Modifier.width(44.dp), color = theme.secondary,
                            fontSize = 13.sp, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"))
                        Spacer(Modifier.width(12.dp))
                        PlayerProgress(((seek ?: playback.positionMs).toFloat() / playback.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f), Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        Text(playbackTime(playback.durationMs), Modifier.width(44.dp), color = theme.secondary,
                            fontSize = 13.sp, textAlign = TextAlign.End,
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"))
                    }
                    Row(Modifier.fillMaxWidth().height(88.dp),
                        horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                        ThorButton(registry, "player:previous", "", Modifier.size(60.dp), icon = Icons.Outlined.SkipPrevious,
                            round = true, iconSize = 16.dp, contentDescription = "上一首",
                            enabled = playback.connected && seek == null, onClick = vm.playback::previous)
                        ThorButton(registry, "player:toggle", "", Modifier.size(80.dp),
                            icon = if (playback.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            primary = true, round = true, iconSize = 16.dp, contentDescription = if (playback.playing) "暂停" else "播放",
                            enabled = playback.connected && seek == null, onClick = vm.playback::toggle)
                        ThorButton(registry, "player:next", "", Modifier.size(60.dp), icon = Icons.Outlined.SkipNext,
                            round = true, iconSize = 16.dp, contentDescription = "下一首",
                            enabled = playback.connected && seek == null, onClick = vm.playback::next)
                    }
                    if (seek != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically) {
                        ThorButton(registry, "seek:back", "−10s", Modifier.widthIn(min = 64.dp), onClick = { vm.adjustSeek(-10_000) })
                        ThorButton(registry, "seek:cancel", "取消", Modifier.widthIn(min = 64.dp), onClick = vm::cancelSeek)
                        ThorButton(registry, "seek:apply", "应用", Modifier.widthIn(min = 64.dp), primary = true, onClick = vm::applySeek)
                        ThorButton(registry, "seek:forward", "+10s", Modifier.widthIn(min = 64.dp), onClick = { vm.adjustSeek(10_000) })
                    }
                }
                if (seek == null) {
                    val next = playback.next
                    ThorActionButton(
                        onClick = vm::toggleQueue,
                        modifier = Modifier.fillMaxWidth().height(64.dp).thorFocus(registry, "player:queue", onClick = vm::toggleQueue),
                        role = ThorSurfaceRole.NowPlaying, focused = registry.selectedKey == "player:queue",
                        shape = RoundedCornerShape(theme.cardRadius), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        if (next != null) ThorCover(next.item, Modifier.size(36.dp))
                        else Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                            Icon(thorLineIcon(Icons.Outlined.QueueMusic), null, Modifier.size(16.dp), tint = theme.secondary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (playback.mode == "单曲循环") "循环播放" else "下一首", color = theme.secondary, fontSize = 11.sp, lineHeight = 14.sp)
                            val nextText = buildAnnotatedString {
                                append(next?.metadata?.title ?: "暂无待播歌曲")
                                if (next != null) withStyle(SpanStyle(color = theme.secondary, fontSize = 12.sp, fontWeight = FontWeight.Normal)) {
                                    append(" · " + next.metadata.artists.joinToString(" / ") { it.name })
                                }
                            }
                            Text(nextText, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("队列 · ${playback.queue.size}", color = theme.secondary, fontSize = 12.sp, maxLines = 1)
                        Spacer(Modifier.width(12.dp))
                        Icon(thorLineIcon(Icons.Outlined.ChevronRight), null, Modifier.size(16.dp), tint = theme.secondary)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThorButton(registry, "player:mode", playback.mode, Modifier.weight(1f).height(52.dp),
                        icon = when (playback.mode) { "随机播放" -> Icons.Outlined.Shuffle; "单曲循环" -> Icons.Outlined.RepeatOne; else -> Icons.Outlined.Repeat },
                        iconSize = 16.dp, enabled = seek == null, contentPadding = PaddingValues(horizontal = 8.dp), onClick = vm.playback::switchMode)
                    ThorButton(registry, "player:lyrics", "歌词", Modifier.weight(1f).height(52.dp), icon = Icons.Outlined.Lyrics,
                        iconSize = 16.dp, contentDescription = "歌词定位", enabled = seek == null, contentPadding = PaddingValues(horizontal = 8.dp), onClick = vm::showLyrics)
                    ThorButton(registry, "player:timer", if (playback.timerActive) timerLabel(playback) else "定时",
                        Modifier.weight(1f).height(52.dp), icon = Icons.Outlined.Timer, selected = playback.timerActive, iconSize = 16.dp,
                        contentDescription = if (playback.timerActive) "定时停止 · ${timerLabel(playback)}" else "定时停止",
                        enabled = seek == null, contentPadding = PaddingValues(horizontal = 8.dp), onClick = vm::showTimer)
                }
            }
        }
    }
}

@Composable
private fun PlayerProgress(fraction: Float, modifier: Modifier = Modifier) {
    val theme = LocalThorTheme.current
    Box(modifier.height(12.dp).semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
        contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(4.dp).thorMaterial(ThorSurfaceRole.ProgressTrack, shape = RoundedCornerShape(2.dp))
            .drawWithContent {
                drawContent()
                if (fraction > 0f) drawRoundRect(theme.accent, size = Size(size.width * fraction, size.height), cornerRadius = CornerRadius(2.dp.toPx()))
            })
        Canvas(Modifier.fillMaxSize()) {
            // The thumb follows actual/preview progress; there is no fixed 100% endpoint marker.
            drawCircle(theme.accent, 4.5.dp.toPx(), Offset(size.width * fraction, size.height / 2f))
        }
    }
}

@UnstableApi
@Composable
internal fun ThorQueuePage(
    vm: ThorShellViewModel,
    registry: ThorFocusRegistry,
    playback: ThorPlaybackState,
    onBack: () -> Unit,
    pageActions: ThorPageActionBindings? = null,
) {
    val list = rememberLazyListState()
    val currentIndex = playback.queue.indexOfFirst { it.entryId == playback.currentEntryId }
    val scene = vm.navigation.value.sceneKey
    val keys = playback.queue.map { "queue:${it.entryId}" }
    val binding = BindThorListFocus(registry, scene, keys, playback.queue.map { it.item }, list, vm) { index ->
        playback.queue.getOrNull(index)?.let { vm.playback.select(it.entryId) }
    }
    val locateCurrent = { if (currentIndex >= 0) binding.request(currentIndex) }
    if (currentIndex >= 0) BindThorPageAction(pageActions, "queue:current", locateCurrent)
    Column(Modifier.fillMaxSize()) {
        PlayerPageHeader(registry, "queue:back", "播放队列", "共 ${playback.queue.size} 首", onBack)
        if (playback.queue.isEmpty()) PlayerEmpty("播放队列为空")
        else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = list,
                verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp)) {
                itemsIndexed(playback.queue, key = { index, _ -> keys[index] }) { index, entry ->
                    val key = "queue:${entry.entryId}"
                    val current = entry.entryId == playback.currentEntryId
                    ThorMusicRow(registry, key, entry.item,
                        trailing = when {
                            entry.pendingRemoval -> "播完移出"
                            current && playback.playing -> "正在播放"
                            current -> "已暂停"
                            else -> null
                        }, onClick = { binding.activate(index) }, onFocused = { binding.onFocused(index) },
                        onMenu = { anchor ->
                            vm.openMenu(ThorMenuSubject(entry.item, key, queueEntryId = entry.entryId), anchor)
                        })
                }
            }
        }
    }
}

@UnstableApi
@Composable
internal fun ThorLyricsPage(vm: ThorShellViewModel, registry: ThorFocusRegistry, onBack: () -> Unit, pageActions: ThorPageActionBindings? = null) {
    val theme = LocalThorTheme.current
    val data by vm.lyrics.collectAsStateWithLifecycle()
    val playback by vm.playback.state.collectAsStateWithLifecycle()
    val lines = data.lyricLine.lines
    val current = lines.indexOfLast { it.start <= playback.positionMs }.coerceAtLeast(0)
    val list = rememberLazyListState()
    val loading = data.source == LyricSource.Loading
    val available = !loading && data.source != LyricSource.Empty && !data.isPureMusic && lines.isNotEmpty()
    val scene = vm.navigation.value.sceneKey
    val songIdentity = playback.current?.let { "${it.isLocal}:${it.id}" } ?: "none"
    val keys = if (available) lines.mapIndexed { index, line -> "lyric:$songIdentity:${line.start}:${line.end}:$index" } else emptyList()
    val binding = BindThorListFocus(registry, scene, keys, List(keys.size) { null }, list, vm) { index ->
        lines.getOrNull(index)?.let { vm.playback.seek(it.start.toLong()) }
    }
    val locateCurrent = { binding.request(current) }
    if (available) BindThorPageAction(pageActions, "lyrics:current", locateCurrent)
    Column(Modifier.fillMaxSize()) {
        PlayerPageHeader(registry, "lyrics:back", "歌词", null, onBack)
        when {
            playback.current == null -> PlayerEmpty("没有正在播放的歌曲")
            loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = theme.accent, modifier = Modifier.size(30.dp))
            }
            !available -> PlayerEmpty(if (data.isPureMusic) "纯音乐" else "暂无歌词")
            else -> {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = list,
                    verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp)) {
                    itemsIndexed(lines, key = { index, _ -> keys[index] }) { index, line ->
                        val key = keys[index]
                        val words = when (line) {
                            is KaraokeLine -> line.syllables.joinToString("") { it.content }
                            is SyncedLine -> line.content
                            else -> ""
                        }
                        val translation = when (line) {
                            is KaraokeLine -> line.translation
                            is SyncedLine -> line.translation
                            else -> null
                        }
                        val action = { binding.activate(index) }
                        Row(Modifier.fillMaxWidth().heightIn(min = 57.dp)
                            .onFocusChanged { if (it.isFocused) binding.onFocused(index) }
                            .thorFocus(registry, key, onClick = action)
                            .thorMaterial(ThorSurfaceRole.MusicRow, focused = registry.selectedKey == key, selected = current == index)
                            .clickable(role = Role.Button, onClick = { registry.select(key); action() })
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(playbackTime(line.start.toLong()), color = theme.secondary, fontSize = 11.sp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(words.ifBlank { "♪" }, color = if (current == index) theme.selectedText else theme.text,
                                    fontSize = 17.sp, fontWeight = if (current == index) FontWeight.Medium else FontWeight.Normal)
                                if (!translation.isNullOrBlank()) Text(translation, color = theme.secondary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@UnstableApi
@Composable
internal fun ThorTimerPage(
    vm: ThorShellViewModel,
    registry: ThorFocusRegistry,
    playback: ThorPlaybackState,
    onBack: () -> Unit,
) {
    val theme = LocalThorTheme.current
    BindThorOptionFocus(vm, registry,
        (if (playback.connected) listOf(15, 30, 45, 60, 90).map { "timer:$it" } else emptyList()) +
            (if (playback.connected && playback.current != null) listOf("timer:end") else emptyList()) +
            (if (playback.timerActive) listOf("timer:clear") else emptyList()))
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PlayerPageHeader(registry, "timer:back", "定时停止", null, onBack)
        Text(if (playback.timerActive) "${timerLabel(playback)}后停止播放" else "选择停止时间",
            color = theme.secondary, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
        listOf(listOf(15, 30, 45), listOf(60, 90)).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { minutes ->
                    ThorButton(registry, "timer:$minutes", "$minutes 分钟", Modifier.weight(1f),
                        enabled = playback.connected, onClick = { vm.playback.startTimer(minutes); onBack() })
                }
                if (row.size == 2) ThorButton(registry, "timer:end", "本曲结束", Modifier.weight(1f),
                    enabled = playback.connected && playback.current != null,
                    onClick = { vm.playback.startTimer(SleepTimer.END_OF_TRACK); onBack() })
            }
        }
        ThorButton(registry, "timer:clear", "关闭定时", modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            icon = Icons.Outlined.TimerOff, enabled = playback.timerActive,
            onClick = { vm.playback.clearTimer(); onBack() })
    }
}

@Composable
private fun PlayerPageHeader(registry: ThorFocusRegistry, key: String, title: String, status: String?, onBack: () -> Unit) {
    val theme = LocalThorTheme.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).thorMaterial(ThorSurfaceRole.Header).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThorButton(registry, key, "", icon = Icons.Outlined.ChevronLeft, flat = true, contentDescription = "返回", onClick = onBack)
        Text(title, Modifier.weight(1f), color = theme.text, fontSize = theme.typography.pageTitle, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (status != null) Text(status, color = theme.secondary, fontSize = 13.sp)
    }
}

@Composable
private fun PlayerEmpty(message: String) {
    val theme = LocalThorTheme.current
    Box(Modifier.fillMaxWidth().heightIn(min = 150.dp), contentAlignment = Alignment.Center) {
        Text(message, color = theme.secondary, fontSize = 15.sp)
    }
}

private fun playbackTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1_000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

private fun timerLabel(playback: ThorPlaybackState): String =
    if (playback.timerRemainingMs > 0) playbackTime(playback.timerRemainingMs) else "本曲结束"

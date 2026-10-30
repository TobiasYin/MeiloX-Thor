package com.ljyh.mei.thor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    var wasSeeking by remember { mutableStateOf(false) }
    val song = playback.current
    val item = song?.toThorItem()
    val liked = song?.let { current ->
        if (current.isLocal) library[ThorCollection.LikedSongs].any { it.key == item?.key }
        else library.songLikes[current.id] == true
    } == true
    DefaultFocus(vm, registry, "player:toggle", playback.current != null)

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThorButton(registry, "player:back", "返回", icon = Icons.AutoMirrored.Outlined.ArrowBack, onClick = onBack)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(song?.title ?: "没有正在播放的歌曲", color = theme.text, fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (song != null) Text(song.artists.joinToString(" / ") { it.name }, color = theme.secondary,
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ThorButton(registry, "player:like", if (liked) "已喜欢" else "喜欢",
                icon = if (liked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                selected = liked, enabled = song != null && seek == null, onClick = vm::likeCurrent)
            ThorButton(registry, "player:more", "更多", icon = Icons.Outlined.MoreHoriz,
                enabled = item != null && seek == null, onClick = {
                    item?.let { frozen ->
                        val bounds = registry.current?.bounds
                        vm.openMenu(ThorMenuSubject(frozen, "player:more", queueEntryId = playback.currentEntryId),
                            ThorMenuAnchor(bounds?.left ?: 0f, bounds?.bottom ?: 64f))
                    }
                })
        }

        if (song == null) {
            PlayerEmpty(if (playback.connected) "从音乐库选择歌曲开始播放" else "正在连接播放器")
            return@Column
        }

        Column(Modifier.fillMaxWidth().thorMaterial(ThorSurfaceRole.Panel).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val editProgress = { if (seek == null) vm.beginSeek() }
            ThorActionButton(
                onClick = editProgress,
                modifier = Modifier.fillMaxWidth().thorFocus(registry, "player:progress",
                    enabled = playback.durationMs > 0, onClick = editProgress),
                enabled = playback.durationMs > 0,
                selected = seek != null,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
            ) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(playbackTime(seek ?: playback.positionMs), fontSize = 12.sp)
                        Text(playbackTime(playback.durationMs), fontSize = 12.sp)
                    }
                    LinearProgressIndicator(
                        progress = { ((seek ?: playback.positionMs).toFloat() / playback.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = theme.accent, trackColor = theme.inset,
                        drawStopIndicator = {},
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                ThorButton(registry, "player:previous", "上一首", modifier = Modifier.widthIn(min = 108.dp),
                    icon = Icons.Outlined.SkipPrevious, enabled = playback.connected && seek == null, onClick = vm.playback::previous)
                ThorButton(registry, "player:toggle", if (playback.playing) "暂停" else "播放",
                    modifier = Modifier.widthIn(min = 118.dp).heightIn(min = 56.dp),
                    icon = if (playback.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    primary = true, enabled = playback.connected && seek == null, onClick = vm.playback::toggle)
                ThorButton(registry, "player:next", "下一首", modifier = Modifier.widthIn(min = 108.dp),
                    icon = Icons.Outlined.SkipNext, enabled = playback.connected && seek == null, onClick = vm.playback::next)
            }
        }

        if (seek != null) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ThorButton(registry, "seek:back", "−10 秒", Modifier.weight(1f), onClick = { vm.adjustSeek(-10_000) })
                ThorButton(registry, "seek:cancel", "取消", Modifier.weight(1f), onClick = vm::cancelSeek)
                ThorButton(registry, "seek:apply", "应用", Modifier.weight(1f), primary = true, onClick = vm::applySeek)
                ThorButton(registry, "seek:forward", "+10 秒", Modifier.weight(1f), onClick = { vm.adjustSeek(10_000) })
            }
        } else {
            val next = playback.next
            ThorActionButton(
                onClick = vm::toggleQueue,
                modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp)
                    .thorFocus(registry, "player:queue", onClick = vm::toggleQueue),
                contentPadding = PaddingValues(horizontal = 11.dp, vertical = 7.dp),
            ) {
                if (next != null) ThorCover(next.item, Modifier.size(40.dp))
                else Icon(Icons.Outlined.QueueMusic, null, Modifier.size(29.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (next != null) "下一首 · ${next.metadata.title}" else "没有待播歌曲", fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (next != null) Text(next.metadata.artists.joinToString(" / ") { it.name }, color = theme.secondary,
                        fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("队列 · ${playback.queue.size}", fontSize = 13.sp)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp))
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThorButton(registry, "player:mode", playback.mode, Modifier.weight(1f),
                icon = if (playback.mode == "随机播放") Icons.Outlined.Shuffle else Icons.Outlined.Repeat,
                enabled = seek == null, onClick = vm.playback::switchMode)
            ThorButton(registry, "player:lyrics", "歌词定位", Modifier.weight(1f), icon = Icons.Outlined.Lyrics,
                enabled = seek == null, onClick = vm::showLyrics)
            ThorButton(registry, "player:timer", if (playback.timerActive) timerLabel(playback) else "定时停止",
                Modifier.weight(1f), icon = Icons.Outlined.Timer, selected = playback.timerActive,
                enabled = seek == null, onClick = vm::showTimer)
        }
        if (playback.error != null) Text("播放失败，请重试或切换歌曲", color = theme.secondary, fontSize = 12.sp)
    }
    LaunchedEffect(seek != null) {
        if (seek != null) { withFrameNanos { }; registry.request("seek:apply") }
        else if (wasSeeking) { withFrameNanos { }; registry.request("player:progress") }
        wasSeeking = seek != null
    }
}

@UnstableApi
@Composable
internal fun ThorQueuePage(
    vm: ThorShellViewModel,
    registry: ThorFocusRegistry,
    playback: ThorPlaybackState,
    onBack: () -> Unit,
) {
    val list = rememberLazyListState()
    val currentIndex = playback.queue.indexOfFirst { it.entryId == playback.currentEntryId }
    val scene = vm.navigation.value.sceneKey
    val keys = playback.queue.map { "queue:${it.entryId}" }
    val savedFocus = remember(scene) { vm.rememberedFocus(scene) }
    val binding = BindThorListFocus(registry, scene, keys, playback.queue.map { it.item }, list, vm) { index ->
        playback.queue.getOrNull(index)?.let { vm.playback.select(it.entryId) }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PlayerPageHeader(registry, "queue:back", "播放队列", "共${playback.queue.size}首", onBack)
        if (playback.queue.isEmpty()) PlayerEmpty("播放队列为空")
        else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list,
                verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 5.dp)) {
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ThorButton(registry, "queue:current", "定位当前", icon = Icons.Outlined.MyLocation,
                    enabled = currentIndex >= 0, onClick = { if (currentIndex >= 0) binding.request(currentIndex) })
            }
        }
    }
    LaunchedEffect(Unit) {
        if (currentIndex >= 0 && savedFocus !in keys) binding.request(currentIndex)
    }
}

@UnstableApi
@Composable
internal fun ThorLyricsPage(vm: ThorShellViewModel, registry: ThorFocusRegistry, onBack: () -> Unit) {
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
    val savedFocus = remember(scene) { vm.rememberedFocus(scene) }
    val binding = BindThorListFocus(registry, scene, keys, List(keys.size) { null }, list, vm) { index ->
        lines.getOrNull(index)?.let { vm.playback.seek(it.start.toLong()) }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PlayerPageHeader(registry, "lyrics:back", "歌词", null, onBack)
        when {
            playback.current == null -> PlayerEmpty("没有正在播放的歌曲")
            loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = theme.accent, modifier = Modifier.size(30.dp))
            }
            !available -> PlayerEmpty(if (data.isPureMusic) "纯音乐" else "暂无歌词")
            else -> {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                            .thorMaterial(ThorSurfaceRole.Panel, focused = registry.selectedKey == key, selected = current == index)
                            .clickable(role = Role.Button, onClick = { registry.select(key); action() })
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(playbackTime(line.start.toLong()), color = theme.secondary, fontSize = 11.sp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(words.ifBlank { "♪" }, color = if (current == index) theme.selectedText else theme.text,
                                    fontSize = 17.sp, fontWeight = if (current == index) FontWeight.SemiBold else FontWeight.Normal)
                                if (!translation.isNullOrBlank()) Text(translation, color = theme.secondary, fontSize = 12.sp)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ThorButton(registry, "lyrics:current", "定位当前", icon = Icons.Outlined.MyLocation, onClick = {
                        binding.request(current)
                    })
                }
            }
        }
    }
    LaunchedEffect(data, songIdentity) {
        if (available && savedFocus !in keys) binding.request(current)
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
    DefaultFocus(vm, registry, "timer:15", playback.connected)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PlayerPageHeader(registry, "timer:back", "定时停止", null, onBack)
        Text(if (playback.timerActive) "${timerLabel(playback)}后停止播放" else "选择停止时间",
            color = theme.secondary, fontSize = 15.sp, modifier = Modifier.padding(vertical = 5.dp))
        listOf(listOf(15, 30, 45), listOf(60, 90)).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { minutes ->
                    ThorButton(registry, "timer:$minutes", "$minutes 分钟", Modifier.weight(1f),
                        enabled = playback.connected, onClick = { vm.playback.startTimer(minutes); onBack() })
                }
                if (row.size == 2) ThorButton(registry, "timer:end", "本曲结束", Modifier.weight(1f),
                    enabled = playback.connected && playback.current != null,
                    onClick = { vm.playback.startTimer(SleepTimer.END_OF_TRACK); onBack() })
            }
        }
        ThorButton(registry, "timer:clear", "关闭定时", modifier = Modifier.fillMaxWidth(),
            icon = Icons.Outlined.TimerOff, enabled = playback.timerActive,
            onClick = { vm.playback.clearTimer(); onBack() })
    }
}

@Composable
private fun PlayerPageHeader(registry: ThorFocusRegistry, key: String, title: String, status: String?, onBack: () -> Unit) {
    val theme = LocalThorTheme.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ThorButton(registry, key, "返回", icon = Icons.AutoMirrored.Outlined.ArrowBack, onClick = onBack)
        Text(title, Modifier.weight(1f), color = theme.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
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

@UnstableApi
@Composable
private fun DefaultFocus(vm: ThorShellViewModel, registry: ThorFocusRegistry, key: String, ready: Boolean = true) {
    val scene = vm.navigation.value.sceneKey
    LaunchedEffect(scene, ready) {
        if (ready && vm.rememberedFocus(scene) == null) {
            vm.rememberFocus(scene, key)
            withFrameNanos { }
            registry.request(key)
        }
    }
}

private fun playbackTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1_000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

private fun timerLabel(playback: ThorPlaybackState): String =
    if (playback.timerRemainingMs > 0) playbackTime(playback.timerRemainingMs) else "本曲结束"

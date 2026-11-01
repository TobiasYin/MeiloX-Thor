package com.ljyh.mei.thor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.thor.ThorShellViewModel
import com.ljyh.mei.thor.components.ThorActionButton
import com.ljyh.mei.thor.components.ThorSurfaceRole
import com.ljyh.mei.thor.components.thorMaterial
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.playback.ThorPlaybackState
import com.ljyh.mei.thor.theme.LocalThorTheme

private const val QUALITY_SCENE = "quality:options"

internal fun thorActualQualityLabel(actual: MusicQuality?, localSource: Boolean): String =
    if (localSource) "本地文件" else actual?.explanation ?: "—"

/** A compact touch entry; the player's sole controller target remains player:toggle. */
@Composable
internal fun ThorPlayerQualityButton(
    registry: ThorFocusRegistry, requested: MusicQuality, actual: MusicQuality?, localSource: Boolean,
    enabled: Boolean = true, onClick: () -> Unit,
) {
    val label = if (localSource) "本地" else actual?.explanation ?: "音质"
    val spokenActual = if (localSource) "本地文件" else actual?.explanation ?: "未知"
    ThorButton(registry, "player:quality", label, Modifier.widthIn(min = 48.dp, max = 96.dp).height(48.dp)
        .testTag("player-quality"), flat = true, enabled = enabled,
        contentDescription = "播放音质，当前${spokenActual}，默认${requested.explanation}",
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), onClick = onClick)
}

/** All eight global preferences remain selectable independently of this track's current source. */
@Composable
internal fun ThorQualitySelector(
    registry: ThorFocusRegistry,
    memory: ThorListFocusMemory,
    requested: MusicQuality,
    actual: MusicQuality?,
    localSource: Boolean,
    onSelect: (MusicQuality) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalThorTheme.current
    val qualities = MusicQuality.entries
    val keys = remember { qualities.map { "$QUALITY_SCENE:${it.name}" } }
    val list = rememberLazyListState()
    // Each opening starts at the requested global default. Subsequent cursor moves, including
    // opening/closing a page-action menu, retain the selector's own logical selection.
    val entryMemory = remember(memory) { object : ThorListFocusMemory {
        private var cursor = "$QUALITY_SCENE:${requested.name}"
        override fun readFocus(scene: String) = cursor
        override fun readScroll(scene: String): Pair<String, Int>? = null
        override fun saveFocus(scene: String, key: String) { cursor = key; memory.saveFocus(scene, key) }
        override fun saveScroll(scene: String, key: String, offset: Int) = memory.saveScroll(scene, key, offset)
        override fun preview(item: ThorItem?) = Unit
    } }
    val binding = BindThorListFocus(registry, QUALITY_SCENE, keys, qualities.map { null }, list, entryMemory) { index ->
        qualities.getOrNull(index)?.let(onSelect)
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).thorMaterial(ThorSurfaceRole.Header).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThorButton(registry, "quality:back", "", icon = Icons.Outlined.ChevronLeft, flat = true,
                contentDescription = "返回", onClick = onBack)
            Text("默认 ${requested.explanation} · 当前 ${thorActualQualityLabel(actual, localSource)}",
                Modifier.weight(1f).testTag("quality-summary"), color = theme.secondary,
                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = list,
            verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp)) {
            itemsIndexed(qualities, key = { index, _ -> keys[index] }) { index, quality ->
                val default = quality == requested
                val current = !localSource && quality == actual
                ThorActionButton(onClick = { binding.activate(index) },
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag("quality-choice:${quality.name}")
                        .thorFocus(registry, keys[index], onClick = { binding.activate(index) })
                        .semantics {
                            selected = default
                            stateDescription = listOfNotNull("默认音质".takeIf { default }, "当前播放".takeIf { current }).joinToString("，")
                        },
                    selected = default, focused = registry.selectedKey == keys[index],
                    role = ThorSurfaceRole.MusicRow, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
                    Text(quality.explanation, Modifier.weight(1f), color = theme.text, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (current) Text("当前", Modifier.testTag("quality-current:${quality.name}"), color = theme.secondary, fontSize = 12.sp)
                    if (default) {
                        Spacer(Modifier.width(10.dp))
                        Icon(thorLineIcon(Icons.Outlined.Check), null, Modifier.size(16.dp), tint = theme.accent)
                        Spacer(Modifier.width(4.dp))
                        Text("默认", color = theme.secondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
@UnstableApi
internal fun ThorQualityPage(vm: ThorShellViewModel, registry: ThorFocusRegistry, playback: ThorPlaybackState, onBack: () -> Unit) {
    val memory = remember(vm) { object : ThorListFocusMemory {
        override fun readFocus(scene: String) = vm.rememberedFocus(scene)
        override fun readScroll(scene: String) = vm.rememberedListScroll(scene)
        override fun saveFocus(scene: String, key: String) = vm.rememberFocus(scene, key)
        override fun saveScroll(scene: String, key: String, offset: Int) = vm.rememberListScroll(scene, key, offset)
        override fun preview(item: ThorItem?) = Unit
    } }
    ThorQualitySelector(registry, memory, playback.requestedQuality, playback.actualQuality, playback.localQualitySource,
        onSelect = vm::selectQuality, onBack = onBack)
}

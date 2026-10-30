package com.ljyh.mei.thor.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.thor.ThorShellViewModel
import com.ljyh.mei.thor.model.ThorDirection
import com.ljyh.mei.thor.model.ThorItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ThorListFocusBinding internal constructor(
    val onFocused: (Int) -> Unit,
    val activate: (Int) -> Unit,
    val request: (Int) -> Unit,
)

/**
 * Direction navigation uses the complete stable-key list rather than only composed LazyColumn rows.
 * Rows still register those same keys with [ThorFocusRegistry]. Null items are useful for lyrics.
 * A fresh press may leave a list; a held direction stops at its boundary until released.
 */
@Composable
@UnstableApi
fun BindThorListFocus(
    registry: ThorFocusRegistry,
    scene: String,
    keys: List<String>,
    items: List<ThorItem?>,
    listState: LazyListState,
    vm: ThorShellViewModel,
    onActivate: (Int) -> Unit,
): ThorListFocusBinding {
    val scope = rememberCoroutineScope()
    val owner = remember(scene, registry) { Any() }
    // Capture before a parent fallback can temporarily focus a header during scene entry.
    val entryFocus = remember(scene) { vm.rememberedFocus(scene) }
    val entryPosition = remember(scene) { vm.rememberedListScroll(scene) }
    var restored by remember(scene) { mutableStateOf(false) }
    var lastIndex by remember(scene) { mutableIntStateOf(0) }
    var focusJob by remember(scene) { mutableStateOf<Job?>(null) }
    val currentKeys by rememberUpdatedState(keys)
    val keyPositions = remember(keys) { keys.withIndex().associate { it.value to it.index } }
    val fallbackRowHeightPx = with(LocalDensity.current) { 56.dp.roundToPx() }

    fun rememberSelection(index: Int) {
        val key = keys.getOrNull(index) ?: return
        lastIndex = index
        vm.rememberFocus(scene, key)
        items.getOrNull(index)?.let(vm::preview)
    }

    fun activate(index: Int) {
        if (index !in keys.indices) return
        rememberSelection(index)
        onActivate(index)
    }

    fun requestItem(index: Int) {
        val key = keys.getOrNull(index) ?: return
        rememberSelection(index)
        // Freeze the target and action now, before the old native focus can catch up with a scroll.
        registry.prepareFocus(owner, key, items.getOrNull(index)) { activate(index) }
        focusJob?.cancel()
        focusJob = scope.launch {
            val layout = listState.layoutInfo
            if (layout.visibleItemsInfo.none { it.index == index }) {
                val viewport = (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(0)
                val rowHeight = layout.visibleItemsInfo.lastOrNull()?.size ?: fallbackRowHeightPx
                val movingDown = index > listState.firstVisibleItemIndex
                listState.scrollToItem(index, if (movingDown) -(viewport - rowHeight).coerceAtLeast(0) else 0)
            }
            // A newly scrolled row registers in composition before native focus is requested.
            snapshotFlow { registry.contains(key) }.first { it }
            withFrameNanos { }
            if (registry.selectedKey == key && currentKeys.contains(key)) registry.request(key)
        }
    }

    SideEffect {
        registry.installListMove(owner) { direction, repeated ->
            val index = keyPositions[registry.selectedKey] ?: -1
            if (index < 0) null
            else if (direction == ThorDirection.Left || direction == ThorDirection.Right) {
                if (repeated) false else null
            } else {
                val target = index + if (direction == ThorDirection.Up) -1 else 1
                if (target !in keys.indices) {
                    if (repeated) false else null
                } else { requestItem(target); true }
            }
        }
    }

    DisposableEffect(owner) {
        onDispose {
            currentKeys.getOrNull(listState.firstVisibleItemIndex)?.let { firstKey ->
                vm.rememberListScroll(scene, firstKey, listState.firstVisibleItemScrollOffset)
            }
            registry.selectedKey?.takeIf(currentKeys::contains)?.let { vm.rememberFocus(scene, it) }
            focusJob?.cancel()
            registry.clearPreparedFocus(owner)
            registry.removeListMove(owner)
        }
    }

    LaunchedEffect(registry.selectedKey, keys) {
        keys.indexOf(registry.selectedKey).takeIf { it >= 0 }?.let(::rememberSelection)
    }

    LaunchedEffect(scene, keys) {
        focusJob?.cancel()
        if (keys.isEmpty()) {
            registry.clearPreparedFocus(owner)
            vm.clearPreview()
            withFrameNanos { }
            if (registry.selectedKey == null || !registry.contains(registry.selectedKey)) registry.requestFirst()
            return@LaunchedEffect
        }
        val saved = if (!restored) entryFocus else vm.rememberedFocus(scene)
        val selected = keys.indexOf(registry.selectedKey)
        val index = keys.indexOf(saved).takeIf { it >= 0 } ?: selected.takeIf { it >= 0 }
            ?: lastIndex.coerceIn(keys.indices)
        val restoringAnotherRegion = !restored && entryFocus != null && entryFocus !in keyPositions && registry.contains(entryFocus)
        val ownsFocus = !restoringAnotherRegion &&
            (!restored || selected >= 0 || registry.selectedKey == null || !registry.contains(registry.selectedKey))
        if (!restored) {
            entryPosition?.let { (firstKey, offset) ->
                keys.indexOf(firstKey).takeIf { it >= 0 }?.let { listState.scrollToItem(it, offset) }
            }
        }
        restored = true
        if (ownsFocus) requestItem(index)
    }

    return ThorListFocusBinding(::rememberSelection, ::activate, ::requestItem)
}

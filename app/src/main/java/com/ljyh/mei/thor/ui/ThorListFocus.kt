package com.ljyh.mei.thor.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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

/** The production binder can also be exercised without a ViewModel/account in native fixtures. */
internal interface ThorListFocusMemory {
    fun readFocus(scene: String): String?
    fun readScroll(scene: String): Pair<String, Int>?
    fun saveFocus(scene: String, key: String)
    fun saveScroll(scene: String, key: String, offset: Int)
    fun preview(item: ThorItem?)
}

/** A complete stable-key list is one closed region, including currently unrealized rows. */
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
    val memory = remember(vm) { object : ThorListFocusMemory {
        override fun readFocus(scene: String) = vm.rememberedFocus(scene)
        override fun readScroll(scene: String) = vm.rememberedListScroll(scene)
        override fun saveFocus(scene: String, key: String) = vm.rememberFocus(scene, key)
        override fun saveScroll(scene: String, key: String, offset: Int) = vm.rememberListScroll(scene, key, offset)
        override fun preview(item: ThorItem?) { if (item == null) vm.clearPreview() else vm.preview(item) }
    } }
    return BindThorListFocus(registry, scene, keys, items, listState, memory, onActivate)
}

@Composable
internal fun BindThorListFocus(
    registry: ThorFocusRegistry,
    scene: String,
    keys: List<String>,
    items: List<ThorItem?>,
    listState: LazyListState,
    memory: ThorListFocusMemory,
    onActivate: (Int) -> Unit,
): ThorListFocusBinding {
    val coroutineScope = rememberCoroutineScope()
    val owner = remember(scene, registry) { Any() }
    val entryFocus = remember(scene, memory) { memory.readFocus(scene) }
    val entryPosition = remember(scene, memory) { memory.readScroll(scene) }
    var restored by remember(scene) { mutableStateOf(false) }
    var focusJob by remember(scene) { mutableStateOf<Job?>(null) }
    val currentKeys by key(owner) { rememberUpdatedState(keys) }
    val keyPositions = remember(keys) { keys.withIndex().associate { it.value to it.index } }
    val fallbackRowHeightPx = with(LocalDensity.current) { 56.dp.roundToPx() }

    fun rememberSelection(index: Int) {
        val key = keys.getOrNull(index) ?: return
        memory.saveFocus(scene, key)
        memory.preview(items.getOrNull(index))
    }

    fun activate(index: Int) {
        if (index !in keys.indices || registry.blocked) return
        rememberSelection(index)
        onActivate(index)
    }

    fun requestItem(index: Int) {
        val key = keys.getOrNull(index) ?: return
        if (registry.blocked || !registry.isAllowed(key)) return
        // Freeze identity/action before an offscreen row exists. Confirm never uses an old row.
        if (!registry.prepareFocus(owner, key, items.getOrNull(index)) { activate(index) }) return
        rememberSelection(index)
        focusJob?.cancel()
        focusJob = coroutineScope.launch {
            val layout = listState.layoutInfo
            if (layout.visibleItemsInfo.none { it.index == index }) {
                val viewport = (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(0)
                val rowHeight = layout.visibleItemsInfo.lastOrNull()?.size ?: fallbackRowHeightPx
                val movingDown = index > listState.firstVisibleItemIndex
                listState.scrollToItem(index, if (movingDown) -(viewport - rowHeight).coerceAtLeast(0) else 0)
            }
            snapshotFlow { registry.contains(key) }.first { it }
            withFrameNanos { }
            if (registry.selectedKey == key && currentKeys.contains(key)) registry.request(key)
        }
    }

    SideEffect {
        registry.installScope(owner, ThorFocusScope(scene, keys, ThorFocusLayout.Vertical))
        registry.installListMove(owner) { direction, _ ->
            if (direction == ThorDirection.Left || direction == ThorDirection.Right || keys.isEmpty()) false
            else {
                val index = keyPositions[registry.selectedKey]
                val target = if (index == null) 0 else index + if (direction == ThorDirection.Up) -1 else 1
                if (target !in keys.indices) false else { requestItem(target); true }
            }
        }
    }

    DisposableEffect(owner) {
        onDispose {
            currentKeys.getOrNull(listState.firstVisibleItemIndex)?.let { firstKey ->
                memory.saveScroll(scene, firstKey, listState.firstVisibleItemScrollOffset)
            }
            registry.selectedKey?.takeIf(currentKeys::contains)?.let { memory.saveFocus(scene, it) }
            focusJob?.cancel()
            registry.clearPreparedFocus(owner)
            registry.removeListMove(owner)
            registry.removeScope(owner)
        }
    }

    LaunchedEffect(registry.selectedKey, keys) {
        keys.indexOf(registry.selectedKey).takeIf { it >= 0 }?.let(::rememberSelection)
    }

    LaunchedEffect(scene, keys, registry.blocked) {
        focusJob?.cancel()
        if (registry.blocked) return@LaunchedEffect
        if (keys.isEmpty()) {
            registry.clearPreparedFocus(owner)
            memory.preview(null)
            // Loading may start empty. Do not mark restored or fall through to chrome controls.
            return@LaunchedEffect
        }
        val saved = if (!restored) entryFocus else memory.readFocus(scene)
        val selected = keys.indexOf(registry.selectedKey)
        val index = thorRestoredListIndex(keys, saved, selected.takeIf { restored && it >= 0 })
        // LaunchedEffect can start before SideEffect installs this region. Do not let a
        // non-suspending restore (saved focus without saved scroll) permanently lose its
        // request through requestItem's scope guard. This observes actual readiness.
        val targetKey = keys[index]
        snapshotFlow { registry.isAllowed(targetKey) }.first { it }
        if (!restored) {
            val validPosition = entryPosition?.takeIf { (firstKey, _) -> firstKey in keyPositions }
            if (entryFocus !in keyPositions && validPosition == null) {
                // SaveableStateHolder can retain an old LazyListState after fresh-scene memory
                // was cleared or its saved keys disappeared. Reset even a partial first row.
                listState.scrollToItem(0, 0)
            } else validPosition?.let { (firstKey, offset) ->
                keys.indexOf(firstKey).takeIf { it >= 0 }?.let { listState.scrollToItem(it, offset) }
            }
        }
        restored = true
        requestItem(index)
    }

    return ThorListFocusBinding(::rememberSelection, ::activate, ::requestItem)
}

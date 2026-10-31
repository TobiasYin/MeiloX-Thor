package com.ljyh.mei.thor.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ljyh.mei.thor.components.ThorSurfaceRole
import com.ljyh.mei.thor.components.thorMaterial
import com.ljyh.mei.thor.model.ThorDirection
import com.ljyh.mei.thor.theme.LocalThorTheme

/** A closed, logical action list; LazyColumn realization never limits controller movement. */
@Composable
internal fun ThorPageActionMenu(
    actions: List<ThorPageAction>,
    selectedId: String?,
    registry: ThorFocusRegistry,
    onSelect: (String) -> Unit,
    onActivate: (ThorPageAction) -> Unit,
    onClose: () -> Unit,
) {
    val theme = LocalThorTheme.current
    val owner = remember(registry) { Any() }
    val list = rememberLazyListState()
    val latestSelected by rememberUpdatedState(selectedId)
    val latestActivate by rememberUpdatedState(onActivate)
    val latestSelect by rememberUpdatedState(onSelect)
    val enabled = actions.filter { it.enabled }
    val keys = actions.map { "page-action:${it.id}" }
    val entrance = remember { Animatable(if (theme.reduceMotion) 1f else 0f) }
    LaunchedEffect(theme.reduceMotion) {
        if (theme.reduceMotion) entrance.snapTo(1f)
        else entrance.animateTo(1f, tween(theme.menuEnterMs))
    }
    SideEffect {
        registry.installScope(owner, ThorFocusScope("page-actions", keys))
        registry.installListMove(owner) { direction, _ ->
            if (direction != ThorDirection.Up && direction != ThorDirection.Down) false
            else {
                val logicalId = registry.selectedKey?.removePrefix("page-action:") ?: latestSelected
                val index = enabled.indexOfFirst { it.id == logicalId }
                val target = if (index < 0) enabled.firstOrNull()
                    else enabled.getOrNull(index + if (direction == ThorDirection.Up) -1 else 1)
                if (target == null) false else {
                    registry.prepareFocus(owner, "page-action:${target.id}", null) { latestActivate(target) }
                    latestSelect(target.id)
                    true
                }
            }
        }
    }
    DisposableEffect(owner) {
        onDispose { registry.removeListMove(owner); registry.clearPreparedFocus(owner); registry.removeScope(owner) }
    }
    LaunchedEffect(selectedId, actions) {
        val index = actions.indexOfFirst { it.id == selectedId && it.enabled }
        if (index < 0) return@LaunchedEffect
        val action = actions[index]
        registry.prepareFocus(owner, keys[index], null) { latestActivate(action) }
        if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index)
        withFrameNanos { }
        registry.request(keys[index])
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().background(theme.scrim).pointerInput(Unit) { detectTapGestures { onClose() } })
        Column(Modifier.width(minOf(340.dp, maxWidth - 24.dp))
            .heightIn(max = maxHeight - 24.dp)
            .graphicsLayer { alpha = entrance.value; translationY = (1f - entrance.value) * 12.dp.toPx() }
            .thorMaterial(ThorSurfaceRole.Overlay)
            .semantics { paneTitle = "页面操作" }
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); waitForUpOrCancellation() } }) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("页面操作", Modifier.weight(1f), color = theme.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                ThorButton(registry, "page-actions:close", "", icon = Icons.Outlined.Close, flat = true,
                    contentDescription = "关闭页面操作", onClick = onClose)
            }
            HorizontalDivider(color = theme.outline)
            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), state = list,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                itemsIndexed(actions, key = { _, action -> action.id }) { index, action ->
                    ThorButton(registry, keys[index], action.title, Modifier.fillMaxWidth().height(48.dp),
                        flat = true, selected = action.id == selectedId, enabled = action.enabled,
                        onClick = { onSelect(action.id); onActivate(action) })
                }
            }
            HorizontalDivider(color = theme.outline)
            Text("↑↓ 选择    A 确认    B 返回", Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                color = theme.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

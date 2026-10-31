package com.ljyh.mei.thor.menu

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.thor.ThorShellViewModel
import com.ljyh.mei.thor.components.ThorActionButton
import com.ljyh.mei.thor.components.ThorSurfaceRole
import com.ljyh.mei.thor.components.thorMaterial
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.theme.LocalThorTheme
import com.ljyh.mei.thor.ui.ThorButton
import com.ljyh.mei.thor.ui.ThorCover
import com.ljyh.mei.thor.ui.thorLineIcon
import com.ljyh.mei.thor.ui.ThorDesignIcons
import com.ljyh.mei.thor.ui.ThorFocusRegistry
import com.ljyh.mei.thor.ui.ThorFocusScope
import com.ljyh.mei.thor.ui.ThorFocusLayout
import com.ljyh.mei.thor.ui.thorFocus
import kotlin.math.roundToInt

/** One anchored surface for every level; no business operation is owned by the menu composition. */
@UnstableApi
@Composable
fun ThorContextMenu(vm: ThorShellViewModel, menu: ThorMenuState, registry: ThorFocusRegistry) {
    val theme = LocalThorTheme.current
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    // A repository refresh can change writable targets while this menu remains open.
    val library by vm.library.collectAsState()
    val notice by vm.notice.collectAsState()
    val menuError = notice?.takeIf { !it.success }?.message?.takeIf(String::isNotBlank)
    var errorLines by remember(menuError) { mutableIntStateOf(2) }
    val actions = vm.menuRows()
    val playlists = remember(library, menu.subject) { vm.playlistTargets() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val subjectKey = menu.subject.item.key.stableId
    val title = when (menu.level) {
        ThorMenuLevel.Actions -> menu.subject.item.title
        ThorMenuLevel.Playlists -> "加入歌单"
        ThorMenuLevel.Name -> if (menu.renaming) "重命名歌单" else "新建歌单"
        ThorMenuLevel.ConfirmDelete -> "删除歌单"
    }
    val currentMenu by rememberUpdatedState(menu)
    val scopeOwner = remember(registry, subjectKey) { Any() }
    val scopeKeys = when (menu.level) {
        ThorMenuLevel.Actions -> actions.indices.map { "menu:action:$it" }
        ThorMenuLevel.Playlists -> playlists.map { "menu:playlist:${ThorPlaylistTarget.Existing(it.key as ThorItemKey.Playlist).stableKey}" } +
            "menu:playlist:${ThorPlaylistTarget.Create.stableKey}"
        ThorMenuLevel.ConfirmDelete -> ThorDeleteChoice.entries.map { "menu:delete:${it.name}" }
        ThorMenuLevel.Name -> listOf("menu:name", "menu:name:cancel", "menu:name:save")
    }
    SideEffect {
        registry.blocked = menu.busy
        registry.installScope(scopeOwner, ThorFocusScope("context:${menu.level}", scopeKeys,
            if (menu.level == ThorMenuLevel.Name) ThorFocusLayout.Spatial else ThorFocusLayout.Vertical))
    }
    DisposableEffect(scopeOwner) { onDispose { registry.removeScope(scopeOwner) } }
    DisposableEffect(subjectKey) {
        onDispose { vm.textEditing.value = false; keyboard?.hide() }
    }
    LaunchedEffect(menu.level) {
        if (menu.level != ThorMenuLevel.Name) { vm.textEditing.value = false; keyboard?.hide() }
    }

    BoxWithConstraints(Modifier.fillMaxSize().imePadding().onGloballyPositioned { origin = it.boundsInRoot().topLeft }) {
        val panelWidth = minOf(if (menu.subject.item.key is ThorItemKey.Destination) 380.dp else 300.dp, (maxWidth - 24.dp).coerceAtLeast(1.dp))
        val viewportPanelHeight = (maxHeight - 24.dp).coerceAtLeast(1.dp)
        fun layerHeight(level: ThorMenuLevel) = when (level) {
            ThorMenuLevel.Actions -> 65.dp + 8.dp + 38.dp + 48.dp * actions.size
            ThorMenuLevel.Playlists -> 65.dp + 8.dp + 38.dp + 48.dp * (playlists.size + 1)
            ThorMenuLevel.Name -> 315.dp
            ThorMenuLevel.ConfirmDelete -> 301.dp
        }
        // Reserve space once, without displaying empty rows. Later target refreshes may grow
        // within the remaining viewport, but must not move this menu away from its anchor.
        val reservedHeight = remember(subjectKey, menu.anchor) {
            maxOf(layerHeight(ThorMenuLevel.Actions), layerHeight(ThorMenuLevel.Playlists), layerHeight(ThorMenuLevel.Name),
                if (menu.subject.item.owned && menu.subject.item.key is ThorItemKey.Playlist) layerHeight(ThorMenuLevel.ConfirmDelete) else 0.dp)
                .coerceAtMost(viewportPanelHeight)
        }
        val placement = remember(subjectKey, menu.anchor, constraints.maxWidth, constraints.maxHeight,
            origin, density.density, density.fontScale) {
            thorMenuPlacement(
                menu.anchor.x - origin.x, menu.anchor.y - origin.y,
                constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(),
                with(density) { panelWidth.toPx() }, with(density) { reservedHeight.toPx() },
                with(density) { viewportPanelHeight.toPx() }, with(density) { 12.dp.toPx() },
            )
        }
        val maxPanelHeight = with(density) { placement.maxHeight.toDp() }
        val duration = theme.menuEnterMs
        val easing = CubicBezierEasing(.2f, .8f, .2f, 1f)
        val errorHeight = if (menuError == null) 0.dp else (13f + 16f * errorLines * density.fontScale).dp
        val animatedHeight by animateDpAsState(
            targetValue = minOf(layerHeight(menu.level) + errorHeight, maxPanelHeight),
            animationSpec = tween(duration, easing = easing), label = "menu-height",
        )
        // A shrinking IME viewport takes effect immediately, even mid-transition.
        val panelHeight = minOf(animatedHeight, maxPanelHeight)
        Box(Modifier.fillMaxSize().background(theme.background.copy(alpha = .58f))
            .pointerInput(subjectKey, menu.busy) { detectTapGestures { if (!currentMenu.busy) vm.closeMenu() } })
        Column(
            Modifier.offset { placement.offset }.width(panelWidth).height(panelHeight)
                .thorMaterial(ThorSurfaceRole.Overlay, cornerRadius = theme.cardRadius)
                .semantics { paneTitle = title }
                // Register a panel hit target so blank/header touches never pass to the scrim.
                .pointerInput(subjectKey) {
                    awaitEachGesture { awaitFirstDown(requireUnconsumed = false); waitForUpOrCancellation() }
                },
        ) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (menu.level != ThorMenuLevel.Actions) ThorButton(registry, "menu:back", "",
                    icon = Icons.Outlined.ChevronLeft, flat = true, enabled = !menu.busy,
                    contentDescription = "返回上级菜单", onClick = { keyboard?.hide(); vm.back() })
                Text(title, Modifier.weight(1f).semantics { heading() }, color = theme.text,
                    fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (menu.busy) CircularProgressIndicator(Modifier.padding(8.dp).size(18.dp), color = theme.accent, strokeWidth = 2.dp)
                if (menu.level == ThorMenuLevel.Actions) ThorButton(registry, "menu:back", "",
                    icon = Icons.Outlined.Close, flat = true, enabled = !menu.busy,
                    contentDescription = "关闭菜单", onClick = { keyboard?.hide(); vm.back() })
            }
            HorizontalDivider(color = theme.outline)
            AnimatedContent(
                targetState = menu.level,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    // The design moves only the incoming body 22dp, not an entire panel width.
                    (slideInHorizontally(tween(duration, easing = easing)) { with(density) { 22.dp.roundToPx() } * direction } +
                        fadeIn(tween(duration, easing = easing), initialAlpha = .45f)) togetherWith
                        fadeOut(tween(0)) using SizeTransform(clip = true,
                            sizeAnimationSpec = { _, _ -> tween(duration, easing = easing) })
                },
                label = "menu-level",
            ) { level ->
                val interactive = level == menu.level && !menu.busy
                when (level) {
                    ThorMenuLevel.Actions -> {
                        val scroll = rememberLazyListState()
                        LaunchedEffect(menu.selection, interactive) {
                            if (interactive && actions.isNotEmpty()) {
                                val selection = menu.selection.coerceIn(actions.indices)
                                if (theme.reduceMotion) scroll.scrollToItem(selection) else scroll.animateScrollToItem(selection)
                                withFrameNanos { }
                                registry.request("menu:action:$selection")
                            }
                        }
                        LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            itemsIndexed(actions, key = { _, row -> row.action }) { index, row ->
                                val activate = { if (interactive) { vm.selectMenu(index); vm.activateMenu() } }
                                ThorActionButton(
                                    onClick = activate,
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                        .thorFocus(registry, "menu:action:$index", enabled = interactive, onClick = activate)
                                        .semantics { row.toggled?.let { stateDescription = if (it) { if (row.action == ThorMenuAction.Like) "已喜欢" else "已收藏" } else { if (row.action == ThorMenuAction.Like) "未喜欢" else "未收藏" } } },
                                    role = ThorSurfaceRole.Flat, focused = interactive && index == menu.selection,
                                    selected = index == menu.selection, enabled = interactive,
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                ) {
                                    val tint = if (row.destructive) MaterialTheme.colorScheme.error
                                        else if (index == menu.selection || row.toggled == true) theme.selectedText else theme.text
                                    Icon(thorLineIcon(menuActionIcon(row.action, row.toggled == true)), null, Modifier.size(16.dp), tint = tint)
                                    Spacer(Modifier.width(12.dp))
                                    Text(row.title, Modifier.weight(1f), color = tint,
                                        fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (row.action == ThorMenuAction.AddToPlaylist || row.action == ThorMenuAction.Delete)
                                        Icon(thorLineIcon(Icons.Outlined.ChevronRight), null, Modifier.size(16.dp), tint = theme.secondary)
                                }
                            }
                        }
                    }
                    ThorMenuLevel.Playlists -> {
                        val scroll = rememberLazyListState()
                        val choices = playlists.map { ThorPlaylistTarget.Existing(it.key as ThorItemKey.Playlist) } + ThorPlaylistTarget.Create
                        val selectedTarget = menu.playlistTarget
                        LaunchedEffect(selectedTarget, choices, interactive) {
                            val selection = choices.indexOf(selectedTarget)
                            if (interactive && selectedTarget != null && selection >= 0) {
                                if (theme.reduceMotion) scroll.scrollToItem(selection) else scroll.animateScrollToItem(selection)
                                withFrameNanos { }
                                registry.request("menu:playlist:${selectedTarget.stableKey}")
                            }
                        }
                        LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            itemsIndexed(playlists, key = { _, item -> item.key.stableId }) { _, item ->
                                val target = ThorPlaylistTarget.Existing(item.key as ThorItemKey.Playlist)
                                val selected = target == selectedTarget
                                val activate = { if (interactive) vm.activatePlaylistTarget(target) }
                                ThorActionButton(
                                    onClick = activate,
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                        .thorFocus(registry, "menu:playlist:${target.stableKey}", enabled = interactive, onClick = activate),
                                    role = ThorSurfaceRole.Flat, focused = interactive && selected,
                                    selected = selected, enabled = interactive,
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                ) {
                                    Icon(thorLineIcon(Icons.Outlined.QueueMusic), null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text(item.title, Modifier.weight(1f), color = if (selected) theme.selectedText else theme.text,
                                        fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    item.trackCount?.let { Text("$it 首", color = theme.secondary, fontSize = 12.sp) }
                                }
                            }
                            item(key = THOR_CREATE_PLAYLIST_TARGET_KEY) {
                                val target = ThorPlaylistTarget.Create
                                val selected = target == selectedTarget
                                val activate = { if (interactive) vm.activatePlaylistTarget(target) }
                                ThorActionButton(
                                    onClick = activate,
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                        .thorFocus(registry, "menu:playlist:${target.stableKey}", enabled = interactive, onClick = activate),
                                    role = ThorSurfaceRole.Flat, focused = interactive && selected,
                                    selected = selected, enabled = interactive,
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                ) {
                                    Icon(thorLineIcon(Icons.Outlined.Add), null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text("新建歌单", Modifier.weight(1f), fontSize = 15.sp)
                                    Icon(thorLineIcon(Icons.Outlined.ChevronRight), null, Modifier.size(16.dp), tint = theme.secondary)
                                }
                            }
                        }
                    }
                    ThorMenuLevel.ConfirmDelete -> {
                        val target = menu.subject.item.key as? ThorItemKey.Playlist
                        val scroll = rememberLazyListState()
                        LaunchedEffect(menu.selection, interactive) {
                            val choice = menu.deleteChoice()
                            if (interactive && choice != null) {
                                if (theme.reduceMotion) scroll.scrollToItem(choice.ordinal + 1) else scroll.animateScrollToItem(choice.ordinal + 1)
                                withFrameNanos { }
                                registry.request("menu:delete:${choice.name}")
                            }
                        }
                        LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            item(key = "delete-description") {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(menu.subject.item.title, color = theme.text, fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (target?.local == true) "只删除歌单和成员记录，不删除歌曲文件。" else "删除后无法撤销。",
                                        color = theme.secondary, fontSize = 13.sp, lineHeight = 18.sp,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            itemsIndexed(ThorDeleteChoice.entries, key = { _, choice -> choice.name }) { _, choice ->
                                val selected = menu.selection == choice.ordinal
                                val activate = { if (interactive && target != null) vm.activateDeleteChoice(choice, target) }
                                Row(Modifier.fillMaxWidth().height(48.dp)
                                    .thorFocus(registry, "menu:delete:${choice.name}", enabled = interactive, onClick = activate)
                                    .thorMaterial(ThorSurfaceRole.Flat, focused = interactive && selected, selected = selected)
                                    .alpha(if (menu.busy) .45f else 1f)
                                    .clickable(enabled = interactive, onClick = activate)
                                    .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (choice == ThorDeleteChoice.Cancel) "取消" else "删除歌单",
                                        color = if (choice == ThorDeleteChoice.Delete) MaterialTheme.colorScheme.error else theme.text,
                                        fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                    ThorMenuLevel.Name -> {
                        ThorMenuNameEditor(
                            entryKey = subjectKey, renaming = menu.renaming, initialName = menu.name,
                            saveLabel = if (menu.renaming) "保存" else if (menu.subject.item.key is ThorItemKey.Destination) "创建" else "创建并加入",
                            registry = registry, enabled = interactive,
                            onNameChanged = vm::setMenuName, onSubmit = vm::saveMenuName,
                            onCancel = { vm.back() },
                            onEditingChanged = { if (level == currentMenu.level) vm.textEditing.value = it },
                            parentOwnsScope = true,
                        )
                    }
                }
            }
            Text("↑↓ 选择 · A ${if (menu.level == ThorMenuLevel.Playlists) "加入" else "确认"} · B ${if (menu.level == ThorMenuLevel.Actions) "关闭" else "返回"}",
                modifier = Modifier.fillMaxWidth().height(38.dp).padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
                color = theme.secondary, fontSize = 11.sp, lineHeight = 26.sp, maxLines = 1)
            if (menuError != null) {
                HorizontalDivider(color = theme.outline)
                Text(
                    text = menuError,
                    modifier = Modifier.fillMaxWidth().background(theme.inset)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { errorLines = it.lineCount.coerceIn(1, 2) },
                )
            }
        }
    }
}

/** The local IME value owns this edit session; delayed parent echoes cannot replace it. */
@Composable
internal fun ThorMenuNameEditor(
    entryKey: String,
    renaming: Boolean,
    initialName: String,
    saveLabel: String,
    registry: ThorFocusRegistry,
    enabled: Boolean,
    onNameChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onEditingChanged: (Boolean) -> Unit,
    parentOwnsScope: Boolean = false,
) {
    val theme = LocalThorTheme.current
    val keyboard = LocalSoftwareKeyboardController.current
    if (!parentOwnsScope) {
        val scopeOwner = remember(registry) { Any() }
        SideEffect { registry.installScope(scopeOwner, ThorFocusScope("menu:name",
            listOf("menu:name", "menu:name:cancel", "menu:name:save"), ThorFocusLayout.Spatial)) }
        DisposableEffect(scopeOwner) { onDispose { registry.removeScope(scopeOwner) } }
    }
    var field by remember(entryKey, renaming) {
        mutableStateOf(TextFieldValue(initialName.take(80), TextRange(0, initialName.take(80).length)))
    }
    val submit = {
        if (enabled && field.text.isNotBlank()) {
            onNameChanged(field.text)
            keyboard?.hide()
            onSubmit()
        }
    }
    LaunchedEffect(enabled) { if (enabled) { withFrameNanos { }; registry.request("menu:name") } }
    DisposableEffect(Unit) { onDispose { onEditingChanged(false) } }
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("歌单名称", color = theme.text, fontSize = 14.sp)
            BasicTextField(
                value = field,
                onValueChange = { value ->
                    if (enabled) {
                        val text = value.text.take(80)
                        fun clamp(range: TextRange) = TextRange(range.start.coerceIn(0, text.length), range.end.coerceIn(0, text.length))
                        field = value.copy(text = text, selection = clamp(value.selection), composition = value.composition?.let(::clamp))
                        onNameChanged(text)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp)
                    .thorFocus(registry, "menu:name", enabled = enabled, onClick = { if (enabled) keyboard?.show() })
                    .onFocusChanged { focused = it.isFocused; onEditingChanged(it.isFocused && enabled) }
                    .thorMaterial(ThorSurfaceRole.Inset, focused = focused)
                    .border(1.dp, if (focused) theme.focus else theme.outline, RoundedCornerShape(theme.controlRadius))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                textStyle = TextStyle(color = theme.text, fontSize = 14.sp),
                cursorBrush = SolidColor(theme.accent), singleLine = true, enabled = enabled,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
        }
        HorizontalDivider(color = theme.outline)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            ThorButton(registry, "menu:name:cancel", "取消", Modifier.widthIn(min = 80.dp), enabled = enabled,
                onClick = { if (enabled) { keyboard?.hide(); onCancel() } })
            ThorButton(registry, "menu:name:save", saveLabel, Modifier.widthIn(min = 80.dp), primary = true,
                enabled = enabled && field.text.isNotBlank(), onClick = submit)
        }
    }

}

internal data class ThorMenuPlacement(val offset: IntOffset, val maxHeight: Float)

/** The caller caches this per open menu/viewport; changing layer content only changes height. */
internal fun thorMenuPlacement(
    anchorX: Float, anchorY: Float, boundsWidth: Float, boundsHeight: Float,
    menuWidth: Float, reservedHeight: Float, maxHeight: Float, margin: Float,
): ThorMenuPlacement {
    val maximum = maxHeight.coerceAtLeast(1f)
    val reserved = minOf(reservedHeight.coerceAtLeast(1f), maximum, (boundsHeight - margin * 2).coerceAtLeast(1f))
    val offset = thorMenuOffset(anchorX, anchorY, boundsWidth, boundsHeight, menuWidth, reserved, margin)
    val bottomMargin = margin.coerceAtLeast(0f).coerceAtMost((boundsHeight - reserved).coerceAtLeast(0f) / 2f)
    val available = (boundsHeight - bottomMargin - offset.y).coerceIn(1f, maximum)
    return ThorMenuPlacement(offset, available)
}

/** All values are lower-window pixels, after subtracting the overlay's root origin. */
internal fun thorMenuOffset(
    anchorX: Float, anchorY: Float, boundsWidth: Float, boundsHeight: Float,
    menuWidth: Float, menuHeight: Float, margin: Float,
): IntOffset {
    fun coordinate(anchor: Float, extent: Float, available: Float, vertical: Boolean): Int {
        val safeAnchor = anchor.takeIf(Float::isFinite) ?: 0f
        val minimum = margin.coerceAtLeast(0f).coerceAtMost((available - extent).coerceAtLeast(0f) / 2f)
        val maximum = (available - extent - minimum).coerceAtLeast(minimum)
        val after = safeAnchor + minimum
        val before = safeAnchor - extent - minimum
        // Match the prototype: right of the touch, preferably above; flip only at an edge.
        val desired = if (vertical) { if (before >= minimum) before else after }
            else if (after + extent <= available - minimum) after else before
        return desired.coerceIn(minimum, maximum).roundToInt()
    }
    return IntOffset(coordinate(anchorX, menuWidth, boundsWidth, false), coordinate(anchorY, menuHeight, boundsHeight, true))

}


private fun menuActionIcon(action: ThorMenuAction, filled: Boolean = false) = when (action) {
    ThorMenuAction.Like -> ThorDesignIcons.icon("heart", filled)
    ThorMenuAction.Collect -> ThorDesignIcons.icon("bookmark", filled)
    ThorMenuAction.PlayNext -> ThorDesignIcons.icon("list-start")
    ThorMenuAction.Enqueue -> ThorDesignIcons.icon("list-end")
    ThorMenuAction.AddToPlaylist -> ThorDesignIcons.icon("list-plus")
    ThorMenuAction.Album -> Icons.Outlined.Album
    ThorMenuAction.Artist -> Icons.Outlined.Person
    ThorMenuAction.RemoveFromPlaylist, ThorMenuAction.RemoveFromQueue -> ThorDesignIcons.icon("list-minus")
    ThorMenuAction.SkipCurrent -> Icons.Outlined.SkipNext
    ThorMenuAction.Rename -> Icons.Outlined.Edit
    ThorMenuAction.Delete -> Icons.Outlined.DeleteOutline
}

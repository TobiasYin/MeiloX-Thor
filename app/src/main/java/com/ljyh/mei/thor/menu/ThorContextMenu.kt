package com.ljyh.mei.thor.menu

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
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
import com.ljyh.mei.thor.components.ThorSurfaceRole
import com.ljyh.mei.thor.components.thorMaterial
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.theme.LocalThorTheme
import com.ljyh.mei.thor.ui.ThorButton
import com.ljyh.mei.thor.ui.ThorCover
import com.ljyh.mei.thor.ui.ThorFocusRegistry
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
    SideEffect { registry.blocked = menu.busy }
    DisposableEffect(subjectKey) {
        onDispose { vm.textEditing.value = false; keyboard?.hide() }
    }
    LaunchedEffect(menu.level) {
        if (menu.level != ThorMenuLevel.Name) { vm.textEditing.value = false; keyboard?.hide() }
    }

    BoxWithConstraints(Modifier.fillMaxSize().imePadding().onGloballyPositioned { origin = it.boundsInRoot().topLeft }) {
        val panelWidth = minOf(280.dp, (maxWidth - 24.dp).coerceAtLeast(1.dp))
        fun layerHeight(level: ThorMenuLevel) = when (level) {
            ThorMenuLevel.Actions -> 49.dp + 12.dp + 48.dp * actions.size
            ThorMenuLevel.Playlists -> 49.dp + 12.dp + 48.dp * (playlists.size + 1)
            ThorMenuLevel.Name -> 201.dp
            ThorMenuLevel.ConfirmDelete -> 237.dp
        }
        // Reserve space once, without displaying empty rows. Later target refreshes may grow
        // within the remaining viewport, but must not move this menu away from its anchor.
        val reservedHeight = remember(subjectKey, menu.anchor) {
            maxOf(layerHeight(ThorMenuLevel.Actions), layerHeight(ThorMenuLevel.Playlists), layerHeight(ThorMenuLevel.Name),
                if (menu.subject.item.owned && menu.subject.item.key is ThorItemKey.Playlist) layerHeight(ThorMenuLevel.ConfirmDelete) else 0.dp)
                .coerceAtMost(360.dp)
        }
        val placement = remember(subjectKey, menu.anchor, constraints.maxWidth, constraints.maxHeight,
            origin, density.density, density.fontScale) {
            thorMenuPlacement(
                menu.anchor.x - origin.x, menu.anchor.y - origin.y,
                constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(),
                with(density) { panelWidth.toPx() }, with(density) { reservedHeight.toPx() },
                with(density) { 360.dp.toPx() }, with(density) { 12.dp.toPx() },
            )
        }
        val maxPanelHeight = with(density) { placement.maxHeight.toDp() }
        val duration = if (theme.reduceMotion) 0 else 170
        val errorHeight = if (menuError == null) 0.dp else (13f + 16f * errorLines * density.fontScale).dp
        val animatedHeight by animateDpAsState(
            targetValue = minOf(layerHeight(menu.level) + errorHeight, maxPanelHeight),
            animationSpec = tween(duration), label = "menu-height",
        )
        // A shrinking IME viewport takes effect immediately, even mid-transition.
        val panelHeight = minOf(animatedHeight, maxPanelHeight)
        Box(Modifier.fillMaxSize().background(theme.contact.copy(alpha = .22f))
            .pointerInput(subjectKey, menu.busy) { detectTapGestures { if (!currentMenu.busy) vm.closeMenu() } })
        Column(
            Modifier.offset { placement.offset }.width(panelWidth).height(panelHeight)
                .clip(RoundedCornerShape(theme.cardRadius))
                .thorMaterial(ThorSurfaceRole.Raised)
                .semantics { paneTitle = title }
                // Register a panel hit target so blank/header touches never pass to the scrim.
                .pointerInput(subjectKey) {
                    awaitEachGesture { awaitFirstDown(requireUnconsumed = false); waitForUpOrCancellation() }
                },
        ) {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = !menu.busy, onClick = { keyboard?.hide(); vm.back() },
                    modifier = Modifier.size(40.dp).thorFocus(registry, "menu:back", enabled = !menu.busy,
                        onClick = { if (!currentMenu.busy) { keyboard?.hide(); vm.back() } })) {
                    Icon(if (menu.level == ThorMenuLevel.Actions) Icons.Outlined.Close else Icons.Outlined.ArrowBack,
                        if (menu.level == ThorMenuLevel.Actions) "关闭菜单" else "返回", Modifier.size(20.dp), tint = theme.text)
                }
                Text(title, Modifier.weight(1f).semantics { heading() }, color = theme.text,
                    fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (menu.busy) CircularProgressIndicator(Modifier.padding(8.dp).size(18.dp), color = theme.accent, strokeWidth = 2.dp)
            }
            HorizontalDivider(color = theme.outline)
            AnimatedContent(
                targetState = menu.level,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally(tween(duration)) { direction * it } +
                        fadeIn(tween(duration))) togetherWith
                        (slideOutHorizontally(tween(duration)) { -direction * it } +
                            fadeOut(tween(duration))) using SizeTransform(clip = true,
                            sizeAnimationSpec = { _, _ -> tween(duration) })
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
                                scroll.animateScrollToItem(selection)
                                withFrameNanos { }
                                registry.request("menu:action:$selection")
                            }
                        }
                        LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(6.dp)) {
                            itemsIndexed(actions, key = { _, row -> row.action }) { index, row ->
                                val activate = { if (interactive) { vm.selectMenu(index); vm.activateMenu() } }
                                Row(Modifier.fillMaxWidth().height(48.dp)
                                    .thorFocus(registry, "menu:action:$index", enabled = interactive, onClick = activate)
                                    .thorMaterial(ThorSurfaceRole.Panel, focused = interactive && index == menu.selection, selected = index == menu.selection)
                                    .alpha(if (menu.busy) .45f else 1f)
                                    .clickable(enabled = interactive, onClick = activate)
                                    .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(row.title, Modifier.weight(1f), color = if (row.destructive) MaterialTheme.colorScheme.error else theme.text,
                                        fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (row.action == ThorMenuAction.AddToPlaylist || row.action == ThorMenuAction.Delete) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = theme.secondary)
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
                                scroll.animateScrollToItem(selection)
                                withFrameNanos { }
                                registry.request("menu:playlist:${selectedTarget.stableKey}")
                            }
                        }
                        LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(6.dp)) {
                            itemsIndexed(playlists, key = { _, item -> item.key.stableId }) { _, item ->
                                val target = ThorPlaylistTarget.Existing(item.key as ThorItemKey.Playlist)
                                val selected = target == selectedTarget
                                val activate = { if (interactive) vm.activatePlaylistTarget(target) }
                                Row(Modifier.fillMaxWidth().height(48.dp)
                                    .thorFocus(registry, "menu:playlist:${target.stableKey}", enabled = interactive, onClick = activate)
                                    .thorMaterial(ThorSurfaceRole.Panel, focused = interactive && selected, selected = selected)
                                    .alpha(if (menu.busy) .45f else 1f)
                                    .clickable(enabled = interactive, onClick = activate)
                                    .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    ThorCover(item, Modifier.size(32.dp))
                                    Text(item.title, Modifier.weight(1f), color = theme.text, fontSize = 14.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            item(key = THOR_CREATE_PLAYLIST_TARGET_KEY) {
                                val target = ThorPlaylistTarget.Create
                                val selected = target == selectedTarget
                                val activate = { if (interactive) vm.activatePlaylistTarget(target) }
                                Row(Modifier.fillMaxWidth().height(48.dp)
                                    .thorFocus(registry, "menu:playlist:${target.stableKey}", enabled = interactive, onClick = activate)
                                    .thorMaterial(ThorSurfaceRole.Panel, focused = interactive && selected, selected = selected)
                                    .alpha(if (menu.busy) .45f else 1f)
                                    .clickable(enabled = interactive, onClick = activate)
                                    .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(Icons.Outlined.Add, null, Modifier.size(20.dp), tint = theme.accent)
                                    Text("新建歌单", color = theme.text, fontSize = 15.sp)
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
                                scroll.animateScrollToItem(choice.ordinal + 1)
                                withFrameNanos { }
                                registry.request("menu:delete:${choice.name}")
                            }
                        }
                        LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(6.dp)) {
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
                                    .thorMaterial(ThorSurfaceRole.Panel, focused = interactive && selected, selected = selected)
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
                        )
                    }
                }
            }
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
) {
    val theme = LocalThorTheme.current
    val keyboard = LocalSoftwareKeyboardController.current
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = field,
            onValueChange = { value ->
                if (enabled) {
                    val text = value.text.take(80)
                    fun clamp(range: TextRange) = TextRange(range.start.coerceIn(0, text.length), range.end.coerceIn(0, text.length))
                    field = value.copy(text = text, selection = clamp(value.selection), composition = value.composition?.let(::clamp))
                    onNameChanged(text)
                }
            },
            modifier = Modifier.fillMaxWidth()
                .thorFocus(registry, "menu:name", enabled = enabled, onClick = { if (enabled) keyboard?.show() })
                .onFocusChanged { onEditingChanged(it.isFocused && enabled) },
            label = { Text("歌单名称") }, singleLine = true, enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = theme.text, unfocusedTextColor = theme.text,
                focusedBorderColor = theme.focus, unfocusedBorderColor = theme.outline,
                focusedContainerColor = theme.inset, unfocusedContainerColor = theme.inset,
                cursorColor = theme.accent,
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThorButton(registry, "menu:name:cancel", "取消", Modifier.weight(1f), enabled = enabled,
                onClick = { if (enabled) { keyboard?.hide(); onCancel() } })
            ThorButton(registry, "menu:name:save", saveLabel, Modifier.weight(1.4f), primary = true,
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
    fun coordinate(anchor: Float, extent: Float, available: Float): Int {
        val safeAnchor = anchor.takeIf(Float::isFinite) ?: 0f
        val minimum = margin.coerceAtLeast(0f).coerceAtMost((available - extent).coerceAtLeast(0f) / 2f)
        val maximum = (available - extent - minimum).coerceAtLeast(minimum)
        val desired = if (safeAnchor + extent <= available - minimum) safeAnchor else safeAnchor - extent
        return desired.coerceIn(minimum, maximum).roundToInt()
    }
    return IntOffset(coordinate(anchorX, menuWidth, boundsWidth), coordinate(anchorY, menuHeight, boundsHeight))
}

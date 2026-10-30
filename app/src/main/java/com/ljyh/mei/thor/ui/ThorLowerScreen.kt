package com.ljyh.mei.thor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.thor.ThorShellViewModel
import com.ljyh.mei.thor.components.ThorActionButton
import com.ljyh.mei.thor.components.ThorSurfaceRole
import com.ljyh.mei.thor.components.thorMaterial
import com.ljyh.mei.thor.menu.*
import com.ljyh.mei.thor.model.*
import com.ljyh.mei.thor.state.*
import com.ljyh.mei.thor.theme.LocalThorTheme

/** A new lower window can take input before the previous composition has disposed. */
class ThorCommandDispatcher {
    private val consumers = linkedMapOf<Any, (ThorCommand) -> Boolean>()
    fun install(owner: Any, handler: (ThorCommand) -> Boolean) { consumers[owner] = handler }
    fun remove(owner: Any) { consumers.remove(owner) }
    fun dispatch(command: ThorCommand): Boolean = consumers.values.lastOrNull()?.invoke(command) ?: true
}

@UnstableApi
@Composable
fun ThorLowerScreen(
    viewModel: ThorShellViewModel,
    dispatcher: ThorCommandDispatcher,
    onExit: () -> Unit,
    onLegacyRoute: (String) -> Unit,
    onRequestAirflow: (Boolean) -> Unit,
    onCalibrateAirflow: () -> Unit = {},
    airflowStatus: String = "",
    isScanning: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val navigation by viewModel.navigation.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val playback by viewModel.playback.state.collectAsStateWithLifecycle()
    val seek by viewModel.seekPreview.collectAsStateWithLifecycle()
    val menu by viewModel.menu.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val theme = LocalThorTheme.current
    val registry = remember { ThorFocusRegistry() }
    val menuRegistry = remember { ThorFocusRegistry() }
    val owner = remember { Any() }
    val sceneStates = rememberSaveableStateHolder()
    val focusManager = LocalFocusManager.current
    val sourcePlaylist = if (navigation.overlay == null) (navigation.page as? ThorPage.Detail)?.item?.takeIf { it.owned }?.key as? ThorItemKey.Playlist else null
    val onBack = { if (!viewModel.back()) onExit() }

    SideEffect {
        registry.blocked = menu != null
        registry.onFocus = { key, item ->
            if (!key.startsWith("notice:")) viewModel.rememberFocus(navigation.sceneKey, key)
            item?.let(viewModel::preview)
        }
        dispatcher.install(owner) { command ->
            val currentMenu = viewModel.menu.value
            if (currentMenu != null) {
                when (command) {
                    ThorCommand.Back -> viewModel.back()
                    ThorCommand.Confirm -> when (currentMenu.level) {
                        ThorMenuLevel.Name -> if (!menuRegistry.confirm()) viewModel.saveMenuName()
                        ThorMenuLevel.Actions, ThorMenuLevel.Playlists, ThorMenuLevel.ConfirmDelete -> viewModel.activateMenu()
                    }
                    is ThorCommand.Move -> when (command.direction) {
                        ThorDirection.Up -> if (currentMenu.level == ThorMenuLevel.Name) focusManager.moveFocus(FocusDirection.Up) else viewModel.moveMenu(-1, command.repeated)
                        ThorDirection.Down -> if (currentMenu.level == ThorMenuLevel.Name) focusManager.moveFocus(FocusDirection.Down) else viewModel.moveMenu(1, command.repeated)
                        ThorDirection.Left -> if (currentMenu.level != ThorMenuLevel.Name) viewModel.back()
                        ThorDirection.Right -> if (currentMenu.level == ThorMenuLevel.Actions &&
                            viewModel.menuRows().getOrNull(currentMenu.selection)?.action in setOf(ThorMenuAction.AddToPlaylist, ThorMenuAction.Delete)) viewModel.activateMenu()
                    }
                    else -> Unit
                }
                true
            } else if (viewModel.seekPreview.value != null) {
                when (command) {
                    ThorCommand.Back, ThorCommand.TogglePlayer -> viewModel.cancelSeek()
                    ThorCommand.Confirm -> if (registry.selectedKey == "player:progress") viewModel.applySeek()
                        else if (!registry.confirm()) viewModel.applySeek()
                    is ThorCommand.Move -> when (command.direction) {
                        ThorDirection.Left -> viewModel.adjustSeek(-10_000)
                        ThorDirection.Right -> viewModel.adjustSeek(10_000)
                        ThorDirection.Up -> focusManager.moveFocus(FocusDirection.Up)
                        ThorDirection.Down -> focusManager.moveFocus(FocusDirection.Down)
                    }
                    else -> Unit
                }
                true
            } else when (command) {
                ThorCommand.Back -> { onBack(); true }
                ThorCommand.Confirm -> registry.confirm()
                ThorCommand.ContextMenu -> {
                    val entry = registry.current
                    val current = viewModel.playback.state.value
                    val item = entry?.item ?: when {
                        navigation.overlay == ThorOverlay.Player -> current.current?.toThorItem()
                        navigation.overlay == null -> (navigation.page as? ThorPage.Detail)?.item
                        else -> null
                    }
                    item?.let { selectedItem ->
                        val queueEntry = (selectedItem.key as? ThorItemKey.QueueEntry)?.entryId
                            ?: current.currentEntryId.takeIf { navigation.overlay == ThorOverlay.Player }
                        viewModel.openMenu(ThorMenuSubject(selectedItem, registry.selectedKey.orEmpty(), sourcePlaylist.takeIf { selectedItem.key is ThorItemKey.Song }, queueEntry),
                            ThorMenuAnchor((entry?.bounds?.left ?: 24f) + 16f, entry?.bounds?.bottom ?: 100f))
                    }
                    true
                }
                ThorCommand.TabPrevious -> { viewModel.switchTab(-1); true }
                ThorCommand.TabNext -> { viewModel.switchTab(1); true }
                ThorCommand.TogglePlayer -> { viewModel.togglePlayer(); true }
                ThorCommand.ToggleQueue -> { viewModel.toggleQueue(); true }
                ThorCommand.TogglePlayback -> { viewModel.playback.toggle(); true }
                ThorCommand.PreviousTrack -> { viewModel.playback.previous(); true }
                ThorCommand.NextTrack -> { viewModel.playback.next(); true }
                is ThorCommand.Move -> registry.move(command.direction, command.repeated) ?: focusManager.moveFocus(when (command.direction) {
                    ThorDirection.Up -> FocusDirection.Up; ThorDirection.Down -> FocusDirection.Down
                    ThorDirection.Left -> FocusDirection.Left; ThorDirection.Right -> FocusDirection.Right
                })
            }
        }
    }
    DisposableEffect(owner) { onDispose { dispatcher.remove(owner) } }
    BackHandler(onBack = onBack)

    val playerView = navigation.overlay == ThorOverlay.Player
    val hasBottomActions = navigation.overlay == null &&
        (navigation.page is ThorPage.Detail || navigation.page is ThorPage.Collection)
    val bottomHeight = if (playerView) 28.dp else if (hasBottomActions) 56.dp else 32.dp
    val miniVisible = navigation.overlay != ThorOverlay.Player && playback.current != null

    Box(modifier.fillMaxSize().background(theme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxSize().then(if (menu != null) Modifier.clearAndSetSemantics {} else Modifier)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    sceneStates.SaveableStateProvider(navigation.sceneKey) {
                        when (navigation.overlay) {
                            ThorOverlay.Player -> ThorPlayerPage(viewModel, registry, playback, onBack)
                            ThorOverlay.Queue -> ThorQueuePage(viewModel, registry, playback, onBack)
                            ThorOverlay.Lyrics -> ThorLyricsPage(viewModel, registry, onBack)
                            ThorOverlay.Timer -> ThorTimerPage(viewModel, registry, playback, onBack)
                            null -> ThorBrowsePage(viewModel, registry, navigation, library, onBack, onLegacyRoute,
                                onRequestAirflow, onCalibrateAirflow, airflowStatus, isScanning)
                        }
                    }
                }
                if (miniVisible) {
                    val current = playback.current!!
                    Row(Modifier.fillMaxWidth().height(56.dp)
                        .thorMaterial(ThorSurfaceRole.NowPlaying, cornerRadius = 0.dp)
                        .drawBehind { drawLine(theme.outline, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        ThorActionButton(
                            onClick = { registry.select("mini:player"); viewModel.togglePlayer() },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                                .thorFocus(registry, "mini:player", onClick = viewModel::togglePlayer),
                            role = ThorSurfaceRole.Flat, focused = registry.selectedKey == "mini:player",
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            ThorCover(current.toThorItem(), Modifier.size(36.dp))
                            Spacer(Modifier.width(7.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(current.title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    color = theme.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${if (playback.playing) "正在播放" else "已暂停"} · ${current.artists.joinToString(" / ") { it.name }}",
                                    fontSize = 12.sp, color = theme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        ThorButton(registry, "mini:toggle", "", Modifier.size(48.dp),
                            icon = if (playback.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            flat = true, contentDescription = if (playback.playing) "暂停" else "播放",
                            onClick = viewModel.playback::toggle)
                    }
                }
                Row(Modifier.fillMaxWidth().height(bottomHeight)
                    .drawBehind { if (!playerView) drawLine(theme.outline, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
                    .padding(horizontal = if (playerView) 12.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (playerView) Arrangement.Center else Arrangement.spacedBy(12.dp)) {
                    val hint = if (seek != null) "← → 调整    A 应用    B 取消" else when (navigation.overlay) {
                        ThorOverlay.Player -> "B 返回    X 菜单    Y 播放 / 暂停    Select 队列"
                        ThorOverlay.Lyrics -> "A 跳转    B 返回"
                        else -> "A 确认    B 返回    X 菜单    Y 播放 / 暂停"
                    }
                    Text(hint, modifier = if (playerView) Modifier else Modifier.weight(1f),
                        fontSize = 11.sp, color = theme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (navigation.overlay == null) ThorBrowseBottomActions(viewModel, registry, navigation, library,
                        onLegacyRoute, isScanning)
                }
            }
            if (navigation.isRoot) ThorNavigationRail(viewModel, registry, navigation.tab)
        }
        if (menu != null) ThorContextMenu(viewModel, menu!!, menuRegistry)
        if (notice != null && menu == null) {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = bottomHeight + (if (miniVisible) 56.dp else 0.dp) + 8.dp)
                    .fillMaxWidth().heightIn(min = 48.dp)
                    .thorMaterial(ThorSurfaceRole.Overlay, cornerRadius = theme.controlRadius)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(notice!!.message, Modifier.weight(1f), color = theme.text, fontSize = 14.sp, maxLines = 2)
                if (notice!!.undoToken != null) ThorButton(registry, "notice:undo", "撤销", flat = true, onClick = viewModel::undo)
                ThorButton(registry, "notice:close", "", icon = Icons.Outlined.Close, flat = true,
                    contentDescription = "关闭提示", onClick = viewModel::clearNotice)
            }
        }
    }
    LaunchedEffect(notice) {
        if (notice?.success == true && notice!!.undoToken == null) { kotlinx.coroutines.delay(3500); viewModel.clearNotice() }
        if (notice == null && registry.selectedKey?.startsWith("notice:") == true) {
            withFrameNanos { }; registry.request(viewModel.rememberedFocus(navigation.sceneKey)) || registry.requestFirst()
        }
    }
    LaunchedEffect(navigation.sceneKey, menu == null) {
        if (menu == null) {
            withFrameNanos { }
            registry.request(viewModel.rememberedFocus(navigation.sceneKey)) || registry.requestFirst()
        }
    }
}


@Composable
private fun ThorNavigationRail(vm: ThorShellViewModel, registry: ThorFocusRegistry, selectedTab: ThorTab) {
    val theme = LocalThorTheme.current
    Column(Modifier.width(72.dp).fillMaxHeight()
        .thorMaterial(ThorSurfaceRole.NavRail, cornerRadius = 0.dp)
        .drawBehind { drawLine(theme.outline, Offset.Zero, Offset(0f, size.height), 1.dp.toPx()) }
        .padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("L1 / R1", fontSize = 11.sp, color = theme.secondary, maxLines = 1)
        Spacer(Modifier.height(5.dp))
        ThorTab.entries.forEach { tab ->
            val key = "tab:${tab.name}"
            val icon = when (tab) {
                ThorTab.Home -> Icons.Outlined.Home
                ThorTab.Discover -> Icons.Outlined.Explore
                ThorTab.Library -> Icons.Outlined.LibraryMusic
                ThorTab.My -> Icons.Outlined.Person
            }
            ThorActionButton(
                onClick = { registry.select(key); vm.selectTab(tab) },
                modifier = Modifier.fillMaxWidth().height(64.dp)
                    .thorFocus(registry, key, onClick = { vm.selectTab(tab) }),
                selected = tab == selectedTab, role = ThorSurfaceRole.Flat,
                focused = registry.selectedKey == key, shape = RoundedCornerShape(theme.cardRadius),
                contentPadding = PaddingValues(0.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(thorLineIcon(icon), null, Modifier.size(16.dp))
                    Text(tab.title, fontSize = 12.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.weight(1f))
        ThorActionButton(
            onClick = { registry.select("rail:player"); vm.togglePlayer() },
            modifier = Modifier.fillMaxWidth().height(64.dp)
                .thorFocus(registry, "rail:player", onClick = vm::togglePlayer),
            role = ThorSurfaceRole.Flat, focused = registry.selectedKey == "rail:player",
            contentPadding = PaddingValues(0.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(thorLineIcon(Icons.Outlined.Album), null, Modifier.size(16.dp), tint = theme.secondary)
                Text("播放中", fontSize = 11.sp, color = theme.secondary, maxLines = 1)
            }
        }
    }
}

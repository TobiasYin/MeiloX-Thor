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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import kotlinx.coroutines.launch

private data class ThorPageActionSession(
    val scene: String,
    val account: Long,
    val playbackEntry: String?,
    val actions: List<ThorPageAction>,
    val selectedId: String?,
)

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
    airflowStrength: Float = 0f,
    airflowCalibrationLabel: String = "校准",
    airflowCalibrationProgress: Float = 0f,
    onControllerScopeChanged: () -> Unit = {},
) {
    val navigation by viewModel.navigation.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val playback by viewModel.playback.state.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val libraryCategory by viewModel.libraryCategory.collectAsStateWithLifecycle()
    val homeCategory by viewModel.homeCategory.collectAsStateWithLifecycle()
    val artistBrowse by viewModel.artistBrowse.collectAsStateWithLifecycle()
    val searchEntry by viewModel.searchEntry.collectAsStateWithLifecycle()
    val latestScopeChange by rememberUpdatedState(onControllerScopeChanged)
    val seek by viewModel.seekPreview.collectAsStateWithLifecycle()
    val menu by viewModel.menu.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val textEditing by viewModel.textEditing.collectAsStateWithLifecycle()
    val theme = LocalThorTheme.current
    val registry = remember { ThorFocusRegistry() }
    val menuRegistry = remember { ThorFocusRegistry() }
    val pageActionRegistry = remember { ThorFocusRegistry() }
    val pageActionBindings = remember { ThorPageActionBindings() }
    val catalogPickerActive = pageActionBindings.has("catalog:picker-active")
    val accountInput = remember { com.ljyh.mei.thor.account.ThorAccountInputHost() }
    val playerConnection by viewModel.playerConnection.collectAsStateWithLifecycle()
    var pageActionSession by remember { mutableStateOf<ThorPageActionSession?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val owner = remember { Any() }
    val sceneStates = rememberSaveableStateHolder()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val sourcePlaylist = if (navigation.overlay == null) (navigation.page as? ThorPage.Detail)?.item?.takeIf { it.owned }?.key as? ThorItemKey.Playlist else null
    fun closePageActions(action: ThorPageAction? = null) {
        val session = pageActionSession ?: return
        pageActionSession = null
        focusManager.clearFocus(force = true)
        coroutineScope.launch {
            withFrameNanos { }
            registry.blocked = viewModel.menu.value != null
            registry.request(viewModel.rememberedFocus(viewModel.navigation.value.sceneKey)) || registry.requestFirst()
            if (session.scene == viewModel.navigation.value.sceneKey &&
                session.account == viewModel.library.value.account.generation &&
                (session.playbackEntry == null || session.playbackEntry == viewModel.playback.state.value.currentEntryId)
            ) action?.takeIf { it.enabled }?.onClick?.invoke()
        }
    }
    val onBack: () -> Unit = {
        val current = viewModel.navigation.value
        val localPage = current.overlay == null && viewModel.menu.value == null && viewModel.seekPreview.value == null
        when {
            pageActionSession != null -> closePageActions()
            localPage && current.page == ThorPage.Search &&
                handleThorSearchEditingCommand(ThorCommand.Back, viewModel.textEditing.value, pageActionBindings) -> Unit
            localPage && pageActionBindings.invoke("page:back") -> Unit
            localPage && current.page is ThorPage.Account && accountInput.handle(ThorCommand.Back) -> Unit
            !viewModel.back() -> onExit()
        }
    }
    fun currentPageActionSession(): ThorPageActionSession? {
        val currentNavigation = viewModel.navigation.value
        val currentLibrary = viewModel.library.value
        val currentPlayback = viewModel.playback.state.value
        val actions = thorPageActions(viewModel, currentNavigation, currentLibrary,
            currentPlayback, viewModel.preferences.value, onLegacyRoute, onRequestAirflow,
            onCalibrateAirflow, isScanning, airflowCalibrationLabel, pageActionBindings).toMutableList()
        viewModel.notice.value?.let { shown ->
            val token = shown.undoToken
            if (token != null) actions += ThorPageAction("notice:undo", "撤销") {
                if (viewModel.notice.value?.id == shown.id && viewModel.notice.value?.undoToken == token) viewModel.undo()
            }
            actions += ThorPageAction("notice:close", "关闭提示") {
                viewModel.clearNoticeIfCurrent(shown.id)
            }
        }
        if (actions.isEmpty()) return null
        return ThorPageActionSession(currentNavigation.sceneKey, currentLibrary.account.generation,
            currentPlayback.currentEntryId.takeIf { currentNavigation.overlay != null },
            actions, actions.firstOrNull { it.enabled }?.id)
    }

    SideEffect {
        accountInput.observe((navigation.page as? ThorPage.Account)?.route, library.account.generation)
        registry.blocked = menu != null || pageActionSession != null
        registry.onFocus = { key, item ->
            if (registry.isAllowed(key)) viewModel.rememberFocus(navigation.sceneKey, key)
            item?.let(viewModel::preview)
        }
        dispatcher.install(owner) commandHandler@{ command ->
            if (command != ThorCommand.Back) viewModel.clearExitGuard()
            if (command == ThorCommand.TogglePlayer) {
                focusManager.clearFocus(force = true)
                keyboard?.hide()
                if (pageActionSession != null) { pageActionSession = null; viewModel.showPlayer() }
                else viewModel.togglePlayer()
                return@commandHandler true
            }
            if (command == ThorCommand.PageActions) {
                if (pageActionSession != null) closePageActions()
                else if (viewModel.menu.value == null && viewModel.seekPreview.value == null && !viewModel.textEditing.value) {
                    currentPageActionSession()?.let { session ->
                        focusManager.clearFocus(force = true)
                        keyboard?.hide()
                        pageActionSession = session
                    }
                }
                return@commandHandler true
            }
            pageActionSession?.let { session ->
                if (command is ThorCommand.Move) return@commandHandler pageActionRegistry.move(command.direction, command.repeated)
                when (command) {
                    ThorCommand.Back -> closePageActions()
                    ThorCommand.Confirm -> session.actions.firstOrNull { it.id == session.selectedId && it.enabled }?.let { closePageActions(it) }
                    else -> Unit
                }
                return@commandHandler true
            }
            if (command is ThorCommand.Shoulder) {
                viewModel.handleShoulder(command.right, command.phase)
                return@commandHandler true
            }
            val currentMenu = viewModel.menu.value
            if (currentMenu != null) {
                when (command) {
                    ThorCommand.Back -> viewModel.back()
                    ThorCommand.Confirm -> when (currentMenu.level) {
                        ThorMenuLevel.Name -> if (!menuRegistry.confirm()) viewModel.saveMenuName()
                        ThorMenuLevel.Actions, ThorMenuLevel.Playlists, ThorMenuLevel.ConfirmDelete -> viewModel.activateMenu()
                    }
                    is ThorCommand.Move -> if (currentMenu.level == ThorMenuLevel.Name) menuRegistry.move(command.direction, command.repeated) else when (command.direction) {
                        ThorDirection.Up -> viewModel.moveMenu(-1, command.repeated)
                        ThorDirection.Down -> viewModel.moveMenu(1, command.repeated)
                        ThorDirection.Left -> if (currentMenu.level != ThorMenuLevel.Name) viewModel.back()
                        ThorDirection.Right -> if (currentMenu.level == ThorMenuLevel.Actions &&
                            viewModel.menuRows().getOrNull(currentMenu.selection)?.action in setOf(ThorMenuAction.AddToPlaylist, ThorMenuAction.Delete)) viewModel.activateMenu()
                    }
                    else -> Unit
                }
                true
            } else if (viewModel.seekPreview.value != null) {
                when (command) {
                    ThorCommand.Back -> viewModel.cancelSeek()
                    ThorCommand.Confirm -> viewModel.applySeek()
                    is ThorCommand.Move -> when (command.direction) {
                        ThorDirection.Left -> viewModel.adjustSeek(-10_000)
                        ThorDirection.Right -> viewModel.adjustSeek(10_000)
                        ThorDirection.Up, ThorDirection.Down -> Unit
                    }
                    else -> Unit
                }
                true
            } else if (viewModel.navigation.value.overlay == null && viewModel.navigation.value.page == ThorPage.Search &&
                handleThorSearchEditingCommand(command, viewModel.textEditing.value, pageActionBindings)) true
            else if (command != ThorCommand.Back && navigation.overlay == null && navigation.page is ThorPage.Account &&
                command !in setOf(ThorCommand.ToggleQueue, ThorCommand.TogglePlayback, ThorCommand.PreviousTrack, ThorCommand.NextTrack) &&
                accountInput.handle(command)) true
            else when (command) {
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
                ThorCommand.ShuffleAll -> { viewModel.playPage(true); true }
                ThorCommand.PlayAll -> { viewModel.playPage(false); true }
                ThorCommand.PageActions -> true
                is ThorCommand.Shoulder -> true
                is ThorCommand.Move -> {
                    val horizontal = command.direction == ThorDirection.Left || command.direction == ThorDirection.Right
                    when {
                        horizontal && pageActionBindings.has("catalog:picker-active") -> true
                        horizontal && viewModel.cycleBrowseCategory(if (command.direction == ThorDirection.Left) -1 else 1) -> true
                        else -> registry.move(command.direction, command.repeated)
                    }
                }
            }
        }
    }
    DisposableEffect(owner) { onDispose { dispatcher.remove(owner); viewModel.cancelSeek() } }
    BackHandler(onBack = onBack)

    val playerView = navigation.overlay == ThorOverlay.Player
    val hasBottomActions = navigation.overlay == null &&
        (navigation.page is ThorPage.Detail || navigation.page is ThorPage.Collection || navigation.page is ThorPage.Artist ||
            (navigation.page is ThorPage.Root && navigation.tab != ThorTab.My))
    val refreshOnlyRoot = navigation.overlay == null && navigation.page is ThorPage.Root &&
        (navigation.tab == ThorTab.Home || navigation.tab == ThorTab.Discover)
    val bottomHeight = if (playerView) 28.dp else if (refreshOnlyRoot) 48.dp else if (hasBottomActions) 56.dp else 32.dp
    val miniVisible = navigation.overlay != ThorOverlay.Player && playback.current != null

    Box(modifier.fillMaxSize().background(theme.background).windowInsetsPadding(WindowInsets.safeDrawing)
        .pointerInput(viewModel) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                viewModel.clearExitGuard()
                do { val event = awaitPointerEvent(PointerEventPass.Final) } while (event.changes.any { it.pressed })
            }
        }) {
        Row(Modifier.fillMaxSize().then(if (menu != null || pageActionSession != null) Modifier.clearAndSetSemantics {} else Modifier)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    sceneStates.SaveableStateProvider(navigation.sceneKey) {
                        when (navigation.overlay) {
                            ThorOverlay.Player -> ThorPlayerPage(viewModel, registry, playback, onBack)
                            ThorOverlay.Queue -> ThorQueuePage(viewModel, registry, playback, onBack, pageActionBindings)
                            ThorOverlay.Lyrics -> ThorLyricsPage(viewModel, registry, onBack, pageActionBindings)
                            ThorOverlay.Timer -> ThorTimerPage(viewModel, registry, playback, onBack)
                            null -> ThorBrowsePage(viewModel, registry, navigation, library, onBack, onLegacyRoute,
                                onRequestAirflow, onCalibrateAirflow, airflowStatus, isScanning,
                                airflowStrength, airflowCalibrationLabel, airflowCalibrationProgress, pageActionBindings,
                                playerConnection, accountInput)
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
                    val hint = if (seek != null) "L1/R1 按住拖动·松开应用   A 应用   B 取消   Start 播放页" else when (navigation.overlay) {
                        ThorOverlay.Player -> "A/Y 播放   L1/R1 ±15s   L2/R2 切歌   Select 队列·长按操作   B/Start 返回"
                        ThorOverlay.Lyrics -> "A 跳转    B 返回"
                        else -> if (navigation.page is ThorPage.Account || navigation.page == ThorPage.AudioSettings || navigation.page == ThorPage.Equalizer)
                            "↑↓ 选择   A 确认   B 返回   Start 播放   Select 长按操作"
                        else if (!catalogPickerActive && (navigation.page is ThorPage.Artist || navigation.page == ThorPage.Root(ThorTab.Home)))
                            "↑↓ 列表   ←→ 分类   A 确认   B 返回   Select 长按操作"
                        else "↑↓ 列表   A 确认   B 返回   X 菜单   Select 长按操作"
                    }
                    Text(hint, modifier = if (playerView) Modifier else Modifier.weight(1f),
                        fontSize = 11.sp, color = theme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (navigation.overlay == null && !catalogPickerActive) ThorBrowseBottomActions(viewModel, registry, navigation, library,
                        onLegacyRoute, isScanning)
                }
            }
            if (navigation.isRoot) ThorNavigationRail(viewModel, registry, navigation.tab)
        }
        if (menu != null) ThorContextMenu(viewModel, menu!!, menuRegistry)
        pageActionSession?.let { session ->
            ThorPageActionMenu(session.actions, session.selectedId, pageActionRegistry,
                onSelect = { id -> pageActionSession = pageActionSession?.copy(selectedId = id) },
                onActivate = { action -> closePageActions(action) }, onClose = { closePageActions() })
        }
        if (notice != null && menu == null && pageActionSession == null) {
            ThorNoticeBanner(notice, registry,
                editingSearch = navigation.overlay == null && navigation.page == ThorPage.Search && textEditing,
                onUndo = { id -> if (viewModel.notice.value?.id == id) viewModel.undo() },
                onClose = viewModel::clearNoticeIfCurrent,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = bottomHeight + (if (miniVisible) 56.dp else 0.dp) + 8.dp)
            )
        }
    }
    ThorNoticeAutoDismiss(notice, viewModel::clearNoticeIfCurrent)
    LaunchedEffect(notice?.id) {
        if (notice == null && registry.selectedKey?.startsWith("notice:") == true) {
            withFrameNanos { }; registry.request(viewModel.rememberedFocus(navigation.sceneKey)) || registry.requestFirst()
        }
    }
    LaunchedEffect(navigation.sceneKey, menu == null, pageActionSession == null) {
        if (menu == null && pageActionSession == null) {
            withFrameNanos { }
            registry.request(viewModel.rememberedFocus(navigation.sceneKey)) || registry.requestFirst()
        }
    }
    LaunchedEffect(navigation.sceneKey, library.account.generation, playback.currentEntryId) {
        pageActionSession?.let { session ->
            if (session.scene != navigation.sceneKey || session.account != library.account.generation ||
                session.playbackEntry != null && session.playbackEntry != playback.currentEntryId) closePageActions()
        }
    }
    LaunchedEffect(navigation.sceneKey, menu?.level, menu?.subject?.item?.key, pageActionSession != null, libraryCategory, homeCategory, artistBrowse?.section, catalogPickerActive, searchEntry) {
        latestScopeChange()
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

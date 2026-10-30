package com.ljyh.mei.thor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.thor.ThorShellViewModel
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
                    ThorCommand.Confirm -> if (currentMenu.level == ThorMenuLevel.Name) {
                        if (!menuRegistry.confirm()) viewModel.saveMenuName()
                    } else viewModel.activateMenu()
                    is ThorCommand.Move -> when (command.direction) {
                        ThorDirection.Up -> if (currentMenu.level == ThorMenuLevel.Name) focusManager.moveFocus(FocusDirection.Up) else viewModel.moveMenu(-1)
                        ThorDirection.Down -> if (currentMenu.level == ThorMenuLevel.Name) focusManager.moveFocus(FocusDirection.Down) else viewModel.moveMenu(1)
                        ThorDirection.Left -> if (currentMenu.level != ThorMenuLevel.Name) viewModel.back()
                        ThorDirection.Right -> if (currentMenu.level == ThorMenuLevel.Actions && viewModel.menuRows().getOrNull(currentMenu.selection)?.action == ThorMenuAction.AddToPlaylist) viewModel.activateMenu()
                    }
                    else -> Unit
                }
                true
            } else if (viewModel.seekPreview.value != null) {
                when (command) {
                    ThorCommand.Back, ThorCommand.TogglePlayer -> viewModel.cancelSeek()
                    ThorCommand.Confirm -> if (registry.selectedKey?.startsWith("seek:") == true) registry.confirm() else viewModel.applySeek()
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

    Box(modifier.fillMaxSize().background(theme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize().then(if (menu != null) Modifier.clearAndSetSemantics {} else Modifier).padding(horizontal = 10.dp, vertical = 5.dp)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    sceneStates.SaveableStateProvider(navigation.sceneKey) {
                    when (navigation.overlay) {
                        ThorOverlay.Player -> ThorPlayerPage(viewModel, registry, playback, onBack)
                        ThorOverlay.Queue -> ThorQueuePage(viewModel, registry, playback, onBack)
                        ThorOverlay.Lyrics -> ThorLyricsPage(viewModel, registry, onBack)
                        ThorOverlay.Timer -> ThorTimerPage(viewModel, registry, playback, onBack)
                        null -> ThorBrowsePage(viewModel, registry, navigation, library, onBack, onLegacyRoute, onRequestAirflow, onCalibrateAirflow, airflowStatus, isScanning)
                    }
                    }
                }
                if (navigation.isRoot) Column(
                    Modifier.width(72.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ThorTab.entries.forEach { tab ->
                        ThorButton(registry, "tab:${tab.name}", tab.title,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            selected = tab == navigation.tab, onClick = { viewModel.selectTab(tab) })
                    }
                }
            }
            if (navigation.overlay == null && playback.current != null) {
                HorizontalDivider(color = theme.outline, modifier = Modifier.padding(top = 3.dp))
                Row(Modifier.fillMaxWidth().height(51.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThorCover(playback.current!!.toThorItem(), Modifier.size(35.dp))
                    Text(playback.current!!.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    ThorButton(registry, "mini:player", "播放页", icon = Icons.Outlined.OpenInFull, onClick = viewModel::togglePlayer)
                    ThorButton(registry, "mini:toggle", if (playback.playing) "暂停" else "播放", icon = if (playback.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, onClick = viewModel.playback::toggle)
                }
            }
            HorizontalDivider(color = theme.outline)
            Row(Modifier.fillMaxWidth().heightIn(min = 25.dp), verticalAlignment = Alignment.CenterVertically) {
                val aHint = if (navigation.overlay == null && registry.current?.item?.key is ThorItemKey.Song) "播放所选" else "确认"
                Text("A $aHint   B 返回   X 更多   Y 播放/暂停", color = theme.secondary, fontSize = 10.sp, modifier = Modifier.weight(1f), maxLines = 1)
                Text("Start 播放页   Select 队列", color = theme.secondary, fontSize = 10.sp, maxLines = 1)
            }
        }
        if (menu != null) ThorContextMenu(viewModel, menu!!, menuRegistry)
        if (notice != null && menu == null) Surface(
            modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 10.dp, vertical = 2.dp),
            shape = MaterialTheme.shapes.medium, color = theme.elevated,
            tonalElevation = 6.dp, shadowElevation = 8.dp,
        ) {
            Row(Modifier.padding(start = 14.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(notice!!.message, Modifier.widthIn(max = 260.dp), fontSize = 13.sp, maxLines = 2)
                if (notice!!.undoToken != null) ThorButton(registry, "notice:undo", "撤销", onClick = viewModel::undo)
                ThorButton(registry, "notice:close", "关闭", icon = Icons.Outlined.Close, onClick = viewModel::clearNotice)
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

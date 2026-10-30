package com.ljyh.mei.thor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.thor.ThorShellViewModel
import com.ljyh.mei.thor.menu.ThorMenuAnchor
import com.ljyh.mei.thor.model.*
import com.ljyh.mei.thor.state.*
import com.ljyh.mei.thor.theme.LocalThorTheme

@Composable
internal fun ThorPageHeader(registry: ThorFocusRegistry, title: String, count: Int? = null, onBack: (() -> Unit)? = null, onSearch: (() -> Unit)? = null, countUnit: String = "首") {
    Row(Modifier.fillMaxWidth().heightIn(min = 49.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null) ThorButton(registry, "header:back", "", icon = Icons.Outlined.ArrowBack, contentDescription = "返回", onClick = onBack)
        Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null) Text("共 $count $countUnit", color = LocalThorTheme.current.secondary, fontSize = 12.sp)
        if (onSearch != null) ThorButton(registry, "header:search", "搜索", icon = Icons.Outlined.Search, onClick = onSearch)
    }
}

@UnstableApi
@Composable
internal fun ThorBrowseList(
    vm: ThorShellViewModel, registry: ThorFocusRegistry, scene: String, items: List<ThorItem>,
    modifier: Modifier = Modifier, sourcePlaylist: ThorItemKey.Playlist? = null,
    onItem: ((ThorItem, Int) -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val keys = remember(scene, items) {
        val occurrences = mutableMapOf<String, Int>()
        items.map { item ->
            val id = item.key.stableId
            val occurrence = occurrences[id] ?: 0
            occurrences[id] = occurrence + 1
            "$scene:item:$id:$occurrence"
        }
    }
    val focus = BindThorListFocus(registry, scene, keys, items, listState, vm) { index ->
        val item = items[index]
        when {
            onItem != null -> onItem(item, index)
            items.all { it.key is ThorItemKey.Song && it.tracks.isNotEmpty() } -> vm.play(items.flatMap { it.tracks }, index,
                (vm.navigation.value.page as? ThorPage.Collection)?.collection?.title ?: "播放列表")
            else -> vm.open(item)
        }
    }
    LazyColumn(modifier.fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 3.dp)) {
        itemsIndexed(items, key = { index, _ -> keys[index] }) { index, item ->
            ThorMusicRow(registry, keys[index], item,
                trailing = item.trackCount?.let { "$it" },
                onClick = { focus.activate(index) },
                onMenu = { anchor -> focus.onFocused(index); vm.openMenu(ThorMenuSubject(item, keys[index], sourcePlaylist), anchor) },
                onFocused = { focus.onFocused(index) },
            )
        }
    }
}

@UnstableApi
@Composable
internal fun ThorBrowsePage(
    vm: ThorShellViewModel, registry: ThorFocusRegistry, navigation: ThorNavigation, library: ThorLibraryState,
    onBack: () -> Unit, onLegacyRoute: (String) -> Unit,
    onRequestAirflow: (Boolean) -> Unit, onCalibrateAirflow: () -> Unit, airflowStatus: String,
    isScanning: Boolean = false,
) {
    val loading by vm.detailLoading.collectAsStateWithLifecycle()
    when (val page = navigation.page) {
        is ThorPage.Root -> when (page.tab) {
            ThorTab.My -> ThorMyPage(vm, registry, library, onLegacyRoute)
            ThorTab.Library -> ThorLibraryPage(vm, registry, library, onLegacyRoute, isScanning)
            else -> {
                val collection = if (page.tab == ThorTab.Home) ThorCollection.Home else ThorCollection.Discover
                Column(Modifier.fillMaxSize()) {
                    ThorPageHeader(registry, page.tab.title, onSearch = { vm.push(ThorPage.Search) })
                    val items = library[collection]
                    if (items.isEmpty()) ThorEmptyState(vm, registry, library.errors[collection] ?: if (collection in library.loading) "正在加载" else "还没有内容",
                        if (collection in library.loading) null else "刷新", { vm.refresh(collection) }, Modifier.weight(1f), collection in library.loading)
                    else ThorBrowseList(vm, registry, page.key, items, Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        ThorButton(registry, "root:local", "本地音乐", modifier = Modifier.weight(1f), icon = Icons.Outlined.FolderOpen, onClick = { vm.openCollection(ThorCollection.LocalSongs) })
                        ThorButton(registry, "root:refresh", "刷新", icon = Icons.Outlined.Refresh, onClick = { vm.refresh(collection) })
                    }
                }
            }
        }
        is ThorPage.Collection -> Column(Modifier.fillMaxSize()) {
            val items = library[page.collection]
            val songList = page.collection in setOf(ThorCollection.LocalSongs, ThorCollection.LikedSongs, ThorCollection.Downloads, ThorCollection.History)
            ThorPageHeader(registry, page.collection.title, items.size, onBack = onBack, countUnit = if (songList) "首" else "项")
            if (items.isEmpty()) {
                val busy = page.collection in library.loading
                val local = page.collection in setOf(ThorCollection.LocalSongs, ThorCollection.LocalAlbums)
                ThorEmptyState(vm, registry, library.errors[page.collection] ?: if (local && isScanning) "正在扫描本地音乐" else if (busy) "正在加载" else "暂无${page.collection.title}",
                    if (busy) null else if (local && isScanning) "停止扫描" else if (local) "导入音乐" else "刷新", {
                        if (local) onLegacyRoute("local_music") else vm.refresh(page.collection)
                    }, Modifier.weight(1f), busy)
            } else ThorBrowseList(vm, registry, page.key, items, Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                if (songList) {
                    val tracks = items.flatMap { it.tracks }
                    ThorButton(registry, "collection:play", "播放全部", icon = Icons.Outlined.PlayArrow, primary = true, enabled = tracks.isNotEmpty(), onClick = { vm.play(tracks, 0, page.collection.title, shuffle = false) })
                    ThorButton(registry, "collection:shuffle", "随机播放", icon = Icons.Outlined.Shuffle, enabled = tracks.isNotEmpty(), onClick = { vm.play(tracks, tracks.indices.random(), page.collection.title, shuffle = true) })
                }
                if (page.collection == ThorCollection.OwnedPlaylists) ThorButton(registry, "collection:create", "新建歌单", icon = Icons.Outlined.Add, onClick = vm::createPlaylist)
                if (page.collection == ThorCollection.Downloads) ThorButton(registry, "collection:downloads", "管理下载", onClick = { onLegacyRoute("download_manage") })
                if (page.collection in setOf(ThorCollection.LocalSongs, ThorCollection.LocalAlbums)) ThorButton(registry, "collection:scan", if (isScanning) "停止扫描" else "扫描", onClick = { onLegacyRoute("local_music") })
                ThorButton(registry, "collection:refresh", "", icon = Icons.Outlined.Refresh, contentDescription = "刷新", onClick = { vm.refresh(page.collection) })
            }
        }
        is ThorPage.Detail -> Column(Modifier.fillMaxSize()) {
            val item = page.item
            ThorPageHeader(registry, item.title, item.trackCount ?: item.tracks.size, onBack)
            if (item.tracks.isEmpty()) ThorEmptyState(vm, registry, if (loading) "正在加载" else "暂无歌曲", if (loading) null else "重新加载", { vm.open(item) }, Modifier.weight(1f), loading)
            else ThorBrowseList(vm, registry, page.key, item.tracks.map { it.toThorItem() }, Modifier.weight(1f),
                sourcePlaylist = (item.key as? ThorItemKey.Playlist)?.takeIf { item.owned },
                onItem = { _, index -> vm.play(item.tracks, index, item.title) })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                ThorButton(registry, "detail:play", "播放全部", icon = Icons.Outlined.PlayArrow, primary = true, enabled = item.tracks.isNotEmpty(), onClick = { vm.play(item.tracks, 0, item.title) })
                ThorButton(registry, "detail:more", "更多", icon = Icons.Outlined.MoreHoriz, onClick = { vm.openMenu(ThorMenuSubject(item, "detail:more"), ThorMenuAnchor(30f, 100f)) })
            }
        }
        ThorPage.Search -> ThorSearchPage(vm, registry, onBack)
        ThorPage.Appearance -> ThorAppearancePage(vm, registry, onBack)
        ThorPage.Controls -> ThorControlsPage(vm, registry, onBack, onRequestAirflow, onCalibrateAirflow, airflowStatus)
    }
}

@UnstableApi
@Composable
private fun ThorLibraryPage(vm: ThorShellViewModel, registry: ThorFocusRegistry, library: ThorLibraryState, onLegacyRoute: (String) -> Unit, isScanning: Boolean) {
    var category by rememberSaveable { mutableIntStateOf(0) }
    val collections = when (category) {
        1 -> listOf(ThorCollection.CollectedAlbums, ThorCollection.LocalAlbums)
        2 -> listOf(ThorCollection.OwnedPlaylists, ThorCollection.SavedPlaylists)
        else -> listOf(ThorCollection.LikedSongs, ThorCollection.LocalSongs, ThorCollection.Downloads, ThorCollection.History)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 49.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("音乐库", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            listOf("歌曲", "专辑", "歌单").forEachIndexed { index, title ->
                ThorButton(registry, "library:category:$index", title, selected = category == index, onClick = { category = index })
            }
        }
        ThorBrowseList(vm, registry, "library:$category", collections.map { collection ->
            ThorItem(ThorItemKey.Destination(collection.name), collection.title, "${library[collection].size} ${if (category == 0) "首" else "项"}", library[collection].firstOrNull()?.artworkUri)
        }, Modifier.weight(1f), onItem = { item, _ -> vm.openCollection(ThorCollection.valueOf((item.key as ThorItemKey.Destination).id)) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (category == 2) ThorButton(registry, "library:create", "新建歌单", icon = Icons.Outlined.Add, onClick = vm::createPlaylist)
            else ThorButton(registry, "library:import", if (isScanning) "停止扫描" else "导入音乐", icon = Icons.Outlined.FolderOpen, onClick = { onLegacyRoute("local_music") })
        }
    }
}

@UnstableApi
@Composable
private fun ThorMyPage(vm: ThorShellViewModel, registry: ThorFocusRegistry, library: ThorLibraryState, onLegacyRoute: (String) -> Unit) {
    val account = library.account
    val items = listOf(
        ThorItem(ThorItemKey.Destination("account"), if (account.signedIn) account.nickname else "连接网易云音乐", if (account.signedIn) "网易云音乐 · ${if (account.sessionType == ThorSessionType.Mobile) "手机账号" else "Web 账号"}" else "未登录", account.avatarUri),
        ThorItem(ThorItemKey.Destination("bindings"), "账号绑定"),
        ThorItem(ThorItemKey.Destination("appearance"), "主题与外观"),
        ThorItem(ThorItemKey.Destination("controls"), "手柄与吹气"),
        ThorItem(ThorItemKey.Destination("play"), "播放设置"),
        ThorItem(ThorItemKey.Destination("about"), "关于"),
    )
    Column(Modifier.fillMaxSize()) {
        ThorPageHeader(registry, "我的")
        ThorBrowseList(vm, registry, "my", items, Modifier.weight(1f), onItem = { item, _ ->
            when ((item.key as ThorItemKey.Destination).id) {
                "appearance" -> vm.push(ThorPage.Appearance)
                "controls" -> vm.push(ThorPage.Controls)
                "account" -> onLegacyRoute(if (account.signedIn) "account/home" else "account/login")
                "bindings" -> onLegacyRoute(if (account.signedIn) "account/security/bindings" else "account/login")
                "play" -> onLegacyRoute("setting/play")
                else -> onLegacyRoute("about")
            }
        })
    }
}

@UnstableApi
@Composable
private fun ThorSearchPage(vm: ThorShellViewModel, registry: ThorFocusRegistry, onBack: () -> Unit) {
    val search by vm.search.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ThorPageHeader(registry, "搜索", onBack = onBack)
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                search.query, vm::setSearchQuery, Modifier.weight(1f).onFocusChanged { vm.textEditing.value = it.isFocused },
                singleLine = true, label = { Text("歌曲、专辑、歌单") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm.search() }),
            )
            ThorButton(registry, "search:submit", "搜索", primary = true, onClick = vm::search)
        }
        if (search.items.isNotEmpty()) ThorBrowseList(vm, registry, "search:${search.query}", search.items, Modifier.weight(1f))
        else ThorEmptyState(vm, registry, search.error ?: when { search.loading -> "正在搜索"; search.searched -> "没有找到相关音乐"; else -> "" },
            if (search.error != null) "重试" else null, vm::search, Modifier.weight(1f), search.loading)
    }
    DisposableEffect(Unit) { onDispose { vm.textEditing.value = false } }
}

@UnstableApi
@Composable
private fun ThorAppearancePage(vm: ThorShellViewModel, registry: ThorFocusRegistry, onBack: () -> Unit) {
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ThorPageHeader(registry, "主题与外观", onBack = onBack)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            item { Text("材质", color = LocalThorTheme.current.secondary, fontSize = 12.sp) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ThorThemePack.entries.forEach { pack ->
                ThorButton(registry, "theme:${pack.name}", pack.title, Modifier.weight(1f), selected = preferences.theme == pack, onClick = { vm.updatePreferences { it.copy(theme = pack) } })
            } } }
            item { Text("显示模式", color = LocalThorTheme.current.secondary, fontSize = 12.sp) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ThorAppearance.entries.forEach { appearance ->
                ThorButton(registry, "appearance:${appearance.name}", appearance.title, Modifier.weight(1f), selected = preferences.appearance == appearance, onClick = { vm.updatePreferences { it.copy(appearance = appearance) } })
            } } }
            item { ThorButton(registry, "appearance:motion", "减少动态 · ${if (preferences.reduceMotion) "开启" else "关闭"}", Modifier.fillMaxWidth(), selected = preferences.reduceMotion, onClick = { vm.updatePreferences { it.copy(reduceMotion = !it.reduceMotion) } }) }
            item { ThorButton(registry, "appearance:transparency", "减少透明 · ${if (preferences.reduceTransparency) "开启" else "关闭"}", Modifier.fillMaxWidth(), selected = preferences.reduceTransparency, onClick = { vm.updatePreferences { it.copy(reduceTransparency = !it.reduceTransparency) } }) }
        }
    }
}

@UnstableApi
@Composable
private fun ThorControlsPage(vm: ThorShellViewModel, registry: ThorFocusRegistry, onBack: () -> Unit, onRequestAirflow: (Boolean) -> Unit, onCalibrateAirflow: () -> Unit, airflowStatus: String) {
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ThorPageHeader(registry, "手柄与吹气", onBack = onBack)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 5.dp)) {
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThorButton(registry, "controls:airflow", "吹气 · ${if (preferences.airflowEnabled) "开启" else "关闭"}", Modifier.weight(1f), selected = preferences.airflowEnabled, onClick = { onRequestAirflow(!preferences.airflowEnabled) })
                ThorButton(registry, "controls:calibrate", "校准", enabled = preferences.airflowEnabled, onClick = onCalibrateAirflow)
            } }
            if (airflowStatus.isNotEmpty()) item { Text(airflowStatus, fontSize = 12.sp, color = LocalThorTheme.current.secondary) }
            val mappings = listOf("方向键 / 左摇杆" to "移动焦点", "A / B / X" to "确认 / 返回 / 更多", "Y" to "播放 / 暂停", "L1 / R1" to "切换一级页面", "L2 / R2" to "上一首 / 下一首", "Start / Select" to "播放页 / 队列")
            itemsIndexed(mappings) { _, pair -> Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text(pair.first, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(pair.second, fontSize = 13.sp, color = LocalThorTheme.current.secondary)
            } }
        }
    }
}

@Composable
@UnstableApi
private fun ThorEmptyState(vm: ThorShellViewModel, registry: ThorFocusRegistry, message: String, action: String?, onAction: () -> Unit, modifier: Modifier = Modifier, loading: Boolean = false) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(action, loading, registry.blocked) {
        vm.clearPreview()
        if (!loading && !registry.blocked) {
            withFrameNanos { }
            if ((!entered && action != null) || registry.selectedKey == null || !registry.contains(registry.selectedKey)) {
                registry.request(if (action != null) "empty:action" else null) || registry.requestFirst()
            }
            entered = true
        }
    }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (loading) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        Text(message, Modifier.padding(12.dp), color = LocalThorTheme.current.secondary, style = MaterialTheme.typography.bodyMedium)
        if (action != null) ThorButton(registry, "empty:action", action, primary = true, onClick = onAction)
    }
}

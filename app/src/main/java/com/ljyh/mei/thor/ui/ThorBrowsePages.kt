package com.ljyh.mei.thor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.thor.ThorShellViewModel
import com.ljyh.mei.thor.components.ThorSurfaceRole
import com.ljyh.mei.thor.components.ThorActionButton
import com.ljyh.mei.thor.components.thorMaterial
import com.ljyh.mei.thor.menu.ThorMenuAnchor
import com.ljyh.mei.thor.model.*
import com.ljyh.mei.thor.state.*
import com.ljyh.mei.thor.theme.LocalThorTheme
import com.ljyh.mei.thor.theme.thorThemeTokens
import com.ljyh.mei.thor.account.ThorAccountRoute
import com.ljyh.mei.thor.account.ThorAccountInputHost
import com.ljyh.mei.playback.PlayerConnection

@Composable
internal fun ThorPageHeader(registry: ThorFocusRegistry, title: String, count: Int? = null, onBack: (() -> Unit)? = null, onSearch: (() -> Unit)? = null, countUnit: String = "首", onMy: (() -> Unit)? = null) {
    val theme = LocalThorTheme.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).thorMaterial(ThorSurfaceRole.Header).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null) ThorButton(registry, "header:back", "", icon = Icons.Outlined.ChevronLeft, contentDescription = "返回", flat = true, onClick = onBack)
        Text(title, Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = theme.typography.pageTitle, lineHeight = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null) Text("共 ${count} ${countUnit}", color = LocalThorTheme.current.secondary, fontSize = 14.sp)
        if (onSearch != null) ThorButton(registry, "header:search", "", icon = Icons.Outlined.Search, contentDescription = "搜索", flat = true, onClick = onSearch)
        if (onMy != null) ThorButton(registry, "header:my", "", icon = Icons.Outlined.Settings, contentDescription = "我的", flat = true, onClick = onMy)
    }
}

private val libraryHeaderCollections = setOf(ThorCollection.CollectedAlbums, ThorCollection.LikedSongs, ThorCollection.OwnedPlaylists, ThorCollection.SavedPlaylists)
private val accountCollections = libraryHeaderCollections + setOf(ThorCollection.History, ThorCollection.DailySongs)

@UnstableApi
@Composable
private fun ThorCollectionHeader(vm: ThorShellViewModel, registry: ThorFocusRegistry, collection: ThorCollection, count: Int, onBack: () -> Unit) {
    val theme = LocalThorTheme.current
    BoxWithConstraints(Modifier.fillMaxWidth().thorMaterial(ThorSurfaceRole.Header)) {
        val showContext = maxWidth >= 420.dp
        val showCount = maxWidth >= 380.dp
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ThorButton(registry, "header:back", "", icon = Icons.Outlined.ChevronLeft, flat = true, contentDescription = "返回", onClick = onBack)
            if (showContext) Text("音乐库", Modifier.padding(end = 8.dp), color = theme.secondary, fontSize = 14.sp)
            listOf("专辑" to ThorCollection.CollectedAlbums, "歌曲" to ThorCollection.LikedSongs, "歌单" to ThorCollection.OwnedPlaylists).forEach { (label, target) ->
                val selected = collection == target || (target == ThorCollection.OwnedPlaylists && collection == ThorCollection.SavedPlaylists)
                ThorCategoryButton(registry, "collection:category:${target.name}", label, selected,
                    onClick = { if (!selected) vm.replaceCollection(target) })
            }
            Spacer(Modifier.weight(1f))
            if (showCount) Text("共 ${count} ${when (collection) { ThorCollection.CollectedAlbums -> "张"; ThorCollection.LikedSongs -> "首"; else -> "个" }}",
                color = theme.secondary, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** The shell places these actions beside its key hints, below the miniature player. */
@UnstableApi
@Composable
internal fun ThorBrowseBottomActions(
    vm: ThorShellViewModel, registry: ThorFocusRegistry, navigation: ThorNavigation, library: ThorLibraryState,
    onLegacyRoute: (String) -> Unit, isScanning: Boolean, modifier: Modifier = Modifier,
) {
    if (navigation.overlay != null) return
    when (val page = navigation.page) {
        is ThorPage.Root -> when (page.tab) {
            ThorTab.Home, ThorTab.Discover -> {
                val collection = if (page.tab == ThorTab.Home) vm.homeCategory.value.collection else ThorCollection.Discover
                Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ThorButton(registry, "root:refresh", "", Modifier.size(48.dp), icon = Icons.Outlined.Refresh,
                        flat = true, contentDescription = "刷新", onClick = { vm.refresh(collection) })
                }
            }
            ThorTab.Library -> {
                val category by vm.libraryCategory.collectAsStateWithLifecycle()
                Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (category == 2) ThorButton(registry, "library:create", "新建歌单", icon = Icons.Outlined.Add, onClick = vm::createPlaylist)
                    else ThorButton(registry, "library:import", if (isScanning) "停止扫描" else "导入音乐", icon = Icons.Outlined.FolderOpen,
                        onClick = { onLegacyRoute("local_music") })
                }
            }
            ThorTab.My -> Unit
        }
        is ThorPage.Collection -> {
            val items = library[page.collection]
            val tracks = items.flatMap { it.tracks }
            val playable = page.collection in setOf(ThorCollection.LocalSongs, ThorCollection.LikedSongs, ThorCollection.Downloads, ThorCollection.History, ThorCollection.DailySongs)
            Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page.collection == ThorCollection.OwnedPlaylists) ThorButton(registry, "collection:create", "新建歌单", primary = true,
                    icon = Icons.Outlined.Add, onClick = vm::createPlaylist)
                if (page.collection == ThorCollection.Downloads) ThorButton(registry, "collection:downloads", "", icon = Icons.Outlined.Download,
                    contentDescription = "管理下载", flat = true, onClick = { onLegacyRoute("download_manage") })
                if (page.collection in setOf(ThorCollection.LocalSongs, ThorCollection.LocalAlbums)) ThorButton(registry, "collection:scan", "", icon = Icons.Outlined.FolderOpen,
                    contentDescription = if (isScanning) "停止扫描" else "扫描音乐", flat = true, onClick = { onLegacyRoute("local_music") })
                ThorButton(registry, "collection:refresh", "", icon = Icons.Outlined.Refresh, contentDescription = "刷新", flat = true, onClick = { vm.refresh(page.collection) })
                if (playable) {
                    ThorButton(registry, "collection:shuffle", "L3 随机播放", Modifier.widthIn(min = 96.dp), enabled = tracks.isNotEmpty(),
                        onClick = { vm.playPage(true) })
                    ThorButton(registry, "collection:play", "R3 播放全部", Modifier.widthIn(min = 96.dp), primary = true, enabled = tracks.isNotEmpty(),
                        onClick = { vm.playPage(false) })
                }
            }
        }
        is ThorPage.Artist -> {
            val state by vm.artistBrowse.collectAsStateWithLifecycle()
            val current = state?.takeIf { it.item.key == page.item.key }
            val songs = current?.section != ThorArtistSection.Albums
            val partial = current?.hasMore == true
            val enabled = current?.items?.any { it.tracks.isNotEmpty() } == true
            Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ThorButton(registry, "artist:refresh", "", icon = Icons.Outlined.Refresh, contentDescription = "刷新", flat = true, onClick = vm::refreshArtist)
                if (songs) {
                    ThorButton(registry, "artist:shuffle", if (partial) "L3 随机已加载" else "L3 随机播放", Modifier.widthIn(min = 96.dp), enabled = enabled,
                        onClick = { vm.playPage(true) })
                    ThorButton(registry, "artist:play", if (partial) "R3 播放已加载" else "R3 播放全部", Modifier.widthIn(min = 96.dp), primary = true, enabled = enabled,
                        onClick = { vm.playPage(false) })
                }
            }
        }
        is ThorPage.Detail -> {
            val item = page.item
            Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ThorButton(registry, "detail:more", "", icon = Icons.Outlined.MoreHoriz, flat = true, contentDescription = "更多操作", onClick = {
                    val bounds = registry.current?.bounds
                    vm.openMenu(ThorMenuSubject(item, "detail:more"), ThorMenuAnchor(bounds?.left ?: 30f, bounds?.bottom ?: 100f))
                })
                ThorButton(registry, "detail:shuffle", "L3 随机播放", Modifier.widthIn(min = 96.dp), enabled = item.tracks.isNotEmpty(),
                    onClick = { vm.playPage(true) })
                ThorButton(registry, "detail:play", "R3 播放全部", Modifier.widthIn(min = 96.dp), primary = true, enabled = item.tracks.isNotEmpty(),
                    onClick = { vm.playPage(false) })
            }
        }
        else -> Unit
    }
}

private fun destinationIcon(item: ThorItem) = (item.key as? ThorItemKey.Destination)?.id?.let { id ->
    when (id) {
        "LikedSongs" -> Icons.Outlined.FavoriteBorder
        "CollectedAlbums", "SavedPlaylists" -> Icons.Outlined.BookmarkBorder
        "OwnedPlaylists" -> Icons.Outlined.QueueMusic
        "LocalSongs", "LocalAlbums" -> Icons.Outlined.FolderOpen
        "Downloads" -> Icons.Outlined.Download
        "History" -> Icons.Outlined.History
        "account" -> Icons.Outlined.AccountCircle.takeIf { item.artworkUri.isNullOrBlank() }
        "bindings" -> Icons.Outlined.Link
        "appearance" -> Icons.Outlined.Palette
        "controls" -> Icons.Outlined.Gamepad
        "play" -> Icons.Outlined.Settings
        "about" -> Icons.Outlined.Info
        else -> Icons.Outlined.MusicNote
    }
}

@Composable
private fun ThorThemeSample(registry: ThorFocusRegistry, pack: ThorThemePack, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val current = LocalThorTheme.current
    val sample = remember(pack, current.isDark, current.reduceMotion, current.reduceTransparency) {
        thorThemeTokens(pack, current.isDark, current.reduceMotion, current.reduceTransparency)
    }
    val key = "theme:${pack.name}"
    CompositionLocalProvider(LocalThorTheme provides sample) {
        ThorActionButton(
            onClick = { if (!registry.blocked) { registry.select(key); onClick() } },
            modifier = modifier.heightIn(min = 100.dp).thorFocus(registry, key, onClick = onClick),
            selected = selected, focused = registry.selectedKey == key, role = ThorSurfaceRole.Panel,
            contentPadding = PaddingValues(8.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().height(42.dp).thorMaterial(ThorSurfaceRole.Deck).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(25.dp).thorMaterial(ThorSurfaceRole.Primary), contentAlignment = Alignment.Center) {
                        Icon(thorLineIcon(Icons.Outlined.PlayArrow), null, Modifier.size(14.dp), tint = sample.onAccent)
                    }
                    Box(Modifier.weight(1f).height(5.dp).thorMaterial(ThorSurfaceRole.ProgressTrack))
                }
                Text(pack.title, color = sample.text, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/** Explicit pagination keeps one closed list and hands its tail cursor to the new first row. */
internal data class ThorListContinuation(
    val loading: Boolean,
    val error: String? = null,
    val append: Boolean = true,
    val onLoad: () -> Unit,
)

@Composable
internal fun ThorCatalogContinuationRow(
    registry: ThorFocusRegistry, key: String, loading: Boolean, error: String?, onClick: () -> Unit,
) {
    ThorButton(registry, key, if (loading) "正在加载" else if (error != null) "加载失败 · 重试" else "加载更多",
        Modifier.fillMaxWidth().height(56.dp).testTag("catalog-load-more"), icon = if (loading) null else Icons.Outlined.ExpandMore,
        selected = registry.selectedKey == key, flat = true, onClick = { if (!loading) onClick() })
}

@UnstableApi
@Composable
internal fun ThorBrowseList(
    vm: ThorShellViewModel, registry: ThorFocusRegistry, scene: String, items: List<ThorItem>,
    modifier: Modifier = Modifier, sourcePlaylist: ThorItemKey.Playlist? = null,
    continuation: ThorListContinuation? = null,
    pageActions: ThorPageActionBindings? = null,
    trailingLabel: ((ThorItem) -> String?)? = null,
    onItem: ((ThorItem, Int) -> Unit)? = null,
) {
    val library by vm.library.collectAsStateWithLifecycle()
    val localLikes = remember(library[ThorCollection.LikedSongs]) { library[ThorCollection.LikedSongs].map { it.key }.toSet() }
    val collected = remember(library[ThorCollection.CollectedAlbums], library[ThorCollection.SavedPlaylists]) {
        (library[ThorCollection.CollectedAlbums] + library[ThorCollection.SavedPlaylists]).map { it.key }.toSet()
    }
    val listState = rememberLazyListState()
    val itemKeys = remember(scene, items) {
        val occurrences = mutableMapOf<String, Int>()
        items.map { item ->
            val id = item.key.stableId
            val occurrence = occurrences[id] ?: 0
            occurrences[id] = occurrence + 1
            "$scene:item:$id:$occurrence"
        }
    }
    val tailKey = "$scene:more:${items.size}"
    val keys = if (continuation != null) itemKeys + tailKey else itemKeys
    var appendFrom by remember(scene) { mutableStateOf<Int?>(null) }
    val handoff by rememberUpdatedState(when {
        appendFrom != null && items.size > appendFrom!! -> itemKeys.getOrNull(appendFrom!!)
        continuation == null -> itemKeys.lastOrNull()
        else -> null
    })
    // Only a departed pagination row may suggest a new target. Song identity, duplicate order,
    // ordinary return focus and exact viewport memory continue to use the existing VM contract.
    val memory = remember(vm, scene) { object : ThorListFocusMemory {
        override fun readFocus(scene: String): String? {
            val saved = vm.rememberedFocus(scene)
            return if (saved?.startsWith("$scene:more:") == true && handoff != null) handoff else saved
        }
        override fun readScroll(scene: String) = vm.rememberedListScroll(scene)
        override fun saveFocus(scene: String, key: String) = vm.rememberFocus(scene, key)
        override fun saveScroll(scene: String, key: String, offset: Int) = vm.rememberListScroll(scene, key, offset)
        override fun preview(item: ThorItem?) { if (item != null) vm.preview(item) }
    } }
    val requestMore: () -> Unit = {
        continuation?.takeUnless { it.loading }?.let {
            appendFrom = items.size.takeIf { _ -> it.append && registry.selectedKey == tailKey }
            it.onLoad()
        }
    }
    if (continuation != null) BindThorPageAction(pageActions, "catalog:load-more", requestMore)
    val focus = BindThorListFocus(registry, scene, keys, items + if (continuation != null) listOf(null) else emptyList(), listState, memory) { index ->
        if (index == items.size) {
            requestMore()
        } else {
            val item = items[index]
            when {
                onItem != null -> onItem(item, index)
                items.all { it.key is ThorItemKey.Song && it.tracks.isNotEmpty() } -> vm.play(items.flatMap { it.tracks }, index,
                    (vm.navigation.value.page as? ThorPage.Collection)?.collection?.title ?: "播放列表")
                else -> vm.open(item)
            }
        }
    }
    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp)) {
        itemsIndexed(items, key = { index, _ -> itemKeys[index] }) { index, item ->
            ThorMusicRow(registry, itemKeys[index], item,
                trailing = trailingLabel?.invoke(item) ?: item.trackCount?.let { "$it" },
                onClick = { focus.activate(index) },
                onMenu = { anchor -> focus.onFocused(index); vm.openMenu(ThorMenuSubject(item, itemKeys[index], sourcePlaylist), anchor) },
                onFocused = { focus.onFocused(index) },
                leadingIcon = destinationIcon(item),
                mark = when (val key = item.key) {
                    is ThorItemKey.Song -> if (if (key.local) key in localLikes else library.songLikes[key.id] == true) Icons.Outlined.Favorite else null
                    is ThorItemKey.Album, is ThorItemKey.Playlist -> if (item.key in collected) Icons.Outlined.Bookmark else null
                    else -> null
                },
            )
        }
        if (continuation != null) item(key = tailKey) {
            ThorCatalogContinuationRow(registry, tailKey, continuation.loading, continuation.error) { focus.activate(items.size) }
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
    airflowStrength: Float = 0f,
    airflowCalibrationLabel: String = "校准",
    airflowCalibrationProgress: Float = 0f,
    pageActions: ThorPageActionBindings? = null,
    playerConnection: PlayerConnection? = null,
    accountInput: ThorAccountInputHost? = null,
) {
    val loading by vm.detailLoading.collectAsStateWithLifecycle()
    when (val page = navigation.page) {
        is ThorPage.Root -> when (page.tab) {
            ThorTab.My -> ThorMyPage(vm, registry, library, onLegacyRoute)
            ThorTab.Library -> ThorLibraryPage(vm, registry, library)
            ThorTab.Home -> ThorHomeDiscoveryPage(vm, registry, library, pageActions)
            ThorTab.Discover -> ThorDiscoverPage(vm, registry, library, pageActions)
        }
        is ThorPage.Collection -> Column(Modifier.fillMaxSize()) {
            val items = library[page.collection]
            val songList = page.collection in setOf(ThorCollection.LocalSongs, ThorCollection.LikedSongs, ThorCollection.Downloads, ThorCollection.History, ThorCollection.DailySongs)
            if (page.collection in libraryHeaderCollections) ThorCollectionHeader(vm, registry, page.collection, items.size, onBack)
            else ThorPageHeader(registry, page.collection.title, items.size, onBack = onBack, countUnit = if (songList) "首" else if (page.collection == ThorCollection.LocalAlbums) "张" else "个")
            if (items.isEmpty()) {
                val needsLogin = !library.account.signedIn && page.collection in accountCollections
                val busy = !needsLogin && page.collection in library.loading
                val local = page.collection in setOf(ThorCollection.LocalSongs, ThorCollection.LocalAlbums)
                ThorEmptyState(vm, registry, if (needsLogin) "登录后查看${page.collection.title}" else library.errors[page.collection] ?: if (local && isScanning) "正在扫描本地音乐" else if (busy) "正在加载" else "暂无${page.collection.title}",
                    if (needsLogin) "登录网易云音乐" else if (busy) null else if (local && isScanning) "停止扫描" else if (local) "导入音乐" else "刷新", {
                        if (needsLogin) vm.push(ThorPage.Account(ThorAccountRoute.Login)) else if (local) onLegacyRoute("local_music") else vm.refresh(page.collection)
                    }, Modifier.weight(1f), busy)
            } else ThorBrowseList(vm, registry, page.key, items, Modifier.weight(1f))
        }
        is ThorPage.Artist -> ThorArtistDiscoveryPage(vm, registry, page.item, onBack)
        is ThorPage.Detail -> Column(Modifier.fillMaxSize()) {
            val item = page.item
            ThorPageHeader(registry, item.title, item.trackCount ?: item.tracks.size, onBack)
            if (item.tracks.isEmpty()) ThorEmptyState(vm, registry, if (loading) "正在加载" else "暂无歌曲", if (loading) null else "重新加载", { vm.open(item) }, Modifier.weight(1f), loading)
            else ThorBrowseList(vm, registry, page.key, item.tracks.map { it.toThorItem() }, Modifier.weight(1f),
                sourcePlaylist = (item.key as? ThorItemKey.Playlist)?.takeIf { item.owned },
                onItem = { _, index -> vm.play(item.tracks, index, item.title) })
        }
        ThorPage.Search -> ThorSearchPage(vm, registry, onBack, pageActions)
        ThorPage.Appearance -> ThorAppearancePage(vm, registry, onBack)
        ThorPage.Controls -> ThorControlsPage(vm, registry, onBack, onRequestAirflow, onCalibrateAirflow, airflowStatus,
            airflowStrength, airflowCalibrationLabel, airflowCalibrationProgress)
        is ThorPage.Account -> ThorAccountPage(page.route, vm, registry,
            onNavigate = { vm.push(ThorPage.Account(it)) }, onBack = onBack,
            onLoginSuccess = vm::completeAccountLogin, pageActions = pageActions, input = accountInput)
        ThorPage.AudioSettings -> ThorAudioSettingsPage(vm, registry, onBack,
            onOpenEqualizer = { vm.push(ThorPage.Equalizer) }, playerConnection = playerConnection, pageActions = pageActions)
        ThorPage.Equalizer -> ThorEqualizerSettingsPage(vm, registry, onBack, pageActions)
    }
}

@UnstableApi
@Composable
private fun ThorLibraryPage(vm: ThorShellViewModel, registry: ThorFocusRegistry, library: ThorLibraryState) {
    val category by vm.libraryCategory.collectAsStateWithLifecycle()
    val collections = when (category) {
        1 -> listOf(ThorCollection.CollectedAlbums, ThorCollection.LocalAlbums)
        2 -> listOf(ThorCollection.OwnedPlaylists, ThorCollection.SavedPlaylists)
        else -> listOf(ThorCollection.LikedSongs, ThorCollection.LocalSongs, ThorCollection.Downloads, ThorCollection.History)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).thorMaterial(ThorSurfaceRole.Header).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("音乐库", Modifier.weight(1f), color = LocalThorTheme.current.secondary, fontSize = 14.sp)
            listOf("专辑" to 1, "歌曲" to 0, "歌单" to 2).forEach { (title, index) ->
                ThorCategoryButton(registry, "library:category:$index", title, selected = category == index, onClick = { vm.selectLibraryCategory(index) })
            }
            ThorButton(registry, "header:search", "", icon = Icons.Outlined.Search, flat = true, contentDescription = "搜索", onClick = { vm.push(ThorPage.Search) })
            ThorButton(registry, "header:my", "", icon = Icons.Outlined.Settings, flat = true, contentDescription = "我的", onClick = { vm.selectTab(ThorTab.My) })
        }
        ThorBrowseList(vm, registry, "root:Library:$category", collections.map { collection ->
            ThorItem(ThorItemKey.Destination(collection.name), collection.title, "${library[collection].size} ${if (category == 0) "首" else "项"}", library[collection].firstOrNull()?.artworkUri)
        }, Modifier.weight(1f), onItem = { item, _ -> vm.openCollection(ThorCollection.valueOf((item.key as ThorItemKey.Destination).id)) })
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
        ThorPageHeader(registry, "我的", onSearch = { vm.push(ThorPage.Search) }, onMy = { vm.selectTab(ThorTab.My) })
        ThorBrowseList(vm, registry, "my", items, Modifier.weight(1f), onItem = { item, _ ->
            when ((item.key as ThorItemKey.Destination).id) {
                "appearance" -> vm.push(ThorPage.Appearance)
                "controls" -> vm.push(ThorPage.Controls)
                "account" -> vm.push(ThorPage.Account(if (account.signedIn) ThorAccountRoute.Home else ThorAccountRoute.Login))
                "bindings" -> vm.push(ThorPage.Account(ThorAccountRoute.Bindings))
                "play" -> vm.push(ThorPage.AudioSettings)
                else -> onLegacyRoute("about")
            }
        })
    }
}

@UnstableApi
@Composable
private fun ThorAppearancePage(vm: ThorShellViewModel, registry: ThorFocusRegistry, onBack: () -> Unit) {
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    BindThorOptionFocus(vm, registry, ThorThemePack.entries.map { "theme:${it.name}" } +
        ThorAppearance.entries.map { "appearance:${it.name}" } +
        ThorBackgroundMode.entries.map { "background:mode:${it.name}" } +
        (if (preferences.backgroundMode != ThorBackgroundMode.Clean) ThorBackgroundIntensity.entries.map { "background:intensity:${it.name}" } else emptyList()) +
        listOf("appearance:motion", "appearance:transparency"))
    Column(Modifier.fillMaxSize()) {
        ThorPageHeader(registry, "主题与外观", onBack = onBack)
        // A small fixed set stays composed, so native focus can reach its controls while scrolling.
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("材质", color = LocalThorTheme.current.secondary, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThorThemePack.entries.forEach { pack ->
                    ThorThemeSample(registry, pack, preferences.theme == pack, Modifier.weight(1f),
                        onClick = { vm.updatePreferences { it.copy(theme = pack) } })
                }
            }
            Text("显示模式", color = LocalThorTheme.current.secondary, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThorAppearance.entries.forEach { appearance ->
                    ThorButton(registry, "appearance:${appearance.name}", appearance.title, Modifier.weight(1f),
                        selected = preferences.appearance == appearance,
                        onClick = { vm.updatePreferences { it.copy(appearance = appearance) } })
                }
            }
            Text("播放背景", color = LocalThorTheme.current.secondary, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThorBackgroundMode.entries.forEach { mode ->
                    ThorButton(registry, "background:mode:${mode.name}", mode.title, Modifier.weight(1f),
                        selected = preferences.backgroundMode == mode,
                        onClick = { vm.updatePreferences { it.copy(backgroundMode = mode) } })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("强度", color = LocalThorTheme.current.secondary, fontSize = 12.sp)
                ThorBackgroundIntensity.entries.forEach { intensity ->
                    ThorButton(registry, "background:intensity:${intensity.name}", intensity.title, Modifier.weight(1f),
                        enabled = preferences.backgroundMode != ThorBackgroundMode.Clean,
                        selected = preferences.backgroundIntensity == intensity,
                        onClick = { vm.updatePreferences { it.copy(backgroundIntensity = intensity) } })
                }
            }
            ThorButton(registry, "appearance:motion", "减少动态 · ${if (preferences.reduceMotion) "开启" else "关闭"}", Modifier.fillMaxWidth(),
                selected = preferences.reduceMotion, onClick = { vm.updatePreferences { it.copy(reduceMotion = !it.reduceMotion) } })
            ThorButton(registry, "appearance:transparency", "减少透明 · ${if (preferences.reduceTransparency) "开启" else "关闭"}", Modifier.fillMaxWidth(),
                selected = preferences.reduceTransparency, onClick = { vm.updatePreferences { it.copy(reduceTransparency = !it.reduceTransparency) } })
        }
    }
}

@UnstableApi
@Composable
private fun ThorControlsPage(vm: ThorShellViewModel, registry: ThorFocusRegistry, onBack: () -> Unit, onRequestAirflow: (Boolean) -> Unit, onCalibrateAirflow: () -> Unit, airflowStatus: String,
    airflowStrength: Float, airflowCalibrationLabel: String, airflowCalibrationProgress: Float) {
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    BindThorOptionFocus(vm, registry, listOf("controls:airflow") + if (preferences.airflowEnabled) listOf("controls:calibrate") else emptyList())
    Column(Modifier.fillMaxSize()) {
        ThorPageHeader(registry, "手柄与吹气", onBack = onBack)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 5.dp)) {
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThorButton(registry, "controls:airflow", "吹气 · ${if (preferences.airflowEnabled) "开启" else "关闭"}", Modifier.weight(1f), selected = preferences.airflowEnabled, onClick = { onRequestAirflow(!preferences.airflowEnabled) })
                ThorButton(registry, "controls:calibrate", airflowCalibrationLabel, enabled = preferences.airflowEnabled, onClick = onCalibrateAirflow)
            } }
            if (preferences.airflowEnabled) item {
                val level = (if (airflowCalibrationProgress > 0f) airflowCalibrationProgress else airflowStrength)
                    .takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
                Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f).height(4.dp).thorMaterial(ThorSurfaceRole.ProgressTrack)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(level).background(LocalThorTheme.current.accent))
                    }
                    Text("${(level * 100).toInt()}%", color = LocalThorTheme.current.secondary, fontSize = 12.sp)
                }
            }
            if (airflowStatus.isNotEmpty()) item { Text(airflowStatus, fontSize = 12.sp, color = LocalThorTheme.current.secondary) }
            val mappings = listOf("方向键 / 左摇杆" to "列表 / 选项", "A / B / X" to "确认 / 返回 / 条目操作", "Y" to "播放 / 暂停", "L1 / R1" to "一级切页 / 播放快进", "L2 / R2" to "上一首 / 下一首", "L3 / R3" to "随机 / 播放全部", "Start" to "播放页", "Select 短按 / 长按" to "队列 / 页面操作")
            itemsIndexed(mappings) { _, pair -> Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text(pair.first, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(pair.second, fontSize = 13.sp, color = LocalThorTheme.current.secondary)
            } }
        }
    }
}

@Composable
@UnstableApi
internal fun ThorEmptyState(vm: ThorShellViewModel, registry: ThorFocusRegistry, message: String, action: String?, onAction: () -> Unit, modifier: Modifier = Modifier, loading: Boolean = false) {
    BindThorOptionFocus(vm, registry, if (action != null) listOf("empty:action") else emptyList(), ThorFocusLayout.Vertical)
    LaunchedEffect(action, loading) { vm.clearPreview() }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (loading) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        Text(message, Modifier.padding(12.dp), color = LocalThorTheme.current.secondary, style = MaterialTheme.typography.bodyMedium)
        if (action != null) ThorButton(registry, "empty:action", action, primary = true, onClick = onAction)
    }
}

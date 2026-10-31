package com.ljyh.mei.thor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.thor.ThorShellViewModel
import com.ljyh.mei.thor.components.ThorActionButton
import com.ljyh.mei.thor.components.ThorSurfaceRole
import com.ljyh.mei.thor.components.thorMaterial
import com.ljyh.mei.thor.model.*
import com.ljyh.mei.thor.state.ThorPage
import com.ljyh.mei.thor.theme.LocalThorTheme

internal data class ThorCatalogChoice(val value: String, val title: String)
private enum class CatalogPicker(val title: String) { Playlist("歌单分类"), ArtistArea("歌手地区"), ArtistType("歌手类型") }

// These are the same request values already supported by the client's FindMusic catalog.
private val playlistChoices = ("全部,华语,欧美,日语,韩语,粤语,小语种,流行,摇滚,民谣,电子,舞曲,说唱,轻音乐,爵士,乡村,R&B/Soul,古典,民族,英伦,金属,朋克,蓝调,雷鬼,世界音乐,拉丁,另类/独立,New Age,古风,后摇,Bossa Nova,清晨,夜晚,学习,工作,午休,下午茶,地铁,驾车,运动,旅行,散步,酒吧,怀旧,清新,浪漫,性感,伤感,治愈,放松,孤独,感动,兴奋,快乐,安静,思念,影视原声,ACG,儿童,校园,游戏,70后,80后,90后,网络歌曲,KTV,经典,翻唱,吉他,钢琴,器乐,榜单,00后")
    .split(',').map { ThorCatalogChoice(it, it) }
private val artistAreaChoices = listOf(-1 to "全部地区", 7 to "华语", 96 to "欧美", 8 to "日本", 16 to "韩国", 0 to "其他")
    .map { (value, title) -> ThorCatalogChoice(value.toString(), title) }
private val artistTypeChoices = listOf(-1 to "全部类型", 1 to "男歌手", 2 to "女歌手", 3 to "乐队组合")
    .map { (value, title) -> ThorCatalogChoice(value.toString(), title) }

/** Header tabs are touch controls. Horizontal controller commands select them through the VM. */
@Composable
internal fun ThorDiscoveryTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ThorActionButton(onClick, modifier.height(48.dp).widthIn(min = 48.dp)
        .focusProperties { canFocus = false }.semantics { this.selected = selected },
        selected = selected, focused = false, role = ThorSurfaceRole.Flat,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
private fun ThorFilterChip(label: String, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ThorActionButton(onClick, modifier.height(48.dp).widthIn(min = 48.dp)
        .focusProperties { canFocus = false }.semantics { contentDescription = description },
        role = ThorSurfaceRole.Flat, focused = false, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
        Icon(thorLineIcon(Icons.Outlined.ExpandMore), null, Modifier.size(12.dp))
    }
}

@Composable
internal fun ThorHomeCatalogHeader(
    category: ThorHomeCategory, filters: ThorCatalogFilters,
    onCategory: (ThorHomeCategory) -> Unit, onSearch: () -> Unit,
    onPlaylistFilter: () -> Unit, onArtistArea: () -> Unit, onArtistType: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(56.dp).thorMaterial(ThorSurfaceRole.Header).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ThorHomeCategory.entries.forEach { item ->
            ThorDiscoveryTab(item.title, category == item, Modifier.testTag("home-category:${item.name}")) { onCategory(item) }
        }
        Spacer(Modifier.weight(1f))
        when (category) {
            ThorHomeCategory.Playlists -> ThorFilterChip(filters.playlistCategory, "歌单分类", Modifier.widthIn(max = 100.dp), onPlaylistFilter)
            ThorHomeCategory.Artists -> {
                ThorFilterChip(artistAreaChoices.firstOrNull { it.value == filters.artistArea.toString() }?.title ?: "全部地区",
                    "歌手地区", Modifier.widthIn(max = 78.dp), onArtistArea)
                ThorFilterChip(artistTypeChoices.firstOrNull { it.value == filters.artistType.toString() }?.title ?: "全部类型",
                    "歌手类型", Modifier.widthIn(max = 78.dp), onArtistType)
            }
            else -> Unit
        }
        ThorActionButton(onSearch, Modifier.size(48.dp).focusProperties { canFocus = false }
            .semantics { contentDescription = "搜索" }, role = ThorSurfaceRole.Flat, focused = false,
            contentPadding = PaddingValues(0.dp)) { Icon(thorLineIcon(Icons.Outlined.Search), null, Modifier.size(18.dp)) }
    }
}

/** A temporary, closed list in the existing lower window; it never opens an Android dialog. */
@Composable
internal fun ThorCatalogPicker(
    registry: ThorFocusRegistry, memory: ThorListFocusMemory, scene: String,
    title: String, choices: List<ThorCatalogChoice>, selectedValue: String,
    onSelect: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier,
) {
    val theme = LocalThorTheme.current
    val listState = rememberLazyListState()
    val keys = remember(scene, choices) { choices.map { "$scene:${it.value}" } }
    val binding = BindThorListFocus(registry, scene, keys, choices.map { null }, listState, memory) { index ->
        choices.getOrNull(index)?.let { onSelect(it.value) }
    }
    Column(modifier.fillMaxSize()) {
        ThorPageHeader(registry, title, onBack = onBack)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp)) {
            itemsIndexed(choices, key = { index, _ -> keys[index] }) { index, choice ->
                ThorActionButton(onClick = { binding.activate(index) },
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag("catalog-choice:${choice.value}")
                        .thorFocus(registry, keys[index], onClick = { binding.activate(index) })
                        .semantics { selected = choice.value == selectedValue },
                    selected = choice.value == selectedValue, focused = registry.selectedKey == keys[index],
                    role = ThorSurfaceRole.MusicRow, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
                    Text(choice.title, Modifier.weight(1f), color = theme.text, fontSize = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (choice.value == selectedValue) Icon(thorLineIcon(Icons.Outlined.Check), null, Modifier.size(18.dp), tint = theme.accent)
                }
            }
        }
    }
}

/** The same chooser callback is reached by touch and the long-Select page action menu. */
@Composable
@UnstableApi
internal fun ThorHomeDiscoveryPage(vm: ThorShellViewModel, registry: ThorFocusRegistry, library: ThorLibraryState, pageActions: ThorPageActionBindings?) {
    val category by vm.homeCategory.collectAsStateWithLifecycle()
    val filters by vm.catalogFilters.collectAsStateWithLifecycle()
    var picker by remember(category) { mutableStateOf<CatalogPicker?>(null) }
    val scene = "root:Home:${category.name}"
    BindThorPageAction(pageActions, "catalog:playlist-filter") { picker = CatalogPicker.Playlist }
    BindThorPageAction(pageActions, "catalog:artist-area") { picker = CatalogPicker.ArtistArea }
    BindThorPageAction(pageActions, "catalog:artist-type") { picker = CatalogPicker.ArtistType }
    val activePicker = picker
    if (activePicker != null) {
        BindThorPageAction(pageActions, "catalog:picker-active") {}
        BindThorPageAction(pageActions, "page:back") { picker = null }
        val choices = when (activePicker) {
            CatalogPicker.Playlist -> playlistChoices
            CatalogPicker.ArtistArea -> artistAreaChoices
            CatalogPicker.ArtistType -> artistTypeChoices
        }
        val value = when (activePicker) {
            CatalogPicker.Playlist -> filters.playlistCategory
            CatalogPicker.ArtistArea -> filters.artistArea.toString()
            CatalogPicker.ArtistType -> filters.artistType.toString()
        }
        val pickerScene = "$scene:filter:${activePicker.name}"
        val memory = remember(vm, pickerScene, value) { object : ThorListFocusMemory {
            override fun readFocus(scene: String) = vm.rememberedFocus(scene) ?: "$scene:$value"
            override fun readScroll(scene: String) = vm.rememberedListScroll(scene)
            override fun saveFocus(scene: String, key: String) = vm.rememberFocus(scene, key)
            override fun saveScroll(scene: String, key: String, offset: Int) = vm.rememberListScroll(scene, key, offset)
            override fun preview(item: ThorItem?) = Unit
        } }
        ThorCatalogPicker(registry, memory, pickerScene, activePicker.title, choices, value,
            onSelect = { selected ->
                picker = null
                when (activePicker) {
                    CatalogPicker.Playlist -> vm.setPlaylistCategory(selected)
                    CatalogPicker.ArtistArea -> vm.setArtistArea(selected.toInt())
                    CatalogPicker.ArtistType -> vm.setArtistType(selected.toInt())
                }
            }, onBack = { picker = null })
        return
    }
    val collection = category.collection
    val entries = library[collection]
    val items = if (category == ThorHomeCategory.Recommended)
        listOf(ThorItem(ThorItemKey.Destination("DailySongs"), "每日推荐", artworkUri = library[ThorCollection.DailySongs].firstOrNull()?.artworkUri)) + entries
    else entries
    val error = library.errors[collection]
    val busy = collection in library.loading || collection in library.loadingMore
    val continuation = if (collection in library.hasMore || (items.isNotEmpty() && (error != null || busy)))
        ThorListContinuation(busy, error, append = collection in library.hasMore) {
            if (collection in library.hasMore) vm.loadMore(collection) else vm.refresh(collection)
        }
    else null
    Column(Modifier.fillMaxSize()) {
        ThorHomeCatalogHeader(category, filters, vm::selectHomeCategory, { vm.push(ThorPage.Search) },
            { picker = CatalogPicker.Playlist }, { picker = CatalogPicker.ArtistArea }, { picker = CatalogPicker.ArtistType })
        if (items.isEmpty()) ThorEmptyState(vm, registry, error ?: if (busy) "正在加载" else "暂无内容",
            if (busy) null else "刷新", { vm.refresh(collection) }, Modifier.weight(1f), busy)
        else ThorBrowseList(vm, registry, scene, items, Modifier.weight(1f), continuation = continuation,
            onItem = { item, _ -> if (item.key == ThorItemKey.Destination("DailySongs")) vm.openCollection(ThorCollection.DailySongs) else vm.open(item) })
    }
}

@Composable
internal fun ThorArtistCatalogHeader(
    title: String, section: ThorArtistSection, count: Int,
    onSection: (ThorArtistSection) -> Unit, onBack: () -> Unit, hasMore: Boolean = false,
) {
    val theme = LocalThorTheme.current
    Row(Modifier.fillMaxWidth().height(56.dp).thorMaterial(ThorSurfaceRole.Header).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ThorActionButton(onBack, Modifier.size(48.dp).focusProperties { canFocus = false }
            .semantics { contentDescription = "返回" }, role = ThorSurfaceRole.Flat, focused = false,
            contentPadding = PaddingValues(0.dp)) { Icon(thorLineIcon(Icons.Outlined.ChevronLeft), null, Modifier.size(18.dp)) }
        Text(title, Modifier.weight(1f), color = theme.text, fontSize = theme.typography.pageTitle,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ThorArtistSection.entries.forEach { item ->
            ThorDiscoveryTab(item.title, section == item, Modifier.testTag("artist-section:${item.name}")) { onSection(item) }
        }
        Text("$count${if (hasMore) "+" else ""}${if (section == ThorArtistSection.Albums) "张" else "首"}", color = theme.secondary, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
@UnstableApi
internal fun ThorArtistDiscoveryPage(vm: ThorShellViewModel, registry: ThorFocusRegistry, item: ThorItem, onBack: () -> Unit) {
    val state by vm.artistBrowse.collectAsStateWithLifecycle()
    val current = state?.takeIf { it.item.key == item.key }
    val section = current?.section ?: ThorArtistSection.HotSongs
    val items = current?.items.orEmpty()
    val loading = current == null || current.isLoading
    val error = current?.error
    val continuation = if (current != null && (current.hasMore || (items.isNotEmpty() && (error != null || loading))))
        ThorListContinuation(loading, error, append = current.hasMore) { if (current.hasMore) vm.loadMoreArtist() else vm.refreshArtist() }
    else null
    Column(Modifier.fillMaxSize()) {
        ThorArtistCatalogHeader(current?.item?.title ?: item.title, section, items.size, vm::selectArtistSection, onBack, hasMore = current?.hasMore == true)
        if (items.isEmpty()) ThorEmptyState(vm, registry, error ?: if (loading) "正在加载" else if (section == ThorArtistSection.Albums) "暂无专辑" else "暂无歌曲",
            if (loading) null else "刷新", vm::refreshArtist, Modifier.weight(1f), loading)
        else ThorBrowseList(vm, registry, current!!.sceneKey, items, Modifier.weight(1f), continuation = continuation,
            onItem = { selected, index ->
                if (section == ThorArtistSection.Albums) vm.open(selected)
                else vm.play(items.flatMap { it.tracks }, index, current.item.title)
            })
    }
}

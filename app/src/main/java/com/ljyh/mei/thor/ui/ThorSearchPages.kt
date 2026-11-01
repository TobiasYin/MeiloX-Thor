package com.ljyh.mei.thor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.thor.ThorShellViewModel
import com.ljyh.mei.thor.components.ThorActionButton
import com.ljyh.mei.thor.components.ThorSurfaceRole
import com.ljyh.mei.thor.components.thorMaterial
import com.ljyh.mei.thor.model.*
import com.ljyh.mei.thor.theme.LocalThorTheme

/** Type tabs share the existing header height and never enter the result-list focus scope. */
@Composable
internal fun ThorSearchCatalogHeader(
    category: ThorSearchCategory,
    onCategory: (ThorSearchCategory) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().height(56.dp).testTag("thor-search-header")
        .thorMaterial(ThorSurfaceRole.Header).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ThorActionButton(onBack, Modifier.size(48.dp).focusProperties { canFocus = false }
            .semantics { contentDescription = "返回" }, role = ThorSurfaceRole.Flat, focused = false,
            contentPadding = PaddingValues(0.dp)) { Icon(thorLineIcon(Icons.Outlined.ChevronLeft), null, Modifier.size(18.dp)) }
        ThorSearchCategory.entries.forEach { type ->
            ThorDiscoveryTab(type.title, category == type, Modifier.weight(1f).testTag("search-category:${type.name}")) { onCategory(type) }
        }
    }
}

/** One short failure state, with a real 48dp retry target; successful rows remain usable. */
@Composable
internal fun ThorSearchPartialFailure(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalThorTheme.current
    Row(modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("部分结果未能加载", Modifier.weight(1f), color = theme.secondary, fontSize = 12.sp, maxLines = 1)
        ThorActionButton(onRetry, Modifier.widthIn(min = 48.dp).height(48.dp).focusProperties { canFocus = false }
            .testTag("search-partial-retry"), role = ThorSurfaceRole.Flat, focused = false,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("重试", fontSize = 14.sp) }
    }
}

/** Shared by touch and page actions: changing a view never submits an in-progress draft. */
internal fun selectThorSearchCategory(
    category: ThorSearchCategory,
    bindings: ThorPageActionBindings?,
    select: (ThorSearchCategory) -> Unit,
) {
    bindings?.invoke("search:finishEditing")
    select(category)
}

@UnstableApi
@Composable
internal fun ThorSearchPage(vm: ThorShellViewModel, registry: ThorFocusRegistry, onBack: () -> Unit, pageActions: ThorPageActionBindings?) {
    val search by vm.search.collectAsStateWithLifecycle()
    val entry by vm.searchEntry.collectAsStateWithLifecycle()
    val items = search.visibleItems
    val failedType = if (search.category == ThorSearchCategory.All) search.failedCategories.isNotEmpty()
        else search.category in search.failedCategories
    val emptyMessage = search.error ?: when {
        search.loading -> "正在搜索"
        failedType -> if (search.category == ThorSearchCategory.All) "搜索失败，请重试" else "${search.category.title}搜索失败，请重试"
        search.searched -> if (search.category == ThorSearchCategory.All) "没有找到相关音乐" else "没有找到相关${search.category.title}"
        else -> ""
    }
    val retry: () -> Unit = { if (pageActions?.invoke("search:submit") != true) vm.search() }
    Column(Modifier.fillMaxSize()) {
        ThorSearchCatalogHeader(search.category, { selectThorSearchCategory(it, pageActions, vm::selectSearchCategory) }, onBack)
        ThorSearchInput(search.query, search.loading, registry, pageActions,
            onQueryChange = vm::setSearchQuery, onSubmit = { vm.search(it) },
            onEditingChange = { vm.textEditing.value = it })
        if (items.isNotEmpty() && failedType)
            ThorSearchPartialFailure(retry)
        if (items.isNotEmpty()) ThorBrowseList(vm, registry, thorSearchResultsScene(entry, search.category), items, Modifier.weight(1f),
            trailingLabel = { it.searchCategory()?.title },
            // A search selection always plays the exact song, including in a song-only view.
            onItem = { item, _ -> vm.open(item) })
        else ThorEmptyState(vm, registry, emptyMessage, if (!search.loading && (search.error != null || failedType)) "重试" else null,
            retry, Modifier.weight(1f), search.loading)
    }
    DisposableEffect(Unit) { onDispose { vm.textEditing.value = false } }
}

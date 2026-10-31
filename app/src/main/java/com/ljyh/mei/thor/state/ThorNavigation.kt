package com.ljyh.mei.thor.state

import com.ljyh.mei.thor.model.ThorCollection
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.account.ThorAccountRoute

enum class ThorTab(val title: String) { Home("首页"), Discover("发现"), Library("音乐库"), My("我的") }

sealed interface ThorPage {
    val key: String
    data class Root(val tab: ThorTab) : ThorPage { override val key get() = "root:${tab.name}" }
    data class Collection(val collection: ThorCollection) : ThorPage { override val key get() = "collection:${collection.name}" }
    data class Detail(val item: ThorItem) : ThorPage { override val key get() = "detail:${item.key.stableId}" }
    data class Artist(val item: ThorItem) : ThorPage { override val key get() = "artist:${(item.key as ThorItemKey.Artist).id}" }
    data object Search : ThorPage { override val key = "search" }
    data object Appearance : ThorPage { override val key = "appearance" }
    data object Controls : ThorPage { override val key = "controls" }
    data class Account(val route: ThorAccountRoute) : ThorPage { override val key get() = "account:$route" }
    data object AudioSettings : ThorPage { override val key = "audio-settings" }
    data object Equalizer : ThorPage { override val key = "equalizer" }
}

enum class ThorOverlay { Player, Queue, Lyrics, Timer }

data class ThorNavigation(
    val tab: ThorTab = ThorTab.Home,
    val stacks: Map<ThorTab, List<ThorPage>> = emptyMap(),
    val overlays: List<ThorOverlay> = emptyList(),
) {
    val page: ThorPage get() = stacks[tab]?.lastOrNull() ?: ThorPage.Root(tab)
    val overlay: ThorOverlay? get() = overlays.lastOrNull()
    val isRoot: Boolean get() = page is ThorPage.Root && overlay == null
    val sceneKey: String get() = overlay?.let { "overlay:${it.name}" } ?: page.key

    fun push(page: ThorPage): ThorNavigation {
        val stack = stacks[tab].orEmpty().ifEmpty { listOf(ThorPage.Root(tab)) }
        if (stack.last().key == page.key) return this
        return copy(stacks = stacks + (tab to stack + page))
    }

    fun replaceDetail(item: ThorItem): ThorNavigation {
        val stack = stacks[tab] ?: return this
        return copy(stacks = stacks + (tab to stack.map { if (it is ThorPage.Detail && it.item.key == item.key) ThorPage.Detail(item) else it }))
    }

    /** Authentication returns to its caller; credentials never enter the navigation stack. */
    fun completeAccountLogin(): ThorNavigation {
        val stack = stacks[tab].orEmpty()
        val start = stack.indexOfFirst { page ->
            page is ThorPage.Account && page.route in setOf(ThorAccountRoute.Login,
                ThorAccountRoute.MobileLogin, ThorAccountRoute.WebLogin, ThorAccountRoute.CookieLogin)
        }
        if (start < 0) return this
        val caller = stack.take(start).ifEmpty { listOf(ThorPage.Root(tab)) }
        val target = if (caller.last() == ThorPage.Root(ThorTab.My)) caller + ThorPage.Account(ThorAccountRoute.Home) else caller
        return copy(stacks = stacks + (tab to target))
    }

    /** Keep generic destinations, but discard every old account's loaded detail and Web context. */
    fun forChangedAccount(): ThorNavigation = copy(
        stacks = stacks.mapValues { (_, stack) -> stack.filter { page ->
            page !is ThorPage.Detail && page !is ThorPage.Artist && !(page is ThorPage.Account &&
                (page.route is ThorAccountRoute.Connections || page.route is ThorAccountRoute.Web))
        } },
    )

    /** Sibling library categories share a header and a single back-stack level. */
    fun replaceCollection(collection: ThorCollection): ThorNavigation {
        if (page !is ThorPage.Collection) return push(ThorPage.Collection(collection))
        val stack = stacks[tab].orEmpty()
        return copy(stacks = stacks + (tab to (stack.dropLast(1) + ThorPage.Collection(collection))))
    }

    /** Shoulder navigation never escapes an immersive child page. */
    fun switchTab(delta: Int): ThorNavigation {
        if (!isRoot) return this
        val tabs = ThorTab.entries
        val target = tabs[(tab.ordinal + delta).mod(tabs.size)]
        return copy(tab = target)
    }

    fun toggle(overlay: ThorOverlay): ThorNavigation = if (this.overlay == overlay) {
        copy(overlays = overlays.dropLast(1))
    } else copy(overlays = overlays + overlay)

    fun back(): ThorNavigation? {
        if (overlays.isNotEmpty()) return copy(overlays = overlays.dropLast(1))
        val stack = stacks[tab].orEmpty()
        if (stack.size > 1) return copy(stacks = stacks + (tab to stack.dropLast(1)))
        return null
    }
}

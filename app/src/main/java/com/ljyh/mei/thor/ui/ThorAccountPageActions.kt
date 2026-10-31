package com.ljyh.mei.thor.ui

import com.ljyh.mei.thor.account.ThorAccountRoute

/** Account actions use only callbacks installed by the live leaf; stale page actions are disabled. */
internal fun thorAccountPageActions(route: ThorAccountRoute, bindings: ThorPageActionBindings?): List<ThorPageAction> {
    fun action(id: String, title: String) = ThorPageAction(id, title, bindings?.has(id) == true) { bindings?.invoke(id) }
    return buildList {
        if (route == ThorAccountRoute.WebLogin || route is ThorAccountRoute.Web) {
            add(action("account:web:next", "下一个网页控件"))
            add(action("account:web:previous", "上一个网页控件"))
            add(action("account:web:activate", "确认网页控件"))
            add(action("account:refresh", "刷新网页"))
            if (route == ThorAccountRoute.WebLogin) add(action("account:web:cookie", "Web Cookie 登录"))
        } else if (route == ThorAccountRoute.Home || route == ThorAccountRoute.Security || route == ThorAccountRoute.Bindings ||
            route == ThorAccountRoute.Devices || route == ThorAccountRoute.Authorizations || route == ThorAccountRoute.History || route is ThorAccountRoute.Connections) {
            add(action("account:refresh", "刷新"))
        }
    }
}

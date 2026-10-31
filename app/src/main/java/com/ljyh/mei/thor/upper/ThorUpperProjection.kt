package com.ljyh.mei.thor.upper

import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.state.ThorPage

/** Visual projection only: settings may borrow the current cover without changing selection. */
internal fun thorUpperProjection(
    page: ThorPage,
    playbackScene: Boolean,
    preview: ThorItem?,
    current: ThorItem?,
): ThorItem? = when {
    playbackScene -> current
    preview != null -> preview
    page == ThorPage.Controls -> current ?: ThorItem(ThorItemKey.Destination("controls"), "手柄与吹气")
    page == ThorPage.Appearance -> current ?: ThorItem(ThorItemKey.Destination("appearance"), "外观")
    else -> null
}

package com.ljyh.mei.thor.upper

import com.ljyh.mei.thor.model.ThorItem
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
    page == ThorPage.Controls || page == ThorPage.Appearance -> current
    else -> null
}

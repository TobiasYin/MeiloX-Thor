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
    page is ThorPage.Account -> current ?: ThorItem(ThorItemKey.Destination("account"), "我的账号")
    page == ThorPage.AudioSettings -> current ?: ThorItem(ThorItemKey.Destination("play"), "播放设置")
    page == ThorPage.Equalizer -> current ?: ThorItem(ThorItemKey.Destination("equalizer"), "均衡器")
    preview != null -> preview
    page == ThorPage.Controls -> current ?: ThorItem(ThorItemKey.Destination("controls"), "手柄与吹气")
    page == ThorPage.Appearance -> current ?: ThorItem(ThorItemKey.Destination("appearance"), "外观")
    else -> null
}

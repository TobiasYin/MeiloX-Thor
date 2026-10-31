package com.ljyh.mei.thor.state

import com.ljyh.mei.thor.account.ThorAccountRoute
import com.ljyh.mei.thor.model.ThorCollection

/** Compatibility entry points are resolved before any legacy Activity/window is touched. */
internal fun thorInternalPageForRoute(route: String): ThorPage? = when (route.trim('/')) {
    "account/login" -> ThorPage.Account(ThorAccountRoute.Login)
    "account/home" -> ThorPage.Account(ThorAccountRoute.Home)
    "account/security" -> ThorPage.Account(ThorAccountRoute.Security)
    "account/security/bindings" -> ThorPage.Account(ThorAccountRoute.Bindings)
    "account/security/devices" -> ThorPage.Account(ThorAccountRoute.Devices)
    "account/security/authorizations" -> ThorPage.Account(ThorAccountRoute.Authorizations)
    "setting/play" -> ThorPage.AudioSettings
    "setting/equalizer" -> ThorPage.Equalizer
    "library/downloads" -> ThorPage.Collection(ThorCollection.Downloads)
    else -> null
}

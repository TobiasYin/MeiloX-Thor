package com.ljyh.mei.thor.account

import com.ljyh.mei.data.model.melox.AccountConnectionType
import com.ljyh.mei.thor.model.ThorCommand
import com.ljyh.mei.ui.screen.account.OfficialNeteasePage

/** Navigation carries destinations only, never credentials or a second account session. */
sealed interface ThorAccountRoute {
    data object Login : ThorAccountRoute
    data object MobileLogin : ThorAccountRoute
    data object WebLogin : ThorAccountRoute
    data object CookieLogin : ThorAccountRoute
    data object Home : ThorAccountRoute
    data object Security : ThorAccountRoute
    data object Bindings : ThorAccountRoute
    data object Devices : ThorAccountRoute
    data object Authorizations : ThorAccountRoute
    data object History : ThorAccountRoute
    data class Connections(val userId: Long, val type: AccountConnectionType) : ThorAccountRoute
    data class Web(val page: OfficialNeteasePage) : ThorAccountRoute
}

/** The lower shell retains global shortcuts; only an active account leaf installs a consumer. */
class ThorAccountInputHost {
    private var consumer: Pair<Any, (ThorCommand) -> Boolean>? = null
    private val mobileDraft = ThorLoginFormDraft()
    private val cookieDraft = ThorLoginFormDraft()
    private var observedForm: ThorAccountRoute? = null
    private var observedGeneration: Long? = null

    /** Pass the underlying page route while Player covers it; an overlay is not a form exit. */
    fun observe(route: ThorAccountRoute?, generation: Long) {
        val nextForm = route.takeIf { it == ThorAccountRoute.MobileLogin || it == ThorAccountRoute.CookieLogin }
        val previousForm = observedForm
        val changedAccount = observedGeneration?.let { it != generation } == true
        observedForm = nextForm
        observedGeneration = generation
        if (changedAccount || nextForm == null) {
            mobileDraft.clear()
            cookieDraft.clear()
        } else if (previousForm != nextForm) {
            when (previousForm) {
                ThorAccountRoute.MobileLogin -> mobileDraft.clear()
                ThorAccountRoute.CookieLogin -> cookieDraft.clear()
                else -> Unit
            }
        }
        // Keep input ownership independent: a newly committed leaf may already have installed
        // its handler before this parent SideEffect observes its route/generation.
    }

    internal fun loginDraft(route: ThorAccountRoute): ThorLoginFormDraft = when (route) {
        ThorAccountRoute.MobileLogin -> mobileDraft
        ThorAccountRoute.CookieLogin -> cookieDraft
        else -> error("This route has no login form draft")
    }

    fun install(owner: Any, onCommand: (ThorCommand) -> Boolean) { consumer = owner to onCommand }
    fun remove(owner: Any) { if (consumer?.first === owner) consumer = null }
    fun handle(command: ThorCommand): Boolean = consumer?.second?.invoke(command) ?: false
}

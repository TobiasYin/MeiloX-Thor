package com.ljyh.mei.thor.account

import com.ljyh.mei.data.network.NeteaseUrsUpSmsChallenge
import com.ljyh.mei.thor.model.ThorCommand
import org.junit.Assert.*
import org.junit.Test

class ThorLoginFormDraftTest {
    private fun populate(draft: ThorLoginFormDraft) {
        draft.phoneState.value = "qa-phone"
        draft.countryState.value = "1"
        draft.passwordState.value = "qa-password"
        draft.smsCodeState.value = "qa-code"
        draft.cookieState.value = "qa-cookie"
        draft.passwordModeState.value = true
        draft.challengeState.value = NeteaseUrsUpSmsChallenge("qa-phone", "1", "qa-destination", "qa-message", "qa-app")
        draft.startCooldown(10_000)
    }

    private fun assertCleared(draft: ThorLoginFormDraft) {
        assertTrue(draft.phoneState.value.isEmpty())
        assertEquals("86", draft.countryState.value)
        assertTrue(draft.passwordState.value.isEmpty())
        assertTrue(draft.smsCodeState.value.isEmpty())
        assertTrue(draft.cookieState.value.isEmpty())
        assertFalse(draft.passwordModeState.value)
        assertNull(draft.challengeState.value)
        assertEquals(0L, draft.cooldownUntilMs)
    }

    @Test fun playerOverlayPreservesTheUnderlyingFormIdentityAndDraftOnlyInMemory() {
        val host = ThorAccountInputHost()
        host.observe(ThorAccountRoute.MobileLogin, 7)
        val draft = host.loginDraft(ThorAccountRoute.MobileLogin)
        populate(draft)
        // Root continues passing MobileLogin while Player/Queue covers the same page.
        repeat(3) { host.observe(ThorAccountRoute.MobileLogin, 7) }
        assertSame(draft, host.loginDraft(ThorAccountRoute.MobileLogin))
        assertEquals("qa-phone", draft.phoneState.value)
        assertEquals("qa-password", draft.passwordState.value)
        assertNotNull(draft.challengeState.value)
        assertEquals(30, draft.cooldownRemainingSeconds(40_000))
        assertEquals("ThorLoginFormDraft", draft.toString())
    }

    @Test fun normalFormExitClearsAllSensitiveFieldsEvenForExistingCompositionAliases() {
        for (destination in listOf(null, ThorAccountRoute.Login, ThorAccountRoute.Home, ThorAccountRoute.WebLogin)) {
            val host = ThorAccountInputHost()
            host.observe(ThorAccountRoute.MobileLogin, 7)
            val alias = host.loginDraft(ThorAccountRoute.MobileLogin)
            populate(alias)
            host.observe(destination, 7)
            assertCleared(alias)
            assertSame(alias, host.loginDraft(ThorAccountRoute.MobileLogin))
        }
    }

    @Test fun accountGenerationChangeClearsBothDraftsWithoutChangingTheirStableObjects() {
        val host = ThorAccountInputHost()
        host.observe(ThorAccountRoute.MobileLogin, 7)
        val mobile = host.loginDraft(ThorAccountRoute.MobileLogin)
        val cookie = host.loginDraft(ThorAccountRoute.CookieLogin)
        populate(mobile); populate(cookie)
        host.observe(ThorAccountRoute.MobileLogin, 8)
        assertCleared(mobile)
        assertCleared(cookie)
        assertSame(mobile, host.loginDraft(ThorAccountRoute.MobileLogin))
        assertSame(cookie, host.loginDraft(ThorAccountRoute.CookieLogin))
    }

    @Test fun switchingBetweenMobileAndCookieFormsClearsTheOldFormWithoutMixingTheirValues() {
        val host = ThorAccountInputHost()
        host.observe(ThorAccountRoute.MobileLogin, 7)
        val mobile = host.loginDraft(ThorAccountRoute.MobileLogin)
        populate(mobile)
        // The new child can ask for its state before the parent's observe SideEffect commits.
        val cookie = host.loginDraft(ThorAccountRoute.CookieLogin)
        cookie.cookieState.value = "qa-new-cookie"
        host.observe(ThorAccountRoute.CookieLogin, 7)
        assertNotSame(mobile, cookie)
        assertCleared(mobile)
        assertEquals("qa-new-cookie", cookie.cookieState.value)
        assertTrue(cookie.phoneState.value.isEmpty())
        host.observe(ThorAccountRoute.MobileLogin, 7)
        assertCleared(cookie)
        assertCleared(mobile)
    }

    @Test fun cooldownUsesOneElapsedDeadlineAndCeilingSecondsAcrossOverlayTime() {
        val draft = ThorLoginFormDraft()
        draft.startCooldown(10_000)
        assertEquals(70_000L, draft.cooldownUntilMs)
        assertEquals(60, draft.cooldownRemainingSeconds(10_000))
        assertEquals(60, draft.cooldownRemainingSeconds(10_001))
        assertEquals(59, draft.cooldownRemainingSeconds(11_000))
        assertEquals(1, draft.cooldownRemainingSeconds(69_999))
        assertEquals(0, draft.cooldownRemainingSeconds(70_000))
        assertEquals(0, draft.cooldownRemainingSeconds(90_000))
        draft.startCooldown(Long.MAX_VALUE - 10, 60_000)
        assertEquals(Long.MAX_VALUE, draft.cooldownUntilMs)
        assertEquals(1, draft.cooldownRemainingSeconds(Long.MAX_VALUE - 1))
        draft.clear()
        assertEquals(0, draft.cooldownRemainingSeconds(0))
    }

    @Test fun observingRouteOrAccountCannotRemoveTheCurrentLeafInputConsumer() {
        val host = ThorAccountInputHost()
        host.install(Any()) { it == ThorCommand.Back }
        host.observe(ThorAccountRoute.MobileLogin, 7)
        host.observe(ThorAccountRoute.CookieLogin, 8)
        assertTrue(host.handle(ThorCommand.Back))
    }
}

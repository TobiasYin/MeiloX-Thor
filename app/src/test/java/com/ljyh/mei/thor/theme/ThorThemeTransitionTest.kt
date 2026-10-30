package com.ljyh.mei.thor.theme

import com.ljyh.mei.thor.model.ThorThemePack
import org.junit.Assert.*
import org.junit.Test

class ThorThemeTransitionTest {
    private val day = thorThemeTokens(ThorThemePack.Ceramic, false)
    private val night = thorThemeTokens(ThorThemePack.Glass, true)

    @Test fun aSecondDisplayDoesNotRestartTheSharedTransition() {
        val clock = ThorThemeTransitionClock()
        clock.begin(day, 0)
        assertEquals(day, clock.sample(0).tokens)
        clock.begin(night, 1_000_000_000L)
        val before = clock.sample(1_090_000_000L)
        assertFalse(before.complete)
        clock.begin(night, 1_100_000_000L)
        assertEquals(before, clock.sample(1_090_000_000L))
        assertEquals(night, clock.sample(1_180_000_000L).tokens)
        assertTrue(clock.sample(1_180_000_000L).complete)
    }

    @Test fun rapidSwitchStartsFromTheCurrentAppearanceInsteadOfAnOldEndpoint() {
        val clock = ThorThemeTransitionClock()
        clock.begin(day, 0); clock.begin(night, 10)
        val current = clock.sample(90_000_010L).tokens
        val next = thorThemeTokens(ThorThemePack.Titanium, true)
        clock.begin(next, 90_000_010L)
        val start = clock.sample(90_000_010L).tokens
        assertEquals(current.background, start.background)
        assertEquals(current.caseBase, start.caseBase)
        assertEquals(next, clock.sample(270_000_010L).tokens)
    }

    @Test fun reducedMotionSnapsAndReducedTransparencyCannotRetainOldGlints() {
        val clock = ThorThemeTransitionClock()
        clock.begin(day, 0)
        val still = night.copy(reduceMotion = true)
        clock.begin(still, 1)
        assertEquals(still, clock.sample(1).tokens)
        assertTrue(clock.sample(1).complete)
        val opaque = thorThemeTokens(ThorThemePack.Titanium, true, reduceTransparency = true)
        clock.begin(opaque, 2)
        assertEquals(0f, clock.sample(3).tokens.paneGlint.alpha)
        assertEquals(0f, clock.sample(3).tokens.caseReflection.alpha)
    }
}

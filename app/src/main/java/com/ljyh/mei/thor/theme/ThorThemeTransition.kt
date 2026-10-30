package com.ljyh.mei.thor.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** CSS transition's default `ease`; shared with 120/180ms component state changes. */
internal val ThorMaterialEasing = CubicBezierEasing(.25f, .1f, .25f, 1f)

/**
 * One monotonic timeline for Activity and Presentation. It contains visual tokens only, never
 * preferences, route keys, focus owners or player objects. Each visible composition samples the
 * same transition; a late second window does not restart it. New choices start at the current
 * interpolated appearance, so rapid changes do not flash through a stale pack.
 */
internal class ThorThemeTransitionClock {
    private var from: ThorThemeTokens? = null
    private var target: ThorThemeTokens? = null
    private var startedAt = 0L

    @Synchronized
    fun begin(next: ThorThemeTokens, nowNanos: Long) {
        if (target == next) return
        val current = target?.let { sample(nowNanos).tokens }
        from = if (next.reduceMotion) next else current ?: next
        target = next
        startedAt = nowNanos
    }

    @Synchronized
    fun sample(nowNanos: Long): ThorThemeFrame {
        val destination = checkNotNull(target)
        val source = from ?: destination
        val progress = if (destination.reduceMotion || source == destination) 1f else
            ((nowNanos - startedAt).coerceAtLeast(0).toDouble() / 180_000_000.0).toFloat().coerceIn(0f, 1f)
        return ThorThemeFrame(
            if (progress >= 1f) destination else interpolateThorTheme(source, destination, ThorMaterialEasing.transform(progress)),
            progress >= 1f,
        )
    }
}

internal data class ThorThemeFrame(val tokens: ThorThemeTokens, val complete: Boolean)
private val SharedThorThemeClock = ThorThemeTransitionClock()

@Composable
internal fun animatedThorTheme(target: ThorThemeTokens): ThorThemeTokens {
    val current = remember { mutableStateOf(target) }
    LaunchedEffect(target) {
        SharedThorThemeClock.begin(target, System.nanoTime())
        var frame = SharedThorThemeClock.sample(System.nanoTime())
        current.value = frame.tokens
        while (!frame.complete) {
            withFrameNanos { now ->
                frame = SharedThorThemeClock.sample(now)
                current.value = frame.tokens
            }
        }
    }
    return if (target.reduceMotion) target else current.value
}

internal fun interpolateThorTheme(from: ThorThemeTokens, to: ThorThemeTokens, fraction: Float): ThorThemeTokens = to.copy(
    background = lerp(from.background, to.background, fraction),
    surface = lerp(from.surface, to.surface, fraction),
    elevated = lerp(from.elevated, to.elevated, fraction),
    inset = lerp(from.inset, to.inset, fraction),
    text = lerp(from.text, to.text, fraction),
    secondary = lerp(from.secondary, to.secondary, fraction),
    accent = lerp(from.accent, to.accent, fraction),
    onAccent = lerp(from.onAccent, to.onAccent, fraction),
    focus = lerp(from.focus, to.focus, fraction),
    outline = lerp(from.outline, to.outline, fraction),
    edge = lerp(from.edge, to.edge, fraction),
    selected = lerp(from.selected, to.selected, fraction),
    selectedText = lerp(from.selectedText, to.selectedText, fraction),
    raisedTop = lerp(from.raisedTop, to.raisedTop, fraction),
    raisedBottom = lerp(from.raisedBottom, to.raisedBottom, fraction),
    primaryTop = lerp(from.primaryTop, to.primaryTop, fraction),
    primaryBottom = lerp(from.primaryBottom, to.primaryBottom, fraction),
    highlight = lerp(from.highlight, to.highlight, fraction),
    contact = lerp(from.contact, to.contact, fraction),
    shadow = lerp(from.shadow, to.shadow, fraction),
    primaryHighlight = lerp(from.primaryHighlight, to.primaryHighlight, fraction),
    primaryLowlight = lerp(from.primaryLowlight, to.primaryLowlight, fraction),
    paneGlint = lerp(from.paneGlint, to.paneGlint, fraction),
    grain = lerp(from.grain, to.grain, fraction),
    ambient = lerp(from.ambient, to.ambient, fraction),
    caseEdge = lerp(from.caseEdge, to.caseEdge, fraction),
    caseBase = lerp(from.caseBase, to.caseBase, fraction),
    caseHighlight = lerp(from.caseHighlight, to.caseHighlight, fraction),
    caseReflection = lerp(from.caseReflection, to.caseReflection, fraction),
    caseSpineLight = lerp(from.caseSpineLight, to.caseSpineLight, fraction),
    caseSpineDark = lerp(from.caseSpineDark, to.caseSpineDark, fraction),
    caseText = lerp(from.caseText, to.caseText, fraction),
    caseShadow = lerp(from.caseShadow, to.caseShadow, fraction),
    bezel = lerp(from.bezel, to.bezel, fraction),
    shadowBlur = from.shadowBlur + (to.shadowBlur - from.shadowBlur) * fraction,
    shadowLift = from.shadowLift + (to.shadowLift - from.shadowLift) * fraction,
    glintAngle = from.glintAngle + (to.glintAngle - from.glintAngle) * fraction,
).let { result ->
    // Turning off transparency is immediate, even if a prior theme transition is in flight.
    if (to.reduceTransparency) result.copy(paneGlint = Color.Transparent, grain = Color.Transparent,
        caseReflection = Color.Transparent) else result
}

package com.ljyh.mei.thor.ui

import androidx.compose.ui.geometry.Rect
import com.ljyh.mei.thor.model.ThorDirection

enum class ThorFocusLayout { Vertical, Horizontal, Spatial }

/** Only these stable keys participate in controller/native focus for the current region. */
data class ThorFocusScope(
    val id: String,
    val keys: List<String>,
    val layout: ThorFocusLayout = ThorFocusLayout.Vertical,
)

internal data class ThorFocusCandidate(val bounds: Rect, val enabled: Boolean = true)

/** Closed navigation: no wrap, no cross-axis exit, and no search outside the explicit keys. */
internal fun thorScopeTarget(
    scope: ThorFocusScope,
    selectedKey: String?,
    direction: ThorDirection,
    candidates: Map<String, ThorFocusCandidate>,
): String? {
    val horizontal = direction == ThorDirection.Left || direction == ThorDirection.Right
    if (scope.layout == ThorFocusLayout.Vertical && horizontal ||
        scope.layout == ThorFocusLayout.Horizontal && !horizontal) return null
    val keys = scope.keys.distinct().filter { candidates[it]?.enabled == true }
    if (keys.isEmpty()) return null
    val current = keys.indexOf(selectedKey)
    if (current < 0) return keys.first()
    if (scope.layout != ThorFocusLayout.Spatial) {
        val step = if (direction == ThorDirection.Up || direction == ThorDirection.Left) -1 else 1
        return keys.getOrNull(current + step)
    }
    val source = candidates.getValue(keys[current]).bounds
    if (!source.validFocusBounds()) return null
    val forward = direction == ThorDirection.Right || direction == ThorDirection.Down
    data class Target(val key: String, val beam: Boolean, val score: Float, val order: Int)
    return keys.mapIndexedNotNull { order, key ->
        if (key == selectedKey) return@mapIndexedNotNull null
        val target = candidates.getValue(key).bounds
        if (!target.validFocusBounds()) return@mapIndexedNotNull null
        val major = if (horizontal) target.center.x - source.center.x else target.center.y - source.center.y
        if (if (forward) major <= .5f else major >= -.5f) return@mapIndexedNotNull null
        val minor = if (horizontal) target.center.y - source.center.y else target.center.x - source.center.x
        val beam = if (horizontal) target.bottom > source.top && target.top < source.bottom
            else target.right > source.left && target.left < source.right
        Target(key, beam, 13f * major * major + minor * minor, order)
    }.minWithOrNull(compareBy<Target> { !it.beam }.thenBy { it.score }.thenBy { it.order })?.key
}

private fun Rect.validFocusBounds(): Boolean = width > 0f && height > 0f &&
    left.isFinite() && right.isFinite() && top.isFinite() && bottom.isFinite()

/** Fresh lists start at zero; a deleted saved key never becomes a header/ordinal fallback. */
internal fun thorRestoredListIndex(keys: List<String>, savedKey: String?, currentIndex: Int? = null): Int =
    currentIndex?.takeIf { it in keys.indices }
        ?: keys.indexOf(savedKey).takeIf { it >= 0 }
        ?: 0

package com.ljyh.mei.thor.upper

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Local lyric browsing never seeks. The caller supplies one monotonic millisecond clock.
 * Observe [returnAtMs] to schedule one delayed [resumeIfIdle] call; no polling is needed.
 */
@Stable
internal class ThorLyricBrowseState {
    var isBrowsing: Boolean by mutableStateOf(false)
        private set
    var isTouching: Boolean by mutableStateOf(false)
        private set
    var isScrolling: Boolean by mutableStateOf(false)
        private set
    var isDragging: Boolean by mutableStateOf(false)
        private set
    var returnAtMs: Long? by mutableStateOf(null)
        private set

    // Programmatic following animations also report scrolling; they must not stop themselves.
    val canFollow: Boolean get() = !isBrowsing && !isTouching && !isDragging

    fun onTouchStarted(@Suppress("UNUSED_PARAMETER") nowMs: Long) {
        isTouching = true
        returnAtMs = null
    }

    fun onTouchEnded(nowMs: Long) {
        isTouching = false
        updateReturnDeadline(nowMs)
    }

    fun onDragStarted(@Suppress("UNUSED_PARAMETER") nowMs: Long) {
        isDragging = true
        isBrowsing = true
        returnAtMs = null
    }

    fun onDragStopped(nowMs: Long) {
        isDragging = false
        updateReturnDeadline(nowMs)
    }

    fun onScrollChanged(scrolling: Boolean, nowMs: Long) {
        isScrolling = scrolling
        updateReturnDeadline(nowMs)
    }

    /** A stale timer cannot resume during a new gesture or its fling. True only on transition. */
    fun resumeIfIdle(nowMs: Long): Boolean {
        val deadline = returnAtMs ?: return false
        if (nowMs < deadline || !isBrowsing || isTouching || isDragging || isScrolling) return false
        reset()
        return true
    }

    /** The caller performs the selected line's seek, then immediately follows playback again. */
    fun onLineSelected() = reset()

    /** Also call when the current track changes; a new track starts in follow mode. */
    fun onHidden() = reset()

    private fun updateReturnDeadline(nowMs: Long) {
        if (!isBrowsing || isTouching || isDragging || isScrolling) returnAtMs = null
        else if (returnAtMs == null) returnAtMs = nowMs + IDLE_RETURN_MS
    }

    private fun reset() {
        isBrowsing = false
        isTouching = false
        isScrolling = false
        isDragging = false
        returnAtMs = null
    }

    private companion object {
        const val IDLE_RETURN_MS = 15_000L
    }
}

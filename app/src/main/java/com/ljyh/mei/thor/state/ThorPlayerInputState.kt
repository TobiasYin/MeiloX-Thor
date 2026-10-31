package com.ljyh.mei.thor.state

import com.ljyh.mei.thor.menu.ThorMenuState
import com.ljyh.mei.thor.menu.ThorMenuLevel
import com.ljyh.mei.thor.model.ThorShoulderPhase

internal enum class ThorShoulderSeekAction { None, BackwardTap, ForwardTap, Begin, BackwardStep, ForwardStep, Apply, Cancel }

/** One active direction owns a preview. An older shoulder's release cannot commit its replacement. */
internal class ThorPlayerShoulderState {
    private var right: Boolean? = null
    private var entry: String? = null
    private var holding = false

    fun reset() { right = null; entry = null; holding = false }

    fun accept(direction: Boolean, phase: ThorShoulderPhase, entryId: String?, available: Boolean): ThorShoulderSeekAction {
        if (!available || entryId == null) {
            val cancel = holding
            reset()
            return if (cancel) ThorShoulderSeekAction.Cancel else ThorShoulderSeekAction.None
        }
        if (entry != null && entry != entryId) {
            val cancel = holding
            reset()
            if (phase == ThorShoulderPhase.Press) { right = direction; entry = entryId }
            return if (cancel) ThorShoulderSeekAction.Cancel else ThorShoulderSeekAction.None
        }
        if (phase == ThorShoulderPhase.Press) {
            val cancel = holding && right != direction
            if (right != direction) { right = direction; entry = entryId; holding = false }
            return if (cancel) ThorShoulderSeekAction.Cancel else ThorShoulderSeekAction.None
        }
        if (right != direction || entry != entryId) return ThorShoulderSeekAction.None
        return when (phase) {
            ThorShoulderPhase.Tap -> if (holding) ThorShoulderSeekAction.None else if (direction) ThorShoulderSeekAction.ForwardTap else ThorShoulderSeekAction.BackwardTap
            ThorShoulderPhase.HoldStart -> if (holding) ThorShoulderSeekAction.None else {
                holding = true
                ThorShoulderSeekAction.Begin
            }
            ThorShoulderPhase.HoldStep -> if (!holding) ThorShoulderSeekAction.None else if (direction) ThorShoulderSeekAction.ForwardStep else ThorShoulderSeekAction.BackwardStep
            ThorShoulderPhase.Release -> {
                val apply = holding
                reset()
                if (apply) ThorShoulderSeekAction.Apply else ThorShoulderSeekAction.None
            }
            ThorShoulderPhase.Cancel -> { val cancel = holding; reset(); if (cancel) ThorShoulderSeekAction.Cancel else ThorShoulderSeekAction.None }
            ThorShoulderPhase.Press -> ThorShoulderSeekAction.None
        }
    }
}

internal data class ThorQuickPlayerReturn(val navigation: ThorNavigation, val menu: ThorMenuState?)

/** Return frames preserve unsaved menu text; all frames belong to one account generation. */
internal class ThorQuickPlayerHistory {
    private data class Frame(val overlays: List<ThorOverlay>, val account: Long, val menu: ThorMenuState?)
    private val frames = mutableListOf<Frame>()
    fun clear() { frames.clear() }

    fun enter(navigation: ThorNavigation, menu: ThorMenuState?, account: Long): ThorNavigation {
        if (frames.lastOrNull()?.account?.let { it != account } == true) clear()
        val draft = menu?.takeIf { !it.busy && it.level == ThorMenuLevel.Name }
        if (draft != null) frames += Frame(navigation.overlays, account, draft)
        else if (frames.isEmpty() && navigation.overlay != ThorOverlay.Player) {
            // A bare queue/lyrics shortcut still returns to its browsing origin.
            frames += Frame(emptyList(), account, null)
        }
        return navigation.copy(overlays = listOf(ThorOverlay.Player))
    }

    fun leave(navigation: ThorNavigation, account: Long): ThorQuickPlayerReturn? {
        if (navigation.overlay != ThorOverlay.Player) return null
        val frame = frames.removeLastOrNull() ?: return null
        if (frame.account != account) { clear(); return null }
        val restored = navigation.copy(overlays = frame.overlays)
        return ThorQuickPlayerReturn(restored, frame.menu)
    }
}

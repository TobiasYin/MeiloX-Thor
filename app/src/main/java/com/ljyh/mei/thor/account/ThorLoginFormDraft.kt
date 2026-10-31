package com.ljyh.mei.thor.account

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ljyh.mei.data.network.NeteaseUrsUpSmsChallenge

/**
 * Short-lived process memory only. Never put this object in SavedState, a navigation argument,
 * DataStore, a log, or a diagnostic dump. The owning input host clears it on leaving the flow.
 * Busy state and network operations deliberately remain owned by the visible form composition.
 */
@Stable
internal class ThorLoginFormDraft {
    val phoneState = mutableStateOf("")
    val countryState = mutableStateOf("86")
    val passwordState = mutableStateOf("")
    val smsCodeState = mutableStateOf("")
    val cookieState = mutableStateOf("")
    val passwordModeState = mutableStateOf(false)
    val challengeState = mutableStateOf<NeteaseUrsUpSmsChallenge?>(null)
    val cooldownUntilState = mutableLongStateOf(0L)
    var cooldownUntilMs: Long by cooldownUntilState

    /** Supply SystemClock.elapsedRealtime(), so an overlay cannot restart the SMS cooldown. */
    fun startCooldown(nowElapsedMs: Long, durationMs: Long = 60_000L) {
        val now = nowElapsedMs.coerceAtLeast(0L)
        val duration = durationMs.coerceAtLeast(0L)
        cooldownUntilMs = if (duration == 0L) 0L
            else if (duration > Long.MAX_VALUE - now) Long.MAX_VALUE else now + duration
    }

    /** Ceiling seconds keeps resend disabled until the full monotonic interval has elapsed. */
    fun cooldownRemainingSeconds(nowElapsedMs: Long): Int {
        val now = nowElapsedMs.coerceAtLeast(0L)
        if (cooldownUntilMs <= now) return 0
        val remaining = cooldownUntilMs - now
        return (((remaining - 1L) / 1_000L) + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun clear() {
        phoneState.value = ""
        countryState.value = "86"
        passwordState.value = ""
        smsCodeState.value = ""
        cookieState.value = ""
        passwordModeState.value = false
        challengeState.value = null
        cooldownUntilMs = 0L
    }

    override fun toString(): String = "ThorLoginFormDraft"
}

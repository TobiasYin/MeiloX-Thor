package com.ljyh.mei.thor.input

enum class ThorExitDecision { Armed, Exit, IgnoreRepeat }

/** Home-only exit policy. The UI clears this on other input, navigation and backgrounding. */
class ThorExitGuard(private val windowMs: Long = 2_000L) {
    init { require(windowMs > 0) }
    private var armedAtMs: Long? = null

    val armedUntilMs: Long?
        get() = armedAtMs?.let { if (it > Long.MAX_VALUE - windowMs) Long.MAX_VALUE else it + windowMs }

    fun onBack(nowMs: Long, repeated: Boolean = false): ThorExitDecision {
        if (repeated) return ThorExitDecision.IgnoreRepeat
        val previous = armedAtMs
        if (previous != null && nowMs >= previous && nowMs <= checkNotNull(armedUntilMs)) {
            clear()
            return ThorExitDecision.Exit
        }
        armedAtMs = nowMs
        return ThorExitDecision.Armed
    }

    fun clear() { armedAtMs = null }
}

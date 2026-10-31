package com.ljyh.mei.thor.model

// The field contains hundredth-frequency harmonics. A single 2π wrap would visibly jump every
// minute; 200π closes all their cycles while keeping shader floats precise during long sessions.
internal const val ThorBackdropCycle = 628.3185307179587

internal fun advanceThorBackdropPhase(phase: Double, increment: Double): Double =
    (phase + increment) % ThorBackdropCycle

package com.ljyh.mei.thor.theme

import com.ljyh.mei.thor.artwork.thorArtworkLuminance
import com.ljyh.mei.thor.artwork.toneThorArtworkColor

/** Derived from this theme's real reading ink; artwork never replaces foreground/focus colors. */
internal data class ThorBackdropContrast(val isDark: Boolean, val luminanceLimit: Float)

internal fun thorBackdropContrast(isDark: Boolean, foregroundArgb: IntArray): ThorBackdropContrast {
    require(foregroundArgb.isNotEmpty())
    // A little headroom covers half-float shader/color encoding and final 8-bit window rounding.
    val ratio = 4.8
    val luminances = foregroundArgb.map(::thorArtworkLuminance)
    val limit = if (isDark) (luminances.min() + .05) / ratio - .05
        else (luminances.max() + .05) * ratio - .05
    return ThorBackdropContrast(isDark, limit.coerceIn(0.0, 1.0).toFloat())
}

/** Used only to prepare three fallback gradient colors, not a full bitmap/frame on the CPU. */
internal fun readableThorBackdropColor(argb: Int, contrast: ThorBackdropContrast): Int {
    val luminance = thorArtworkLuminance(argb)
    val target = if (contrast.isDark) minOf(luminance, contrast.luminanceLimit.toDouble())
        else maxOf(luminance, contrast.luminanceLimit.toDouble())
    return toneThorArtworkColor(argb, target)
}

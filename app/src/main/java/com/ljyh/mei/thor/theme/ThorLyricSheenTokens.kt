package com.ljyh.mei.thor.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ljyh.mei.thor.model.ThorThemePack

/** Lyric-only finish: neutral reading ink, a soft satin shoulder and a narrow pearl ridge. */
@Immutable
data class ThorLyricSheenTokens(
    val ink: Color,
    val warmReflection: Color,
    val coolReflection: Color,
    val ridge: Color,
    val halo: Color,
    val satinOpacity: Float,
    val ridgeOpacity: Float,
    val haloOpacity: Float,
    val softRadius: Dp,
    val ridgeRadius: Dp,
    val haloSpread: Dp,
) {
    val decorationsAllowed: Boolean get() = satinOpacity > 0f || ridgeOpacity > 0f
}

/**
 * Follow the animated reading ink, rather than the destination's day/night flag. This keeps the
 * finish on the same 180ms cross-window theme timeline without adding a second animation clock.
 * Neither the album palette nor the interaction accent participates in the lyric finish.
 */
internal fun thorLyricSheenTokens(
    pack: ThorThemePack,
    readingInk: Color,
    reduceTransparency: Boolean,
): ThorLyricSheenTokens {
    val night = ((readingInk.luminance() - .10f) / .60f).coerceIn(0f, 1f)
    val warmNight = when (pack) {
        ThorThemePack.Ceramic -> Color(0xFFE1D8C8)
        ThorThemePack.Glass -> Color(0xFFDDDCD7)
        ThorThemePack.Titanium -> Color(0xFFDDD7CA)
    }
    val coolNight = when (pack) {
        ThorThemePack.Ceramic -> Color(0xFFCDD5DE)
        ThorThemePack.Glass -> Color(0xFFC6D2DE)
        ThorThemePack.Titanium -> Color(0xFFCFD4DA)
    }
    val satin = when (pack) {
        ThorThemePack.Ceramic -> .20f
        ThorThemePack.Glass -> .22f
        ThorThemePack.Titanium -> .26f
    }
    return ThorLyricSheenTokens(
        ink = lerp(Color(0xFF17212B), Color(0xFFEEF1F4), night),
        warmReflection = lerp(Color(0xFF3B3C3B), warmNight, night),
        coolReflection = lerp(Color(0xFF394752), coolNight, night),
        ridge = lerp(Color(0xFF43505C), Color(0xFFF8FAFC), night),
        halo = lerp(Color(0xFF17212B), Color(0xFFF8FAFC), night),
        satinOpacity = if (reduceTransparency) 0f else satin,
        ridgeOpacity = if (reduceTransparency) 0f else .38f + .48f * night,
        // Daytime glyph edges stay crisp. Night gets a sub-pixel pearl contour, never a blur.
        haloOpacity = if (reduceTransparency) 0f else .06f * night,
        softRadius = when (pack) { ThorThemePack.Glass -> 30.dp; ThorThemePack.Ceramic -> 28.dp; ThorThemePack.Titanium -> 26.dp },
        ridgeRadius = when (pack) { ThorThemePack.Glass -> 1.dp; ThorThemePack.Ceramic -> 1.2.dp; ThorThemePack.Titanium -> 1.4.dp },
        haloSpread = .45.dp,
    )
}

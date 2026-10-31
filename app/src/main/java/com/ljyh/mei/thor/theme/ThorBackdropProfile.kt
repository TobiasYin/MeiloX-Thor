package com.ljyh.mei.thor.theme

import com.ljyh.mei.thor.model.ThorBackgroundIntensity
import com.ljyh.mei.thor.model.ThorBackgroundMode

/** Coordinates and radii are fractions of the complete upper-screen viewport. */
internal data class ThorBackdropField(
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float,
)

/**
 * Renderer-independent environmental appearance. Strength is a relative color weight, not a
 * Canvas alpha, and may exceed one at High. The renderer owns its palette, phase and transitions.
 * The fields extend across the screen; the quiet mask attenuates color without clipping it.
 */
internal data class ThorBackdropProfile(
    val mode: ThorBackgroundMode,
    val visible: Boolean,
    val colorStrength: Float,
    val deformationStrength: Float,
    /** Angular phase advance per second; zero whenever animation is disallowed. */
    val speed: Float,
    val animate: Boolean,
    /** Cover-palette contribution: one is the full cover palette, zero has no cover color. */
    val artworkColorWeight: Float,
    val primaryField: ThorBackdropField,
    val secondaryField: ThorBackdropField,
    val quietStartX: Float,
    val quietEndX: Float,
    /** Fraction of cover color retained after the quiet mask reaches its end. */
    val quietRetention: Float,
    val highlights: Boolean,
)

private val ArtworkPrimary = ThorBackdropField(.24f, .58f, .96f, 1.08f)
private val ArtworkSecondary = ThorBackdropField(.72f, .22f, .86f, .94f)
private val GradientPrimary = ThorBackdropField(.18f, .74f, 1.12f, 1.20f)
private val GradientSecondary = ThorBackdropField(.88f, .08f, 1.08f, 1.16f)

/**
 * Material packs supply colors separately. Day/night changes optical weight while preserving
 * field geometry, so selecting a new material or appearance does not relocate the background.
 * Motion and transparency overrides never replace the user's selected mode or intensity.
 */
internal fun thorBackdropProfile(
    mode: ThorBackgroundMode,
    intensity: ThorBackgroundIntensity,
    isDark: Boolean,
    reduceMotion: Boolean = false,
    reduceTransparency: Boolean = false,
    active: Boolean = true,
    playing: Boolean = true,
): ThorBackdropProfile {
    val visible = mode != ThorBackgroundMode.Clean
    val artwork = mode == ThorBackgroundMode.ArtworkFlow
    val animate = artwork && active && playing && !reduceMotion
    val colorWeight = when (mode) {
        ThorBackgroundMode.ArtworkFlow -> if (isDark) 1.08f else .94f
        ThorBackgroundMode.SoftGradient -> if (isDark) .85f else .75f
        ThorBackgroundMode.Clean -> 0f
    }
    val deformation = when (mode) {
        ThorBackgroundMode.ArtworkFlow -> .30f
        ThorBackgroundMode.SoftGradient -> .06f
        ThorBackgroundMode.Clean -> 0f
    }
    val speed = when (mode) {
        ThorBackgroundMode.ArtworkFlow -> .20f
        ThorBackgroundMode.SoftGradient -> 0f
        ThorBackgroundMode.Clean -> 0f
    }
    return ThorBackdropProfile(
        mode = mode,
        visible = visible,
        // The backdrop is already opaque: reduced transparency removes optical gloss without
        // erasing the user's selected artwork color field.
        colorStrength = colorWeight * intensity.multiplier * if (reduceTransparency) .70f else 1f,
        deformationStrength = deformation * intensity.multiplier,
        speed = if (animate) speed else 0f,
        animate = animate,
        artworkColorWeight = when (mode) {
            ThorBackgroundMode.ArtworkFlow -> 1f
            ThorBackgroundMode.SoftGradient -> .8f
            ThorBackgroundMode.Clean -> 0f
        },
        primaryField = if (artwork) ArtworkPrimary else GradientPrimary,
        secondaryField = if (artwork) ArtworkSecondary else GradientSecondary,
        quietStartX = .46f,
        quietEndX = .88f,
        quietRetention = if (isDark) .62f else .56f,
        highlights = visible && !reduceTransparency,
    )
}

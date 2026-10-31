package com.ljyh.mei.thor.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ljyh.mei.constants.AppAppearance
import com.ljyh.mei.constants.AppAppearanceKey
import com.ljyh.mei.constants.ReduceTransparencyKey
import com.ljyh.mei.thor.model.ThorAppearance
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.thor.model.ThorThemePack
import com.ljyh.mei.thor.model.ThorBackgroundMode
import com.ljyh.mei.thor.model.ThorBackgroundIntensity
import com.ljyh.mei.ui.theme.MusicTheme
import io.github.neoruaa.glasmio.core.GlassAppearance
import io.github.neoruaa.glasmio.core.GlassColors
import io.github.neoruaa.glasmio.core.GlassDimensions
import io.github.neoruaa.glasmio.core.GlasmioShapes
import io.github.neoruaa.glasmio.core.GlasmioTheme

private val ThorThemeKey = stringPreferencesKey("thor.theme")
private val ThorReduceMotionKey = booleanPreferencesKey("thor.reduceMotion")
private val ThorAirflowKey = booleanPreferencesKey("thor.airflowEnabled")
private val ThorBackgroundModeKey = stringPreferencesKey("thor.background.mode")
private val ThorBackgroundIntensityKey = stringPreferencesKey("thor.background.intensity")

internal val LocalThorLegacyMode = staticCompositionLocalOf { false }

/** The same non-account preference projection is used by Thor and its compatibility surfaces. */
internal fun decodeThorPreferences(preferences: Preferences) = ThorPreferences(
    theme = ThorThemePack.entries.firstOrNull { it.name == preferences[ThorThemeKey] } ?: ThorThemePack.Ceramic,
    appearance = when (preferences[AppAppearanceKey]) {
        AppAppearance.Light.name -> ThorAppearance.Day
        AppAppearance.Dark.name -> ThorAppearance.Night
        else -> ThorAppearance.System
    },
    reduceMotion = preferences[ThorReduceMotionKey] ?: false,
    reduceTransparency = preferences[ReduceTransparencyKey] ?: false,
    airflowEnabled = preferences[ThorAirflowKey] ?: false,
    backgroundMode = ThorBackgroundMode.entries.firstOrNull { it.name == preferences[ThorBackgroundModeKey] } ?: ThorBackgroundMode.ArtworkFlow,
    backgroundIntensity = ThorBackgroundIntensity.entries.firstOrNull { it.name == preferences[ThorBackgroundIntensityKey] } ?: ThorBackgroundIntensity.Medium,
)

/** Keep the ordinary client path intact; only a validated Thor route supplies preferences. */
@Composable
internal fun ThorLegacyTheme(
    preferences: ThorPreferences?,
    seedColor: Color,
    isDark: Boolean,
    dynamicAccent: Boolean,
    content: @Composable () -> Unit,
) {
    MusicTheme(seedColor = seedColor, isDark = isDark, dynamicAccent = dynamicAccent) {
        if (preferences == null) {
            content()
        } else {
            // MusicTheme retains the existing licensed symbol provider and legacy infrastructure.
            // Thor supplies the actual color scheme, type and shapes inside that infrastructure.
            ThorTheme(preferences) {
                val theme = LocalThorTheme.current
                GlasmioTheme(
                    isDark = theme.isDark,
                    colorScheme = MaterialTheme.colorScheme,
                    typography = MaterialTheme.typography,
                    colors = thorLegacyGlassColors(theme),
                    appearance = thorLegacyGlassAppearance(theme),
                    dimensions = GlassDimensions(
                        compactCornerRadius = theme.controlRadius,
                        regularCornerRadius = theme.cardRadius,
                        sheetCornerRadius = theme.deckRadius,
                        controlHeight = 48.dp,
                        iconButtonSize = 48.dp,
                    ),
                    shapes = GlasmioShapes(
                        extraSmall = RoundedCornerShape(theme.smallRadius),
                        small = RoundedCornerShape(theme.controlRadius),
                        medium = RoundedCornerShape(theme.cardRadius),
                        large = RoundedCornerShape(theme.deckRadius),
                        extraLarge = RoundedCornerShape(theme.deckRadius),
                    ),
                    letterSpacing = 0.sp,
                ) {
                    CompositionLocalProvider(
                        LocalThorLegacyMode provides true,
                        LocalContentColor provides theme.text,
                        LocalTextStyle provides MaterialTheme.typography.bodyLarge,
                        content = content,
                    )
                }
            }
        }
    }
}

/** Legacy components read these roles directly, in addition to MaterialTheme's scheme. */
internal fun thorLegacyGlassColors(theme: ThorThemeTokens) = GlassColors(
    isDark = theme.isDark,
    accent = theme.accent,
    content = theme.text,
    secondaryContent = theme.secondary,
    container = theme.surface,
    // Legacy prominent controls choose black/white by luminance. The enamel's top swatch
    // keeps that choice legible for all three night palettes as well as their day palettes.
    prominentContainer = theme.primaryTop,
    subtleStroke = theme.edge,
    groupedBackground = theme.background,
    elevatedBackground = theme.surface,
    separator = theme.outline,
    tertiaryContent = theme.secondary,
    destructive = theme.error,
)

internal fun thorLegacyGlassAppearance(theme: ThorThemeTokens) = GlassAppearance(
    liquidGlassEnabled = theme.pack == ThorThemePack.Glass && !theme.reduceTransparency,
    reduceTransparency = theme.reduceTransparency,
)

/** Artwork may retain its ambient rendering without replacing a Thor compatibility palette. */
@Composable
internal fun ThorRespectingMusicTheme(seedColor: Color, isDark: Boolean, content: @Composable () -> Unit) {
    if (LocalThorLegacyMode.current) content()
    else MusicTheme(seedColor = seedColor, isDark = isDark, content = content)
}

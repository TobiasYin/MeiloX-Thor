package com.ljyh.mei.thor.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ljyh.mei.thor.model.ThorAppearance
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.thor.model.ThorThemePack

/** Semantic roles shared by both displays. Artwork colors never replace interaction colors. */
@Immutable
data class ThorThemeTokens(
    val background: Color,
    val surface: Color,
    val elevated: Color,
    val inset: Color,
    val text: Color,
    val secondary: Color,
    val accent: Color,
    val onAccent: Color,
    val focus: Color,
    val outline: Color,
    val edge: Color,
    val selected: Color,
    val selectedText: Color,
    val raisedTop: Color,
    val raisedBottom: Color,
    val primaryTop: Color,
    val primaryBottom: Color,
    val highlight: Color,
    val contact: Color,
    val shadow: Color,
    val primaryHighlight: Color,
    val primaryLowlight: Color,
    val paneGlint: Color,
    val grain: Color,
    val ambient: Color,
    val caseEdge: Color,
    val caseBase: Color,
    val caseHighlight: Color,
    val caseReflection: Color,
    val caseSpineLight: Color,
    val caseSpineDark: Color,
    val caseText: Color,
    val caseShadow: Color,
    val pack: ThorThemePack,
    val isDark: Boolean,
    val controlRadius: Dp,
    val cardRadius: Dp,
    val smallRadius: Dp,
    val deckRadius: Dp,
    val shadowBlur: Dp,
    val shadowLift: Dp,
    val glintAngle: Float,
    val reduceMotion: Boolean = false,
    val reduceTransparency: Boolean = false,
) {
    val success: Color get() = accent
    val warning: Color get() = if (isDark) Color(0xFFF4C36B) else Color(0xFF815000)
    val error: Color get() = if (isDark) Color(0xFFFFB4AB) else Color(0xFFA82C25)
    val onError: Color get() = if (isDark) Color(0xFF561B16) else Color.White
    val scrim: Color get() = background.copy(alpha = .72f)
    val disabledText: Color get() = secondary
    val overlay: Color get() = surface
}

val LocalThorTheme = staticCompositionLocalOf { thorThemeTokens(ThorThemePack.Ceramic, false) }

/** Color and material selection is independent of navigation, focus and playback state. */
fun thorThemeTokens(
    pack: ThorThemePack,
    isDark: Boolean,
    reduceMotion: Boolean = false,
    reduceTransparency: Boolean = false,
): ThorThemeTokens {
    val tokens = when (pack) {
        ThorThemePack.Ceramic -> if (isDark) {
            ThorThemeTokens(
                background = Color(0xFF171E1C),
                surface = Color(0xFF252E29),
                elevated = Color(0xFF333D35),
                inset = Color(0xFF101714),
                text = Color(0xFFEEF3EB),
                secondary = Color(0xFFB8C5B8),
                accent = Color(0xFF9AD9BA),
                onAccent = Color(0xFF112C21),
                focus = Color(0xFFA4E5C4),
                outline = Color(0xFF3A473D),
                edge = Color(0xFF59685B),
                selected = Color(0xFF2B4335),
                selectedText = Color(0xFFB6E9CC),
                raisedTop = Color(0xFF333D35),
                raisedBottom = Color(0xFF242D27),
                primaryTop = Color(0xFFB1E3C6),
                primaryBottom = Color(0xFF88C8A9),
                highlight = Color(0x1FD6E9CA),
                contact = Color(0x70020C08),
                shadow = Color(0x3D020A06),
                primaryHighlight = Color(0x99F0FFF0),
                primaryLowlight = Color(0x38214F37),
                paneGlint = Color(0x12CAE2CB),
                grain = Color(0x04D2E4CE),
                ambient = Color(0xFF253E30),
                caseEdge = Color(0xC9BBCFC5),
                caseBase = Color(0xFF435E51),
                caseHighlight = Color(0x66E3F2E4),
                caseReflection = Color(0x2BE3F5EB),
                caseSpineLight = Color(0xFFADC7B5),
                caseSpineDark = Color(0xFF3A5D4C),
                caseText = Color(0xFF12291E),
                caseShadow = Color(0x5C000A08),
                pack = ThorThemePack.Ceramic, isDark = true,
                controlRadius = 12.dp, cardRadius = 14.dp, smallRadius = 6.dp, deckRadius = 24.dp,
                shadowBlur = 12.dp, shadowLift = 4.dp, glintAngle = 155f,
            )
        } else {
            ThorThemeTokens(
                background = Color(0xFFEFEEE8),
                surface = Color(0xFFFAFAF5),
                elevated = Color(0xFFFFFFFF),
                inset = Color(0xFFE3E5DE),
                text = Color(0xFF202B28),
                secondary = Color(0xFF526059),
                accent = Color(0xFF126B58),
                onAccent = Color(0xFFFFFFFF),
                focus = Color(0xFF00624C),
                outline = Color(0xFFCFD4CB),
                edge = Color(0xFFB1BBB1),
                selected = Color(0xFFDEECE3),
                selectedText = Color(0xFF075843),
                raisedTop = Color(0xFFFFFFFF),
                raisedBottom = Color(0xFFEEEFE7),
                primaryTop = Color(0xFF19745F),
                primaryBottom = Color(0xFF105A49),
                highlight = Color(0xD9FFFFFF),
                contact = Color(0x2425392E),
                shadow = Color(0x14263C2D),
                primaryHighlight = Color(0x52FFFFFF),
                primaryLowlight = Color(0x38002B1F),
                paneGlint = Color(0x7AFFFFFF),
                grain = Color(0x04273D2F),
                ambient = Color(0xFFD1DFD3),
                caseEdge = Color(0xE8E1EBE8),
                caseBase = Color(0xFFC9D8D2),
                caseHighlight = Color(0xBBFFFFFF),
                caseReflection = Color(0x45FFFFFF),
                caseSpineLight = Color(0xFFEFF5EF),
                caseSpineDark = Color(0xFF8FA9A0),
                caseText = Color(0xFF253B32),
                caseShadow = Color(0x351A342E),
                pack = ThorThemePack.Ceramic, isDark = false,
                controlRadius = 12.dp, cardRadius = 14.dp, smallRadius = 6.dp, deckRadius = 24.dp,
                shadowBlur = 12.dp, shadowLift = 4.dp, glintAngle = 155f,
            )
        }
        ThorThemePack.Glass -> if (isDark) {
            ThorThemeTokens(
                background = Color(0xFF151C2B),
                surface = Color(0xFF252F44),
                elevated = Color(0xFF35415B),
                inset = Color(0xFF0E1523),
                text = Color(0xFFEDF2FF),
                secondary = Color(0xFFBCC9E3),
                accent = Color(0xFFA5BEFF),
                onAccent = Color(0xFF17274B),
                focus = Color(0xFFB8CBFF),
                outline = Color(0xFF3E4E6B),
                edge = Color(0xFF607397),
                selected = Color(0xFF2D426B),
                selectedText = Color(0xFFCCD9FF),
                raisedTop = Color(0xFF35415B),
                raisedBottom = Color(0xFF242E43),
                primaryTop = Color(0xFFBED0FF),
                primaryBottom = Color(0xFF91ABEE),
                highlight = Color(0x30D7E5FF),
                contact = Color(0x8003091C),
                shadow = Color(0x47040B23),
                primaryHighlight = Color(0xB3F1F6FF),
                primaryLowlight = Color(0x4D2A4773),
                paneGlint = Color(0x13BDD3FF),
                grain = Color(0x04D3E2FF),
                ambient = Color(0xFF243C63),
                caseEdge = Color(0xD4BED0F5),
                caseBase = Color(0xFF42597A),
                caseHighlight = Color(0x8CE7F1FF),
                caseReflection = Color(0x38DBECFF),
                caseSpineLight = Color(0xFFB3C9EA),
                caseSpineDark = Color(0xFF35517B),
                caseText = Color(0xFF152440),
                caseShadow = Color(0x6602071A),
                pack = ThorThemePack.Glass, isDark = true,
                controlRadius = 14.dp, cardRadius = 16.dp, smallRadius = 8.dp, deckRadius = 26.dp,
                shadowBlur = 16.dp, shadowLift = 5.dp, glintAngle = 118f,
            )
        } else {
            ThorThemeTokens(
                background = Color(0xFFE9EDF4),
                surface = Color(0xFFF7F9FE),
                elevated = Color(0xFFFFFFFF),
                inset = Color(0xFFDEE5F0),
                text = Color(0xFF202D43),
                secondary = Color(0xFF4D5D75),
                accent = Color(0xFF315BC5),
                onAccent = Color(0xFFFFFFFF),
                focus = Color(0xFF204FAF),
                outline = Color(0xFFCCD6E6),
                edge = Color(0xFFA6B7D3),
                selected = Color(0xFFDCE6FB),
                selectedText = Color(0xFF234C9D),
                raisedTop = Color(0xFFFFFFFF),
                raisedBottom = Color(0xFFE7EDF8),
                primaryTop = Color(0xFF3D66CF),
                primaryBottom = Color(0xFF284BA8),
                highlight = Color(0xF0FFFFFF),
                contact = Color(0x24263C6A),
                shadow = Color(0x171A397E),
                primaryHighlight = Color(0x66FFFFFF),
                primaryLowlight = Color(0x47102663),
                paneGlint = Color(0xB3FFFFFF),
                grain = Color(0x044667B1),
                ambient = Color(0xFFC6D7F1),
                caseEdge = Color(0xE6DDE8FA),
                caseBase = Color(0xFFC3D4EA),
                caseHighlight = Color(0xCCFFFFFF),
                caseReflection = Color(0x66FFFFFF),
                caseSpineLight = Color(0xFFF0F7FF),
                caseSpineDark = Color(0xFF869FBD),
                caseText = Color(0xFF25384F),
                caseShadow = Color(0x381A2A53),
                pack = ThorThemePack.Glass, isDark = false,
                controlRadius = 14.dp, cardRadius = 16.dp, smallRadius = 8.dp, deckRadius = 26.dp,
                shadowBlur = 16.dp, shadowLift = 5.dp, glintAngle = 118f,
            )
        }
        ThorThemePack.Titanium -> if (isDark) {
            ThorThemeTokens(
                background = Color(0xFF222320),
                surface = Color(0xFF32342E),
                elevated = Color(0xFF44463C),
                inset = Color(0xFF161813),
                text = Color(0xFFF2EFE5),
                secondary = Color(0xFFC4C2B4),
                accent = Color(0xFFEFBC7B),
                onAccent = Color(0xFF33220D),
                focus = Color(0xFFF6C990),
                outline = Color(0xFF4A4D40),
                edge = Color(0xFF6C6F60),
                selected = Color(0xFF4A3D28),
                selectedText = Color(0xFFF7D4A3),
                raisedTop = Color(0xFF44463C),
                raisedBottom = Color(0xFF2D3028),
                primaryTop = Color(0xFFF5D09A),
                primaryBottom = Color(0xFFDCA65F),
                highlight = Color(0x30FFF7DD),
                contact = Color(0x99020500),
                shadow = Color(0x52020400),
                primaryHighlight = Color(0xBFFFF7E1),
                primaryLowlight = Color(0x4D6B4915),
                paneGlint = Color(0x0AFFF3CA),
                grain = Color(0x09F0E4BF),
                ambient = Color(0xFF493F2B),
                caseEdge = Color(0xD4D9D7BE),
                caseBase = Color(0xFF62654F),
                caseHighlight = Color(0x80FBF6D6),
                caseReflection = Color(0x2EFFF5D6),
                caseSpineLight = Color(0xFFCECFAF),
                caseSpineDark = Color(0xFF5C6141),
                caseText = Color(0xFF252B14),
                caseShadow = Color(0x73000400),
                pack = ThorThemePack.Titanium, isDark = true,
                controlRadius = 8.dp, cardRadius = 10.dp, smallRadius = 4.dp, deckRadius = 18.dp,
                shadowBlur = 8.dp, shadowLift = 3.dp, glintAngle = 175f,
            )
        } else {
            ThorThemeTokens(
                background = Color(0xFFE3E0D9),
                surface = Color(0xFFF4F1E9),
                elevated = Color(0xFFFCFAF4),
                inset = Color(0xFFD4D2CC),
                text = Color(0xFF2C2A25),
                secondary = Color(0xFF58554C),
                accent = Color(0xFF87461F),
                onAccent = Color(0xFFFFFFFF),
                focus = Color(0xFF743815),
                outline = Color(0xFFC2BEB4),
                edge = Color(0xFFA7A195),
                selected = Color(0xFFEBDECA),
                selectedText = Color(0xFF743B16),
                raisedTop = Color(0xFFFCFAF4),
                raisedBottom = Color(0xFFD8D6CF),
                primaryTop = Color(0xFF944D22),
                primaryBottom = Color(0xFF6E3617),
                highlight = Color(0xE0FFFFFF),
                contact = Color(0x40302A22),
                shadow = Color(0x1A30251B),
                primaryHighlight = Color(0x70FFE7C7),
                primaryLowlight = Color(0x5C3C1C00),
                paneGlint = Color(0x52FFFFFF),
                grain = Color(0x0A3C352B),
                ambient = Color(0xFFD5C7AF),
                caseEdge = Color(0xE8E6E3D6),
                caseBase = Color(0xFFC9C7BA),
                caseHighlight = Color(0xBFFFFFF5),
                caseReflection = Color(0x40FFFFFF),
                caseSpineLight = Color(0xFFF5F1DC),
                caseSpineDark = Color(0xFF99967F),
                caseText = Color(0xFF383524),
                caseShadow = Color(0x4731291B),
                pack = ThorThemePack.Titanium, isDark = false,
                controlRadius = 8.dp, cardRadius = 10.dp, smallRadius = 4.dp, deckRadius = 18.dp,
                shadowBlur = 8.dp, shadowLift = 3.dp, glintAngle = 175f,
            )
        }
    }
    return tokens.copy(
        reduceMotion = reduceMotion,
        reduceTransparency = reduceTransparency,
        paneGlint = if (reduceTransparency) Color.Transparent else tokens.paneGlint,
        grain = if (reduceTransparency) Color.Transparent else tokens.grain,
    )
}

private val ThorTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Medium),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 16.sp),
)

@Composable
fun ThorTheme(preferences: ThorPreferences, content: @Composable () -> Unit) {
    val dark = when (preferences.appearance) {
        ThorAppearance.System -> isSystemInDarkTheme()
        ThorAppearance.Day -> false
        ThorAppearance.Night -> true
    }
    val tokens = remember(preferences.theme, dark, preferences.reduceMotion, preferences.reduceTransparency) {
        thorThemeTokens(preferences.theme, dark, preferences.reduceMotion, preferences.reduceTransparency)
    }
    val colors = remember(tokens) {
        val base = if (dark) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = tokens.accent, onPrimary = tokens.onAccent,
            primaryContainer = tokens.selected, onPrimaryContainer = tokens.selectedText,
            secondary = tokens.accent, onSecondary = tokens.onAccent,
            secondaryContainer = tokens.selected, onSecondaryContainer = tokens.selectedText,
            tertiary = tokens.accent, onTertiary = tokens.onAccent,
            background = tokens.background, onBackground = tokens.text,
            surface = tokens.surface, onSurface = tokens.text,
            surfaceVariant = tokens.inset, onSurfaceVariant = tokens.secondary,
            surfaceTint = Color.Transparent,
            surfaceDim = tokens.inset, surfaceBright = tokens.elevated,
            surfaceContainerLowest = tokens.background, surfaceContainerLow = tokens.surface,
            surfaceContainer = tokens.surface, surfaceContainerHigh = tokens.elevated,
            surfaceContainerHighest = tokens.raisedTop,
            outline = tokens.edge, outlineVariant = tokens.outline,
            inverseSurface = tokens.text, inverseOnSurface = tokens.background,
            error = tokens.error, onError = tokens.onError,
            scrim = tokens.scrim,
        )
    }
    val shapes = remember(tokens.controlRadius, tokens.cardRadius) {
        Shapes(
            extraSmall = RoundedCornerShape(tokens.smallRadius),
            small = RoundedCornerShape(tokens.controlRadius),
            medium = RoundedCornerShape(tokens.cardRadius),
            large = RoundedCornerShape(tokens.deckRadius),
            extraLarge = RoundedCornerShape(tokens.deckRadius),
        )
    }
    CompositionLocalProvider(LocalThorTheme provides tokens, LocalContentColor provides tokens.text) {
        MaterialTheme(colorScheme = colors, typography = ThorTypography, shapes = shapes, content = content)
    }
}

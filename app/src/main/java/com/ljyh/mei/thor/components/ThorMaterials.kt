package com.ljyh.mei.thor.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.ljyh.mei.thor.model.ThorThemePack
import com.ljyh.mei.thor.theme.LocalThorTheme
import com.ljyh.mei.thor.theme.ThorMaterialEasing

/** Roles reproduce the last shared-material CSS, without owning geometry, focus or navigation. */
enum class ThorSurfaceRole {
    Canvas, Panel, Raised, Inset, Primary,
    Flat, Header, NavRail, NowPlaying, Deck, MusicRow, Overlay, ProgressTrack, Transport,
}

/**
 * Paint only. shape wins over cornerRadius; neither changes measured bounds or the hit target.
 * Press and selection are independent and use the prototype's 120/180ms interruptible timelines.
 */
fun Modifier.thorMaterial(
    role: ThorSurfaceRole = ThorSurfaceRole.Panel,
    focused: Boolean = false,
    selected: Boolean = false,
    pressed: Boolean = false,
    shape: Shape? = null,
    enabled: Boolean = true,
    showSelectionMarker: Boolean = true,
    focusRingColor: Color = Color.Unspecified,
    cornerRadius: Dp? = null,
): Modifier = composed {
    val theme = LocalThorTheme.current
    val resolvedShape = shape ?: cornerRadius?.let { RoundedCornerShape(it) } ?: when (role) {
        ThorSurfaceRole.Canvas, ThorSurfaceRole.Header, ThorSurfaceRole.NavRail, ThorSurfaceRole.NowPlaying -> RectangleShape
        ThorSurfaceRole.Deck -> RoundedCornerShape(theme.deckRadius)
        ThorSurfaceRole.MusicRow, ThorSurfaceRole.Overlay, ThorSurfaceRole.Panel -> RoundedCornerShape(theme.cardRadius)
        ThorSurfaceRole.ProgressTrack -> RoundedCornerShape(theme.smallRadius)
        else -> RoundedCornerShape(theme.controlRadius)
    }
    val press by animateFloatAsState(if (pressed && enabled) 1f else 0f,
        tween(theme.pressMs, easing = ThorMaterialEasing), label = "thor-press")
    val selection by animateFloatAsState(if (selected) 1f else 0f,
        tween(theme.surfaceMs, easing = ThorMaterialEasing), label = "thor-selection")
    val focus by animateFloatAsState(if (focused && enabled) 1f else 0f,
        tween(theme.pressMs, easing = ThorMaterialEasing), label = "thor-focus")
    // Never dim labels/icons as one layer: the .45 token belongs to disabled material decoration.
    val decoration = if (enabled) 1f else theme.disabledOpacity
    val relief = (1f - press) * decoration
    val raised = role == ThorSurfaceRole.Raised || role == ThorSurfaceRole.Transport
    val recessed = role == ThorSurfaceRole.Inset || role == ThorSurfaceRole.Deck || role == ThorSurfaceRole.ProgressTrack
    val primary = role == ThorSurfaceRole.Primary
    fun tint(color: Color, alpha: Float = 1f) = color.copy(alpha = color.alpha * alpha)
    var material: Modifier = this
    fun outer(radius: Dp, y: Dp, color: Color, alpha: Float = relief) {
        if (alpha <= 0f || color.alpha <= 0f) return
        material = material.dropShadow(resolvedShape, Shadow(radius = radius, color = tint(color, alpha), offset = DpOffset(0.dp, y)))
    }
    when {
        primary -> { outer(3.dp, 2.dp, theme.contact); outer(16.dp, 7.dp, theme.shadow) }
        role == ThorSurfaceRole.Overlay -> { outer(8.dp, 3.dp, theme.contact); outer(40.dp, 16.dp, theme.shadow) }
        role == ThorSurfaceRole.MusicRow -> outer(5.dp, 2.dp, theme.shadow, selection * relief)
        role == ThorSurfaceRole.NowPlaying -> {
            if (theme.pack == ThorThemePack.Glass) outer(10.dp, 3.dp, theme.shadow)
            else outer(8.dp, (-2).dp, theme.shadow)
        }
        role == ThorSurfaceRole.Header && theme.pack == ThorThemePack.Glass -> outer(10.dp, 3.dp, theme.shadow)
        raised -> {
            outer(2.dp, 1.dp, theme.contact)
            outer(if (role == ThorSurfaceRole.Transport && theme.pack == ThorThemePack.Titanium) 8.dp else theme.shadowBlur,
                if (role == ThorSurfaceRole.Transport && theme.pack == ThorThemePack.Titanium) 4.dp else theme.shadowLift, theme.shadow)
        }
        role == ThorSurfaceRole.Panel -> { outer(2.dp, 1.dp, theme.contact); outer(10.dp, 4.dp, theme.shadow) }
        recessed && role != ThorSurfaceRole.ProgressTrack -> outer(0.dp, 1.dp, theme.highlight, decoration)
    }
    material = material.clip(resolvedShape).drawWithCache {
        fun gradient(degrees: Float, vararg stops: Pair<Float, Color>): Brush {
            val line = thorCssGradientLine(size.width, size.height, degrees)
            return Brush.linearGradient(*stops, start = Offset(line.startX, line.startY), end = Offset(line.endX, line.endY))
        }
        val metalPanel = theme.pack == ThorThemePack.Titanium && role in setOf(ThorSurfaceRole.Header, ThorSurfaceRole.NavRail, ThorSurfaceRole.NowPlaying)
        val base = when (role) {
            ThorSurfaceRole.Flat, ThorSurfaceRole.MusicRow -> Color.Transparent
            ThorSurfaceRole.Canvas, ThorSurfaceRole.Header -> if (role == ThorSurfaceRole.Header && theme.pack == ThorThemePack.Glass) theme.surface else theme.background
            ThorSurfaceRole.Inset, ThorSurfaceRole.Deck -> theme.inset
            ThorSurfaceRole.ProgressTrack -> lerp(theme.inset, theme.secondary, .24f)
            else -> theme.surface
        }
        val backdrop = when {
            primary -> gradient(170f, 0f to theme.primaryTop, 1f to theme.primaryBottom)
            raised || metalPanel -> gradient(if (metalPanel) 175f else theme.glintAngle, 0f to theme.raisedTop, 1f to theme.raisedBottom)
            else -> Brush.verticalGradient(listOf(base, base))
        }
        val glint = if (theme.reduceTransparency) null else when {
            role == ThorSurfaceRole.Header || role == ThorSurfaceRole.NowPlaying -> when (theme.pack) {
                ThorThemePack.Glass -> gradient(118f, 0f to theme.paneGlint, .32f to Color.Transparent,
                    .64f to Color.Transparent, .67f to theme.paneGlint, .74f to Color.Transparent, 1f to Color.Transparent)
                ThorThemePack.Titanium -> null
                else -> gradient(180f, 0f to theme.paneGlint, 1f to Color.Transparent)
            }
            role == ThorSurfaceRole.NavRail && !metalPanel -> gradient(theme.glintAngle, 0f to theme.paneGlint, .43f to Color.Transparent, 1f to Color.Transparent)
            role == ThorSurfaceRole.Deck && theme.pack == ThorThemePack.Glass -> gradient(125f, 0f to theme.paneGlint, .45f to Color.Transparent, 1f to Color.Transparent)
            role == ThorSurfaceRole.Overlay -> gradient(160f, 0f to theme.paneGlint, .42f to Color.Transparent, 1f to Color.Transparent)
            role == ThorSurfaceRole.Panel -> gradient(180f, 0f to theme.paneGlint, 1f to Color.Transparent)
            else -> null
        }
        val rowGlint = gradient(180f, 0f to tint(theme.paneGlint, .5f), 1f to Color.Transparent)
        onDrawBehind {
            drawRect(backdrop, alpha = decoration)
            if (glint != null) drawRect(glint, alpha = decoration)
            if (!theme.reduceTransparency && theme.pack == ThorThemePack.Titanium && (metalPanel || role == ThorSurfaceRole.Deck)) {
                var y = 0f
                while (y < size.height) {
                    drawRect(tint(theme.grain, decoration), Offset(0f, y), Size(size.width, 1.dp.toPx()))
                    y += 3.dp.toPx()
                }
            }
            if (selection > 0f) {
                drawRect(theme.selected, alpha = selection)
                if (role == ThorSurfaceRole.MusicRow && !theme.reduceTransparency) drawRect(rowGlint, alpha = selection)
            }
            if (role == ThorSurfaceRole.MusicRow && selection < 1f) {
                drawLine(tint(theme.outline, .5f * (1f - selection)), Offset(58.dp.toPx().coerceAtMost(size.width), size.height - .5.dp.toPx()),
                    Offset((size.width - 12.dp.toPx()).coerceAtLeast(0f), size.height - .5.dp.toPx()), 1.dp.toPx())
            }
        }
    }
    fun inner(radius: Dp, y: Dp, color: Color, alpha: Float) {
        if (alpha <= 0f || color.alpha <= 0f) return
        material = material.innerShadow(resolvedShape, Shadow(radius = radius, color = tint(color, alpha), offset = DpOffset(0.dp, y)))
    }
    // CSS inset edge highlights and bottom lowlights; never overpaint foreground content.
    if (primary) {
        inner(0.dp, 1.dp, theme.primaryHighlight, relief)
        inner(0.dp, (-2).dp, theme.primaryLowlight, relief)
    } else if (raised || role in setOf(ThorSurfaceRole.Panel, ThorSurfaceRole.Overlay, ThorSurfaceRole.NowPlaying) ||
        (role == ThorSurfaceRole.Header && theme.pack == ThorThemePack.Glass)) {
        inner(0.dp, 1.dp, theme.highlight, relief)
        if (role == ThorSurfaceRole.Transport && theme.pack == ThorThemePack.Titanium) inner(0.dp, (-2).dp, theme.contact, relief)
    }
    if (role == ThorSurfaceRole.NavRail) {
        material = material.innerShadow(resolvedShape, Shadow(radius = 0.dp, color = tint(theme.highlight, decoration), offset = DpOffset(1.dp, 0.dp)))
    }
    if (recessed) inner(if (role == ThorSurfaceRole.ProgressTrack) 2.dp else 3.dp, 1.dp, theme.contact, decoration * (1f - press))
    if (selection > 0f && role != ThorSurfaceRole.MusicRow) {
        inner(0.dp, 1.dp, theme.highlight, selection * relief)
        inner(0.dp, (-1).dp, theme.contact, selection * relief)
    }
    inner(4.dp, 2.dp, theme.contact, press * decoration)
    inner(0.dp, 1.dp, theme.highlight, press * decoration)
    material.drawWithCache {
        val ringInk = if (focusRingColor != Color.Unspecified) focusRingColor else if (primary && !selected) theme.onAccent else theme.focus
        onDrawBehind {
            if (role == ThorSurfaceRole.MusicRow && selection > 0f) {
                outlineRing(resolvedShape, tint(theme.focus, selection), theme.selectionStroke.toPx())
                if (showSelectionMarker) drawRoundRect(tint(theme.focus, selection), Offset(0f, size.height * .3f),
                    Size(3.dp.toPx(), size.height * .4f), CornerRadius(2.dp.toPx()))
            }
            if (focus > 0f) outlineRing(resolvedShape, tint(ringInk, focus), 2.dp.toPx())
        }
    }
}

private fun DrawScope.outlineRing(shape: Shape, color: Color, stroke: Float) {
    inset(stroke / 2) {
        when (val outline = shape.createOutline(size, layoutDirection, this)) {
            is Outline.Rectangle -> drawRect(color, style = Stroke(stroke))
            is Outline.Rounded -> drawPath(Path().apply { addRoundRect(outline.roundRect) }, color, style = Stroke(stroke))
            is Outline.Generic -> drawPath(outline.path, color, style = Stroke(stroke))
        }
    }
}

@Composable
fun ThorMaterialSurface(
    modifier: Modifier = Modifier,
    role: ThorSurfaceRole = ThorSurfaceRole.Panel,
    selected: Boolean = false,
    focused: Boolean = false,
    shape: Shape? = null,
    cornerRadius: Dp? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val theme = LocalThorTheme.current
    val color = if (selected) theme.selectedText else if (role == ThorSurfaceRole.Primary) theme.onAccent else theme.text
    Box(modifier.thorMaterial(role, focused, selected, shape = shape, cornerRadius = cornerRadius)) {
        CompositionLocalProvider(LocalContentColor provides color) { content() }
    }
}

@Composable
fun ThorActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    selected: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    role: ThorSurfaceRole? = null,
    shape: Shape? = null,
    focused: Boolean? = null,
    cornerRadius: Dp? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val theme = LocalThorTheme.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var nativeFocus by remember { mutableStateOf(false) }
    val selectedInk by animateFloatAsState(if (selected) 1f else 0f,
        tween(theme.pressMs, easing = ThorMaterialEasing), label = "thor-button-ink")
    val resolvedRole = role ?: if (primary) ThorSurfaceRole.Primary else ThorSurfaceRole.Raised
    val normalInk = if (resolvedRole == ThorSurfaceRole.Primary) theme.onAccent else theme.text
    val color = if (!enabled) theme.disabledText else lerp(normalInk, theme.selectedText, selectedInk)
    Row(modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
        .thorMaterial(resolvedRole, focused ?: nativeFocus, selected, pressed, shape, enabled, cornerRadius = cornerRadius)
        .onFocusChanged { nativeFocus = it.isFocused }
        .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null, onClick = onClick)
        .padding(contentPadding), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides color,
            LocalTextStyle provides MaterialTheme.typography.labelLarge.copy(fontSize = theme.typography.control, fontWeight = FontWeight.Normal),
        ) { content() }
    }
}

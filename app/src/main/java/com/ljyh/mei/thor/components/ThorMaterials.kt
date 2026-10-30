package com.ljyh.mei.thor.components

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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ljyh.mei.thor.model.ThorThemePack
import com.ljyh.mei.thor.theme.LocalThorTheme
import kotlin.math.cos
import kotlin.math.sin

enum class ThorSurfaceRole { Canvas, Panel, Raised, Inset, Primary }

/** Material effects only: this modifier never changes layout bounds or installs focus handlers. */
fun Modifier.thorMaterial(
    role: ThorSurfaceRole = ThorSurfaceRole.Panel,
    focused: Boolean = false,
    selected: Boolean = false,
    pressed: Boolean = false,
): Modifier = composed {
    val theme = LocalThorTheme.current
    val shape = RoundedCornerShape(theme.controlRadius)
    val elevation = when {
        pressed || role == ThorSurfaceRole.Canvas || role == ThorSurfaceRole.Inset -> 0.dp
        role == ThorSurfaceRole.Primary -> 5.dp
        role == ThorSurfaceRole.Raised -> theme.shadowLift
        else -> 0.dp
    }
    shadow(elevation, shape, clip = false, ambientColor = theme.shadow, spotColor = theme.contact)
        .clip(shape)
        .drawWithCache {
            val radius = CornerRadius(theme.controlRadius.toPx())
            val base = when {
                selected -> theme.selected
                role == ThorSurfaceRole.Canvas -> theme.background
                role == ThorSurfaceRole.Inset -> theme.inset
                else -> theme.surface
            }
            val angle = Math.toRadians(theme.glintAngle.toDouble())
            val diagonal = size.width + size.height
            val delta = Offset((cos(angle) * diagonal).toFloat(), (sin(angle) * diagonal).toFloat())
            val midpoint = Offset(size.width / 2f, size.height / 2f)
            val material = when {
                selected -> Brush.verticalGradient(listOf(theme.selected, theme.selected))
                role == ThorSurfaceRole.Primary -> Brush.linearGradient(listOf(theme.primaryTop, theme.primaryBottom))
                role == ThorSurfaceRole.Raised -> Brush.linearGradient(
                    listOf(theme.raisedTop, theme.raisedBottom), midpoint - delta / 2f, midpoint + delta / 2f,
                )
                else -> Brush.verticalGradient(listOf(base, base))
            }
            val ringColor = if (role == ThorSurfaceRole.Primary && !selected) theme.onAccent else theme.focus
            val stroke = 2.dp.toPx()
            onDrawBehind {
                drawRoundRect(material, cornerRadius = radius)
                if (role != ThorSurfaceRole.Canvas) {
                    val edge = if (pressed || role == ThorSurfaceRole.Inset) theme.contact else theme.highlight
                    drawLine(edge, Offset(radius.x, .5f), Offset(size.width - radius.x, .5f), 1.dp.toPx())
                    if (!theme.reduceTransparency && !selected && role != ThorSurfaceRole.Primary) {
                        drawRoundRect(
                            Brush.linearGradient(listOf(theme.paneGlint, Color.Transparent), Offset.Zero, Offset(size.width * .8f, size.height)),
                            cornerRadius = radius,
                        )
                    }
                    // Fine grain belongs to broad panels/recesses. On raised controls its bright
                    // stripes would reduce the already light metal surface's secondary contrast.
                    if (theme.pack == ThorThemePack.Titanium && !theme.reduceTransparency && !selected &&
                        (role == ThorSurfaceRole.Panel || role == ThorSurfaceRole.Inset)
                    ) {
                        var y = 2f
                        while (y < size.height) {
                            drawLine(theme.grain, Offset(0f, y), Offset(size.width, y), 1f)
                            y += 3.dp.toPx()
                        }
                    }
                }
                if (focused) {
                    drawRoundRect(
                        ringColor, Offset(stroke / 2, stroke / 2),
                        Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)),
                        radius, style = Stroke(stroke),
                    )
                }
            }
        }
}

@Composable
fun ThorMaterialSurface(
    modifier: Modifier = Modifier,
    role: ThorSurfaceRole = ThorSurfaceRole.Panel,
    selected: Boolean = false,
    focused: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val theme = LocalThorTheme.current
    val contentColor = when {
        selected -> theme.selectedText
        role == ThorSurfaceRole.Primary -> theme.onAccent
        else -> theme.text
    }
    Box(modifier.thorMaterial(role, focused, selected)) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

/** Optional reusable control; callers remain free to compose native controls with the same tokens. */
@Composable
fun ThorActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    selected: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val theme = LocalThorTheme.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .thorMaterial(if (!enabled) ThorSurfaceRole.Inset else if (primary) ThorSurfaceRole.Primary else ThorSurfaceRole.Raised, focused, selected, pressed)
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, role = Role.Button, interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (!enabled) theme.disabledText else if (primary && !selected) theme.onAccent else if (selected) theme.selectedText else theme.text,
            LocalTextStyle provides MaterialTheme.typography.labelLarge,
        ) { content() }
    }
}

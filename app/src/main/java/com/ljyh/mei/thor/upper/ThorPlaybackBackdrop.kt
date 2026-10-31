package com.ljyh.mei.thor.upper

import android.graphics.RuntimeShader
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.ljyh.mei.thor.artwork.CaseAmbientPalette
import com.ljyh.mei.thor.artwork.forAmbient
import com.ljyh.mei.thor.model.ThorBackgroundMode
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.thor.model.advanceThorBackdropPhase
import com.ljyh.mei.thor.theme.LocalThorTheme
import com.ljyh.mei.thor.theme.readableThorBackdropColor
import com.ljyh.mei.thor.theme.thorBackdropContrast
import com.ljyh.mei.thor.theme.thorBackdropProfile
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.isActive
import kotlin.math.exp
import kotlin.math.sin

/** Optional test witness; production does not allocate or update diagnostics. */
internal class ThorBackdropDiagnostics {
    val frameTicks = AtomicLong()
    val draws = AtomicLong()
    @Volatile var backend: String = "pending"
}

/** One shader per composition/window. Palette transitions never compile another program. */
internal fun createThorBackdropShader(): RuntimeShader = RuntimeShader(ThorBackdropAgsl)

/**
 * A soft optical field rather than a blurred full-resolution cover. Gaussian color pools and
 * a defocused light ribbon fuse the real cover colors without a large offscreen blur pass.
 */
@Composable
internal fun ThorPlaybackBackdrop(
    palette: CaseAmbientPalette,
    active: Boolean,
    playing: Boolean,
    preferences: ThorPreferences,
    modifier: Modifier = Modifier,
    diagnostics: ThorBackdropDiagnostics? = null,
) {
    val theme = LocalThorTheme.current
    val readingContrast = remember(theme.isDark, theme.text, theme.secondary) {
        thorBackdropContrast(theme.isDark, intArrayOf(theme.text.toArgb(), theme.secondary.toArgb()))
    }
    val profile = thorBackdropProfile(preferences.backgroundMode, preferences.backgroundIntensity,
        theme.isDark, theme.reduceMotion, theme.reduceTransparency, active, playing)
    val mapped = remember(palette, theme.isDark) { palette.forAmbient(theme.isDark) }
    val colorMillis = if (theme.reduceMotion || !profile.visible || !active) 0 else 1_100
    val body = animateColorAsState(mapped.body, tween(colorMillis), label = "backdrop-body")
    val glow = animateColorAsState(mapped.glow, tween(colorMillis), label = "backdrop-glow")
    val accent = animateColorAsState(mapped.accent, tween(colorMillis), label = "backdrop-accent")
    val motionMillis = if (theme.reduceMotion || !active) 0 else 320
    val strength = animateFloatAsState(profile.colorStrength, tween(motionMillis), label = "backdrop-strength")
    val shape = animateFloatAsState(if (profile.mode == ThorBackgroundMode.ArtworkFlow) 1f else 0f,
        tween(motionMillis), label = "backdrop-shape")
    val colorWeight = animateFloatAsState(profile.artworkColorWeight, tween(motionMillis), label = "backdrop-palette-weight")
    val deformation = animateFloatAsState(if (profile.mode == ThorBackgroundMode.ArtworkFlow) profile.deformationStrength else 0f,
        tween(motionMillis), label = "backdrop-deformation")
    val flowGeometry = thorBackdropProfile(ThorBackgroundMode.ArtworkFlow, preferences.backgroundIntensity, theme.isDark)
    val softGeometry = thorBackdropProfile(ThorBackgroundMode.SoftGradient, preferences.backgroundIntensity, theme.isDark)
    val phase = remember { mutableFloatStateOf(0f) }
    val velocity = remember { floatArrayOf(0f) }
    val shader = remember { runCatching(::createThorBackdropShader).getOrNull() }
    val shaderBrush = remember(shader) { shader?.let(::ShaderBrush) }
    val latestSpeed by rememberUpdatedState(profile.speed.takeIf { it > 0f } ?: .20f)

    LaunchedEffect(active, profile.animate, profile.mode, theme.reduceMotion, diagnostics) {
        if (!active || theme.reduceMotion || profile.mode != ThorBackgroundMode.ArtworkFlow) {
            velocity[0] = 0f
            return@LaunchedEffect
        }
        if (!profile.animate && velocity[0] <= .005f) return@LaunchedEffect
        var previous = 0L
        var pending = 0f
        var elapsed = phase.floatValue.toDouble()
        while (isActive) {
            val now = withFrameNanos { it }
            diagnostics?.frameTicks?.incrementAndGet()
            val dt = if (previous == 0L) 0f else ((now - previous) / 1_000_000_000f).coerceIn(0f, .05f)
            previous = now
            val target = if (profile.animate) 1f else 0f
            velocity[0] += (target - velocity[0]) * (1f - exp(-dt / if (profile.animate) .18f else .09f))
            elapsed = advanceThorBackdropPhase(elapsed, (dt * latestSpeed * velocity[0]).toDouble())
            pending += dt
            val resting = !profile.animate && velocity[0] <= .005f
            // Only drawing reads this state. The CD model and lyrics do not recompose at 30 Hz.
            if (pending >= 1f / 30f || resting) {
                phase.floatValue = elapsed.toFloat()
                pending = 0f
            }
            if (resting) { velocity[0] = 0f; break }
        }
    }

    // This retained half-resolution layer is 960×540 on Thor. Scaling the low-frequency light
    // field is naturally soft and avoids re-shading two million pixels whenever the CD moves.
    BoxWithConstraints(modifier.clipToBounds()) {
        Canvas(Modifier.requiredSize(maxWidth / 2, maxHeight / 2).align(Alignment.TopStart).graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
            scaleX = 2f; scaleY = 2f; transformOrigin = TransformOrigin(0f, 0f)
        }) {
            diagnostics?.draws?.incrementAndGet()
            val base = theme.background
            if (!profile.visible && strength.value <= .001f) {
                diagnostics?.backend = "clean"
                drawRect(base)
                return@Canvas
            }
            val first = lerp(theme.ambient, body.value, colorWeight.value)
            val second = lerp(theme.ambient, glow.value, colorWeight.value)
            val third = lerp(theme.ambient, accent.value, colorWeight.value)
            // Keep the last phase across mode changes; only geometry morphs, never the clock.
            val time = phase.floatValue
            fun blend(a: Float, b: Float) = a + (b - a) * shape.value
            val fromPrimary = softGeometry.primaryField
            val toPrimary = flowGeometry.primaryField
            val fromSecondary = softGeometry.secondaryField
            val toSecondary = flowGeometry.secondaryField
            if (shader != null && shaderBrush != null) {
                diagnostics?.backend = "agsl"
                shader.setFloatUniform("resolution", size.width, size.height)
                shader.setFloatUniform("clock", time)
                shader.setFloatUniform("strength", strength.value)
                shader.setFloatUniform("deformation", deformation.value)
                shader.setFloatUniform("highlights", if (profile.highlights) 1f else 0f)
                shader.setFloatUniform("quietRange", profile.quietStartX, profile.quietEndX, profile.quietRetention)
                shader.setFloatUniform("readingContrast", if (readingContrast.isDark) 1f else 0f, readingContrast.luminanceLimit)
                shader.setFloatUniform("primaryField", blend(fromPrimary.centerX, toPrimary.centerX), blend(fromPrimary.centerY, toPrimary.centerY),
                    blend(fromPrimary.radiusX, toPrimary.radiusX), blend(fromPrimary.radiusY, toPrimary.radiusY))
                shader.setFloatUniform("secondaryField", blend(fromSecondary.centerX, toSecondary.centerX), blend(fromSecondary.centerY, toSecondary.centerY),
                    blend(fromSecondary.radiusX, toSecondary.radiusX), blend(fromSecondary.radiusY, toSecondary.radiusY))
                shader.setColorUniform("baseColor", base.toArgb())
                shader.setColorUniform("bodyColor", first.toArgb())
                shader.setColorUniform("glowColor", second.toArgb())
                shader.setColorUniform("accentColor", third.toArgb())
                drawRect(shaderBrush)
            } else {
                diagnostics?.backend = "canvas"
                drawRect(base)
                listOf(first, second, third).forEachIndexed { index, color ->
                    val safe = Color(readableThorBackdropColor(color.toArgb(), readingContrast))
                    val center = Offset(size.width * (.16f + .33f * index + sin(time + index) * .10f),
                        size.height * (if (index == 1) .22f else .72f))
                    drawCircle(Brush.radialGradient(listOf(safe.copy(alpha = (.85f * strength.value).coerceIn(0f, .95f)),
                        Color.Transparent), center, size.width * .70f), size.width * .70f, center)
                }
                drawRect(Brush.horizontalGradient(0f to Color.Transparent,
                    profile.quietStartX to Color.Transparent, profile.quietEndX to base.copy(alpha = 1f - profile.quietRetention),
                    1f to base.copy(alpha = 1f - profile.quietRetention)))
            }
        }
    }
}

internal const val ThorBackdropAgsl = """
uniform float2 resolution;
uniform float clock;
uniform float strength;
uniform float deformation;
uniform float highlights;
uniform float3 quietRange;
// dark flag, maximum night / minimum day relative luminance for the actual reading ink.
uniform float2 readingContrast;
uniform float4 primaryField;
uniform float4 secondaryField;
layout(color) uniform half4 baseColor;
layout(color) uniform half4 bodyColor;
layout(color) uniform half4 glowColor;
layout(color) uniform half4 accentColor;

float pool(float2 uv, float2 center, float2 radius) {
    float2 d = (uv - center) / radius;
    return exp(-2.8 * dot(d, d));
}

half4 main(float2 p) {
    float2 uv = p / max(resolution, float2(1.0));
    float t = clock;
    float2 warp = float2(sin(uv.y * 4.0 + t * 1.3), cos(uv.x * 3.6 - t * .9));
    float2 q = uv + warp * deformation * .38;
    float2 drift1 = float2(sin(t * .83), cos(t * .61) - 1.0) * deformation * .55;
    float2 drift2 = float2(cos(t * .67) - 1.0, sin(t * .79)) * deformation * .65;
    float2 drift3 = float2(sin(t * .57), cos(t * .43) - 1.0) * deformation * .45;
    float a = pool(q, primaryField.xy + drift1, primaryField.zw * .61);
    float b = pool(q, secondaryField.xy + drift2, secondaryField.zw * .58);
    float c = pool(q, float2(.82, .82) + drift3, float2(.64, .70));
    // Soft partitions keep separate cover hues instead of averaging them into one grey wash.
    float3 weights = pow(float3(a, b, c), float3(1.65)) * float3(1.15, 1.0, .95);
    float total = weights.x + weights.y + weights.z;
    half3 tint = (bodyColor.rgb * weights.x + glowColor.rgb * weights.y + accentColor.rgb * weights.z) / max(total, .001);
    float coverage = smoothstep(.015, .82, a + b + c);
    half3 color = mix(baseColor.rgb, tint, clamp(strength * .98 * coverage, 0.0, .96));

    // A wide, defocused caustic adds depth; no sharp ribbons, particles or flashing noise.
    float curve = .72 - uv.x * .57 + sin(uv.x * 3.5 + t * .62) * .10;
    float ribbon = exp(-pow((uv.y - curve) / .17, 2.0));
    float halo = pool(uv, float2(.30, .52), float2(.57, .62));
    color = mix(color, accentColor.rgb, ribbon * halo * highlights * min(strength, 1.0) * .19);
    float edge = smoothstep(.28, .90, length((uv - float2(.43, .50)) * float2(.86, 1.0)));
    color = mix(color, baseColor.rgb, edge * .32);
    float quiet = smoothstep(quietRange.x, quietRange.y, uv.x);
    color = mix(color, baseColor.rgb, quiet * (1.0 - quietRange.z));
    // Keep the visible cover hue rather than hiding it behind a nearly opaque theme-colored
    // right panel. Tone only the reading zone, in linear sRGB, against this theme's true ink.
    float3 linearColor = toLinearSrgb(float3(color));
    float luminance = dot(linearColor, float3(.2126, .7152, .0722));
    float limit = readingContrast.y;
    float3 safeColor;
    if (readingContrast.x > .5) {
        safeColor = linearColor * min(1.0, limit / max(luminance, .0001));
    } else {
        float lift = clamp((limit - luminance) / max(1.0 - luminance, .0001), 0.0, 1.0);
        safeColor = mix(linearColor, float3(1.0), lift);
    }
    color = half3(fromLinearSrgb(mix(linearColor, safeColor, smoothstep(.44, .52, uv.x))));
    float grain = fract(sin(dot(floor(p * .72), float2(12.9898, 78.233))) * 43758.5453) - .5;
    color += half3(grain * .0025 * highlights * (1.0 - quiet));
    return half4(clamp(color, 0.0, 1.0), 1.0);
}
"""

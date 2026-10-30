package com.ljyh.mei.thor.artwork

import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.ljyh.mei.thor.model.ThorArtworkSpec
import com.ljyh.mei.thor.theme.LocalThorTheme
import com.ljyh.mei.thor.theme.ThorThemeTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.sin

/** The caller controls visibility and airflow; this view never changes music selection or playback. */
@Composable
fun ThorCdCase(
    artwork: ThorArtworkSpec,
    airflow: Float,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    floating: Boolean = true,
    onAmbientColor: (Color) -> Unit = {},
) {
    val theme = LocalThorTheme.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleVisible by remember(lifecycleOwner) { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    val active = visible && lifecycleVisible
    val motion = remember { CaseMotion() }
    val wakeFrames = remember { Channel<Unit>(Channel.CONFLATED) }
    val pose = remember { mutableStateOf(motion.pose) }
    val hover = remember { mutableFloatStateOf(0f) }
    val entrance = remember { Animatable(0f) }
    val painter = remember { CdCasePainter() }
    val latestAmbient by rememberUpdatedState(onAmbientColor)
    val front = remember(artwork.frontUri, context) { mutableStateOf<Bitmap?>(null) }
    val back = remember(artwork.backUri, context) { mutableStateOf<Bitmap?>(null) }
    var touchSize by remember { mutableStateOf(IntSize.Zero) }
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    DisposableEffect(lifecycleOwner, motion) {
        val observer = LifecycleEventObserver { _, _ ->
            lifecycleVisible = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); motion.pause() }
    }
    DisposableEffect(wakeFrames) { onDispose { wakeFrames.cancel() } }
    LaunchedEffect(theme.reduceMotion) {
        motion.setReducedMotion(theme.reduceMotion)
        pose.value = motion.pose
    }
    LaunchedEffect(artwork.key, active, theme.reduceMotion) {
        entrance.snapTo(0f)
        if (active && !theme.reduceMotion) {
            entrance.snapTo(.06f)
            entrance.animateTo(0f, tween(320))
        }
    }
    LaunchedEffect(artwork.key, artwork.frontUri, artwork.backUri, context) {
        suspend fun load(uri: String?): Bitmap? {
            if (uri.isNullOrBlank()) return null
            return try {
                val result = context.imageLoader.execute(
                    ImageRequest.Builder(context).data(uri).size(768).allowHardware(false).build(),
                )
                (result as? SuccessResult)?.image?.toBitmap()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) { null }
        }
        front.value = load(artwork.frontUri)
        front.value?.let { bitmap -> latestAmbient(withContext(Dispatchers.Default) { sampleArtworkColor(bitmap) }) }
        back.value = if (artwork.backUri == artwork.frontUri) front.value else load(artwork.backUri) ?: front.value
    }
    LaunchedEffect(active, airflow, theme.reduceMotion) {
        if (active) motion.setAirflow(airflow) else motion.pause()
        pose.value = motion.pose
        wakeFrames.trySend(Unit)
    }
    LaunchedEffect(active, theme.reduceMotion, floating) {
        if (!active || theme.reduceMotion) {
            motion.pause(); pose.value = motion.pose; hover.floatValue = 0f
            return@LaunchedEffect
        }
        if (!floating) hover.floatValue = 0f
        var elapsed = 0.0
        while (isActive) {
            if (!floating && !motion.needsFrame) wakeFrames.receive()
            var previous = 0L
            while (isActive && (floating || motion.needsFrame)) {
                val frame = withFrameNanos { it }
                val dt = if (previous == 0L) 0.0 else ((frame - previous) / 1_000_000_000.0).coerceIn(0.0, .05)
                previous = frame
                elapsed += dt
                pose.value = motion.advance(dt)
                hover.floatValue = if (floating) sin(elapsed * Math.PI * 2.0 / 7.5).toFloat() else 0f
            }
        }
    }
    fun turn(direction: Int): Boolean {
        motion.turn(direction); pose.value = motion.pose; wakeFrames.trySend(Unit)
        return true
    }

    Canvas(
        modifier
            .fillMaxSize()
            .testTag("thor-cd-case")
            .onSizeChanged { touchSize = it }
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .semantics {
                role = Role.Button
                contentDescription = artwork.title.ifBlank { "专辑封面" }
                onClick("转动封面") { turn(1) }
                customActions = listOf(
                    CustomAccessibilityAction("向左转动") { turn(-1) },
                    CustomAccessibilityAction("向右转动") { turn(1) },
                )
            }
            .onKeyEvent { event ->
                val key = event.nativeKeyEvent
                val direction = when (key.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> -1
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_A -> 1
                    else -> 0
                }
                if (direction != 0 && key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) turn(direction)
                direction != 0
            }
            .focusable()
            .pointerInput(motion, touchSize, theme.reduceMotion, active) {
                if (!active) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (touchSize.width <= 0 || touchSize.height <= 0) return@awaitEachGesture
                    down.consume(); focusRequester.requestFocus()
                    motion.beginDrag(); pose.value = motion.pose; wakeFrames.trySend(Unit)
                    var previous = down.position
                    var lastTime = down.uptimeMillis
                    var lastMove = down.uptimeMillis
                    var dragged = false
                    var released = false
                    try {
                        while (!released) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                motion.endDrag((change.uptimeMillis - lastMove) / 1000.0)
                                if (!dragged) motion.turn(1)
                                released = true
                            } else {
                                val total = change.position - down.position
                                var delta = change.position - previous
                                if (!dragged && total.getDistance() > viewConfiguration.touchSlop) { dragged = true; delta = total }
                                if (dragged && delta != Offset.Zero) {
                                    motion.dragBy(delta.x / touchSize.width.toDouble(), delta.y / touchSize.height.toDouble(), (change.uptimeMillis - lastTime) / 1000.0)
                                    lastMove = change.uptimeMillis
                                }
                                previous = change.position; lastTime = change.uptimeMillis
                                change.consume()
                            }
                            pose.value = motion.pose
                        }
                    } finally {
                        if (!released) motion.endDrag(.1)
                        pose.value = motion.pose; wakeFrames.trySend(Unit)
                    }
                }
            },
    ) {
        val current = pose.value
        val modelScale = min(size.width * .36f, size.height * .36f) * (1f + .035f * current.lift.toFloat())
        val centerX = size.width * (.5f + entrance.value)
        val center = Offset(centerX, size.height * .47f - hover.floatValue * 3.dp.toPx() * (1 - current.lift.toFloat()) - current.lift.toFloat() * 4.dp.toPx())
        val shadowCenter = Offset(centerX, size.height * .84f)
        val shadowRadius = modelScale * (1.04f + current.lift.toFloat() * .12f)
        withTransform({ scale(1f, .13f, shadowCenter) }) {
            drawCircle(
                Brush.radialGradient(listOf(theme.caseShadow.copy(alpha = theme.caseShadow.alpha * (1f - .3f * current.lift.toFloat())), Color.Transparent), shadowCenter, shadowRadius),
                shadowRadius, shadowCenter,
            )
        }
        drawIntoCanvas { canvas -> painter.draw(canvas.nativeCanvas, CaseGeometry.project(current), front.value, back.value, artwork.title, artwork.artist, theme, center, modelScale) }
        if (focused) drawRoundRect(theme.focus, cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(2.dp.toPx()))
    }
}

private fun sampleArtworkColor(bitmap: Bitmap): Color {
    if (bitmap.isRecycled || bitmap.width == 0 || bitmap.height == 0) return Color.Gray
    var r = 0L; var g = 0L; var b = 0L; var count = 0
    val stepX = (bitmap.width / 12).coerceAtLeast(1); val stepY = (bitmap.height / 12).coerceAtLeast(1)
    for (y in 0 until bitmap.height step stepY) for (x in 0 until bitmap.width step stepX) {
        val color = bitmap.getPixel(x, y)
        r += android.graphics.Color.red(color); g += android.graphics.Color.green(color); b += android.graphics.Color.blue(color); count++
    }
    return Color(android.graphics.Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt()))
}

private class CdCasePainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL) }
    private val matrix = Matrix()
    private val path = Path()
    private val source = floatArrayOf(0f, 0f, 512f, 0f, 512f, 512f, 0f, 512f)
    private val destination = FloatArray(8)
    private val imageRect = RectF(18f, 18f, 494f, 494f)
    private val bitmapSource = Rect()
    private var labelSource: Pair<String, String>? = null
    private var titleLabel = ""
    private var artistLabel = ""
    private var spineLabel = ""

    fun draw(
        canvas: android.graphics.Canvas,
        faces: List<ProjectedCaseFace>,
        front: Bitmap?, back: Bitmap?, title: String, artist: String,
        theme: ThorThemeTokens, center: Offset, scale: Float,
    ) {
        if (labelSource?.first != title || labelSource?.second != artist) {
            labelSource = title to artist
            titleLabel = caseLabel(title, 11)
            artistLabel = caseLabel(artist, 18)
            spineLabel = caseLabel(listOf(artist, title).filter { it.isNotBlank() }.joinToString(" · "), 30)
        }
        for (face in faces) {
            face.corners.forEachIndexed { i, p -> destination[i * 2] = center.x + p.x.toFloat() * scale; destination[i * 2 + 1] = center.y + p.y.toFloat() * scale }
            if (!matrix.setPolyToPoly(source, 0, destination, 0, 4)) continue
            canvas.save()
            canvas.concat(matrix)
            paint.shader = null; paint.color = theme.caseBase.toArgb()
            canvas.drawRect(0f, 0f, 512f, 512f, paint)
            if (face.face == CaseFace.Front || face.face == CaseFace.Back) {
                val bitmap = if (face.face == CaseFace.Front) front else back ?: front
                if (bitmap != null && !bitmap.isRecycled) {
                    val side = min(bitmap.width, bitmap.height)
                    val x = (bitmap.width - side) / 2; val y = (bitmap.height - side) / 2
                    bitmapSource.set(x, y, x + side, y + side)
                    canvas.drawBitmap(bitmap, bitmapSource, imageRect, paint)
                }
                if (!theme.reduceTransparency) {
                    paint.shader = LinearGradient(0f, 0f, 512f, 350f, intArrayOf(theme.caseReflection.toArgb(), Color.Transparent.toArgb(), theme.caseReflection.copy(alpha = theme.caseReflection.alpha * .35f).toArgb()), floatArrayOf(0f, .42f, 1f), Shader.TileMode.CLAMP)
                    canvas.drawRect(0f, 0f, 512f, 512f, paint); paint.shader = null
                }
                if (bitmap == null || bitmap.isRecycled) {
                    // Placeholder labels retain opaque semantic ink; glass reflections stay below
                    // their inset field rather than bleaching the artist's smaller characters.
                    paint.color = theme.inset.toArgb(); canvas.drawRect(imageRect, paint)
                    text.color = theme.text.toArgb(); text.textSize = 40f
                    canvas.drawText(titleLabel, 42f, 370f, text)
                    text.color = theme.secondary.toArgb(); text.textSize = 25f
                    canvas.drawText(artistLabel, 42f, 414f, text)
                }
                paint.color = theme.caseHighlight.copy(alpha = theme.caseHighlight.alpha * .6f).toArgb()
                canvas.drawRect(13f, 5f, 37f, 507f, paint)
                line.strokeWidth = 3f; line.color = theme.caseEdge.toArgb()
                canvas.drawRect(2f, 2f, 510f, 510f, line)
            } else {
                paint.shader = LinearGradient(0f, 0f, 512f, 0f, theme.caseSpineDark.toArgb(), theme.caseSpineLight.toArgb(), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, 512f, 512f, paint); paint.shader = null
                if (face.face == CaseFace.Left || face.face == CaseFace.Right) {
                    canvas.save(); canvas.rotate(90f, 256f, 256f)
                    text.textSize = 32f; text.color = theme.caseText.toArgb()
                    canvas.drawText(spineLabel, 24f, 267f, text)
                    canvas.restore()
                }
                line.strokeWidth = 5f; line.color = theme.caseEdge.toArgb(); canvas.drawRect(0f, 0f, 512f, 512f, line)
            }
            canvas.restore()
            path.reset(); path.moveTo(destination[0], destination[1]); for (i in 1..3) path.lineTo(destination[i * 2], destination[i * 2 + 1]); path.close()
            line.strokeWidth = .8f; line.color = theme.caseHighlight.toArgb(); canvas.drawPath(path, line)
        }
    }
}

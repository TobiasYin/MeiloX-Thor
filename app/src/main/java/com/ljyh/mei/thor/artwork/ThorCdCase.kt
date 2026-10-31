package com.ljyh.mei.thor.artwork

import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.Log
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
import androidx.compose.runtime.staticCompositionLocalOf
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
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.thor.model.ThorArtworkSpec
import com.ljyh.mei.thor.theme.LocalThorTheme
import com.ljyh.mei.thor.theme.ThorThemeTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.exp
import java.util.concurrent.atomic.AtomicInteger

/** Extracted once per decoded cover; never used as text or focus ink. */
data class CaseAmbientPalette(val body: Color, val glow: Color, val accent: Color = glow)

/** Non-observable instrumentation data; the ordinary application supplies no collector. */
internal data class ThorCaseFrame(
    val pose: CasePose,
    /** Actual decorative vertical offset in dp, positive upward, excluding direct-touch lift. */
    val bobDp: Double,
    val phase: CaseMotionPhase,
    val airflowAllowed: Boolean,
    val playbackBreathing: Boolean,
    val active: Boolean,
    val reducedMotion: Boolean,
)

internal class ThorCaseDiagnostics {
    val frameTicks = AtomicInteger()
    @Volatile var latest: ThorCaseFrame? = null
        private set

    fun record(frame: ThorCaseFrame, tick: Boolean = false) {
        latest = frame
        if (tick) frameTicks.incrementAndGet()
    }
}

internal val LocalThorCaseDiagnostics = staticCompositionLocalOf<ThorCaseDiagnostics?> { null }

/** The caller controls visibility and airflow; this view never changes music selection or playback. */
@Composable
fun ThorCdCase(
    artwork: ThorArtworkSpec,
    airflow: Float,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    floating: Boolean = true,
    onAmbientColor: (Color) -> Unit = {},
    onAmbientPalette: (CaseAmbientPalette) -> Unit = {},
    airflowAllowed: Boolean = true,
    playbackBreathing: Boolean = false,
) {
    val theme = LocalThorTheme.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleVisible by remember(lifecycleOwner) { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    val active = visible && lifecycleVisible
    val latestActive by rememberUpdatedState(active)
    val latestPlaybackBreathing by rememberUpdatedState(playbackBreathing)
    val motion = remember { CaseMotion() }
    val breathing = remember { CaseBreathing() }
    val breathingPose = remember { mutableStateOf(breathing.pose) }
    val diagnostics = LocalThorCaseDiagnostics.current
    val wakeFrames = remember { Channel<Unit>(Channel.CONFLATED) }
    val pose = remember { mutableStateOf(motion.pose) }
    val hover = remember { mutableFloatStateOf(0f) }
    val hoverClock = remember { doubleArrayOf(0.0, 0.0) }
    val entrance = remember { Animatable(0f) }
    val painter = remember { CdCasePainter() }
    val latestAmbient by rememberUpdatedState(onAmbientColor)
    val latestPalette by rememberUpdatedState(onAmbientPalette)
    val paletteIdentity = Triple(artwork.key, artwork.frontUri, artwork.backUri)
    val latestPaletteIdentity by rememberUpdatedState(paletteIdentity)
    val latestPaletteContext by rememberUpdatedState(context)
    val latestAirflow by rememberUpdatedState(airflow)
    val diagnosticTimes = remember { longArrayOf(-500L, -500L) }
    val front = remember(artwork.frontUri, context) { mutableStateOf<Bitmap?>(null) }
    val back = remember(artwork.backUri, context) { mutableStateOf<Bitmap?>(null) }
    var touchSize by remember { mutableStateOf(IntSize.Zero) }
    var focused by remember { mutableStateOf(false) }
    var touchFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    fun recordMotion(tick: Boolean = false) {
        diagnostics?.record(ThorCaseFrame(
            pose = breathingPose.value.applyTo(pose.value),
            bobDp = (hover.floatValue * 3.0 + breathingPose.value.bobDp) * (1.0 - pose.value.lift),
            phase = motion.phase,
            airflowAllowed = motion.airflowAllowed,
            playbackBreathing = latestPlaybackBreathing,
            active = latestActive,
            reducedMotion = motion.reducedMotion,
        ), tick)
    }

    // Event 1=input, 2=actual frame, 3=inactive/reduced, 4=idle. No artwork or user data.
    fun logMotion(event: Int, dt: Double = 0.0, force: Boolean = false) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        val slot = if (event == 2) 1 else 0
        if (!force && now - diagnosticTimes[slot] < 500L) return
        diagnosticTimes[slot] = now
        Log.d("ThorCaseMotion", "event=$event strength=$latestAirflow active=${if (active) 1 else 0} " +
            "visible=${if (visible) 1 else 0} lifecycle=${lifecycleOwner.lifecycle.currentState.ordinal} " +
            "lifecycleVisible=${if (lifecycleVisible) 1 else 0} reduce=${if (theme.reduceMotion) 1 else 0} " +
            "motionReduce=${if (motion.reducedMotion) 1 else 0} phase=${motion.phase.ordinal} " +
            "yaw=${pose.value.yawDegrees} velocity=${motion.velocity} dt=$dt")
    }

    DisposableEffect(lifecycleOwner, motion) {
        val observer = LifecycleEventObserver { _, _ ->
            lifecycleVisible = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); motion.pause(); breathing.pause() }
    }
    DisposableEffect(wakeFrames) { onDispose { wakeFrames.cancel() } }
    LaunchedEffect(theme.reduceMotion) {
        motion.setReducedMotion(theme.reduceMotion)
        pose.value = motion.pose
        if (theme.reduceMotion) { breathing.pause(); breathingPose.value = breathing.pose }
        recordMotion()
    }
    LaunchedEffect(artwork.key, active, theme.reduceMotion) {
        entrance.snapTo(0f)
        if (active && !theme.reduceMotion) {
            entrance.snapTo(.06f)
            entrance.animateTo(0f, tween(theme.caseMs))
        }
    }
    LaunchedEffect(paletteIdentity, context) {
        val requestedIdentity = paletteIdentity
        suspend fun isCurrentRequest(): Boolean {
            currentCoroutineContext().ensureActive()
            return latestPaletteIdentity == requestedIdentity && latestPaletteContext === context
        }
        suspend fun publish(palette: CaseAmbientPalette) {
            if (!isCurrentRequest()) return
            latestAmbient(palette.body)
            latestPalette(palette)
        }
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
        // Keep the previous environment until a requested image is ready; failure uses neutral below.
        val loadedFront = load(artwork.frontUri)
        if (!isCurrentRequest()) return@LaunchedEffect
        front.value = loadedFront
        val palette = if (loadedFront != null) withContext(Dispatchers.Default) { sampleArtworkPalette(loadedFront) }
            else ThorArtworkPalette.Neutral.asCaseAmbientPalette()
        publish(palette)
        if (!isCurrentRequest()) return@LaunchedEffect
        val loadedBack = if (artwork.backUri == artwork.frontUri) loadedFront else load(artwork.backUri) ?: loadedFront
        if (isCurrentRequest()) back.value = loadedBack
    }
    LaunchedEffect(active, airflow, theme.reduceMotion, airflowAllowed) {
        motion.setAirflowAllowed(airflowAllowed)
        if (active) motion.setAirflow(airflow) else motion.pause()
        pose.value = motion.pose
        logMotion(1)
        recordMotion()
        wakeFrames.trySend(Unit)
    }
    LaunchedEffect(active, theme.reduceMotion, floating, playbackBreathing, airflowAllowed, diagnostics) {
        if (!active || theme.reduceMotion) {
            motion.pause(); pose.value = motion.pose; hover.floatValue = 0f
            hoverClock[1] = 0.0
            breathing.pause(); breathingPose.value = breathing.pose
            logMotion(3, force = true)
            recordMotion()
            return@LaunchedEffect
        }
        while (isActive) {
            if (!floating && !playbackBreathing && !motion.needsFrame && hoverClock[1] < .001 && !breathing.needsFrame) wakeFrames.receive()
            var previous = 0L
            while (isActive && (floating || playbackBreathing || motion.needsFrame || hoverClock[1] >= .001 || breathing.needsFrame)) {
                val frame = withFrameNanos { it }
                val dt = if (previous == 0L) 0.0 else ((frame - previous) / 1_000_000_000.0).coerceIn(0.0, .05)
                previous = frame
                pose.value = motion.advance(dt)
                logMotion(2, dt)
                val browseFloating = floating && !playbackBreathing
                hoverClock[1] += ((if (browseFloating) 1.0 else 0.0) - hoverClock[1]) * (1 - exp(-dt / .45))
                if (!browseFloating && hoverClock[1] < .001) hoverClock[1] = 0.0
                hoverClock[0] = (hoverClock[0] + dt * Math.PI * 2 / 7.5) % (Math.PI * 2)
                hover.floatValue = (sin(hoverClock[0]) * hoverClock[1]).toFloat()
                breathingPose.value = breathing.advance(dt, playbackBreathing,
                    suppressed = motion.phase == CaseMotionPhase.Dragging || motion.phase == CaseMotionPhase.Airflow)
                recordMotion(tick = true)
            }
            logMotion(4, force = true)
        }
    }
    fun turn(direction: Int): Boolean {
        motion.turn(direction); pose.value = motion.pose; wakeFrames.trySend(Unit)
        recordMotion()
        return true
    }

    Canvas(
        modifier
            .fillMaxSize()
            .testTag("thor-cd-case")
            .onSizeChanged { touchSize = it }
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused; if (!it.isFocused) touchFocused = false }
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
                touchFocused = false
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
                    down.consume(); touchFocused = true; focusRequester.requestFocus()
                    motion.beginDrag(); pose.value = motion.pose; wakeFrames.trySend(Unit); recordMotion()
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
                                // A held source resumes on release, including a short touch.
                                // Do not replace it with a turn that clears the source request.
                                if (!dragged && motion.phase != CaseMotionPhase.Airflow) motion.turn(1)
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
                            recordMotion()
                        }
                    } finally {
                        if (!released) motion.endDrag(.1)
                        pose.value = motion.pose; wakeFrames.trySend(Unit)
                        recordMotion()
                    }
                }
            },
    ) {
        val current = breathingPose.value.applyTo(pose.value)
        val baseScale = min(size.width * .315f, size.height * .42f)
        val modelScale = baseScale * (1f + .035f * current.lift.toFloat())
        if (modelScale <= 0f) return@Canvas
        val centerX = size.width * (.5f + entrance.value)
        val bobDp = (hover.floatValue * 3.0 + breathingPose.value.bobDp) * (1.0 - current.lift)
        val center = Offset(centerX, size.height * .5f - bobDp.toFloat() * 1.dp.toPx() - current.lift.toFloat() * 4.dp.toPx())
        val shadowCenter = Offset(centerX, size.height * .86f)
        val shadowRadius = (baseScale + 11.dp.toPx()) * (1f + current.lift.toFloat() * .12f)
        val shadowInk = theme.caseShadow.copy(alpha = theme.caseShadow.alpha * (1f - .3f * current.lift.toFloat()))
        val shadowHeight = size.height * .05f + 11.dp.toPx()
        withTransform({ scale(1f, shadowHeight / shadowRadius, shadowCenter) }) {
            drawCircle(
                Brush.radialGradient(0f to shadowInk, .7f to shadowInk, 1f to Color.Transparent, center = shadowCenter, radius = shadowRadius),
                shadowRadius, shadowCenter,
            )
        }
        drawIntoCanvas { canvas -> painter.draw(
            canvas.nativeCanvas,
            CaseGeometry.project(current, cameraDistance = 900.dp.toPx() / modelScale.toDouble(), depth = 14.dp.toPx() / baseScale.toDouble()),
            front.value, back.value, artwork.title, artwork.artist, theme, center, modelScale,
            logicalSide = baseScale * 2f / density,
        ) }
        if (focused && !touchFocused) drawRoundRect(theme.focus, cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(2.dp.toPx()))
    }
}

private fun sampleArtworkPalette(bitmap: Bitmap): CaseAmbientPalette =
    extractThorArtworkPalette(bitmap).asCaseAmbientPalette()

private class CdCasePainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL) }
    private val matrix = Matrix()
    private val path = Path()
    private val source = floatArrayOf(0f, 0f, 512f, 0f, 512f, 512f, 0f, 512f)
    private val destination = FloatArray(8)
    private val imageRect = RectF(21f, 21f, 491f, 491f)
    private val faceRect = RectF(0f, 0f, 512f, 512f)
    private val faceClip = Path()
    private val bitmapSource = Rect()
    private var material: ThorThemeTokens? = null
    private var reflection: Shader? = null
    private var spine: Shader? = null
    private var labelSource: Pair<String, String>? = null
    private var titleLabel = ""
    private var artistLabel = ""
    private var spineLabel = ""

    fun draw(
        canvas: android.graphics.Canvas,
        faces: List<ProjectedCaseFace>,
        front: Bitmap?, back: Bitmap?, title: String, artist: String,
        theme: ThorThemeTokens, center: Offset, scale: Float, logicalSide: Float,
    ) {
        if (material != theme) {
            material = theme
            // Final CSS reflection: a soft 115° pane and a narrow streak at 55–58%.
            reflection = LinearGradient(0f, 0f, 512f, 238.75f,
                intArrayOf(theme.caseReflection.toArgb(), Color.Transparent.toArgb(), Color.Transparent.toArgb(), theme.caseReflection.toArgb(), Color.Transparent.toArgb(), Color.Transparent.toArgb()),
                floatArrayOf(0f, .29f, .55f, .56f, .58f, 1f), Shader.TileMode.CLAMP)
            spine = LinearGradient(0f, 0f, 512f, 0f,
                intArrayOf(theme.caseBase.toArgb(), theme.caseSpineLight.toArgb(), theme.caseSpineDark.toArgb()),
                floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP)
        }
        val border = 3f * 512f / logicalSide
        val corner = 4f * 512f / logicalSide
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
            val broadFace = face.face == CaseFace.Front || face.face == CaseFace.Back
            if (broadFace) {
                faceClip.reset(); faceClip.addRoundRect(faceRect, corner, corner, Path.Direction.CW)
                canvas.clipPath(faceClip)
            }
            paint.shader = null; paint.color = theme.caseBase.toArgb()
            canvas.drawRect(0f, 0f, 512f, 512f, paint)
            if (broadFace) {
                val bitmap = if (face.face == CaseFace.Front) front else back ?: front
                if (bitmap != null && !bitmap.isRecycled) {
                    val side = min(bitmap.width, bitmap.height)
                    val x = (bitmap.width - side) / 2; val y = (bitmap.height - side) / 2
                    bitmapSource.set(x, y, x + side, y + side)
                    canvas.drawBitmap(bitmap, bitmapSource, imageRect, paint)
                }
                if (!theme.reduceTransparency) {
                    paint.shader = reflection
                    canvas.drawRect(0f, 0f, 512f, 512f, paint); paint.shader = null
                    paint.color = theme.caseHighlight.toArgb()
                    canvas.drawRect(0f, 0f, 8f * 512f / logicalSide, 512f, paint)
                    for (inset in 1..4) {
                        line.color = theme.caseReflection.copy(alpha = theme.caseReflection.alpha * (5 - inset) * .045f).toArgb()
                        line.strokeWidth = 5f
                        val edge = border + inset * 3f
                        canvas.drawRoundRect(edge, edge, 512f - edge, 512f - edge, corner, corner, line)
                    }
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
                line.strokeWidth = border; line.color = theme.caseEdge.toArgb()
                canvas.drawRoundRect(border / 2, border / 2, 512f - border / 2, 512f - border / 2, corner, corner, line)
                line.strokeWidth = 512f / logicalSide; line.color = theme.caseHighlight.toArgb()
                canvas.drawRoundRect(border + .5f, border + .5f, 511.5f - border, 511.5f - border, corner, corner, line)
            } else {
                paint.shader = spine
                canvas.drawRect(0f, 0f, 512f, 512f, paint); paint.shader = null
                if (face.face == CaseFace.Left || face.face == CaseFace.Right) {
                    // Compensate the spine's thin UV aspect before rotating its real 5dp label.
                    val spineWidth = 14f * 512f / logicalSide
                    canvas.save(); canvas.scale(512f / spineWidth, 1f)
                    canvas.translate(spineWidth / 2, 256f); canvas.rotate(90f)
                    text.textSize = 5f * 512f / logicalSide; text.color = theme.caseText.toArgb(); text.textAlign = Paint.Align.CENTER
                    canvas.drawText(spineLabel, 0f, text.textSize * .35f, text)
                    text.textAlign = Paint.Align.LEFT
                    canvas.restore()
                }
                line.strokeWidth = 5f; line.color = theme.caseEdge.toArgb(); canvas.drawRect(0f, 0f, 512f, 512f, line)
            }
            canvas.restore()
            if (!broadFace) {
                path.reset(); path.moveTo(destination[0], destination[1]); for (i in 1..3) path.lineTo(destination[i * 2], destination[i * 2 + 1]); path.close()
                line.strokeWidth = scale * 2f / logicalSide * .5f; line.color = theme.caseHighlight.toArgb(); canvas.drawPath(path, line)
            }
        }
    }
}

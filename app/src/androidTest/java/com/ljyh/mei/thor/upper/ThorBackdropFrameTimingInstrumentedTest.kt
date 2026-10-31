package com.ljyh.mei.thor.upper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.PixelCopy
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.thor.model.ThorAppearance
import com.ljyh.mei.thor.model.ThorBackgroundIntensity
import com.ljyh.mei.thor.model.ThorBackgroundMode
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.thor.model.ThorThemePack
import com.ljyh.mei.thor.test.ThorLiveEnvironment
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A requested-device synthetic native-window measurement, not a general playback FPS claim. No
 * ComposeTestRule: its virtual clock would replace the production Choreographer frame driver.
 */
@RunWith(AndroidJUnit4::class)
class ThorBackdropFrameTimingInstrumentedTest {
    @Test(timeout = 45_000)
    fun recordsRealFullUpperWindowFramesWithCoverExtractionAndScrollingLyrics() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ThorLiveEnvironment.requireRequestedDevice(context)
        val environment = ThorLiveEnvironment.reportName
        val physical = environment == "AYN_Thor_physical"
        val mode = ThorBackgroundMode.valueOf(InstrumentationRegistry.getArguments()
            .getString("thorBackdropMode", ThorBackgroundMode.ArtworkFlow.name))
        val screenshotName = "upper-frame-timing-${mode.name}-$environment-color-cover.png"
        val reportName = "frame-timing-${mode.name}-$environment.json"
        val directory = File(context.cacheDir, "thor-backdrop-evidence").apply { mkdirs() }
        val cover = File(directory, "temporary-frame-timing-cover-${SystemClock.uptimeMillis()}.png")
        val collecting = AtomicBoolean(false)
        val warmupFrames = AtomicLong()
        val droppedMetricsCallbacks = AtomicLong()
        val firstFrame = CountDownLatch(1)
        val samples = mutableListOf<Sample>()
        val metricsThread = HandlerThread("thor-backdrop-frame-metrics-qa")
        var scenario: ActivityScenario<ComponentActivity>? = null
        var activityRef: ComponentActivity? = null
        var listenerInstalled = false
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            firstFrame.countDown()
            if (collecting.get()) {
                val sample = Sample(
                    metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                    metrics.getMetric(FrameMetrics.DRAW_DURATION),
                    metrics.getMetric(FrameMetrics.GPU_DURATION),
                    metrics.getMetric(FrameMetrics.SYNC_DURATION),
                    metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION),
                )
                synchronized(samples) { samples += sample }
                droppedMetricsCallbacks.addAndGet(dropped.toLong())
            } else warmupFrames.incrementAndGet()
        }
        try {
            writeSyntheticCover(cover)
            metricsThread.start()
            val host = ActivityScenario.launch(ComponentActivity::class.java).also { scenario = it }
            val playbackStartedAt = SystemClock.uptimeMillis()
            val preferences = ThorPreferences(theme = ThorThemePack.Glass, appearance = ThorAppearance.Night,
                reduceMotion = false, backgroundMode = mode,
                backgroundIntensity = ThorBackgroundIntensity.High)
            val item = ThorItem(ThorItemKey.Album(9_001L, local = true), "Synthetic Aurora",
                "QA Studio · Local PNG", Uri.fromFile(cover).toString())
            val lyrics = LyricData(source = LyricSource.NetEaseCloudMusic,
                lyricLine = SyncedLyrics(lines = List(24) { index ->
                    SyncedLine(
                        if (index % 3 == 0) "Across the quiet water, a little color follows the breeze"
                        else "海风掠过，远方的光继续向前 ${index + 1}",
                        if (index % 3 == 0) "安静的海面上，颜色随风缓慢流动。"
                        else "The light moves gently across the water.",
                        index * 1_200, (index + 1) * 1_200 - 1,
                    )
                }))
            host.onActivity { activity ->
                activityRef = activity
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                WindowCompat.setDecorFitsSystemWindows(activity.window, false)
                WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    .hide(WindowInsetsCompat.Type.systemBars())
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(metricsThread.looper))
                listenerInstalled = true
                activity.setContent {
                    ThorUpperScreen(item = item, playing = true, preferences = preferences, airflow = 0f,
                        lyricData = lyrics,
                        positionProvider = { ((SystemClock.uptimeMillis() - playbackStartedAt) * 1.5).toLong() },
                        playbackScene = true, playbackEntryId = "synthetic-frame-timing",
                        modifier = Modifier.fillMaxSize())
                }
            }
            assertTrue("A native hardware-window frame must arrive", firstFrame.await(8, TimeUnit.SECONDS))
            // Real Choreographer frames continue during this wait; no virtual test clock is installed.
            SystemClock.sleep(WARMUP_MS)
            val warmupFrameCallbacks = warmupFrames.get()
            val sampleStartedAt = SystemClock.elapsedRealtime()
            collecting.set(true)
            SystemClock.sleep(SAMPLE_MS)
            collecting.set(false)
            val sampleEndedAt = SystemClock.elapsedRealtime()
            // Drain callbacks already queued on the metrics handler before taking the immutable copy.
            val drained = CountDownLatch(1)
            Handler(metricsThread.looper).post { drained.countDown() }
            assertTrue("Frame-metrics callbacks must drain", drained.await(3, TimeUnit.SECONDS))
            val measured = synchronized(samples) { samples.toList() }
            assertTrue("The measured interval must contain native frame metrics", measured.isNotEmpty())
            assertTrue("At least one frame must report a positive total duration", measured.any { it.totalNs > 0 })

            lateinit var nativeWindow: Window
            lateinit var displayInfo: JSONObject
            // onActivity waits for idle, which a live Choreographer scene need never reach.
            val activity = requireNotNull(activityRef)
            instrumentation.runOnMainSync {
                nativeWindow = activity.window
                val view = activity.window.decorView
                assertTrue("The entire upper scene must render in a hardware window", view.isHardwareAccelerated)
                assertTrue("The scene must remain visible", view.isShown)
                val display = requireNotNull(activity.display)
                displayInfo = JSONObject().put("displayId", display.displayId)
                    .put("windowWidthPx", view.width).put("windowHeightPx", view.height)
                    .put("reportedRefreshRateHz", display.refreshRate.toDouble())
            }
            val screenshot = copyFullWindow(nativeWindow)
            try {
                val screenshotFile = File(directory, screenshotName)
                screenshotFile.outputStream().use {
                    assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                if (mode == ThorBackgroundMode.ArtworkFlow) {
                    screenshotFile.copyTo(File(directory, "upper-frame-timing-$environment-color-cover.png"), overwrite = true)
                }
                assertCoverColorsArrived(screenshot)
            } finally { screenshot.recycle() }
            val report = JSONObject()
                .put("environment", environment)
                .put("isPhysicalThorPerformanceResult", physical)
                .put("recordedAtUtc", Instant.now().toString())
                .put("deviceModel", Build.MODEL).put("sdkInt", Build.VERSION.SDK_INT)
                .put("frameDriver", "Production AndroidUiDispatcher/Choreographer; ActivityScenario without ComposeTestRule")
                .put("scene", "ThorUpperScreen + local PNG/Coil palette extraction + CD case + ${mode.name} + real SyncedLyrics")
                .put("mode", mode.name)
                .put("theme", "Glass/Night/High").put("playing", true).put("reduceMotion", false)
                .put("syntheticLyricPlaybackSpeed", 1.5)
                .put("warmupMs", WARMUP_MS).put("warmupFrameCallbacks", warmupFrameCallbacks)
                .put("sampleDurationMs", sampleEndedAt - sampleStartedAt).put("count", measured.size)
                .put("droppedMetricsCallbacks", droppedMetricsCallbacks.get())
                .put("droppedMetricsMeaning", "Undelivered metrics callbacks; not a count of missed display frames")
                .put("totalDuration", summarize(measured.map { it.totalNs }))
                .put("drawDuration", summarize(measured.map { it.drawNs }))
                .put("gpuDuration", summarize(measured.map { it.gpuNs }))
                .put("syncDuration", summarize(measured.map { it.syncNs }))
                .put("commandIssueDuration", summarize(measured.map { it.commandNs }))
                .put("display", displayInfo)
                .put("screenshot", screenshotName)
                .put("scope", "${if (physical) "AYN Thor" else "Emulator"} synthetic native-window timings only; no general playback FPS threshold or extrapolation")
            val reportFile = File(directory, reportName)
            reportFile.writeText(report.toString(2))
            if (mode == ThorBackgroundMode.ArtworkFlow) {
                reportFile.copyTo(File(directory, "frame-timing-$environment.json"), overwrite = true)
            }
        } finally {
            collecting.set(false)
            try {
                activityRef?.let { activity ->
                    instrumentation.runOnMainSync {
                        if (listenerInstalled) activity.window.removeOnFrameMetricsAvailableListener(listener)
                        // Stop the live scene before ActivityScenario.close performs its idle wait.
                        activity.setContent { }
                    }
                }
            } finally {
                try { scenario?.close() }
                finally {
                    metricsThread.quitSafely()
                    try { if (metricsThread.isAlive) metricsThread.join(2_000) }
                    finally { cover.delete() }
                }
            }
        }
    }

    private data class Sample(val totalNs: Long, val drawNs: Long, val gpuNs: Long,
        val syncNs: Long, val commandNs: Long)

    private fun summarize(values: List<Long>): JSONObject {
        val sorted = values.filter { it >= 0 }.sorted()
        fun percentile(fraction: Double): Any = if (sorted.isEmpty()) JSONObject.NULL
            else sorted[(ceil(sorted.size * fraction).toInt() - 1).coerceIn(sorted.indices)] / 1_000_000.0
        return JSONObject().put("supportedSamples", sorted.size)
            .put("p50Ms", percentile(.50)).put("p95Ms", percentile(.95))
            .put("maxMs", if (sorted.isEmpty()) JSONObject.NULL else sorted.last() / 1_000_000.0)
    }

    private fun writeSyntheticCover(file: File) {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(0f, 0f, 512f, 400f,
                intArrayOf(Color.rgb(222, 54, 85), Color.rgb(36, 175, 184), Color.rgb(242, 187, 75)),
                floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, 512f, 512f, paint)
            paint.shader = null
            paint.color = Color.argb(42, 255, 255, 255)
            canvas.drawCircle(144f, 140f, 112f, paint)
            paint.color = Color.argb(34, 10, 20, 50)
            canvas.drawCircle(430f, 440f, 190f, paint)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun copyFullWindow(window: Window): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val committed = CountDownLatch(1)
        lateinit var bounds: Rect
        instrumentation.runOnMainSync {
            val view = window.decorView
            bounds = Rect(0, 0, view.width, view.height)
            view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            view.invalidate()
        }
        assertTrue("The full upper window must submit a GPU frame", committed.await(5, TimeUnit.SECONDS))
        val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(window, bounds, bitmap, { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue("The full upper window must be captured", copied.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        return bitmap
    }

    private fun assertCoverColorsArrived(image: Bitmap) {
        var red = 0; var teal = 0; var gold = 0
        val hsv = FloatArray(3)
        // Saturated red/teal/gold on the CD face distinguish a loaded cover from the neutral fallback.
        for (x in (image.width * .10).toInt() until (image.width * .48).toInt() step 4) {
            for (y in (image.height * .20).toInt() until (image.height * .80).toInt() step 4) {
                Color.colorToHSV(image.getPixel(x, y), hsv)
                if (hsv[1] > .38f && hsv[2] > .30f) when {
                    hsv[0] >= 330f || hsv[0] <= 12f -> red++
                    hsv[0] in 165f..195f -> teal++
                    hsv[0] in 30f..65f -> gold++
                }
            }
        }
        assertTrue("Real cover colors must reach the native scene (red=$red teal=$teal gold=$gold)",
            red > 8 && teal > 8 && gold > 8)
    }

    private companion object {
        const val WARMUP_MS = 1_800L
        const val SAMPLE_MS = 5_000L
    }
}

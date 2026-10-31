package com.ljyh.mei.playback

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.constants.AppAppearance
import com.ljyh.mei.constants.AppAppearanceKey
import com.ljyh.mei.constants.ReduceTransparencyKey
import com.ljyh.mei.thor.ThorShellViewModel
import com.ljyh.mei.thor.model.ThorAppearance
import com.ljyh.mei.thor.state.ThorOverlay
import com.ljyh.mei.thor.test.ThorLiveEnvironment
import com.ljyh.mei.thor.theme.decodeThorPreferences
import com.ljyh.mei.ui.model.LyricSource
import com.ljyh.mei.utils.dataStore
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real installed Activity/Service/online artwork; PixelCopy reads the actual upper window. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ThorVisualEffectsLiveInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val motion = booleanPreferencesKey("thor.reduceMotion")
    private val mode = stringPreferencesKey("thor.background.mode")
    private val intensity = stringPreferencesKey("thor.background.intensity")

    @Test(timeout = 240_000)
    fun actualOnlineArtworkAndLyricsRenderVisibleMovingFlowInDayAndNight() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("thor.visualEffects") == "true")
        ThorLiveEnvironment.requireRequestedDevice(context)
        val before = runBlocking { context.dataStore.data.first() }
        val actual = decodeThorPreferences(before)
        val report = JSONObject().put("environment", ThorLiveEnvironment.reportName)
            .put("status", "running").put("initialPreferences", JSONObject()
                .put("backgroundMode", actual.backgroundMode.name)
                .put("intensity", actual.backgroundIntensity.name)
                .put("reduceMotion", actual.reduceMotion)
                .put("reduceTransparency", actual.reduceTransparency)
                .put("appearance", actual.appearance.name))
        val phases = JSONArray()
        val directory = File(context.cacheDir, "thor-visual-effects-evidence").apply { mkdirs() }
        var stage = "setup"
        try {
            ThorLivePlaybackInstrumentedTest().withLiveFixture { fixture ->
                val owner = fixture.ownerActivity
                val model = main { ViewModelProvider(owner)[ThorShellViewModel::class.java] }
                try {
                    stage = "real_source"
                    val song = fixture.playableSongs().first()
                    assertTrue("real_artwork_uri_required", !song.coverUrl.isNullOrBlank())
                    fixture.verifyFullHttpSource(song)
                    fixture.seed(listOf(song), play = true)
                    fixture.awaitProgress(0)
                    main { model.showPlayer() }
                    await { model.navigation.value.overlay == ThorOverlay.Player &&
                        model.playback.state.value.current?.id == song.id }
                    // Observe the original visual settings before exercising temporary variants.
                    runBlocking { context.dataStore.edit { it[motion] = actual.reduceMotion } }
                    await { model.preferences.value.reduceMotion == actual.reduceMotion }
                    stage = "real_lyrics"
                    await(45_000) { model.lyrics.value?.let {
                        it.source !in setOf(LyricSource.Empty, LyricSource.Loading) &&
                            !it.isPureMusic && it.lyricLine.lines.size >= 5
                    } == true }
                    val lyrics = checkNotNull(model.lyrics.value)
                    report.put("lyricSource", lyrics.source.name)
                        .put("lyricLines", lyrics.lyricLine.lines.size)
                        .put("timedSyllables", lyrics.lyricLine.lines.filterIsInstance<KaraokeLine>().sumOf { it.syllables.size })
                        .put("actualPlayerProgressed", true)
                    SystemClock.sleep(1_000)
                    snapshot(owner, File(directory, "original-settings.png")).recycle()
                    for ((appearance, expected) in listOf(AppAppearance.Light to ThorAppearance.Day,
                        AppAppearance.Dark to ThorAppearance.Night)) {
                        stage = "flow_" + expected.name
                        runBlocking { context.dataStore.edit {
                            it[AppAppearanceKey] = appearance.name
                            it[mode] = "ArtworkFlow"
                            it[intensity] = "Medium"
                            it[motion] = false
                            it[ReduceTransparencyKey] = false
                        } }
                        await { model.preferences.value.let { it.appearance == expected &&
                            !it.reduceMotion && it.backgroundMode.name == "ArtworkFlow" &&
                            it.backgroundIntensity.name == "Medium" } }
                        SystemClock.sleep(1_100)
                        val first = snapshot(owner, File(directory, expected.name.lowercase() + "-first.png"))
                        SystemClock.sleep(2_800)
                        val second = snapshot(owner, File(directory, expected.name.lowercase() + "-second.png"))
                        try {
                            val sample = compareBackground(first, second)
                            assertTrue("real_flow_background_must_move", sample.first >= 6)
                            phases.put(JSONObject().put("appearance", expected.name)
                                .put("changedBackgroundSamples", sample.first)
                                .put("maximumChannelDelta", sample.second)
                                .put("width", first.width).put("height", first.height))
                        } finally { first.recycle(); second.recycle() }
                    }
                    report.put("phases", phases).put("status", "passed")
                } finally {
                    // Stop perpetual drawing before ActivityScenario's existing teardown waits idle.
                    runBlocking { context.dataStore.edit {
                        restore(it, AppAppearanceKey, before[AppAppearanceKey])
                        restore(it, mode, before[mode]); restore(it, intensity, before[intensity])
                        restore(it, ReduceTransparencyKey, before[ReduceTransparencyKey])
                        it[motion] = true
                    } }
                    await { model.preferences.value.reduceMotion }
                }
            }
            // The shared fixture restores the exact nullable original motion key and queue.
            val restored = runBlocking { context.dataStore.data.first() }
            assertTrue("visual_preferences_restored", listOf(AppAppearanceKey, mode, intensity,
                motion, ReduceTransparencyKey).all { key -> value(restored, key) == value(before, key) })
            report.put("preferencesRestored", true).put("queueRestored", true)
        } catch (error: Throwable) {
            report.put("status", "failed").put("failureStage", stage)
                .put("failureType", error.javaClass.simpleName)
            throw AssertionError("thor_visual_effects_failed:" + stage + ":" + error.javaClass.simpleName)
        } finally {
            File(context.cacheDir, "thor-visual-effects-live.json").writeText(report.toString(2))
        }
    }

    private fun compareBackground(a: Bitmap, b: Bitmap): Pair<Int, Int> {
        var changed = 0
        var maxDelta = 0
        // Outside the CD and lyric glyphs: full top/bottom gutters and the far-right margin.
        val points = buildList {
            for (x in listOf(.04f, .18f, .36f, .48f, .65f, .82f, .97f))
                for (y in listOf(.025f, .97f)) add(x to y)
            for (y in listOf(.18f, .32f, .50f, .68f, .82f)) add(.98f to y)
        }
        points.forEach { (x, y) ->
            val p = a.getPixel((a.width * x).toInt(), (a.height * y).toInt())
            val q = b.getPixel((b.width * x).toInt(), (b.height * y).toInt())
            val delta = maxOf(abs((p shr 16 and 255) - (q shr 16 and 255)),
                abs((p shr 8 and 255) - (q shr 8 and 255)), abs((p and 255) - (q and 255)))
            if (delta >= 2) changed++
            maxDelta = maxOf(maxDelta, delta)
        }
        return changed to maxDelta
    }

    private fun snapshot(owner: com.ljyh.mei.thor.ThorActivity, target: File): Bitmap {
        val dimensions = main { owner.window.decorView.width to owner.window.decorView.height }
        val bitmap = Bitmap.createBitmap(dimensions.first, dimensions.second, Bitmap.Config.ARGB_8888)
        val ready = CountDownLatch(1)
        var result = -1
        main { PixelCopy.request(owner.window, bitmap, { result = it; ready.countDown() },
            Handler(Looper.getMainLooper())) }
        assertTrue("actual_window_pixelcopy", ready.await(6, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS)
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun await(timeout: Long = 15_000, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            if (main(predicate)) return
            SystemClock.sleep(40)
        }
        throw AssertionError("actual_visual_state_timeout")
    }

    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable>()
        instrumentation.runOnMainSync { try { result.set(block()) } catch (e: Throwable) { error.set(e) } }
        error.get()?.let { throw it }
        return result.get()
    }
    private fun <T> restore(p: androidx.datastore.preferences.core.MutablePreferences, key: Preferences.Key<T>, v: T?) {
        if (v == null) p.remove(key) else p[key] = v
    }
    @Suppress("UNCHECKED_CAST")
    private fun value(p: Preferences, key: Preferences.Key<*>): Any? = p[key as Preferences.Key<Any>]
}


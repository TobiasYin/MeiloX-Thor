package com.ljyh.mei.thor

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.app.Presentation
import android.app.UiModeManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.MainActivity
import com.ljyh.mei.constants.*
import com.ljyh.mei.playback.EqualizerConfiguration
import com.ljyh.mei.playback.EqualizerConfigurationState
import com.ljyh.mei.playback.MusicService
import com.ljyh.mei.playback.playbackOrderIndices
import com.ljyh.mei.playback.queueEntryId
import com.ljyh.mei.playback.queue.PlaylistQueueSource
import com.ljyh.mei.thor.account.ThorAccountHomeViewModel
import com.ljyh.mei.thor.account.ThorAccountRoute
import com.ljyh.mei.thor.debug.ThorLiveCatalogEntryPoint
import com.ljyh.mei.thor.model.*
import com.ljyh.mei.thor.settings.decodeThorAudioSettings
import com.ljyh.mei.thor.state.ThorOverlay
import com.ljyh.mei.thor.state.ThorPage
import com.ljyh.mei.thor.state.ThorTab
import com.ljyh.mei.thor.theme.decodeThorPreferences
import com.ljyh.mei.thor.test.ThorLiveEnvironment
import com.ljyh.mei.ui.screen.account.AccountDeviceTab
import com.ljyh.mei.ui.screen.account.AccountDevicesViewModel
import com.ljyh.mei.ui.screen.account.AccountSecurityViewModel
import com.ljyh.mei.utils.dataStore
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Opt-in live acceptance of the installed ThorActivity, original lower Presentation, repository,
 * session and Media3 service. No fake VM, isolated Compose fixture or seeded private account.
 *
 * Run with -e thor.live true; physical AYN Thor additionally requires -e thor.physical true.
 * Missing live opt-in is the ONLY assumption;
 * an opted-in run with missing login, backend failure or wrong display is an actual failure.
 * Reports contain allowlisted booleans/counts/window IDs, a lyric-source enum and public song IDs.
 * No account strings, URLs,
 * credentials, backend exception messages, UI-tree dumps or private screenshots are persisted.
 */
@LargeTest
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ThorLiveUiAcceptanceInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext
    private lateinit var activity: ThorActivity
    private lateinit var model: ThorShellViewModel
    private lateinit var baseline: Windows
    private lateinit var lifecycle: LifecycleWitness
    private lateinit var report: JSONObject
    // Existing production wire keys are private in the VM; the test snapshots these exact
    // allowlisted storage keys without expanding the production/test API surface.
    private val ThemeKey = stringPreferencesKey("thor.theme")
    private val ReduceMotionKey = booleanPreferencesKey("thor.reduceMotion")
    private val AirflowKey = booleanPreferencesKey("thor.airflowEnabled")
    private val BackgroundModeKey = stringPreferencesKey("thor.background.mode")
    private val BackgroundIntensityKey = stringPreferencesKey("thor.background.intensity")
    private var stage = "initialization"
    private val originalKeepScreenOn = linkedMapOf<Window, Boolean>()
    private var queueSnapshot: LiveQueueSnapshot? = null
    private var queueSnapshotService: MusicService? = null
    private var accountGeneration = -1L
    private var accountId: String? = null // compare only; never serialized or included in failures

    @Test(timeout = 240_000)
    fun liveMyProfileBindingsDevicesAndAuthorizationsStayInsideTheSameTwoWindows() = liveCase("account") {
        openMy()
        click(hasText("网易云音乐 ·", substring = true) and hasClickAction())
        awaitPage(ThorPage.Account(ThorAccountRoute.Home))
        val home = onMain { ViewModelProvider(activity)[ThorAccountHomeViewModel::class.java] }
        stage = "account_profile"
        await(60_000) { !home.state.value.loading && home.state.value.generation == accountGeneration &&
            (home.state.value.profile != null || home.state.value.error != null) }
        live(home.state.value.error == null && home.state.value.profile?.id?.toString() == accountId,
            "account_profile_failed")
        count("profilePlaylists", home.state.value.playlists.size)
        mark("profileLoaded")
        assertStableWindows()

        clickText("账号安全", scroll = true)
        awaitPage(ThorPage.Account(ThorAccountRoute.Security))
        val security = onMain { ViewModelProvider(activity)[AccountSecurityViewModel::class.java] }
        awaitSecurity(security)
        count("bindingRecords", security.state.value.data?.bindings?.size ?: 0)
        count("securityEndpointErrors", security.state.value.data?.errors?.size ?: 0)
        live(security.state.value.data?.errors?.isEmpty() == true, "security_partial_backend_failure")
        mark("securityLoaded")

        val devices = onMain { ViewModelProvider(activity)[AccountDevicesViewModel::class.java] }
        for ((label, route, tab) in listOf(
            Triple("登录设备", ThorAccountRoute.Devices, AccountDeviceTab.Devices),
            Triple("应用授权", ThorAccountRoute.Authorizations, AccountDeviceTab.Authorizations),
            Triple("授权历史", ThorAccountRoute.History, AccountDeviceTab.History),
        )) {
            stage = "account_${tab.name.lowercase()}"
            clickText(label, scroll = true)
            awaitPage(ThorPage.Account(route))
            await(60_000) { devices.state.value.tab == tab && !devices.state.value.loading &&
                noProgressIndicator() }
            live(devices.state.value.error == null, "account_read_only_endpoint_failed")
            count(tab.name.lowercase(), if (tab == AccountDeviceTab.Devices) devices.state.value.devices.size
                else devices.state.value.applications.size)
            assertStableWindows()
            // Do not click a device/application record: those actions lead to removal/revoke.
            press(KeyEvent.KEYCODE_BUTTON_B)
            awaitPage(ThorPage.Account(ThorAccountRoute.Security))
        }
        stage = "account_bindings"
        clickText("第三方绑定", scroll = true)
        awaitPage(ThorPage.Account(ThorAccountRoute.Bindings))
        awaitSecurity(security)
        live(nodes(hasText("微信") and hasClickAction()).size == 1 &&
            nodes(hasText("QQ") and hasClickAction()).size == 1 &&
            nodes(hasText("微博") and hasClickAction()).size == 1, "binding_rows_missing")
        // No Web binding page, mutation, code request or authentication is opened.
        mark("bindingsReadOnly")
        press(KeyEvent.KEYCODE_BUTTON_B)
        awaitPage(ThorPage.Account(ThorAccountRoute.Security))
        press(KeyEvent.KEYCODE_BUTTON_B)
        awaitPage(ThorPage.Account(ThorAccountRoute.Home))
        press(KeyEvent.KEYCODE_BUTTON_B)
        awaitPage(ThorPage.Root(ThorTab.My))
        assertStableWindows()
    }

    @Test(timeout = 150_000)
    fun liveAudioButtonsReachTheExistingServiceDspAndEveryChangedPreferenceIsRestored() = liveCase("audio") { withPausedPlayback {
        openMy()
        clickText("播放设置", scroll = true)
        awaitPage(ThorPage.AudioSettings)
        await(10_000) { model.playerConnection.value != null }
        val connection = checkNotNull(model.playerConnection.value)
        val player = connection.player
        val playerIdentity = System.identityHashCode(player)
        val startingEntry = model.playback.state.value.currentEntryId
        val original = audioPreferences()
        live(original.processingAvailable, "audio_processing_bypassed_by_current_output")
        val originalLoop = original.loopPlayback
        stage = "audio_loop"
        clickText("队列循环播放", scroll = true)
        await(10_000) { audioPreferences().loopPlayback != originalLoop }
        clickText("队列循环播放", scroll = true)
        await(10_000) { audioPreferences().loopPlayback == originalLoop }
        mark("loopRoundTrip")

        clickText("十段均衡器", scroll = true)
        awaitPage(ThorPage.Equalizer)
        stage = "audio_equalizer"
        if (!audioPreferences().equalizer) clickText("应用内均衡器", scroll = true)
        await(10_000) { audioPreferences().equalizer && actualDsp().enabled }
        clickText("前级增益", scroll = true)
        // The real scalar editor keeps the draft until its explicit Apply button is pressed.
        val before = actualDsp().preamp
        val increase = before < 5.75f
        click(hasText(if (increase) "+ " else "− ", substring = true) and hasClickAction())
        live(actualDsp().preamp == before, "audio_draft_applied_before_confirmation")
        clickText("应用")
        await(10_000) { abs(actualDsp().preamp - (before + if (increase) .5f else -.5f)) < .001f }
        val applied = actualDsp()
        live(applied.gains.size == 10 && applied.preamp == audioPreferences().preamp,
            "audio_dsp_snapshot_mismatch")
        count("dspBands", applied.gains.size)
        mark("realServiceDspConfigurationApplied")
        live(onMain { connection.player === player && System.identityHashCode(connection.player) == playerIdentity },
            "audio_replaced_player")
        live(model.playback.state.value.currentEntryId == startingEntry, "audio_changed_queue_entry")
        press(KeyEvent.KEYCODE_BUTTON_B)
        awaitPage(ThorPage.AudioSettings)
        press(KeyEvent.KEYCODE_BUTTON_B)
        awaitPage(ThorPage.Root(ThorTab.My))
        assertStableWindows()
        // The shared finally restores exact nullable keys and then verifies the actual service
        // snapshot returns to the original configuration. This verifies DSP configuration, not
        // a claim about an emulator's acoustic output or a physical Thor listening assessment.
    } }

    @Test(timeout = 150_000)
    fun liveSystemDayNightAndArtworkFlowPreviewKeepBothWindowsFocusAndPlaybackIdentity() = liveCase("theme") { withPausedPlayback {
        openMy()
        clickText("主题与外观", scroll = true)
        awaitPage(ThorPage.Appearance)
        clickText("随系统", scroll = true)
        await(10_000) { model.preferences.value.appearance == ThorAppearance.System }
        val modes = checkNotNull(context.getSystemService(UiModeManager::class.java))
        val previousSystemMode = modes.nightMode
        val previousAutoAdvance = compose.mainClock.autoAdvance
        val player = onMain { model.playerConnection.value?.player }
        val entry = model.playback.state.value.currentEntryId
        val queue = model.playback.state.value.queue.map { it.entryId }
        val automation = instrumentation.uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.MODIFY_DAY_NIGHT_MODE")
        try {
            stage = "system_day"
            onMain { modes.nightMode = UiModeManager.MODE_NIGHT_NO }
            awaitNightMode(false)
            val dayUpper = capture(baseline.upper.window)
            val dayLower = capture(baseline.lower.window)
            stage = "system_night"
            onMain { modes.nightMode = UiModeManager.MODE_NIGHT_YES }
            awaitNightMode(true)
            val nightUpper = capture(baseline.upper.window)
            val nightLower = capture(baseline.lower.window)
            try {
                live(cornerLuminance(dayUpper) > cornerLuminance(nightUpper) + .20,
                    "upper_system_theme_not_drawn")
                live(cornerLuminance(dayLower) > cornerLuminance(nightLower) + .20,
                    "lower_system_theme_not_drawn")
            } finally { dayUpper.recycle(); dayLower.recycle(); nightUpper.recycle(); nightLower.recycle() }
            mark("systemAppearanceDrawnInBothWindows")
            assertStableWindows()

            // Cache real lower button coordinates while motion is disabled; after enabling Flow,
            // use native window touch and real frame submissions rather than waiting for idle.
            clickText("封面流光", scroll = true)
            clickText("高", scroll = true)
            val motion = buttonRect(hasText("减少动态 · 开启") and hasClickAction(), scroll = true)
            stage = "flow_live_preview"
            compose.mainClock.autoAdvance = false
            nativeTap(baseline.lower, motion)
            await(10_000) { !model.preferences.value.reduceMotion &&
                model.preferences.value.backgroundMode == ThorBackgroundMode.ArtworkFlow }
            val selectedFocus = model.rememberedFocus(ThorPage.Appearance.key)
            driveRealFrames(1_300)
            val before = capture(baseline.upper.window)
            driveRealFrames(850)
            val after = capture(baseline.upper.window)
            try { live(changedBackgroundSamples(before, after) >= 8, "flow_preview_no_real_frame_change") }
            finally { before.recycle(); after.recycle() }
            live(model.rememberedFocus(ThorPage.Appearance.key) == selectedFocus, "theme_reset_controller_focus")
            live(model.playback.state.value.currentEntryId == entry &&
                model.playback.state.value.queue.map { it.entryId } == queue, "theme_changed_playback_queue")
            live(onMain { model.playerConnection.value?.player === player }, "theme_replaced_player")
            assertStableWindows(composeIdle = false)
            mark("flowPreviewChangesRealPixels")
            mark("themePreservesPlaybackAndFocus")
        } finally {
            try {
                onMain { modes.nightMode = previousSystemMode }
                live(modes.nightMode == previousSystemMode, "system_night_mode_not_restored")
            } finally {
                automation.dropShellPermissionIdentity()
                try {
                    // Finish animations before any Compose selector or ActivityScenario cleanup.
                    onMain { model.updatePreferences { it.copy(reduceMotion = true) } }
                    await(10_000) { model.preferences.value.reduceMotion }
                    driveRealFrames(64)
                } finally { compose.mainClock.autoAdvance = previousAutoAdvance }
            }
        }
    } }

    @Test(timeout = 180_000)
    fun liveSearchButtonSubmitsTheVisibleQueryAndActualSongAPlayerBRestoresListFocusAndScroll() = liveCase("search-player") {
        // Preserve the user's complete real service queue in memory before the first search or
        // playback action. Never reconstruct it from song IDs or enqueue fresh entry identities.
        val originalService = checkNotNull(model.playerConnection.value).service
        val restoring = originalService.javaClass.getDeclaredField("isRestoringPlayback").apply { isAccessible = true }
        await(20_000) { onMain { !restoring.getBoolean(originalService) } }
        onMain {
            queueSnapshot = LiveQueueSnapshot.capture(originalService)
            queueSnapshotService = originalService
        }
        val publicCatalog = File(context.cacheDir, "thor-live-catalog.json")
        live(publicCatalog.isFile, "catalog_whitelist_required")
        val catalog = JSONObject(publicCatalog.readText())
        live(catalog.optString("status") == "passed" && catalog.optBoolean("accountVerified") &&
            catalog.optString("environment") == ThorLiveEnvironment.reportName && catalog.optInt("schemaVersion") == 1 &&
            System.currentTimeMillis() - catalog.optLong("checkedAtEpochMs") in 0L..7_200_000L,
            "catalog_whitelist_not_verified_or_stale")
        val candidate = catalog.getJSONArray("playableCandidates").optJSONObject(0)
            ?: fail("catalog_playable_candidate_required")
        val query = candidate.getString("title")
        val preferredId = candidate.getLong("songId")
        openMy()
        click(hasContentDescription("搜索") and hasClickAction())
        awaitPage(ThorPage.Search)
        stage = "search_button"
        compose.onNodeWithTag("thor-search-input").performTextReplacement(query)
        val sawLoading = AtomicBoolean(false)
        val observer = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CountDownLatch(1)
        observer.launch(start = CoroutineStart.UNDISPATCHED) {
            started.countDown()
            model.search.first { it.loading && it.query == query }.let { sawLoading.set(true) }
        }
        try {
            live(started.await(5, TimeUnit.SECONDS), "search_loading_observer_not_started")
            compose.onNodeWithTag("thor-search-submit").performTouchInput { click() }
            await(75_000) { model.search.value.searched && model.search.value.query == query && !model.search.value.loading }
            live(sawLoading.get(), "visible_query_never_entered_loading")
            live(model.search.value.error == null, "real_search_failed")
            val songs = model.search.value.items
            live(songs.size >= 3 && songs.any { it.key is ThorItemKey.Song }, "real_search_has_insufficient_rows")
            count("searchResults", songs.size)
            mark("visibleQueryLoadedFromRealRepository")
            // Select an actual allowed result with the router. Prefer the catalog's fresh detail
            // ID; if search order excludes it, use an actual non-rejected search result, not a fake.
            val target = songs.indexOfFirst { (it.key as? ThorItemKey.Song)?.id == preferredId && !it.isPlaybackUnavailable }
                .takeIf { it >= 0 } ?: songs.indexOfFirst { it.key is ThorItemKey.Song && !it.isPlaybackUnavailable }
            live(target >= 0, "search_contains_no_playable_song")
            stage = "search_results_composition"
            val scene = "search:results:${model.searchEntry.value}"
            report.getJSONObject("checks").put("initialListFocusBeforeResultFrame", model.rememberedFocus(scene) != null)
            // HTTP completion updates StateFlow before the test-owned composition receives its
            // next frame. Settle that real list before waiting on its binder's focus memory.
            compose.waitForIdle()
            stage = "search_initial_real_list_focus"
            awaitActualUi(10_000) {
                val index = selectedSongIndex(scene, songs)
                index >= 0 && actualResultFocused(songs[index])
            }
            count("initialSearchFocusIndex", selectedSongIndex(scene, songs))
            count("targetSearchFocusIndex", target)
            // Each real key must finish its withFrameNanos/BringIntoView transaction before
            // sending the next one. A wall-only polling loop cannot drive those Compose jobs.
            repeat(minOf(8, songs.lastIndex)) { step ->
                stage = "search_down_key_real_focus"
                count("searchDownKeyStep", step + 1)
                val expected = (selectedSongIndex(scene, songs) + 1).coerceAtMost(songs.lastIndex)
                press(KeyEvent.KEYCODE_DPAD_DOWN)
                awaitActualUi(5_000) { selectedSongIndex(scene, songs) == expected && actualResultFocused(songs[expected]) }
            }
            val current = selectedSongIndex(scene, songs)
            live(current >= 0, "result_focus_not_a_song")
            repeat(abs(target - current)) { step ->
                stage = "search_target_key_real_focus"
                count("searchTargetKeyStep", step + 1)
                val before = selectedSongIndex(scene, songs)
                val expected = before + if (current < target) 1 else -1
                press(if (current < target) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
                awaitActualUi(5_000) { selectedSongIndex(scene, songs) == expected && actualResultFocused(songs[expected]) }
            }
            stage = "search_target_native_focus_ready"
            awaitActualUi(10_000) { selectedSongIndex(scene, songs) == target && actualResultFocused(songs[target]) }
            val focus = model.rememberedFocus(scene)
            val song = songs[target]
            live(focus != null, "result_focus_not_saved")
            stage = "search_actual_viewport_before_activation"
            report.getJSONObject("checks").put("scrollMemoryPresentBeforeActivation", model.rememberedListScroll(scene) != null)
            val scrollNode = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode()
            val range = scrollNode.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                ?: fail("actual_result_list_scroll_semantics_missing")
            val positionBefore = onMain { range.value() }
            val maximum = onMain { range.maxValue() }
            val canOffset = maximum > positionBefore || positionBefore > 0f
            if (canOffset) {
                val pixels = if (maximum > positionBefore) 13f else -13f
                // Scroll the ACTUAL LazyColumn by 13 physical pixels. Do not seed VM memory:
                // Binder.activate must persist this real clipped viewport when A is pressed.
                compose.onNode(hasScrollToIndexAction()).performSemanticsAction(SemanticsActions.ScrollBy) { action ->
                    live(action(0f, pixels), "actual_result_list_scroll_action_rejected")
                }
                compose.waitForIdle()
                val after = actualSearchViewport(song)
                live(after.axis != positionBefore, "actual_result_list_did_not_scroll")
                count("requestedRealListScrollPixels", abs(pixels).toInt())
                mark("nonzeroActualViewportExercised")
            }
            val viewport = actualSearchViewport(song)
            count("baselineVisibleSearchRows", viewport.rows.size)
            mark("actualViewportCapturedBeforeActivation")
            stage = "search_A_opens_actual_player"
            press(KeyEvent.KEYCODE_BUTTON_A)
            await(75_000) { model.navigation.value.overlay == ThorOverlay.Player &&
                model.playback.state.value.current?.id == (song.key as ThorItemKey.Song).id }
            stage = "search_activation_saves_actual_viewport"
            // Null before A is valid: the production binder writes viewport memory on activate
            // and disposal, not on every direction key. Check it only after that real boundary.
            await(5_000) { model.rememberedListScroll(scene) != null }
            val activatedViewport = checkNotNull(model.rememberedListScroll(scene))
            mark("activationPersistedActualViewport")
            stage = "search_existing_media3_advances"
            await(75_000) { onMain { model.playerConnection.value?.player?.let {
                it.playbackState == Player.STATE_READY && it.playerError == null && it.currentPosition > 0
            } == true } }
            val position = onMain { model.playerConnection.value?.player?.currentPosition ?: 0 }
            await(10_000) { onMain { model.playerConnection.value?.player?.let {
                it.isPlaying && it.currentPosition >= position + 800
            } == true } }
            mark("actualSongPlayedThroughExistingMedia3")
            // Start uses the real global P shortcut and returns to the exact saved result scene.
            press(KeyEvent.KEYCODE_BUTTON_START)
            awaitPage(ThorPage.Search)
            stage = "search_start_return_actual_viewport"
            recordSearchReturn("startBeforeWait", scene, focus, activatedViewport, viewport, song)
            try {
                awaitActualUi(10_000) { recordSearchReturn("startDuringWait", scene, focus, activatedViewport, viewport, song) }
            } finally { recordSearchReturn("startAfterWait", scene, focus, activatedViewport, viewport, song) }
            assertSearchViewportRestored(viewport, song)
            live(model.rememberedListScroll(scene) == activatedViewport, "start_return_changed_persisted_viewport")
            mark("startRestoresActualFocusAndViewport")
            // The closed controller scope owns selectedKey even if Android native focus is
            // absent after preserving a clipped row. Prove it is actionable:
            // pause the real player, then use the REAL A route to activate this exact song.
            stage = "search_restored_cursor_A_activates_exact_song"
            press(KeyEvent.KEYCODE_BUTTON_Y)
            await(5_000) { onMain { model.playerConnection.value?.player?.playWhenReady == false } }
            press(KeyEvent.KEYCODE_BUTTON_A)
            await(10_000) { model.navigation.value.overlay == ThorOverlay.Player &&
                model.playback.state.value.current?.id == (song.key as ThorItemKey.Song).id }
            await(30_000) { onMain { model.playerConnection.value?.player?.let {
                it.isPlaying && it.playWhenReady && it.playerError == null
            } == true } }
            mark("restoredControllerCursorAPlaysExactSong")
            press(KeyEvent.KEYCODE_BUTTON_B)
            awaitPage(ThorPage.Search)
            stage = "search_back_return_actual_viewport"
            recordSearchReturn("backBeforeWait", scene, focus, activatedViewport, viewport, song)
            try {
                awaitActualUi(10_000) { recordSearchReturn("backDuringWait", scene, focus, activatedViewport, viewport, song) }
            } finally { recordSearchReturn("backAfterWait", scene, focus, activatedViewport, viewport, song) }
            assertSearchViewportRestored(viewport, song)
            live(model.rememberedListScroll(scene) == activatedViewport, "back_return_changed_persisted_viewport")
            mark("startAndBackRestoreExactFocusAndViewport")
            // Native focus is not the controller contract. After both exact viewport returns,
            // require actual Down/Up to move the visible cursor to the neighboring stable item
            // and back. Manual navigation may legitimately BringIntoView a clipped first row.
            val adjacent = if (target < songs.lastIndex) target + 1 else target - 1
            live(adjacent in songs.indices, "no_adjacent_real_result_for_navigation")
            stage = "search_restored_cursor_next_direction"
            press(if (adjacent > target) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
            awaitActualUi(5_000) { selectedSongIndex(scene, songs) == adjacent && actualResultSelected(songs[adjacent]) }
            mark("returnedCursorMovesToActualAdjacentResult")
            stage = "search_restored_cursor_reverse_direction"
            press(if (adjacent > target) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN)
            awaitActualUi(5_000) { selectedSongIndex(scene, songs) == target && actualResultSelected(song) }
            mark("returnedCursorMovesBackToExactResult")
            assertStableWindows()
        } finally {
            observer.cancel()
            // Stop test playback before the common finally restores the exact original queue,
            // occurrence identities, context, position and playback intent.
            onMain { model.playerConnection.value?.player?.pause() }
        }
    }

    @Test(timeout = 120_000)
    fun liveSystemVolumeAndRealLowerWindowFocusRoundTripNeverFocusTheUpperArtwork() = liveCase("window-focus") {
        openMy()
        clickText("主题与外观", scroll = true)
        awaitPage(ThorPage.Appearance)
        // This is the actual upper cover's accessibility action, not a focusable fake control.
        compose.onNodeWithTag("thor-cd-case").performTouchInput { click() }
        compose.waitForIdle()
        assertNoUpperFocus()
        // Android can maintain focus independently per display. Touching the upper cover
        // does not guarantee that the lower Presentation is still the OS focus owner.
        // Attach to an actually focused ORIGINAL base window and require its real loss/return.
        stage = "native_original_focus_owner"
        await(10_000) { onMain {
            val upper = baseline.upper.root.hasWindowFocus()
            val lower = baseline.lower.root.hasWindowFocus()
            report.getJSONObject("checks").put("originalUpperWindowFocused", upper).put("originalLowerWindowFocused", lower)
            upper || lower
        } }
        val focusOwner = onMain { if (baseline.lower.root.hasWindowFocus()) baseline.lower else baseline.upper }
        count("focusOwnerDisplay", focusOwner.displayId)
        lateinit var dialog: Dialog
        onMain {
            dialog = Dialog(focusOwner.root.context).apply {
                val field = TextView(context).apply {
                    text = "Window focus acceptance"
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setPadding(24, 24, 24, 24)
                }
                setContentView(field)
                window?.apply {
                    setType(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
                    attributes = attributes.apply { token = focusOwner.token }
                    clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                    setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                    setWindowAnimations(0)
                }
                show()
                field.requestFocus()
            }
        }
        try {
            stage = "native_dialog_gains_focus"
            await(10_000) {
                onMain {
                    val window = checkNotNull(dialog.window)
                    count("focusDialogDisplay", window.decorView.display.displayId)
                    count("focusDialogFlags", window.attributes.flags)
                    report.getJSONObject("checks").put("dialogActuallyFocused", window.decorView.hasWindowFocus())
                        .put("attachedOwnerActuallyLostFocus", !focusOwner.root.hasWindowFocus())
                    live(window.decorView.display.displayId == focusOwner.displayId, "focus_dialog_wrong_display")
                    dialog.isShowing && window.decorView.hasWindowFocus() && !focusOwner.root.hasWindowFocus()
                }
            }
            assertNoUpperFocus()
        } finally { onMain { dialog.dismiss() } }
        stage = "native_original_focus_restored"
        await(10_000) { onMain { focusOwner.root.hasWindowFocus() } }
        assertNoUpperFocus()
        assertStableWindows()
        mark("realOriginalWindowFocusRestored")

        val audio = checkNotNull(context.getSystemService(AudioManager::class.java))
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val info = instrumentation.uiAutomation.serviceInfo
        val oldFlags = info.flags
        try {
            stage = "system_volume_panel"
            info.flags = oldFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            instrumentation.uiAutomation.serviceInfo = info
            val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val changed = if (volume < maximum) volume + 1 else (volume - 1).coerceAtLeast(0)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, changed, AudioManager.FLAG_SHOW_UI)
            await(6_000) { systemVolumePanelVisible() }
            mark("realSystemVolumePanelObserved")
            assertNoUpperFocus()
            live(onMain { descendants(baseline.upper.root).filter {
                it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView"
            }.let { hosts -> hosts.isNotEmpty() && hosts.all { !it.defaultFocusHighlightEnabled } } }, "upper_default_focus_highlight_enabled")
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            await(6_000) { !systemVolumePanelVisible() }
            stage = "native_volume_focus_restored"
            await(10_000) { onMain { focusOwner.root.hasWindowFocus() } }
            assertNoUpperFocus()
            assertStableWindows()
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
            live(audio.getStreamVolume(AudioManager.STREAM_MUSIC) == volume, "system_volume_not_restored")
            info.flags = oldFlags
            instrumentation.uiAutomation.serviceInfo = info
        }
        mark("systemVolumeRestoredAndUpperRemainsDisplayOnly")
    }

    @Test(timeout = 420_000)
    fun liveOnlineLyricsDragIdleReturnAndLineTouchSeekTheExistingPlayer() = liveCase("lyrics") {
        val service = checkNotNull(model.playerConnection.value).service
        val restoring = service.javaClass.getDeclaredField("isRestoringPlayback").apply { isAccessible = true }
        await(20_000) { onMain { !restoring.getBoolean(service) } }
        onMain {
            queueSnapshot = LiveQueueSnapshot.capture(service)
            queueSnapshotService = service
        }
        stage = "lyrics_catalog_precondition"
        val file = File(context.cacheDir, "thor-live-catalog.json")
        live(file.isFile, "catalog_whitelist_required")
        val catalog = JSONObject(file.readText())
        live(catalog.optString("status") == "passed" && catalog.optBoolean("accountVerified") &&
            catalog.optString("environment") == ThorLiveEnvironment.reportName && catalog.optInt("schemaVersion") == 1 &&
            System.currentTimeMillis() - catalog.optLong("checkedAtEpochMs") in 0L..7_200_000L,
            "catalog_whitelist_not_verified_or_stale")
        val candidates = catalog.getJSONArray("playableCandidates")
        val ids = (0 until candidates.length()).map { candidates.getJSONObject(it).getLong("songId") }.distinct().take(3)
        live(ids.isNotEmpty(), "catalog_playable_candidate_required")
        report.put("candidatePublicSongIds", org.json.JSONArray(ids))
        val repositories = EntryPointAccessors.fromApplication(context, ThorLiveCatalogEntryPoint::class.java)
        var acceptedId: Long? = null
        var attempts = 0
        var freshLrcLines = 0
        for (id in ids) {
            attempts++
            count("lyricCandidatesAttempted", attempts)
            stage = "lyrics_live_netease_read"
            // This is a fresh request through the production repository; no cached/mock body
            // is injected into the manager. Actual rendering still uses its real saved source
            // preference (NetEase, AM or another configured online source).
            val response = try { runBlocking { withTimeout(25_000) { repositories.playerRepository().getLyricV1(id.toString()) } } }
                catch (_: Exception) { null }
            val net = (response as? com.ljyh.mei.data.network.Resource.Success)?.data
            if (net?.code != 200 || net.pureMusic == true || net.lrc?.lyric.isNullOrBlank()) continue
            val parsed = com.ljyh.mei.utils.lyric.LRCParser.parse(checkNotNull(net.lrc?.lyric), net.tlyric?.lyric)
            if (parsed.lines.size < 5) continue
            freshLrcLines = parsed.lines.size
            stage = "lyrics_real_song_load"
            val loaded = try { runBlocking { withTimeout(30_000) {
                repositories.thorMusicRepository().load(ThorItem(ThorItemKey.Song(id), ""))
            } } } catch (_: Exception) { null }
            if (loaded == null || loaded.isPlaybackUnavailable || loaded.tracks.isEmpty()) continue
            onMain { model.open(loaded) }
            if (!awaitOrFalse(35_000) { onMain {
                model.navigation.value.overlay == ThorOverlay.Player && model.playback.state.value.current?.id == id &&
                    service.player.playbackState == Player.STATE_READY && service.player.playerError == null
            } }) continue
            stage = "lyrics_actual_online_source"
            // Source merging is progressive. Wait for a real, non-placeholder UI snapshot to
            // remain stable before starting the browsing timer; never change a per-song source.
            var stableSince = 0L
            var previous: com.ljyh.mei.ui.model.LyricData? = null
            if (!awaitOrFalse(40_000) {
                val current = model.lyrics.value
                val real = current.source !in setOf(com.ljyh.mei.ui.model.LyricSource.Empty,
                    com.ljyh.mei.ui.model.LyricSource.Loading) && !current.isPureMusic &&
                    current.lyricLine.lines.size >= 5 && current.lyricLine.lines.count { lyricWords(it).isNotBlank() } >= 5
                if (!real || previous?.lyricLine != current.lyricLine || previous?.source != current.source) {
                    previous = current
                    stableSince = if (real) SystemClock.uptimeMillis() else 0L
                    false
                } else stableSince > 0 && SystemClock.uptimeMillis() - stableSince >= 5_000
            }) continue
            compose.waitForIdle()
            if (nodes(hasTestTag(LYRIC_VIEWPORT)).size != 1) continue
            acceptedId = id
            break
        }
        live(acceptedId != null, "bounded_candidates_have_no_usable_live_online_lyrics")
        count("freshNeteaseLrcLines", freshLrcLines)
        mark("freshNeteaseLrcEndpointVerified")
        val data = model.lyrics.value
        report.put("lyricSource", data.source.name)
        count("actualUiLyricLines", data.lyricLine.lines.size)
        report.put("selectedPublicSongId", acceptedId)
        val duration = onMain { service.player.duration }
        val usable = data.lyricLine.lines.withIndex().filter { (_, line) ->
            lyricWords(line).isNotBlank() && line.start >= 0 && line.start < duration - 23_000
        }
        live(usable.isNotEmpty(), "online_lyrics_have_no_room_for_real_idle_timer")
        val setupLine = usable[minOf(2, usable.lastIndex)].value
        onMain {
            // Actual Media3 setup seek places a real timed verse in view. All gesture
            // assertions below start after this seek, never using a fake position provider.
            service.player.seekTo((setupLine.start.toLong() + 100).coerceAtLeast(0))
            service.player.pause()
        }
        await(10_000) { model.playback.state.value.current?.id == acceptedId &&
            model.playback.state.value.positionMs >= setupLine.start }
        compose.waitForIdle()
        live(lyricViewportState() == "跟随播放", "online_lyrics_not_following_initially")
        val playerEntry = model.playback.state.value.currentEntryId
        val observedSeeks = AtomicInteger()
        val seekPosition = java.util.concurrent.atomic.AtomicLong(-1)
        val witness = object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    seekPosition.set(newPosition.positionMs)
                    observedSeeks.incrementAndGet()
                }
            }
        }
        onMain { service.player.addListener(witness) }
        try {
            stage = "lyrics_upper_real_drag_no_seek"
            val pausedAt = onMain { service.player.currentPosition }
            val beforeRows = actualLyricRows().map { it.config.getOrNull(SemanticsProperties.Text)?.map { text -> text.text } }
            val gestureStarted = SystemClock.uptimeMillis()
            dragActualLyrics()
            await(5_000) { lyricViewportState() == "浏览歌词" }
            val afterRows = actualLyricRows().map { it.config.getOrNull(SemanticsProperties.Text)?.map { text -> text.text } }
            live(beforeRows != afterRows, "real_lyric_drag_did_not_move_viewport")
            live(observedSeeks.get() == 0 && onMain { abs(service.player.currentPosition - pausedAt) <= 120 },
                "upper_lyric_drag_sought_the_player")
            mark("actualUpperDragBrowsesWithoutSeeking")

            stage = "lyrics_resume_real_player_before_idle"
            press(KeyEvent.KEYCODE_BUTTON_Y)
            await(10_000) { onMain { service.player.isPlaying } }
            stage = "lyrics_wait_real_idle_timer_and_composition"
            awaitActualUi(21_000) { lyricViewportState() == "跟随播放" }
            val elapsed = SystemClock.uptimeMillis() - gestureStarted
            live(elapsed >= 14_900, "online_lyric_browser_returned_before_fifteen_seconds")
            count("observedIdleReturnMilliseconds", elapsed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            live(observedSeeks.get() == 0, "automatic_lyric_return_sought_player")
            live(onMain { service.player.currentPosition >= pausedAt + 10_000 }, "real_playback_did_not_advance_during_browse")
            live(actualLyricRows().any { it.config.getOrNull(SemanticsProperties.Selected) == true },
                "current_real_lyric_not_visible_after_idle_return")
            mark("realFifteenSecondReturnFollowsAdvancingPlayback")

            stage = "lyrics_actual_line_touch_seek_and_play"
            press(KeyEvent.KEYCODE_BUTTON_Y)
            await(5_000) { onMain { !service.player.playWhenReady } }
            val pausedForSelection = onMain { service.player.currentPosition }
            dragActualLyrics()
            await(5_000) { lyricViewportState() == "浏览歌词" }
            live(observedSeeks.get() == 0, "second_lyric_drag_sought_player")
            val currentData = model.lyrics.value
            val viewportBounds = compose.onNodeWithTag(LYRIC_VIEWPORT).fetchSemanticsNode().boundsInRoot
            val words = currentData.lyricLine.lines.map(::lyricWords)
            val target = actualLyricRows().mapNotNull { row ->
                val text = row.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
                val index = currentData.lyricLine.lines.indices.firstOrNull { i ->
                    words[i].isNotBlank() && words.count { it == words[i] } == 1 && words[i] in text &&
                        currentData.lyricLine.lines[i].start >= 0 && currentData.lyricLine.lines[i].start < duration - 1_000 &&
                        abs(currentData.lyricLine.lines[i].start.toLong() - pausedForSelection) >= 1_500
                } ?: return@mapNotNull null
                val bounds = row.boundsInRoot
                if (bounds.top <= viewportBounds.top + viewportBounds.height * .10f ||
                    bounds.bottom >= viewportBounds.bottom - viewportBounds.height * .12f) null
                else Triple(row.id, currentData.lyricLine.lines[index].start.toLong(), index)
            }.firstOrNull() ?: fail("no_unambiguous_real_browsed_line_to_touch")
            // A real upper pointer tap must traverse Column.clickable -> Activity callback ->
            // VM entry fence -> Bridge.seekAndPlay -> this existing Media3 Player.
            compose.onNode(SemanticsMatcher("actual browsed online lyric row") { it.id == target.first })
                .performTouchInput { click() }
            await(10_000) { observedSeeks.get() >= 1 && onMain { service.player.playWhenReady && service.player.isPlaying } }
            live(observedSeeks.get() == 1 && abs(seekPosition.get() - target.second) <= 250,
                "real_lyric_touch_did_not_seek_to_its_actual_timestamp")
            live(onMain { service.player.currentPosition in (target.second - 250)..(target.second + 4_000) },
                "actual_player_position_does_not_match_clicked_lyric")
            live(lyricViewportState() == "跟随播放", "line_touch_did_not_resume_lyric_follow")
            live(model.playback.state.value.currentEntryId == playerEntry, "lyric_touch_changed_queue_entry")
            count("realLineTouchSeekEvents", observedSeeks.get())
            mark("realUpperLineTouchSeeksAndPlaysExistingMedia3")
            assertNoUpperFocus()
            assertStableWindows()
        } finally {
            onMain { service.player.removeListener(witness); service.player.pause() }
        }
    }

    private fun lyricWords(line: com.mocharealm.accompanist.lyrics.core.model.ISyncedLine): String = when (line) {
        is com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine -> line.content
        is com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine -> line.syllables.joinToString("") { it.content }
        else -> ""
    }
    private fun lyricViewportState() = compose.onNodeWithTag(LYRIC_VIEWPORT).fetchSemanticsNode()
        .config.getOrNull(SemanticsProperties.StateDescription)
    private fun actualLyricRows() = nodes(hasClickAction() and hasAnyAncestor(hasTestTag(LYRIC_VIEWPORT)))
    private fun dragActualLyrics() {
        compose.onNodeWithTag(LYRIC_VIEWPORT).performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(width * .5f, height * .82f),
                androidx.compose.ui.geometry.Offset(width * .5f, height * .24f), 700)
        }
        compose.waitForIdle()
    }
    private fun awaitOrFalse(timeout: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) {
            if (SystemClock.elapsedRealtime() >= deadline) return false
            SystemClock.sleep(25)
        }
        return true
    }
    private companion object { const val LYRIC_VIEWPORT = "thor-upper-lyrics-viewport" }

    private fun liveCase(name: String, body: () -> Unit) {
        // Ordinary broad CI may skip an explicitly live class, but never an opted-in check.
        assumeTrue("Explicit thor.live=true required before live account access",
            InstrumentationRegistry.getArguments().getString("thor.live") == "true")
        report = JSONObject().put("schemaVersion", 1).put("environment", ThorLiveEnvironment.reportName)
            .put("case", name).put("status", "running").put("checks", JSONObject())
            .put("counts", JSONObject()).put("checkedAtEpochMs", System.currentTimeMillis())
        writeReport(name)
        var scenario: ActivityScenario<ThorActivity>? = null
        var restore: PreferenceBackup? = null
        val app = context as Application
        lifecycle = LifecycleWitness()
        app.registerActivityLifecycleCallbacks(lifecycle)
        var primary: Throwable? = null
        try {
            ThorLiveEnvironment.requireRequestedDevice(context)
            stage = "preference_backup"
            restore = backupAllowedPreferences()
            // Suppress pre-existing perpetual UI frames before ActivityScenario first waits for
            // idle. Exact original presence/values are restored in finally.
            runBlocking { context.dataStore.edit { prefs ->
                prefs[ReduceMotionKey] = true
                prefs[AirflowKey] = false
            } }
            scenario = ActivityScenario.launch(Intent(context, ThorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            // This is the only onActivity: after starting animated content, onActivity's idle
            // wait is unsafe. All subsequent reads/cleanup use runOnMainSync directly.
            scenario.onActivity { owner ->
                activity = owner
                keepScreenOn(owner.window)
                model = ViewModelProvider(owner)[ThorShellViewModel::class.java]
            }
            await(15_000) { windowsOrNull() != null }
            onMain { model.updatePreferences { it.copy(reduceMotion = true, airflowEnabled = false) } }
            await(10_000) { model.preferences.value.reduceMotion && !model.preferences.value.airflowEnabled }
            compose.waitForIdle()
            baseline = checkNotNull(windowsOrNull())
            onMain { keepScreenOn(baseline.lower.window) }
            // Physical panels expose natural portrait modes, while these actual application
            // windows must already be in the approved landscape pixel sizes. IDs stay dynamic.
            live(onMain { baseline.upper.root.width == 1920 && baseline.upper.root.height == 1080 &&
                baseline.lower.root.width == 1240 && baseline.lower.root.height == 1080 &&
                baseline.upper.displayId != baseline.lower.displayId }, "actual_thor_windows_not_landscape")
            lifecycle.baseline()
            report.put("windowIds", baseline.safeJson())
            stage = "live_profile_preflight"
            await(30_000) { model.library.value.account.signedIn }
            accountGeneration = model.library.value.account.generation
            accountId = model.library.value.account.userId
            val entry = EntryPointAccessors.fromApplication(context, ThorLiveCatalogEntryPoint::class.java)
            val profile = runBlocking { withTimeout(45_000) { entry.meloXRepository().accountProfile() } }
            live(profile.id > 0 && profile.id.toString() == accountId, "login_expired_or_profile_unavailable")
            live(model.library.value.account.generation == accountGeneration, "account_changed_during_preflight")
            val handled = model.javaClass.getDeclaredField("handledAccountGeneration").apply { isAccessible = true }
            await(10_000) { onMain { handled.getLong(model) == accountGeneration } }
            mark("liveAccountVerified")
            await(15_000) { model.playback.state.value.connected }
            body()
            checkAccountFence()
            report.put("status", "passed")
        } catch (failure: Throwable) {
            primary = failure
            report.put("status", "failed").put("failureCode", (failure as? LiveFailure)?.code ?: "live_ui_step_failed")
                .put("failureStage", stage).put("failureType", failure.javaClass.simpleName)
        } finally {
            // Independent restoration steps keep a queue-verification failure from preventing
            // preference/volume-related cleanup. Every failed step still fails the live run.
            fun cleanupStep(code: String, action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    if (primary == null) primary = failure
                    report.put("status", "failed").put("cleanupFailed", true).put("cleanupStage", code)
                }
            }
            cleanupStep("dispose_live_compositions") {
                // Tear down both compositions before restoring possibly-playing audio or the
                // original unlimited animations; ActivityScenario.close otherwise waits idle.
                if (::activity.isInitialized && !activity.isDestroyed) onMain {
                    if (::baseline.isInitialized) baseline.lower.composeHosts
                        .filterIsInstance<androidx.compose.ui.platform.ComposeView>()
                        .forEach { it.disposeComposition() }
                    activity.setContentView(android.widget.FrameLayout(activity))
                }
            }
            cleanupStep("restore_complete_service_queue") {
                queueSnapshot?.let { saved ->
                    val service = checkNotNull(queueSnapshotService)
                    onMain {
                        service.cancelUnavailableRecovery()
                        service.player.pause()
                        service.player.stop()
                        service.queueManager.cancelServerShuffle()
                        saved.restore(service)
                        saved.verify(service)
                        service.schedulePlaybackSnapshot()
                    }
                    mark("originalCompleteServiceQueueRestored")
                    count("restoredQueueEntries", saved.items.size)
                    // Flush mode-change callbacks before restoring nullable preference keys.
                    instrumentation.waitForIdleSync()
                }
            }
            cleanupStep("restore_allowed_preferences") {
                // No preferences map, session/cookie key, profile or raw bytes are serialized.
                restore?.restore()
            }
            cleanupStep("verify_restored_preferences_and_dsp") {
                if (::model.isInitialized && !activity.isDestroyed) {
                    val expected = runBlocking { context.dataStore.data.first().let(::decodeThorPreferences) }
                    await(10_000) { model.preferences.value == expected }
                    val expectedAudio = audioPreferences()
                    if (model.playerConnection.value != null) await(10_000) {
                        val actual = actualDsp()
                        actual.enabled == expectedAudio.equalizer && abs(actual.preamp - expectedAudio.preamp) < .001f &&
                            actual.gains == expectedAudio.bandGains
                    }
                    mark("modifiedPreferencesRestored")
                }
            }
            cleanupStep("restore_window_keep_screen_on") {
                onMain { originalKeepScreenOn.forEach { (window, originallyEnabled) ->
                    if (originallyEnabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    live((window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0) == originallyEnabled,
                        "window_keep_screen_on_not_restored")
                } }
                mark("originalWindowKeepScreenOnRestored")
            }
            cleanupStep("close_real_activity") { scenario?.close() }
            app.unregisterActivityLifecycleCallbacks(lifecycle)
            writeReport(name)
        }
        primary?.let {
            // Deliberately no cause/message: network/Compose errors may embed private profile
            // fields, a signed media URL or a full account page semantics tree.
            throw AssertionError("thor_live_ui_failed:$name:${report.optString("failureStage", stage)}:${report.optString("failureCode", "cleanup_failed")}")
        }
    }

    private fun keepScreenOn(window: Window) {
        originalKeepScreenOn.putIfAbsent(window,
            window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun awaitSecurity(security: AccountSecurityViewModel) {
        await(60_000) { !security.state.value.loading && (security.state.value.data != null || security.state.value.error != null) && noProgressIndicator() }
        live(security.state.value.error == null && security.state.value.data != null, "security_read_failed")
        assertStableWindows()
    }
    private fun noProgressIndicator() = nodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).isEmpty()
    private fun openMy() { click(hasText("我的") and hasClickAction()); awaitPage(ThorPage.Root(ThorTab.My)) }
    private fun clickText(text: String, scroll: Boolean = false) = click(hasText(text) and hasClickAction(), scroll)
    private fun click(matcher: SemanticsMatcher, scroll: Boolean = false) {
        if (scroll) {
            val lists = nodes(hasScrollToIndexAction())
            if (lists.size == 1) compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
            else compose.onNode(matcher).performScrollTo()
        }
        await(10_000) { nodes(matcher and isEnabled()).size == 1 }
        compose.onNode(matcher).performClick()
        compose.waitForIdle()
        checkAccountFence()
    }
    private fun buttonRect(matcher: SemanticsMatcher, scroll: Boolean): Rect {
        if (scroll) compose.onNode(matcher).performScrollTo()
        return compose.onNode(matcher).fetchSemanticsNode().boundsInRoot
    }
    private fun nodes(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes()
    private fun awaitPage(page: ThorPage) {
        await(15_000) { model.navigation.value.page == page && model.navigation.value.overlay == null }
        compose.waitForIdle()
        checkAccountFence()
    }
    private fun press(code: Int) = onMain {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            live(activity.dispatchKeyEvent(KeyEvent(now, now, action, code, 0, 0, 147, 0, 0, InputDevice.SOURCE_GAMEPAD)),
                "real_controller_did_not_consume_key")
        }
    }
    private fun nativeTap(window: WindowRef, bounds: Rect) = onMain {
        val host = descendants(window.root).first { it.javaClass.name == "androidx.compose.ui.platform.ComposeView" }
        val offset = IntArray(2).also(host::getLocationInWindow)
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 24 else 0,
                action, bounds.center.x + offset[0], bounds.center.y + offset[1], 0)
            try { live(window.window.superDispatchTouchEvent(event), "native_lower_touch_not_consumed") }
            finally { event.recycle() }
        }
    }
    private fun selectedSongIndex(scene: String, songs: List<ThorItem>): Int {
        val key = model.rememberedFocus(scene) ?: return -1
        return songs.indexOfFirst { key.startsWith("$scene:item:${it.key.stableId}:") }
    }
    private fun resultRow(song: ThorItem) = compose.onNode(
        hasText(song.title) and hasClickAction() and hasAnyAncestor(hasScrollToIndexAction()) and isSelected())
    private fun actualResultSelected(song: ThorItem): Boolean = nodes(
        hasText(song.title) and hasClickAction() and hasAnyAncestor(hasScrollToIndexAction()))
        .any { it.config.getOrNull(SemanticsProperties.Selected) == true }
    private fun actualResultFocused(song: ThorItem): Boolean = nodes(
        hasText(song.title) and hasClickAction() and hasAnyAncestor(hasScrollToIndexAction()))
        .any { it.config.getOrNull(SemanticsProperties.Focused) == true &&
            it.config.getOrNull(SemanticsProperties.Selected) == true }

    private data class ActualSearchViewport(
        val axis: Float,
        val listBounds: Rect,
        val focusedBounds: Rect,
        // Public catalog text is retained only in this in-memory layout comparison, never JSON.
        val rows: List<Pair<List<String>, Rect>>,
    )
    private fun actualSearchViewport(song: ThorItem): ActualSearchViewport {
        val list = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode()
        val axis = list.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            ?: fail("actual_result_list_scroll_semantics_missing")
        val area = list.boundsInRoot
        val visible = nodes(hasClickAction() and hasAnyAncestor(hasScrollToIndexAction())).filter {
            it.boundsInRoot.bottom > area.top && it.boundsInRoot.top < area.bottom
        }.map { node -> node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() to node.boundsInRoot }
        return ActualSearchViewport(onMain { axis.value() }, area, resultRow(song).fetchSemanticsNode().boundsInRoot, visible)
    }
    /** Safe return-path evidence: booleans/layout numbers only, never text/stable keys. */
    private fun recordSearchReturn(label: String, scene: String, focus: String?, savedScroll: Pair<String, Int>,
        baseline: ActualSearchViewport, song: ThorItem): Boolean {
        val navigation = model.navigation.value
        val pageReturned = navigation.page == ThorPage.Search && navigation.overlay == null
        val focusRestored = model.rememberedFocus(scene) == focus
        val rows = nodes(hasClickAction() and hasAnyAncestor(hasScrollToIndexAction()))
        val expectedRows = nodes(hasText(song.title) and hasClickAction() and hasAnyAncestor(hasScrollToIndexAction()))
        val selected = expectedRows.firstOrNull { it.config.getOrNull(SemanticsProperties.Selected) == true }
        val nativeFocusedSelected = expectedRows.any { it.config.getOrNull(SemanticsProperties.Focused) == true &&
            it.config.getOrNull(SemanticsProperties.Selected) == true }
        val inputFocused = nodes(hasTestTag("thor-search-input")).any { it.config.getOrNull(SemanticsProperties.Focused) == true }
        val lists = nodes(hasScrollToIndexAction())
        val actualRange = lists.singleOrNull()?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        val actualAxis = actualRange?.let { onMain { it.value() } }
        val bounds = selected?.boundsInRoot
        val checks = report.getJSONObject("checks")
        checks.put("${label}PageReturned", pageReturned)
            .put("${label}FocusRememberedRestored", focusRestored)
            .put("${label}NativeFocusedSelected", nativeFocusedSelected)
            .put("${label}AnyResultRowNativeFocused", rows.any { it.config.getOrNull(SemanticsProperties.Focused) == true })
            .put("${label}ExpectedRowSelected", selected != null)
            .put("${label}SearchInputFocused", inputFocused)
            .put("${label}TextEditing", model.textEditing.value)
            .put("${label}ScrollMemoryPresent", model.rememberedListScroll(scene) != null)
            .put("${label}ScrollMemoryMatches", model.rememberedListScroll(scene) == savedScroll)
            .put("${label}ActualAxisMatches", actualAxis == baseline.axis)
            .put("${label}RowBoundsMatches", bounds == baseline.focusedBounds)
        count("${label}RealListCount", lists.size)
        count("${label}ResultRowCount", rows.size)
        count("${label}NativeFocusedRowCount", rows.count { it.config.getOrNull(SemanticsProperties.Focused) == true })
        val metrics = report.getJSONObject("counts")
        metrics.put("${label}AxisBaseline", baseline.axis.toDouble())
        actualAxis?.let { metrics.put("${label}AxisReturned", it.toDouble()) }
        bounds?.let {
            metrics.put("${label}RowTopDifferencePx", (it.top - baseline.focusedBounds.top).toDouble())
                .put("${label}RowBottomDifferencePx", (it.bottom - baseline.focusedBounds.bottom).toDouble())
                .put("${label}RowLeftDifferencePx", (it.left - baseline.focusedBounds.left).toDouble())
                .put("${label}RowRightDifferencePx", (it.right - baseline.focusedBounds.right).toDouble())
        }
        // Confirm and directional movement are explicitly routed through this visible logical
        // cursor; native Focused remains diagnostic and is independently exercised above.
        return pageReturned && focusRestored && selected != null
    }

    private fun assertSearchViewportRestored(expected: ActualSearchViewport, song: ThorItem) {
        val actual = actualSearchViewport(song)
        live(actual.axis == expected.axis, "player_return_changed_real_list_scroll")
        live(actual.focusedBounds == expected.focusedBounds, "player_return_changed_visible_focused_row_bounds")
        // An initially-empty player can add a mini-player after A. Compare the common visible
        // region, so that legitimate bottom-height change does not masquerade as lost scroll.
        val commonTop = maxOf(expected.listBounds.top, actual.listBounds.top)
        val commonBottom = minOf(expected.listBounds.bottom, actual.listBounds.bottom)
        val common = expected.rows.filter { (_, bounds) -> bounds.top >= commonTop && bounds.bottom <= commonBottom }
        live(common.isNotEmpty() && common.all { before -> before in actual.rows }, "player_return_changed_common_visible_row_bounds")
        count("verifiedCommonVisibleSearchRows", common.size)
    }

    private fun withPausedPlayback(block: () -> Unit) {
        val connection = checkNotNull(model.playerConnection.value)
        val originalReady = onMain { connection.player.playWhenReady }
        val originalEntry = model.playback.state.value.currentEntryId
        onMain { connection.service.cancelUnavailableRecovery(); connection.player.pause() }
        try { block() } finally {
            // A natural song boundary cannot be mistaken for a theme/audio mutation. Restore
            // intent only if this same real player/entry still owns the original operation.
            onMain {
                if (model.playerConnection.value?.player === connection.player &&
                    model.playback.state.value.currentEntryId == originalEntry) {
                    connection.player.playWhenReady = originalReady
                }
            }
        }
    }

    private fun audioPreferences() = runBlocking { context.dataStore.data.first().let(::decodeThorAudioSettings) }
    private fun actualDsp(): EqualizerConfiguration = onMain {
        val service = checkNotNull(model.playerConnection.value).service
        // Read-only instrumentation of the SAME state used by service-created DSP processors.
        // Root explicitly authorized this narrow private-state read; no production hook or
        // replacement EqualizerConfigurationState is instantiated by this test.
        val field = service.javaClass.getDeclaredField("equalizerConfigurationState").apply { isAccessible = true }
        (field.get(service) as EqualizerConfigurationState).snapshot()
    }

    private fun awaitNightMode(night: Boolean) {
        await(15_000) { onMain {
            listOf(baseline.upper.root, baseline.lower.root).all { view ->
                (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) == night
            }
        } }
        compose.waitForIdle()
        assertStableWindows()
    }
    private fun assertNoUpperFocus() {
        var root = compose.onNodeWithTag("thor-cd-case").fetchSemanticsNode()
        while (root.parent != null) root = checkNotNull(root.parent)
        live(semantics(root).none { it.config.getOrNull(SemanticsProperties.Focused) == true }, "upper_received_dpad_focus")
        count("upperFocusedNodes", 0)
    }
    private fun semantics(node: SemanticsNode): List<SemanticsNode> = buildList {
        add(node); node.children.forEach { addAll(semantics(it)) }
    }
    private fun systemVolumePanelVisible(): Boolean {
        val windows = instrumentation.uiAutomation.windowsOnAllDisplays
        for (index in 0 until windows.size()) for (window in windows.valueAt(index)) {
            val root = window.root ?: continue
            if (root.packageName?.toString() == "com.android.systemui" && hasVolumeId(root)) return true
        }
        return false
    }
    private fun hasVolumeId(node: AccessibilityNodeInfo): Boolean {
        if (node.viewIdResourceName?.contains("volume", ignoreCase = true) == true) return true
        for (i in 0 until node.childCount) node.getChild(i)?.let { if (hasVolumeId(it)) return true }
        return false
    }

    private fun windowsOrNull(): Windows? = onMain {
        if (!::activity.isInitialized || activity.isDestroyed || !activity.window.decorView.isAttachedToWindow) return@onMain null
        // Pure inspection of the actual host/PRESENTATION; never create a replacement window.
        val host = activity.javaClass.getDeclaredField("displayHost").apply { isAccessible = true }.get(activity)
        val lower = host.javaClass.getDeclaredField("presentation").apply { isAccessible = true }.get(host) as? Presentation
            ?: return@onMain null
        if (!lower.isShowing || lower.window?.decorView?.isAttachedToWindow != true) return@onMain null
        Windows(activity.taskId, ref(activity.window), ref(checkNotNull(lower.window)))
    }
    private fun ref(window: Window): WindowRef = window.decorView.let {
        WindowRef(window, it, checkNotNull(it.windowToken), it.display.displayId,
            descendants(it).filter { child -> child.javaClass.name.contains("ComposeView") })
    }
    private fun assertStableWindows(composeIdle: Boolean = true) {
        if (composeIdle) compose.waitForIdle()
        val current = checkNotNull(windowsOrNull())
        live(baseline.taskId == current.taskId, "activity_task_changed")
        for ((before, after) in listOf(baseline.upper to current.upper, baseline.lower to current.lower)) {
            live(before.root === after.root && before.window === after.window && before.token === after.token &&
                before.displayId == after.displayId && before.composeHosts.size == after.composeHosts.size &&
                before.composeHosts.zip(after.composeHosts).all { (a, b) -> a === b }, "base_window_recreated")
        }
        onMain { live(lifecycle.unchanged(), "activity_lifecycle_or_legacy_navigation_changed") }
        mark("originalUpperAndLowerWindowsRetained")
    }
    private data class WindowRef(val window: Window, val root: View, val token: IBinder,
        val displayId: Int, val composeHosts: List<View>)
    private data class Windows(val taskId: Int, val upper: WindowRef, val lower: WindowRef) {
        fun safeJson() = JSONObject().put("task", taskId).put("upperDisplay", upper.displayId)
            .put("lowerDisplay", lower.displayId).put("upperRoot", System.identityHashCode(upper.root))
            .put("lowerRoot", System.identityHashCode(lower.root)).put("upperToken", System.identityHashCode(upper.token))
            .put("lowerToken", System.identityHashCode(lower.token))
    }
    private fun descendants(root: View): List<View> = buildList {
        add(root); if (root is ViewGroup) for (i in 0 until root.childCount) addAll(descendants(root.getChildAt(i)))
    }
    private fun capture(window: Window): Bitmap {
        val size = onMain { window.decorView.width to window.decorView.height }
        live(size.first > 0 && size.second > 0, "window_has_no_pixels")
        // An invalidation is not proof of a submitted shader frame. Observe the actual
        // hardware commit before PixelCopy, including when test-clock advancement is manual.
        val committed = CountDownLatch(1)
        onMain {
            live(window.decorView.isHardwareAccelerated, "native_window_not_hardware_accelerated")
            window.decorView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            window.decorView.invalidate()
        }
        live(committed.await(6, TimeUnit.SECONDS), "native_window_frame_not_committed")
        val bitmap = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888)
        val result = AtomicInteger(Int.MIN_VALUE)
        val latch = CountDownLatch(1)
        onMain { PixelCopy.request(window, bitmap, { code -> result.set(code); latch.countDown() }, Handler(Looper.getMainLooper())) }
        live(latch.await(6, TimeUnit.SECONDS) && result.get() == PixelCopy.SUCCESS, "native_window_copy_failed")
        return bitmap
    }
    private fun cornerLuminance(bitmap: Bitmap): Double {
        var value = 0.0
        for (y in 2..9) for (x in 2..9) {
            val c = bitmap.getPixel(x, y)
            value += (.2126 * android.graphics.Color.red(c) + .7152 * android.graphics.Color.green(c) + .0722 * android.graphics.Color.blue(c)) / 255.0
        }
        return value / 64.0
    }
    private fun changedBackgroundSamples(a: Bitmap, b: Bitmap): Int {
        // Left/background strips only, excluding title/lyrics, the CD and personal text. Pixels
        // remain in memory and are immediately recycled; the report stores only this count.
        var changed = 0
        for (y in a.height / 20 until a.height * 19 / 20 step 20) for (x in 8 until a.width / 30 step 10) {
            val c = a.getPixel(x, y); val d = b.getPixel(x, y)
            if (abs(android.graphics.Color.red(c) - android.graphics.Color.red(d)) +
                abs(android.graphics.Color.green(c) - android.graphics.Color.green(d)) +
                abs(android.graphics.Color.blue(c) - android.graphics.Color.blue(d)) >= 3) changed++
        }
        count("flowChangedBackgroundSamples", changed)
        return changed
    }
    private fun backupAllowedPreferences(): PreferenceBackup {
        val keys: List<Preferences.Key<*>> = listOf(
            ThemeKey, AppAppearanceKey, ReduceMotionKey,
            ReduceTransparencyKey, AirflowKey, BackgroundModeKey,
            BackgroundIntensityKey, LoopPlaybackKey, EqualizerEnabledKey,
            EqualizerPresetKey, EqualizerPreampKey, EqualizerBandGainsKey,
            IsShuffleModeKey, RepeatModeKey, MusicQualityKey, AutoMixEnabledKey,
        )
        return runBlocking { context.dataStore.data.first().let { prefs -> PreferenceBackup(keys.map { it to prefs[it] }) } }
    }
    private inner class PreferenceBackup(private val values: List<Pair<Preferences.Key<*>, Any?>>) {
        @Suppress("UNCHECKED_CAST")
        fun restore() = runBlocking {
            context.dataStore.edit { prefs -> values.forEach { (key, value) ->
                val typed = key as Preferences.Key<Any>
                if (value == null) prefs.remove(typed) else prefs[typed] = value
            } }
            val restored = context.dataStore.data.first()
            live(values.all { (key, value) -> restored[key] == value }, "allowed_preferences_not_restored")
        }
    }
    /** In-memory actual queue snapshot; private titles and metadata never enter the JSON. */
    private data class LiveQueueSnapshot(
        val items: List<MediaItem>, val index: Int, val positionMs: Long, val order: List<Int>,
        val shuffle: Boolean, val repeat: Int, val ready: Boolean, val wasIdle: Boolean,
        val title: String?, val fm: Boolean, val similarIds: List<String>?, val autoplay: Boolean,
        val autoplayStartIndex: Int?, val playlistSource: PlaylistQueueSource?,
    ) {
        fun restore(service: MusicService) {
            val player = service.player
            player.shuffleModeEnabled = false
            if (items.isEmpty()) player.clearMediaItems()
            else player.activeDeck.setMediaItems(items, index, positionMs)
            player.setPlaybackOrder(order)
            service.queueTitle = title
            service.queueManager.restoreFmMode(fm, similarIds, autoplay)
            service.queueManager.restoreQueueAutoplayStartIndex(autoplayStartIndex)
            service.queueManager.restorePlaylistSource(playlistSource)
            player.repeatMode = repeat
            player.shuffleModeEnabled = shuffle
            if (!wasIdle && items.isNotEmpty()) player.prepare()
            player.playWhenReady = ready
        }
        fun verify(service: MusicService) {
            val player = service.player
            check(items.map { it.queueEntryId } == List(player.mediaItemCount) { player.getMediaItemAt(it).queueEntryId }) { "queue_entry_identity_not_restored" }
            check(items.map { it.mediaId } == List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }) { "queue_media_identity_not_restored" }
            check(order == player.playbackOrderIndices(true)) { "queue_order_not_restored" }
            check(player.shuffleModeEnabled == shuffle && player.repeatMode == repeat && player.playWhenReady == ready) { "queue_modes_not_restored" }
            if (items.isNotEmpty()) check(player.currentMediaItemIndex == index && abs(player.currentPosition - positionMs) < 1_000) { "queue_position_not_restored" }
            val manager = service.queueManager
            check(service.queueTitle == title && manager.isFmMode == fm && manager.similarFmSourceIds == similarIds &&
                manager.queueAutoplayEnabled.value == autoplay && manager.playlistSource == playlistSource) { "queue_context_not_restored" }
            val expectedStart = autoplayStartIndex?.let { items.getOrNull(it)?.queueEntryId }
            check(manager.queueAutoplayStartEntryId.value == expectedStart) { "queue_autoplay_origin_not_restored" }
        }
        companion object {
            fun capture(service: MusicService): LiveQueueSnapshot {
                val player = service.player
                val manager = service.queueManager
                return LiveQueueSnapshot(List(player.mediaItemCount, player::getMediaItemAt), player.currentMediaItemIndex,
                    player.currentPosition, player.playbackOrderIndices(true), player.shuffleModeEnabled, player.repeatMode,
                    player.playWhenReady, player.playbackState == Player.STATE_IDLE, service.queueTitle, manager.isFmMode,
                    manager.similarFmSourceIds, manager.queueAutoplayEnabled.value,
                    (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).queueEntryId == manager.queueAutoplayStartEntryId.value },
                    manager.playlistSource)
            }
        }
    }

    private class LifecycleWitness : Application.ActivityLifecycleCallbacks {
        private var created = 0; private var paused = 0; private var stopped = 0; private var destroyed = 0; private var legacy = 0
        private var previous = emptyList<Int>()
        private fun values() = listOf(created, paused, stopped, destroyed, legacy)
        fun baseline() { previous = values() }
        fun unchanged() = values() == previous && legacy == 0
        override fun onActivityCreated(activity: Activity, state: Bundle?) { if (activity is ThorActivity) created++; if (activity is MainActivity) legacy++ }
        override fun onActivityPaused(activity: Activity) { if (activity is ThorActivity) paused++ }
        override fun onActivityStopped(activity: Activity) { if (activity is ThorActivity) stopped++ }
        override fun onActivityDestroyed(activity: Activity) { if (activity is ThorActivity) destroyed++ }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }
    private fun checkAccountFence() {
        if (accountGeneration < 0) return
        live(model.library.value.account.generation == accountGeneration && model.library.value.account.userId == accountId &&
            model.library.value.account.signedIn, "live_account_changed")
    }
    /**
     * Real-time bridge for UI waits under ComposeTestRule. Native player/network callbacks use
     * Android time, but LaunchedEffect.delay and withFrameNanos can use the test scheduler.
     * Advance by the full elapsed uptime delta, including work done inside the predicate;
     * advancing just one 16ms frame after an expensive poll would make a 15s timer run late.
     * Production deadlines remain SystemClock.uptimeMillis and are never shortened/injected.
     */
    private fun awaitActualUi(timeout: Long, predicate: () -> Boolean) {
        val previousAutoAdvance = compose.mainClock.autoAdvance
        compose.mainClock.autoAdvance = false
        val wallStart = SystemClock.uptimeMillis()
        val clockStart = compose.mainClock.currentTime
        val committed = AtomicInteger()
        val frameLatch = CountDownLatch(2)
        var frameSteps = 0
        try {
            onMain {
                for (window in listOf(baseline.upper, baseline.lower)) {
                    window.root.viewTreeObserver.registerFrameCommitCallback {
                        committed.incrementAndGet(); frameLatch.countDown()
                    }
                    window.root.invalidate()
                }
            }
            while (true) {
                val wallElapsed = SystemClock.uptimeMillis() - wallStart
                val targetClock = clockStart + wallElapsed
                val advance = targetClock - compose.mainClock.currentTime
                if (advance > 0) {
                    compose.mainClock.advanceTimeBy(advance, ignoreFrameDuration = true)
                    frameSteps++
                }
                onMain { baseline.upper.root.invalidate(); baseline.lower.root.invalidate() }
                if (predicate()) break
                if (SystemClock.uptimeMillis() - wallStart >= timeout) fail("actual_ui_condition_timeout")
                SystemClock.sleep(16)
            }
            live(frameLatch.await(2, TimeUnit.SECONDS), "actual_ui_wait_hardware_frame_not_committed")
        } finally {
            count("uiWaitWallMilliseconds", (SystemClock.uptimeMillis() - wallStart).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            count("uiWaitClockMilliseconds", (compose.mainClock.currentTime - clockStart).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            count("uiWaitClockSteps", frameSteps)
            count("uiWaitCommittedWindows", committed.get())
            compose.mainClock.autoAdvance = previousAutoAdvance
        }
    }

    private fun await(timeout: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) {
            if (SystemClock.elapsedRealtime() >= deadline) fail("condition_timeout")
            SystemClock.sleep(25)
        }
    }
    private fun driveRealFrames(milliseconds: Long) {
        // A Compose test clock advances only when explicitly requested; wall-clock sleep
        // alone freezes the production withFrameNanos loop under ComposeTestRule. Advance
        // one test frame per physical interval and let Android submit actual hardware draws.
        val deadline = SystemClock.elapsedRealtime() + milliseconds
        var frames = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.mainClock.advanceTimeByFrame()
            onMain { baseline.upper.root.invalidate() }
            frames++
            SystemClock.sleep(16)
        }
        count("drivenRealFrames", report.getJSONObject("counts").optInt("drivenRealFrames") + frames)
    }
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var value: T? = null
        var error: Throwable? = null
        instrumentation.runOnMainSync {
            try { value = block() } catch (failure: Throwable) { error = failure }
        }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun count(key: String, value: Int) { report.getJSONObject("counts").put(key, value) }
    private fun mark(key: String) { report.getJSONObject("checks").put(key, true) }
    private fun writeReport(name: String) {
        report.put("checkedAtEpochMs", System.currentTimeMillis())
        val file = File(context.cacheDir, "thor-live-ui-$name.json")
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(report.toString(2))
        live(temp.renameTo(file), "live_report_write_failed")
    }
    private class LiveFailure(val code: String) : RuntimeException()
    private fun fail(code: String): Nothing = throw LiveFailure(code)
    private fun live(value: Boolean, code: String) { if (!value) fail(code) }
}

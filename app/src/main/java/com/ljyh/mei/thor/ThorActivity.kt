package com.ljyh.mei.thor

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.MainActivity
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.playback.MusicService
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.thor.audio.*
import com.ljyh.mei.thor.display.ThorDisplayHost
import com.ljyh.mei.thor.model.toThorItem
import com.ljyh.mei.thor.state.ThorOverlay
import com.ljyh.mei.thor.state.ThorPage
import com.ljyh.mei.thor.theme.ThorTheme
import com.ljyh.mei.thor.ui.ThorCommandDispatcher
import com.ljyh.mei.thor.ui.ThorLowerScreen
import com.ljyh.mei.thor.upper.ThorUpperScreen
import com.ljyh.mei.thor.upper.thorUpperProjection
import com.ljyh.mei.ui.screen.local.LocalLibraryViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Dedicated handheld entry. The two compositions share one state and service connection. */
@UnstableApi
@AndroidEntryPoint
class ThorActivity : ComponentActivity() {
    @Inject lateinit var database: AppDatabase
    private val viewModel: ThorShellViewModel by viewModels()
    private val localLibrary: LocalLibraryViewModel by viewModels()
    private val commands = ThorCommandDispatcher()
    private lateinit var displayHost: ThorDisplayHost
    private lateinit var airflow: ThorAirflowSource
    private var bound = false
    private var connection: PlayerConnection? = null
    private var connectionJob: Job? = null
    private var legacyActive by mutableStateOf(false)
    private var legacyLaunchedAt = 0L

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) setAirflowEnabled(true)
        else { viewModel.updatePreferences { it.copy(airflowEnabled = false) }; viewModel.showMessage("麦克风未授权，仍可拖动封面", false) }
    }
    private val musicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) localLibrary.scan() else viewModel.showMessage("需要音乐和音频权限才能导入", false)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!bound || binder !is MusicService.MusicBinder) return
            releaseConnection()
            val job = SupervisorJob(lifecycleScope.coroutineContext[Job])
            connectionJob = job
            connection = PlayerConnection(this@ThorActivity, binder, database, CoroutineScope(lifecycleScope.coroutineContext + job))
            viewModel.attachPlayer(connection)
        }
        override fun onServiceDisconnected(name: ComponentName?) { releaseConnection() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        legacyActive = savedInstanceState?.getBoolean("thor.legacyActive") ?: false
        readThorDestination(intent)
        enableEdgeToEdge()
        hideSystemBars()
        airflow = ThorAirflowSource(this, this)
        displayHost = ThorDisplayHost(this, commands::dispatch, viewModel::clearExitGuard) { LowerContent() }
        if (!legacyActive) displayHost.start()
        setContent {
            val preferences by viewModel.preferences.collectAsStateWithLifecycle()
            val displays by displayHost.state.collectAsStateWithLifecycle()
            val navigation by viewModel.navigation.collectAsStateWithLifecycle()
            val preview by viewModel.preview.collectAsStateWithLifecycle()
            val playback = viewModel.playback.state.collectAsStateWithLifecycle()
            val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
            val strength by airflow.strength.collectAsStateWithLifecycle()
            val lyricEntryId = playback.value.currentEntryId
            val playbackScene = navigation.overlay in setOf(ThorOverlay.Player, ThorOverlay.Queue, ThorOverlay.Lyrics, ThorOverlay.Timer)
            val upperItem = thorUpperProjection(navigation.page, playbackScene, preview, playback.value.current?.toThorItem())
            val upperDisplayOn = displays.displays.firstOrNull { it.id == displays.upperDisplayId }?.state == android.view.Display.STATE_ON
            if (BuildConfig.DEBUG) {
                val diagnostic by rememberUpdatedState(
                    "strength=$strength enabled=${preferences.airflowEnabled} reduceMotion=${preferences.reduceMotion} " +
                        "hasItem=${upperItem != null} displayOn=$upperDisplayOn upperShown=${displays.isDualScreen || legacyActive}",
                )
                LaunchedEffect(Unit) {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        while (isActive) {
                            Log.d("ThorAirflowProjection", "$diagnostic lifecycle=${lifecycle.currentState.ordinal}")
                            delay(500)
                        }
                    }
                }
            }
            ThorTheme(preferences) {
                if (displays.isDualScreen || legacyActive) {
                    ThorUpperScreen(
                        item = upperItem,
                        playing = playback.value.playing, preferences = preferences, airflow = strength,
                        lyricData = lyrics, positionProvider = { playback.value.positionMs },
                        onOpenPlayer = viewModel::togglePlayer, playbackScene = playbackScene,
                        onLyricSelected = { position -> viewModel.playFromLyric(position, lyricEntryId) },
                        playbackEntryId = lyricEntryId,
                        backgroundPreview = navigation.overlay == null && navigation.page == ThorPage.Appearance,
                        visible = upperDisplayOn,
                    )
                } else LowerContent()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch { viewModel.textEditing.collect { displayHost.inputRouter.setTextEditing(it) } }
                launch {
                    try {
                        combine(
                            viewModel.preferences.map { it.airflowEnabled }.distinctUntilChanged(),
                            viewModel.playback.state.map { it.connected to it.playing }.distinctUntilChanged(),
                        ) { _, _ -> Unit }.collect { syncAirflowCapture() }
                    } finally { airflow.stop() }
                }
            }
        }
        lifecycleScope.launch {
            var scanning = false
            localLibrary.scanState.collect { state ->
                if (state.error != null) viewModel.showMessage(state.error!!, false)
                else if (state.isScanning && !scanning) viewModel.showMessage("正在扫描本地音乐")
                else if (scanning && !state.isScanning) { viewModel.showMessage(state.message?.takeIf { it.isNotBlank() } ?: "扫描完成"); viewModel.refresh() }
                scanning = state.isScanning
            }
        }
    }

    @Composable
    private fun LowerContent() {
        val preferences by viewModel.preferences.collectAsStateWithLifecycle()
        val playback by viewModel.playback.state.collectAsStateWithLifecycle()
        val status by airflow.status.collectAsStateWithLifecycle()
        val error by airflow.error.collectAsStateWithLifecycle()
        val step by airflow.calibrationStep.collectAsStateWithLifecycle()
        val nextStep by airflow.nextCalibrationStep.collectAsStateWithLifecycle()
        val strength by airflow.strength.collectAsStateWithLifecycle()
        val progress by airflow.calibrationProgress.collectAsStateWithLifecycle()
        val calibrationFeedback by airflow.calibrationFeedback.collectAsStateWithLifecycle()
        val scan by localLibrary.scanState.collectAsStateWithLifecycle()
        ThorTheme(preferences) {
            ThorLowerScreen(viewModel, commands, ::finish, ::openLegacy, ::requestAirflow, ::calibrate,
                airflowStatus = when {
                    preferences.airflowEnabled && playback.playing -> "播放中已暂停吹气"
                    preferences.airflowEnabled && !playback.connected -> "等待播放器连接"
                    preferences.airflowEnabled && !hasMicrophonePermission() -> "麦克风未授权"
                    else -> error ?: calibrationFeedback ?: when {
                        step == ThorAirflowCalibrationStep.Quiet -> "保持安静"
                        step == ThorAirflowCalibrationStep.Gentle -> "轻吹麦克风"
                        step == ThorAirflowCalibrationStep.Strong -> "用力吹麦克风"
                        preferences.airflowEnabled && preferences.reduceMotion -> "减少动态已开启"
                        status == ThorAirflowStatus.Listening -> "等待吹气"
                        else -> ""
                    }
                }, isScanning = scan.isScanning,
                airflowStrength = strength,
                airflowCalibrationProgress = if (step != null) progress else 0f,
                airflowCalibrationLabel = when (step ?: nextStep) {
                    ThorAirflowCalibrationStep.Quiet -> "安静校准"
                    ThorAirflowCalibrationStep.Gentle -> "轻吹校准"
                    ThorAirflowCalibrationStep.Strong -> "强吹校准"
                }, onControllerScopeChanged = {
                    displayHost.inputRouter.reset()
                    viewModel.clearExitGuard()
                })
        }
    }

    override fun onStart() { super.onStart(); if (!legacyActive) bindPlayback(); viewModel.playback.setVisible(true) }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == RETURN_FROM_LEGACY) {
            legacyActive = false
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) resumeThor()
        }
        readThorDestination(intent)
    }
    private fun readThorDestination(intent: Intent) {
        intent.getStringExtra(INTERNAL_ROUTE)?.let(viewModel::openInternalRoute)
        if (intent.getBooleanExtra(OPEN_PLAYER, false)) viewModel.showPlayer()
    }
    override fun onResume() {
        super.onResume()
        hideSystemBars()
        recoverAbandonedLegacy()
        if (!legacyActive && ::displayHost.isInitialized) resumeThor()
    }
    override fun onPause() {
        if (::displayHost.isInitialized) displayHost.inputRouter.reset()
        viewModel.cancelSeek()
        viewModel.clearExitGuard()
        if (::airflow.isInitialized) airflow.stop()
        super.onPause()
    }
    override fun onStop() {
        viewModel.playback.setVisible(false)
        unbindPlayback()
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("thor.legacyActive", legacyActive); super.onSaveInstanceState(outState) }
    override fun onDestroy() { if (::displayHost.isInitialized) displayHost.close(); if (::airflow.isInitialized) airflow.close(); unbindPlayback(); super.onDestroy() }

    private fun bindPlayback() {
        if (bound) return
        val intent = Intent(this, MusicService::class.java)
        startService(intent)
        bound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    private fun resumeThor() {
        displayHost.start()
        bindPlayback()
        viewModel.playback.setVisible(true)
        syncAirflowCapture()
    }
    private fun releaseConnection() { viewModel.attachPlayer(null); connection?.dispose(); connection = null; connectionJob?.cancel(); connectionJob = null }
    private fun unbindPlayback() { if (bound) { bound = false; unbindService(serviceConnection) }; releaseConnection() }
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = if (::displayHost.isInitialized && !legacyActive && displayHost.inputRouter.onKeyEvent(event)) true else super.dispatchKeyEvent(event)
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = if (::displayHost.isInitialized && !legacyActive && displayHost.inputRouter.onMotionEvent(event)) true else super.dispatchGenericMotionEvent(event)
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) viewModel.clearExitGuard()
        return super.dispatchTouchEvent(event)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::displayHost.isInitialized) {
            if (!hasFocus) { displayHost.inputRouter.reset(); viewModel.clearExitGuard() }
            else if (recoverAbandonedLegacy() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) resumeThor()
        }
    }

    private fun recoverAbandonedLegacy(): Boolean {
        if (!legacyActive || android.os.SystemClock.elapsedRealtime() - legacyLaunchedAt < 1_000) return false
        val tasks = getSystemService(ActivityManager::class.java)?.appTasks.orEmpty().filter {
            it.taskInfo?.baseIntent?.getStringExtra(LEGACY_ROUTE) in LegacyRoutes
        }
        if (tasks.any { it.taskInfo?.isVisible == true }) return false
        legacyActive = false
        return true
    }

    private fun hasMicrophonePermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun requestAirflow(enabled: Boolean) {
        if (!enabled) setAirflowEnabled(false)
        else if (hasMicrophonePermission()) setAirflowEnabled(true)
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    private fun airflowCapturePolicy(enabled: Boolean = viewModel.preferences.value.airflowEnabled): ThorAirflowCapturePolicy {
        val playback = viewModel.playback.state.value
        return ThorAirflowCapturePolicy(enabled, hasMicrophonePermission(),
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), legacyActive,
            playback.connected, playback.playing)
    }
    private fun syncAirflowCapture(enabled: Boolean = viewModel.preferences.value.airflowEnabled): Boolean {
        return if (airflowCapturePolicy(enabled).shouldCapture) airflow.start()
        else { airflow.stop(); true }
    }
    private fun setAirflowEnabled(enabled: Boolean) {
        // Enabling while playback/lifecycle temporarily blocks capture still saves the user's intent.
        val accepted = syncAirflowCapture(enabled)
        viewModel.updatePreferences { it.copy(airflowEnabled = enabled && accepted) }
        if (!accepted) viewModel.showMessage(airflow.error.value ?: "麦克风暂不可用", false)
        else viewModel.showMessage(when {
            !enabled -> "吹气已关闭"
            airflowCapturePolicy(true).pausedForPlayback -> "播放中已暂停吹气"
            !viewModel.playback.state.value.connected -> "等待播放器连接"
            else -> "吹气已开启"
        })
    }
    private fun calibrate() {
        val policy = airflowCapturePolicy()
        if (!policy.shouldCapture) {
            airflow.stop()
            viewModel.showMessage(when {
                policy.playing -> "播放中已暂停吹气"
                !policy.enabled -> "请先开启吹气"
                !policy.playbackConnected -> "等待播放器连接"
                !policy.hasPermission -> "请先允许麦克风权限"
                else -> "请返回应用后校准"
            }, success = policy.playing)
            return
        }
        if (!syncAirflowCapture()) {
            viewModel.showMessage(airflow.error.value ?: "麦克风暂不可用", false)
            return
        }
        if (airflow.calibrationStep.value != null) return
        if (!airflow.calibrate(airflow.nextCalibrationStep.value)) viewModel.showMessage("麦克风暂不可用", false)
    }
    private fun openLegacy(route: String) {
        if (viewModel.openInternalRoute(route)) return
        if (route == "local_music") {
            if (localLibrary.scanState.value.isScanning) localLibrary.cancelScan()
            else if (localLibrary.hasAudioPermission()) localLibrary.scan() else musicPermission.launch(LocalLibraryViewModel.audioPermission)
            return
        }
        if (route !in LegacyRoutes || legacyActive) return
        legacyActive = true
        legacyLaunchedAt = android.os.SystemClock.elapsedRealtime()
        val displays = displayHost.state.value
        displayHost.stop()
        unbindPlayback()
        airflow.stop()
        val intent = Intent(this, MainActivity::class.java).putExtra(LEGACY_ROUTE, route)
            .putExtra(UPPER_DISPLAY, displays.upperDisplayId ?: android.view.Display.DEFAULT_DISPLAY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val options = android.app.ActivityOptions.makeBasic().apply { displays.lowerDisplayId?.let { launchDisplayId = it } }
        try { startActivity(intent, options.toBundle()) }
        catch (error: Exception) {
            legacyActive = false
            displayHost.start(); bindPlayback()
            viewModel.showMessage(error.message ?: "暂时无法打开页面", false)
        }
    }

    companion object {
        const val LEGACY_ROUTE = "com.ljyh.mei.thor.LEGACY_ROUTE"
        const val OPEN_PLAYER = "com.ljyh.mei.thor.OPEN_PLAYER"
        const val INTERNAL_ROUTE = "com.ljyh.mei.thor.INTERNAL_ROUTE"
        const val RETURN_FROM_LEGACY = "com.ljyh.mei.thor.RETURN_FROM_LEGACY"
        const val UPPER_DISPLAY = "com.ljyh.mei.thor.UPPER_DISPLAY"
        val LegacyRoutes = setOf("account/login", "account/home", "account/security/bindings", "setting/play", "local_music", "download_manage", "about")
    }
}

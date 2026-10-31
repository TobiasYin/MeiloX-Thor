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
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.playback.MusicService
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.thor.audio.*
import com.ljyh.mei.thor.display.ThorDisplayHost
import com.ljyh.mei.thor.model.toThorItem
import com.ljyh.mei.thor.state.ThorOverlay
import com.ljyh.mei.thor.theme.ThorTheme
import com.ljyh.mei.thor.ui.ThorCommandDispatcher
import com.ljyh.mei.thor.ui.ThorLowerScreen
import com.ljyh.mei.thor.upper.ThorUpperScreen
import com.ljyh.mei.ui.screen.local.LocalLibraryViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
        if (intent.getBooleanExtra(OPEN_PLAYER, false)) viewModel.showPlayer()
        enableEdgeToEdge()
        hideSystemBars()
        airflow = ThorAirflowSource(this, this)
        displayHost = ThorDisplayHost(this, commands::dispatch) { LowerContent() }
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
            ThorTheme(preferences) {
                if (displays.isDualScreen || legacyActive) {
                    ThorUpperScreen(
                        item = if (playbackScene) playback.value.current?.toThorItem() else preview,
                        playing = playback.value.playing, preferences = preferences, airflow = strength,
                        lyricData = lyrics, positionProvider = { playback.value.positionMs },
                        onOpenPlayer = viewModel::togglePlayer, playbackScene = playbackScene,
                        onLyricSelected = { position -> viewModel.playFromLyric(position, lyricEntryId) },
                        playbackEntryId = lyricEntryId,
                        visible = displays.displays.firstOrNull { it.id == displays.upperDisplayId }?.state == android.view.Display.STATE_ON,
                    )
                } else LowerContent()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch { viewModel.textEditing.collect { displayHost.inputRouter.setTextEditing(it) } }
                launch { viewModel.preferences.collect { preferences ->
                    if (preferences.airflowEnabled && !legacyActive && hasMicrophonePermission()) airflow.start() else airflow.stop()
                } }
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
                airflowStatus = error ?: calibrationFeedback ?: when {
                    step == ThorAirflowCalibrationStep.Quiet -> "保持安静"
                    step == ThorAirflowCalibrationStep.Gentle -> "轻吹麦克风"
                    step == ThorAirflowCalibrationStep.Strong -> "用力吹麦克风"
                    preferences.airflowEnabled && preferences.reduceMotion -> "减少动态已开启"
                    status == ThorAirflowStatus.Listening -> "等待吹气"
                    else -> ""
                }, isScanning = scan.isScanning,
                airflowStrength = strength,
                airflowCalibrationProgress = if (step != null) progress else 0f,
                airflowCalibrationLabel = when (step ?: nextStep) {
                    ThorAirflowCalibrationStep.Quiet -> "安静校准"
                    ThorAirflowCalibrationStep.Gentle -> "轻吹校准"
                    ThorAirflowCalibrationStep.Strong -> "强吹校准"
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
        if (viewModel.preferences.value.airflowEnabled && hasMicrophonePermission()) airflow.start()
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
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::displayHost.isInitialized) {
            if (!hasFocus) displayHost.inputRouter.reset()
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
    private fun setAirflowEnabled(enabled: Boolean) {
        val accepted = !enabled || airflow.start()
        viewModel.updatePreferences { it.copy(airflowEnabled = enabled && accepted) }
        if (!enabled) airflow.stop()
        if (!accepted) viewModel.showMessage(airflow.error.value ?: "麦克风暂不可用", false)
        else viewModel.showMessage(if (enabled) "吹气已开启" else "吹气已关闭")
    }
    private fun calibrate() {
        if (airflow.calibrationStep.value != null) return
        airflow.calibrate(airflow.nextCalibrationStep.value)
    }
    private fun openLegacy(route: String) {
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
        const val RETURN_FROM_LEGACY = "com.ljyh.mei.thor.RETURN_FROM_LEGACY"
        const val UPPER_DISPLAY = "com.ljyh.mei.thor.UPPER_DISPLAY"
        val LegacyRoutes = setOf("account/login", "account/home", "account/security/bindings", "setting/play", "local_music", "download_manage", "about")
    }
}

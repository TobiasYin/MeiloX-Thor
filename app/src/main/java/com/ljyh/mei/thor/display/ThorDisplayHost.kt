package com.ljyh.mei.thor.display

import android.app.Presentation
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ljyh.mei.thor.input.ThorInputRouter
import com.ljyh.mei.thor.model.ThorCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable

/** Owns only windows and controller dispatch. Playback and application state stay with the caller. */
class ThorDisplayHost(
    private val activity: ComponentActivity,
    onCommand: (ThorCommand) -> Boolean,
    private val lowerContent: @Composable () -> Unit,
) : Closeable, DefaultLifecycleObserver {
    val inputRouter = ThorInputRouter(onCommand)
    private val manager = checkNotNull(activity.getSystemService(DisplayManager::class.java))
    private val inputManager = checkNotNull(activity.getSystemService(InputManager::class.java))
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(ThorDisplaySnapshot())
    val state: StateFlow<ThorDisplaySnapshot> = mutableState.asStateFlow()
    private var presentation: LowerPresentation? = null
    private var displayedDescriptor: ThorDisplayDescriptor? = null
    private var lastAttemptedDescriptor: ThorDisplayDescriptor? = null
    private var started = false
    private var closed = false
    private var changingWindow = false
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh(forceReconnect = false)
        override fun onDisplayRemoved(displayId: Int) = refresh(forceReconnect = false)
        override fun onDisplayChanged(displayId: Int) = refresh(forceReconnect = false)
    }
    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = Unit
        override fun onInputDeviceChanged(deviceId: Int) = Unit
        override fun onInputDeviceRemoved(deviceId: Int) = inputRouter.reset()
    }

    fun start() {
        if (closed || started) return
        started = true
        manager.registerDisplayListener(displayListener, handler)
        inputManager.registerInputDeviceListener(inputListener, handler)
        activity.lifecycle.addObserver(this)
        refresh()
    }

    fun refresh(forceReconnect: Boolean = true) {
        if (closed) return
        val descriptors = manager.displays.mapNotNull { display ->
            runCatching {
                val mode = display.mode
                val metrics = activity.createDisplayContext(display).resources.displayMetrics
                ThorDisplayDescriptor(
                    id = display.displayId, name = display.name,
                    width = mode.physicalWidth, height = mode.physicalHeight,
                    densityDpi = metrics.densityDpi, rotation = display.rotation, state = display.state,
                    presentationCapable = display.flags and Display.FLAG_PRESENTATION != 0 &&
                        display.flags and Display.FLAG_PRIVATE == 0,
                )
            }.getOrNull()
        }
        val resolved = resolveThorDisplays(descriptors, activity.display?.displayId)
        val previous = mutableState.value
        val generation = if (resolved.displays == previous.displays && resolved.profile == previous.profile &&
            resolved.upperDisplayId == previous.upperDisplayId && resolved.lowerDisplayId == previous.lowerDisplayId
        ) previous.generation else previous.generation + 1
        val lower = resolved.displays.firstOrNull { it.id == resolved.lowerDisplayId }
        val canShow = started && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            lower != null && lower.presentationCapable && lower.state != Display.STATE_OFF &&
            lower.state != Display.STATE_UNKNOWN
        var problem = resolved.problem
        if (!canShow) {
            dismissPresentation()
            lastAttemptedDescriptor = null
            if (resolved.hasSupportedPair && lower?.state == Display.STATE_OFF) problem = "下屏已关闭"
        } else if (presentation == null && lastAttemptedDescriptor == lower && !forceReconnect) {
            // Repeated OEM DISPLAY_INFO callbacks must not repeatedly create rejected windows.
            problem = previous.problem
        } else if (presentation == null || displayedDescriptor != lower) {
            dismissPresentation()
            lastAttemptedDescriptor = lower
            val display = manager.getDisplay(lower!!.id)
            if (display != null && display.isValid) {
                try {
                    val next = LowerPresentation(activity, display, inputRouter, lowerContent)
                    presentation = next
                    displayedDescriptor = lower
                    next.setOnDismissListener {
                        if (presentation === next) {
                            presentation = null
                            displayedDescriptor = null
                            inputRouter.reset()
                            if (!changingWindow) mutableState.value = mutableState.value.copy(
                                lowerPresented = false, problem = "下屏窗口已关闭，可重新连接",
                            )
                        }
                    }
                    next.show()
                } catch (error: WindowManager.InvalidDisplayException) {
                    dismissPresentation()
                    problem = "下屏窗口不可用：${error.message.orEmpty()}"
                } catch (error: WindowManager.BadTokenException) {
                    dismissPresentation()
                    problem = "下屏窗口连接已失效：${error.message.orEmpty()}"
                } catch (error: SecurityException) {
                    dismissPresentation()
                    problem = "系统未允许下屏窗口：${error.message.orEmpty()}"
                }
            } else problem = "下屏连接已失效"
        }
        val showing = presentation?.isShowing == true
        mutableState.value = resolved.copy(
            generation = generation, lowerPresented = showing,
            problem = if (showing) null else problem,
        )
    }

    fun stop() {
        if (!started) return
        started = false
        manager.unregisterDisplayListener(displayListener)
        inputManager.unregisterInputDeviceListener(inputListener)
        activity.lifecycle.removeObserver(this)
        dismissPresentation()
        mutableState.value = mutableState.value.copy(lowerPresented = false)
    }

    override fun onStart(owner: LifecycleOwner) = refresh()
    override fun onResume(owner: LifecycleOwner) = refresh()
    override fun onStop(owner: LifecycleOwner) {
        dismissPresentation()
        lastAttemptedDescriptor = null
        inputRouter.reset()
        mutableState.value = mutableState.value.copy(lowerPresented = false)
    }
    override fun onDestroy(owner: LifecycleOwner) = close()

    override fun close() {
        if (closed) return
        stop()
        closed = true
        inputRouter.close()
    }

    private fun dismissPresentation() {
        changingWindow = true
        val previous = presentation
        presentation = null
        displayedDescriptor = null
        previous?.dismiss()
        changingWindow = false
        if (previous != null) inputRouter.reset()
    }
}

private class LowerPresentation(
    private val owner: ComponentActivity,
    display: Display,
    private val router: ThorInputRouter,
    private val content: @Composable () -> Unit,
) : Presentation(owner, display) {
    private var composeView: ComposeView? = null
    private val backCallback = OnBackInvokedCallback { router.dispatch(ThorCommand.Back) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        window?.let { target ->
            target.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            target.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            target.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.setDecorFitsSystemWindows(target, false)
            target.decorView.setViewTreeLifecycleOwner(owner)
            target.decorView.setViewTreeViewModelStoreOwner(owner)
            target.decorView.setViewTreeSavedStateRegistryOwner(owner)
            target.decorView.setViewTreeOnBackPressedDispatcherOwner(owner)
        }
        val view = ComposeView(context).apply {
            id = View.generateViewId()
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeOnBackPressedDispatcherOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { CompositionLocalProvider(LocalActivity provides owner) { content() } }
        }
        composeView = view
        setContentView(view)
    }

    override fun onStart() {
        super.onStart()
        window?.let { target ->
            target.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            WindowInsetsControllerCompat(target, target.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            target.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback,
            )
        }
    }

    override fun onStop() {
        window?.onBackInvokedDispatcher?.unregisterOnBackInvokedCallback(backCallback)
        composeView?.disposeComposition()
        router.reset()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        router.onKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        router.onMotionEvent(event) || super.dispatchGenericMotionEvent(event)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) router.reset()
    }

    @Deprecated("Handled through the same application navigation as controller Back")
    override fun onBackPressed() { router.dispatch(ThorCommand.Back) }
}

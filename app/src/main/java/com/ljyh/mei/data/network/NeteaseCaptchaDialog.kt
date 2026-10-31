package com.ljyh.mei.data.network

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout

/** The production window factory also accepts a plain WebView without any network/account setup. */
internal fun createNeteaseCaptchaDialog(
    activity: Activity,
    uiHost: CaptchaUiHost?,
    web: WebView,
    onCancel: () -> Unit,
    onGlobalDeparture: () -> Unit,
    handler: Handler = Handler(Looper.getMainLooper()),
): NeteaseCaptchaDialog = NeteaseCaptchaDialog(activity, uiHost, web, onCancel, onGlobalDeparture, handler)

internal class NeteaseCaptchaDialog internal constructor(
    activity: Activity,
    uiHost: CaptchaUiHost?,
    web: WebView,
    onCancel: () -> Unit,
    onGlobalDeparture: () -> Unit,
    handler: Handler,
) : Dialog(uiHost?.context ?: activity) {
    private val controller = uiHost?.let {
        CaptchaWebController(web, handler, it.onControllerKey, onCancel, onGlobalDeparture)
    }
    val contentWidthPixels: Int

    init {
        uiHost?.let { host -> window?.apply {
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
            attributes = attributes.apply { token = host.parentWindowToken }
        } }
        val metrics = context.resources.displayMetrics
        contentWidthPixels = minOf(metrics.widthPixels, (360 * metrics.density).toInt())
        val height = minOf((metrics.heightPixels * .88f).toInt(), (420 * metrics.density).toInt())
        setContentView(web, FrameLayout.LayoutParams(contentWidthPixels, height))
        setOnCancelListener { onCancel() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        controller?.onKey(event) == true || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        controller?.onMotion(event) == true || super.dispatchGenericMotionEvent(event)

    override fun dismiss() {
        controller?.close()
        super.dismiss()
    }
}

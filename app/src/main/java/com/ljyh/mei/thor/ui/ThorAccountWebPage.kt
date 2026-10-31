package com.ljyh.mei.thor.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.thor.ThorShellViewModel
import com.ljyh.mei.thor.account.ThorAccountInputHost
import com.ljyh.mei.thor.account.ThorAccountRoute
import com.ljyh.mei.thor.model.ThorCommand
import com.ljyh.mei.thor.model.ThorDirection
import com.ljyh.mei.thor.theme.LocalThorTheme
import com.ljyh.mei.ui.screen.account.NeteaseLoginViewModel
import com.ljyh.mei.ui.screen.account.NeteaseWebBridge
import com.ljyh.mei.ui.screen.account.OfficialNeteasePage
import com.ljyh.mei.ui.screen.account.WebCookieLoginPoller
import com.ljyh.mei.ui.screen.account.featureSessionCookies
import com.ljyh.mei.ui.screen.account.musicUFromCookieHeader
import com.ljyh.mei.utils.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Only approved account/authentication sites stay in this leaf; no legacy Activity is launched. */
internal fun thorAccountWebTarget(value: String): String? = runCatching {
    val uri = Uri.parse(value)
    if (uri.scheme != "https" || uri.userInfo != null) return@runCatching null
    val host = uri.host?.lowercase().orEmpty()
    if (listOf("163.com", "netease.com", "qq.com", "weixin.qq.com").any { host == it || host.endsWith(".$it") }) value else null
}.getOrNull()

/** The sole JS interface reports an input-focus boolean, never text, URLs or account data. */
private class ThorWebEditingObserver(private val changed: (Boolean) -> Unit) {
    @JavascriptInterface fun setEditing(editing: Boolean) = changed(editing)
}

@UnstableApi
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ThorAccountWebPage(page: OfficialNeteasePage?, vm: ThorShellViewModel, registry: ThorFocusRegistry,
    navigate: (ThorAccountRoute) -> Unit, back: () -> Unit, loginSuccess: () -> Unit,
    bindings: ThorPageActionBindings?, input: ThorAccountInputHost?,
) {
    val context = LocalContext.current // Presentation context owns the lower display's WebView/IME.
    val keyboard = LocalSoftwareKeyboardController.current
    val theme = LocalThorTheme.current
    val scope = rememberCoroutineScope()
    val login: NeteaseLoginViewModel = viewModel()
    val poller = remember(page) { WebCookieLoginPoller() }
    val preferences by context.dataStore.data.collectAsStateWithLifecycle(initialValue = null)
    val account by vm.library.collectAsStateWithLifecycle()
    var view by remember(page) { mutableStateOf<WebView?>(null) }
    var progress by remember(page) { mutableIntStateOf(0) }
    var error by remember(page) { mutableStateOf<String?>(null) }
    val initialTitle = if (page == null) "网页登录" else stringResource(page.titleRes)
    var title by remember(page) { mutableStateOf(initialTitle) }
    var ready by remember(page) { mutableStateOf(false) }
    val callbacks by rememberUpdatedState(Triple(navigate, back, loginSuccess))
    val currentTheme by rememberUpdatedState(theme)
    val owner = remember(page, input) { Any() }
    fun sendKey(key: Int, shift: Boolean = false) {
        view?.let { target ->
            target.requestFocus()
            val meta = if (shift) KeyEvent.META_SHIFT_ON else 0
            val now = android.os.SystemClock.uptimeMillis()
            target.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0, meta))
            target.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0, meta))
        }
    }
    fun closeOrBack(): Boolean {
        if (vm.textEditing.value) {
            keyboard?.hide()
            view?.evaluateJavascript("document.activeElement && document.activeElement.blur()", null)
            vm.textEditing.value = false
            return true
        }
        if (view?.canGoBack() == true) { view?.goBack(); return true }
        return false
    }
    fun refresh() { error = null; view?.reload() }
    BindThorOptionFocus(vm, registry, listOf("account:web:content"), ThorFocusLayout.Vertical)
    BindThorPageAction(bindings, "account:refresh", ::refresh)
    BindThorPageAction(bindings, "account:web:next") { sendKey(KeyEvent.KEYCODE_TAB) }
    BindThorPageAction(bindings, "account:web:previous") { sendKey(KeyEvent.KEYCODE_TAB, true) }
    BindThorPageAction(bindings, "account:web:activate") { sendKey(KeyEvent.KEYCODE_ENTER) }
    if (page == null) BindThorPageAction(bindings, "account:web:cookie") { navigate(ThorAccountRoute.CookieLogin) }
    DisposableEffect(input, owner) {
        input?.install(owner) { command -> when (command) {
            ThorCommand.Back -> closeOrBack()
            ThorCommand.Confirm -> { sendKey(KeyEvent.KEYCODE_DPAD_CENTER); true }
            is ThorCommand.Move -> {
                sendKey(when(command.direction) {
                    ThorDirection.Up -> KeyEvent.KEYCODE_DPAD_UP
                    ThorDirection.Down -> KeyEvent.KEYCODE_DPAD_DOWN
                    ThorDirection.Left -> KeyEvent.KEYCODE_DPAD_LEFT
                    ThorDirection.Right -> KeyEvent.KEYCODE_DPAD_RIGHT
                }); true
            }
            else -> false
        } }
        onDispose { input?.remove(owner); vm.textEditing.value = false; keyboard?.hide() }
    }
    Column(Modifier.fillMaxSize()) {
        ThorAccountHeader(title, registry, { if (!closeOrBack()) back() }, ::refresh)
        if (progress in 1..99 || !ready) LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.accent)
        error?.let { Text(it, color = theme.error, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        AndroidView(factory = { lowerContext ->
            WebView(lowerContext).apply {
                setBackgroundColor(theme.background.toArgb())
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.userAgentString = settings.userAgentString + " CloudMusic/0.1.2 NeteaseMusic/9.2.10"
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                val web = this
                addJavascriptInterface(ThorWebEditingObserver { editing ->
                    web.post { if (view === web) vm.textEditing.value = editing }
                }, "ThorAccountEditing")
                fun open(target: String) {
                    val absolute = runCatching { java.net.URI(web.url ?: "https://music.163.com").resolve(target).toString() }.getOrDefault(target)
                    val accepted = thorAccountWebTarget(absolute)
                    if (accepted != null) { error = null; web.loadUrl(accepted) }
                    else if (target.startsWith("orpheus://")) {
                        val uri = Uri.parse(target)
                        val nested = uri.getQueryParameter("url")?.let(::thorAccountWebTarget)
                        when {
                            nested != null -> web.loadUrl(nested)
                            target.contains("login", true) -> callbacks.first(ThorAccountRoute.Login)
                            target.contains("account/settings") || target.contains("bindaccount") -> callbacks.first(ThorAccountRoute.Security)
                            target.contains("close", true) -> callbacks.second()
                            else -> error = "此操作暂无法在当前网页完成"
                        }
                    } else error = "此链接无法在账户页面打开"
                }
                fun bridge() = NeteaseWebBridge(lowerContext, scope, currentTheme.isDark,
                    open = { _, target -> open(target) }, close = { callbacks.second() },
                    login = { callbacks.first(ThorAccountRoute.Login) }, setTitle = { title = it })
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val target = request.url.toString()
                        if (thorAccountWebTarget(target) != null) return false
                        open(target); return true
                    }
                    @Deprecated("Legacy WebView callback")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                        if (thorAccountWebTarget(url) != null) return false
                        open(url); return true
                    }
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (url?.let(::thorAccountWebTarget) != null) view.evaluateJavascript("""
                            (function(){if(window.__thorAccountEditing)return;window.__thorAccountEditing=true;
                            function update(){var e=document.activeElement;ThorAccountEditing.setEditing(!!e&&(/^(INPUT|TEXTAREA)$/.test(e.tagName)||e.isContentEditable));}
                            document.addEventListener('focusin',update);document.addEventListener('focusout',function(){setTimeout(update,0)});update();})();
                        """.trimIndent(), null)
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                        if (request.isForMainFrame) error = "网页暂时无法加载，请重试"
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress }
                    override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                        if (view != null && result != null && bridge().onPrompt(view, url, message, result)) return true
                        return super.onJsPrompt(view, url, message, defaultValue, result)
                    }
                }
                view = this
            }
        }, modifier = Modifier.fillMaxWidth().weight(1f)
            .thorFocus(registry, "account:web:content") { view?.requestFocus() }
            .focusable(), update = { it.setBackgroundColor(currentTheme.background.toArgb()) },
            onRelease = { web ->
                if (view === web) { view = null; vm.textEditing.value = false }
                web.stopLoading(); web.removeJavascriptInterface("ThorAccountEditing"); web.webChromeClient = null
                web.webViewClient = WebViewClient(); web.destroy()
            })
    }
    // Cookie values are a short-lived bridge to the official page, never a new store or route argument.
    LaunchedEffect(view, page, if (page == null) 0L else account.account.generation) {
        // DataStore/cookie suspension may resume through a composition interceptor. WebView
        // initialization and CookieManager callbacks require a thread with the main Looper.
        withContext(Dispatchers.Main.immediate) {
            val web = view ?: return@withContext
            val manager = CookieManager.getInstance()
            ready = false
            if (page == null) {
                // Explicit Web sign-in may switch accounts. Never treat an existing Web cookie as success.
                for (name in listOf("MUSIC_U", "MUSIC_A", "URS_APPID", "__csrf")) {
                    setAccountWebCookie(manager, "$name=; Domain=.music.163.com; Path=/; Secure; Max-Age=0")
                    setAccountWebCookie(manager, "$name=; Path=/; Secure; Max-Age=0")
                }
            } else {
                val current = context.dataStore.data.first()
                val cookies = featureSessionCookies(current)
                if (cookies["MUSIC_U"].isNullOrBlank()) { ready = true; error = "请先登录网易云音乐"; return@withContext }
                val changed = musicUFromCookieHeader(manager.getCookie("https://music.163.com").orEmpty()) != cookies["MUSIC_U"]
                for (name in listOf("MUSIC_U", "MUSIC_A", "URS_APPID", "__csrf", "deviceId", "sDeviceId", "os", "appver")) {
                    currentCoroutineContext().ensureActive()
                    val value = cookies[name]
                    if (name == "__csrf" && value == null && !changed) continue
                    setAccountWebCookie(manager, if (value == null) "$name=; Domain=.music.163.com; Path=/; Secure; Max-Age=0"
                        else "$name=$value; Domain=.music.163.com; Path=/; Secure")
                }
            }
            currentCoroutineContext().ensureActive()
            manager.flush(); web.clearHistory(); web.loadUrl(page?.url ?: "https://music.163.com/#/login"); ready = true
            if (page == null) {
                try {
                    if (poller.poll({ musicUFromCookieHeader(manager.getCookie("https://music.163.com").orEmpty()) }, login::loginWithCookie)) callbacks.third()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "登录验证失败，请重试" }
            }
        }
    }
}

private suspend fun setAccountWebCookie(manager: CookieManager, value: String) = suspendCancellableCoroutine<Unit> { continuation ->
    manager.setCookie("https://music.163.com", value) { if (continuation.isActive) continuation.resume(Unit) }
}

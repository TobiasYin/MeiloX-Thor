package com.ljyh.mei.thor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.data.model.melox.*
import com.ljyh.mei.data.repository.AccountFeatureException
import com.ljyh.mei.data.network.CaptchaUiHost
import com.ljyh.mei.data.network.NeteaseUrsUpSmsChallenge
import com.ljyh.mei.data.network.NeteaseUrsUpSmsRequiredException
import com.ljyh.mei.thor.ThorShellViewModel
import com.ljyh.mei.thor.account.*
import com.ljyh.mei.thor.components.ThorSurfaceRole
import com.ljyh.mei.thor.components.thorMaterial
import com.ljyh.mei.thor.model.*
import com.ljyh.mei.thor.theme.LocalThorTheme
import com.ljyh.mei.ui.screen.account.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Only destination callbacks cross into the shell; account credentials never do. */
@UnstableApi
@Composable
internal fun ThorAccountPage(
    route: ThorAccountRoute,
    vm: ThorShellViewModel,
    registry: ThorFocusRegistry,
    onNavigate: (ThorAccountRoute) -> Unit,
    onBack: () -> Unit,
    onLoginSuccess: () -> Unit,
    pageActions: ThorPageActionBindings? = null,
    input: ThorAccountInputHost? = null,
) {
    when (route) {
        ThorAccountRoute.Login -> AccountList("网易云登录", vm, registry, onBack, rows = listOf(
            AccountRow("mobile", "手机号登录", "验证码 / 密码") { onNavigate(ThorAccountRoute.MobileLogin) },
            AccountRow("web", "网页登录") { onNavigate(ThorAccountRoute.WebLogin) },
            AccountRow("cookie", "Web Cookie 登录") { onNavigate(ThorAccountRoute.CookieLogin) },
        ))
        ThorAccountRoute.MobileLogin, ThorAccountRoute.CookieLogin -> AccountLoginForm(route, vm, registry, onBack, onLoginSuccess, input)
        ThorAccountRoute.WebLogin -> ThorAccountWebPage(null, vm, registry, onNavigate, onBack, onLoginSuccess, pageActions, input)
        is ThorAccountRoute.Web -> ThorAccountWebPage(route.page, vm, registry, onNavigate, onBack, onLoginSuccess, pageActions, input)
        ThorAccountRoute.Home -> AccountHome(vm, registry, onNavigate, onBack, input, pageActions)
        ThorAccountRoute.Security, ThorAccountRoute.Bindings -> AccountSecurity(route, vm, registry, onNavigate, onBack, pageActions)
        ThorAccountRoute.Devices, ThorAccountRoute.Authorizations, ThorAccountRoute.History -> AccountDevices(route, vm, registry, onNavigate, onBack, input, pageActions)
        is ThorAccountRoute.Connections -> AccountConnections(route, vm, registry, onNavigate, onBack, pageActions)
    }
}

internal data class AccountRow(val id: String, val title: String, val detail: String = "", val action: (() -> Unit)? = null)

@Composable
internal fun ThorAccountHeader(title: String, registry: ThorFocusRegistry, onBack: () -> Unit, onRefresh: (() -> Unit)? = null) {
    val theme = LocalThorTheme.current
    Row(Modifier.fillMaxWidth().height(56.dp).thorMaterial(ThorSurfaceRole.Header).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ThorButton(registry, "account:back", "", Modifier.size(48.dp), Icons.Outlined.ChevronLeft, flat = true,
            contentDescription = "返回", onClick = onBack)
        Text(title, Modifier.weight(1f), color = theme.text, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        onRefresh?.let { ThorButton(registry, "account:refresh", "", Modifier.size(48.dp), Icons.Outlined.Refresh, flat = true,
            contentDescription = "刷新", onClick = it) }
    }
}

@UnstableApi
@Composable
private fun AccountList(title: String, vm: ThorShellViewModel, registry: ThorFocusRegistry, onBack: () -> Unit,
    rows: List<AccountRow>, loading: Boolean = false, error: String? = null, onRefresh: (() -> Unit)? = null,
    empty: String = "暂无记录", extra: @Composable ColumnScope.() -> Unit = {},
) {
    val theme = LocalThorTheme.current
    val state = rememberLazyListState()
    val navigation by vm.navigation.collectAsStateWithLifecycle()
    val keys = rows.map { "account:${it.id}" }
    val binding = BindThorListFocus(registry, navigation.sceneKey, keys, List(rows.size) { null }, state, vm) { rows.getOrNull(it)?.action?.invoke() }
    Column(Modifier.fillMaxSize()) {
        ThorAccountHeader(title, registry, onBack, onRefresh)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.accent)
        error?.let { Text(it, color = theme.secondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        extra()
        if (rows.isEmpty() && !loading && error == null) Text(empty, color = theme.secondary, modifier = Modifier.padding(20.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .thorMaterial(ThorSurfaceRole.MusicRow, focused = registry.selectedKey == keys[index])
                    .thorFocus(registry, keys[index], onClick = { binding.activate(index) })
                    .onFocusChanged { if (it.isFocused) binding.onFocused(index) }
                    .clickable(enabled = row.action != null, role = Role.Button) { binding.activate(index) }
                    .then(if (row.action == null) Modifier.focusable() else Modifier)
                    .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.title, color = theme.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (row.detail.isNotBlank()) Text(row.detail, color = theme.secondary, fontSize = 12.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                    }
                    if (row.action != null) Icon(thorLineIcon(Icons.Outlined.ChevronRight), null, tint = theme.secondary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun BindAccountInput(input: ThorAccountInputHost?, onCommand: (ThorCommand) -> Boolean) {
    val owner = remember { Any() }
    val callback by rememberUpdatedState(onCommand)
    DisposableEffect(input, owner) {
        input?.install(owner) { callback(it) }
        onDispose { input?.remove(owner) }
    }
}

@UnstableApi
@Composable
private fun AccountHome(vm: ThorShellViewModel, registry: ThorFocusRegistry, navigate: (ThorAccountRoute) -> Unit,
    back: () -> Unit, input: ThorAccountInputHost?, bindings: ThorPageActionBindings?,
) {
    val account by vm.library.collectAsStateWithLifecycle()
    val model: ThorAccountHomeViewModel = viewModel()
    val loaded by model.state.collectAsStateWithLifecycle()
    val state = loaded.takeIf { it.generation == account.account.generation }
        ?: ThorAccountHomeState(loading = account.account.signedIn)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val publicAccount = account.account
    val generation = publicAccount.generation
    var confirmLogout by remember(account.account.generation) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(account.account.generation) { model.load(account.account) }
    fun refresh() { model.load(account.account, true) }
    BindThorPageAction(bindings, "account:refresh", ::refresh)
    BindAccountInput(input) { command -> if (command == ThorCommand.Back && (confirmLogout || busy)) {
        if (!busy) confirmLogout = false
        true
    } else false }
    val rows = if (!account.account.signedIn) listOf(AccountRow("login", "连接网易云音乐") { navigate(ThorAccountRoute.Login) })
    else if (confirmLogout) listOf(
        AccountRow("logout:cancel", "取消") { if (!busy) confirmLogout = false },
        AccountRow("logout:confirm", if (busy) "正在退出…" else "退出当前账号") {
            val ticket = vm.captureAccountTicket(publicAccount)
            if (ticket == null) vm.showMessage("账号状态已更新，请重试", false)
            else if (!busy) scope.launch {
                busy = true
                try {
                    logoutNetease(context, ticket.expectedSessionFingerprint)
                    vm.showMessage("已退出账号")
                    back()
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (vm.library.value.account.generation == generation) vm.showMessage("退出失败，请重试", false) }
                finally { busy = false }
            }
        },
    ) else buildList {
        val profile = state.profile
        val id = account.account.userId?.toLongOrNull()
        add(AccountRow("security", "账号安全") { navigate(ThorAccountRoute.Security) })
        add(AccountRow("bindings", "账号绑定") { navigate(ThorAccountRoute.Bindings) })
        if (id != null) {
            add(AccountRow("following", "关注", profile?.follows?.toString().orEmpty()) { navigate(ThorAccountRoute.Connections(id, AccountConnectionType.Following)) })
            add(AccountRow("followers", "粉丝", profile?.followers?.toString().orEmpty()) { navigate(ThorAccountRoute.Connections(id, AccountConnectionType.Followers)) })
        }
        add(AccountRow("switch", "切换账号") { navigate(ThorAccountRoute.Login) })
        state.playlists.forEach { playlist -> add(AccountRow("playlist:${playlist.id}", playlist.name, "共 ${playlist.trackCount} 首") {
            vm.open(ThorItem(ThorItemKey.Playlist(playlist.id.toString()), playlist.name,
                playlist.creatorName.orEmpty(), playlist.coverUrl, trackCount = playlist.trackCount,
                owned = playlist.creatorId?.toString() == account.account.userId))
        }) }
        add(AccountRow("logout", "退出登录") { confirmLogout = true })
    }
    AccountList(if (confirmLogout) "退出登录" else state.profile?.nickname ?: account.account.nickname,
        vm, registry, back, rows, state.loading, state.error, ::refresh)
}

@UnstableApi
@Composable
private fun AccountSecurity(route: ThorAccountRoute, vm: ThorShellViewModel, registry: ThorFocusRegistry,
    navigate: (ThorAccountRoute) -> Unit, back: () -> Unit, bindings: ThorPageActionBindings?,
) {
    val model: AccountSecurityViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val generation = library.account.generation
    var ready by remember(generation) { mutableStateOf(false) }
    LaunchedEffect(generation) {
        model.refresh()
        model.state.first { !it.loading }
        if (vm.library.value.account.generation == generation) ready = true
    }
    BindThorPageAction(bindings, "account:refresh", model::refresh)
    val summary = state.data.takeIf { ready && state.error == null }
    val rows = if (route == ThorAccountRoute.Bindings) listOf(10 to "微信", 5 to "QQ", 2 to "微博").map { (type, title) ->
        val binding = summary?.bindings?.firstOrNull { it.type == type }
        AccountRow("binding:$type", title, when {
            summary?.bindings == null -> "暂无法获取"
            binding?.bound == true -> binding.displayName.ifBlank { "已绑定" }
            else -> "未绑定"
        }) { navigate(ThorAccountRoute.Web(OfficialNeteasePage.ThirdPartyBinding)) }
    } else listOf(
        AccountRow("phone", "手机绑定", summary?.phone ?: when (summary?.phoneBound) { true -> "已绑定"; false -> "未绑定"; null -> "暂无法获取" }) { navigate(ThorAccountRoute.Web(OfficialNeteasePage.PhoneBinding)) },
        AccountRow("protection", "登录保护", when(summary?.protectionEnabled) { true -> "已开启"; false -> "未开启"; null -> "暂无法获取" }) { navigate(ThorAccountRoute.Web(OfficialNeteasePage.LoginProtection)) },
        AccountRow("devices", "登录设备", summary?.deviceCount?.let { "共 $it 台" }.orEmpty()) { navigate(ThorAccountRoute.Devices) },
        AccountRow("authorizations", "应用授权") { navigate(ThorAccountRoute.Authorizations) },
        AccountRow("history", "授权历史") { navigate(ThorAccountRoute.History) },
        AccountRow("bindings", "第三方绑定") { navigate(ThorAccountRoute.Bindings) },
        AccountRow("security:web", "安全中心") { navigate(ThorAccountRoute.Web(OfficialNeteasePage.SecurityCenter)) },
    )
    AccountList(if (route == ThorAccountRoute.Bindings) "账号绑定" else "账号安全", vm, registry, back,
        rows, state.loading || !ready, state.error ?: summary?.errors?.firstOrNull(), model::refresh)
}

@UnstableApi
@Composable
private fun AccountConnections(route: ThorAccountRoute.Connections, vm: ThorShellViewModel, registry: ThorFocusRegistry,
    navigate: (ThorAccountRoute) -> Unit, back: () -> Unit, bindings: ThorPageActionBindings?,
) {
    val model: AccountConnectionsViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val account by vm.library.collectAsStateWithLifecycle()
    val generation = account.account.generation
    var ready by remember(route, generation) { mutableStateOf(false) }
    LaunchedEffect(route.userId, route.type, generation) {
        model.load(route.userId, route.type)
        model.refresh()
        model.state.first { !it.loading }
        if (vm.library.value.account.generation == generation) ready = true
    }
    BindThorPageAction(bindings, "account:refresh", model::refresh)
    val page = state.page.takeIf { ready && state.error == null }
    val rows = page?.users.orEmpty().map { profile -> AccountRow("person:${profile.id}", profile.nickname, profile.signature.orEmpty()) {
        navigate(ThorAccountRoute.Connections(profile.id, AccountConnectionType.Following))
    } } + if (page?.hasMore == true) listOf(AccountRow("loadMore", if (state.loadingMore) "正在加载…" else "加载更多") { model.loadMore() }) else emptyList()
    AccountList(if (route.type == AccountConnectionType.Following) "关注" else "粉丝", vm, registry, back,
        rows, !ready || state.loading || state.loadingMore, state.error ?: state.loadMoreError, model::refresh)
}

@UnstableApi
@Composable
private fun AccountDevices(route: ThorAccountRoute, vm: ThorShellViewModel, registry: ThorFocusRegistry,
    navigate: (ThorAccountRoute) -> Unit, back: () -> Unit, input: ThorAccountInputHost?, bindings: ThorPageActionBindings?,
) {
    val model: AccountDevicesViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val account by vm.library.collectAsStateWithLifecycle()
    val publicAccount = account.account
    val generation = publicAccount.generation
    var ready by remember(route, generation) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var device by remember(route, generation) { mutableStateOf<AccountDevice?>(null) }
    var application by remember(route, generation) { mutableStateOf<AccountAuthorization?>(null) }
    var captcha by remember(route, generation) { mutableStateOf("") }
    var needsCode by remember(route, generation) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var wait by remember { mutableIntStateOf(0) }
    var error by remember(route, generation) { mutableStateOf<String?>(null) }
    val tab = when (route) {
        ThorAccountRoute.Authorizations -> AccountDeviceTab.Authorizations
        ThorAccountRoute.History -> AccountDeviceTab.History
        else -> AccountDeviceTab.Devices
    }
    LaunchedEffect(tab, generation) {
        if (model.state.value.tab != tab) model.select(tab) else model.refresh()
        model.state.first { !it.loading }
        if (vm.library.value.account.generation == generation) ready = true
    }
    LaunchedEffect(wait) { if (wait > 0) { delay(1000); wait-- } }
    fun dismiss() { if (!busy && !sending) { device = null; application = null; captcha = ""; needsCode = false; error = null } }
    fun perform() {
        if (busy) return
        val frozenDevice = device
        val frozenApp = application
        val frozenCode = captcha
        val ticket = vm.captureAccountTicket(publicAccount)
        if (ticket == null) { vm.showMessage("账号状态已更新，请重试", false); return }
        scope.launch {
            busy = true; error = null
            try {
                if (vm.library.value.account.generation != generation) return@launch
                if (frozenDevice != null) model.removeDevice(frozenDevice, frozenCode, ticket.expectedSessionFingerprint)
                else if (frozenApp != null) model.revoke(frozenApp, ticket.expectedSessionFingerprint)
                if (vm.library.value.account.generation == generation) {
                    device = null; application = null; needsCode = false; captcha = ""
                    vm.showMessage("操作完成")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (vm.library.value.account.generation == generation) {
                    if (frozenDevice != null && failure is AccountFeatureException && failure.code == 401) needsCode = true
                    error = failure.message ?: "操作失败，请重试"
                }
            } finally { busy = false }
        }
    }
    BindThorPageAction(bindings, "account:refresh", model::refresh)
    if (needsCode && device != null) {
        AccountCodeForm(vm, registry, input, busy || sending, captcha, { captcha = it.filter(Char::isDigit).take(8) },
            error, ::dismiss, ::perform, if (wait > 0) "${wait}s" else "获取验证码") {
            val ticket = vm.captureAccountTicket(publicAccount)
            if (ticket == null) vm.showMessage("账号状态已更新，请重试", false)
            else if (!sending && wait == 0) scope.launch {
                sending = true
                try { model.sendCode(ticket.expectedSessionFingerprint); if (vm.library.value.account.generation == generation) wait = 60 }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { if (vm.library.value.account.generation == generation) error = failure.message }
                finally { sending = false }
            }
        }
        return
    }
    BindAccountInput(input) { command -> if (command == ThorCommand.Back && (device != null || application != null)) { dismiss(); true } else false }
    val rows = if (device != null || application != null) listOf(
        AccountRow("action:cancel", "取消") { dismiss() },
        AccountRow("action:confirm", if (busy) "处理中…" else if (device != null) "移除设备" else "撤销授权", device?.name ?: application?.name.orEmpty()) { perform() },
    ) else if (!ready || state.error != null) emptyList() else buildList {
        if (tab == AccountDeviceTab.Devices) state.devices.forEach { record -> add(AccountRow("device:${record.key}", record.name,
            listOfNotNull(if (record.current) "当前设备" else null, record.region.takeIf(String::isNotBlank), record.os.takeIf(String::isNotBlank)).joinToString(" · "),
            if (record.current) null else { { device = record; error = null } })) }
        else state.applications.forEach { record -> add(AccountRow("authorization:${record.clientId}", record.name, record.description,
            if (tab == AccountDeviceTab.History) null else { { application = record; error = null } })) }
        if (state.more) add(AccountRow("more", "加载更多") { model.loadMore() })
    }
    AccountList(if (tab == AccountDeviceTab.Devices) "登录设备" else if (tab == AccountDeviceTab.History) "授权历史" else "应用授权",
        vm, registry, { if (device != null || application != null) dismiss() else back() }, rows,
        !ready || state.loading || busy, error ?: state.error, model::refresh)
}

/** A secret stays only in this composition, never in a navigation argument or saved state. */
@UnstableApi
@Composable
private fun AccountLoginForm(route: ThorAccountRoute, vm: ThorShellViewModel, registry: ThorFocusRegistry,
    back: () -> Unit, success: () -> Unit, input: ThorAccountInputHost?,
) {
    val model: NeteaseLoginViewModel = viewModel()
    val activity = LocalActivity.current
    val lowerView = LocalView.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val fieldEditing = remember { ThorAccountEditingSession() }
    val draft = input?.loginDraft(route) ?: remember(route) { ThorLoginFormDraft() }
    var phone by draft.phoneState
    var country by draft.countryState
    var secret by draft.passwordState
    var code by draft.smsCodeState
    var cookie by draft.cookieState
    var passwordMode by draft.passwordModeState
    var challenge by draft.challengeState
    var busy by remember { mutableStateOf(false) }
    var now by remember(draft) { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    val wait = draft.cooldownRemainingSeconds(now)
    var error by remember { mutableStateOf<String?>(null) }
    val manualCookie = route == ThorAccountRoute.CookieLogin
    fun endEdit() { fieldEditing.finish(); keyboard?.hide(); focus.clearFocus(); vm.textEditing.value = false }
    fun run(login: Boolean = false, operation: suspend () -> Unit) {
        if (busy) return
        endEdit(); error = null
        scope.launch {
            busy = true
            try { operation(); if (login) { secret = ""; cookie = ""; code = ""; success() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (required: NeteaseUrsUpSmsRequiredException) { challenge = required.challenge }
            catch (failure: Exception) { error = failure.message ?: "登录失败，请重试" }
            finally { busy = false }
        }
    }
    fun submit() {
        val frozenChallenge = challenge
        if (manualCookie) {
            val value = cookie.trim()
            if (value.isBlank() || value.any(Char::isWhitespace) || '=' in value || ';' in value) { error = "请输入 MUSIC_U 的值"; return }
            run(true) { check(model.loginWithCookie(value)) { "验证失败，请检查后重试" } }
        } else if (frozenChallenge != null) run(true) { model.loginWithUpSms(frozenChallenge) }
        else if (passwordMode) { val value = secret; run(true) { model.loginWithMobilePassword(phone, country, value) } }
        else { val value = code; run(true) { model.loginWithMobileSms(phone, country, value) } }
    }
    LaunchedEffect(draft, draft.cooldownUntilMs) {
        now = android.os.SystemClock.elapsedRealtime()
        while (draft.cooldownRemainingSeconds(now) > 0) {
            delay(minOf(1000L, (draft.cooldownUntilMs - now).coerceAtLeast(1L)))
            now = android.os.SystemClock.elapsedRealtime()
        }
    }
    DisposableEffect(vm) { onDispose { vm.textEditing.value = false; keyboard?.hide(); focus.clearFocus() } }
    BindAccountInput(input) { command -> if (command == ThorCommand.Back) {
        when { busy -> true; vm.textEditing.value -> { endEdit(); true }; challenge != null -> { challenge = null; true }; else -> false }
    } else false }
    val keys = if (busy) emptyList() else if (manualCookie) listOf("account:cookie", "account:submit")
        else if (challenge != null) listOf("account:copy:number", "account:copy:content", "account:submit", "account:challenge:cancel")
        else buildList { add("account:country"); add("account:phone"); add("account:method"); add(if (passwordMode) "account:password" else "account:code"); if (!passwordMode) add("account:send"); add("account:submit") }
    BindThorOptionFocus(vm, registry, keys, ThorFocusLayout.Spatial,
        preferredKey = if (manualCookie) "account:cookie" else if (challenge != null) "account:copy:number" else "account:phone")
    val theme = LocalThorTheme.current
    Column(Modifier.fillMaxSize()) {
        ThorAccountHeader(if (manualCookie) "Web Cookie 登录" else "手机号登录", registry, { if (!busy) { endEdit(); if (challenge != null) challenge = null else back() } })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.accent)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (manualCookie) AccountField(vm, registry, fieldEditing, "account:cookie", "MUSIC_U", cookie, { cookie = it }, busy, secret = true)
            else if (challenge != null) {
                val current = challenge!!
                Text("请使用登录手机号发送短信", color = theme.text, fontSize = 15.sp)
                Text("发送至 ${current.destination}", color = theme.secondary, fontSize = 14.sp)
                Text(current.content, color = theme.text, fontSize = 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThorButton(registry, "account:copy:number", "复制号码", Modifier.weight(1f), enabled = !busy) { copyAccountText(context, current.destination); vm.showMessage("已复制号码") }
                    ThorButton(registry, "account:copy:content", "复制内容", Modifier.weight(1f), enabled = !busy) { copyAccountText(context, current.content); vm.showMessage("已复制内容") }
                }
                ThorButton(registry, "account:challenge:cancel", "重新获取", Modifier.fillMaxWidth(), enabled = !busy) { challenge = null }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(100.dp)) { AccountField(vm, registry, fieldEditing, "account:country", "区号", country, { country = it.filter(Char::isDigit).take(4) }, busy, keyboardType = KeyboardType.Phone) }
                    Box(Modifier.weight(1f)) { AccountField(vm, registry, fieldEditing, "account:phone", "手机号", phone, { phone = it.filter(Char::isDigit).take(20) }, busy, keyboardType = KeyboardType.Phone) }
                }
                ThorButton(registry, "account:method", if (passwordMode) "使用短信验证码" else "使用密码登录", Modifier.fillMaxWidth(), enabled = !busy, flat = true) { endEdit(); passwordMode = !passwordMode; error = null }
                if (passwordMode) AccountField(vm, registry, fieldEditing, "account:password", "密码", secret, { secret = it }, busy, secret = true)
                else AccountField(vm, registry, fieldEditing, "account:code", "验证码", code, { code = it.filter(Char::isDigit).take(8) }, busy, keyboardType = KeyboardType.Number)
                if (!passwordMode) ThorButton(registry, "account:send", if (wait > 0) "${wait}s 后重新获取" else "获取验证码", Modifier.fillMaxWidth(), enabled = !busy && wait == 0 && phone.isNotBlank()) {
                    val owner = activity
                    val token = lowerView.windowToken
                    if (owner == null || token == null) error = "登录窗口不可用，请返回重试" else run {
                        val host = CaptchaUiHost(context, token) { event ->
                            if (event.keyCode == android.view.KeyEvent.KEYCODE_BUTTON_START) {
                                owner.dispatchKeyEvent(event)
                                true
                            } else false
                        }
                        model.requestMobileSmsCode(owner, phone, country, host)
                        draft.startCooldown(android.os.SystemClock.elapsedRealtime())
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            ThorButton(registry, "account:submit", if (busy) "登录中…" else if (challenge != null) "已发送，继续登录" else "登录",
                Modifier.fillMaxWidth(), primary = true, enabled = !busy, onClick = ::submit)
        }
    }
}

@UnstableApi
@Composable
private fun AccountField(vm: ThorShellViewModel, registry: ThorFocusRegistry, editing: ThorAccountEditingSession, key: String, label: String,
    value: String, onValue: (String) -> Unit, busy: Boolean, secret: Boolean = false,
    keyboardType: KeyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
) {
    ThorAccountField(registry, key, label, value, onValue, enabled = !busy, secret = secret,
        keyboardType = keyboardType, onEditingChange = { vm.textEditing.value = it }, editingSession = editing)
}

@UnstableApi
@Composable
private fun AccountCodeForm(vm: ThorShellViewModel, registry: ThorFocusRegistry, input: ThorAccountInputHost?, busy: Boolean,
    code: String, onCode: (String) -> Unit, error: String?, cancel: () -> Unit, apply: () -> Unit,
    sendLabel: String, send: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldEditing = remember { ThorAccountEditingSession() }
    BindThorOptionFocus(vm, registry, if (busy) emptyList() else listOf("account:verify:code", "account:verify:send", "account:verify:apply", "account:verify:cancel"), ThorFocusLayout.Spatial)
    DisposableEffect(vm) { onDispose { vm.textEditing.value = false; keyboard?.hide(); focus.clearFocus() } }
    BindAccountInput(input) { command -> if (command == ThorCommand.Back) {
        if (vm.textEditing.value) { fieldEditing.finish(); focus.clearFocus(); keyboard?.hide(); vm.textEditing.value = false }
        else if (!busy) cancel()
        true
    } else false }
    Column(Modifier.fillMaxSize()) {
        ThorAccountHeader("安全验证", registry, { if (!busy) cancel() })
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AccountField(vm, registry, fieldEditing, "account:verify:code", "验证码", code, onCode, busy, keyboardType = KeyboardType.Number)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            ThorButton(registry, "account:verify:send", sendLabel, Modifier.fillMaxWidth(), enabled = !busy, onClick = send)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThorButton(registry, "account:verify:cancel", "取消", Modifier.weight(1f), enabled = !busy, onClick = cancel)
                ThorButton(registry, "account:verify:apply", if (busy) "处理中…" else "确认", Modifier.weight(1f), enabled = !busy, primary = true, onClick = apply)
            }
        }
    }
}

private fun copyAccountText(context: Context, value: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("短信验证", value))
}

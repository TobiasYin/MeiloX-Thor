package com.ljyh.mei.thor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.playback.SleepTimer
import com.ljyh.mei.thor.ThorShellViewModel
import com.ljyh.mei.thor.menu.ThorMenuAnchor
import com.ljyh.mei.thor.model.*
import com.ljyh.mei.thor.playback.ThorPlaybackState
import com.ljyh.mei.thor.state.*

/** UI commands only. The shell owns presentation and invokes a row after closing its menu. */
internal data class ThorPageAction(
    val id: String,
    val title: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** Composition-local callbacks such as a list binding or an IME request never enter a ViewModel. */
internal class ThorPageActionBindings {
    private class Binding(val owner: Any, var action: () -> Unit)
    private val bindings = mutableMapOf<String, Binding>()
    var version by mutableIntStateOf(0)
        private set

    fun register(owner: Any, id: String, action: () -> Unit) {
        val previous = bindings[id]
        if (previous?.owner === owner) previous.action = action
        else { bindings[id] = Binding(owner, action); version++ }
    }

    fun remove(owner: Any) {
        if (bindings.entries.removeAll { it.value.owner === owner }) version++
    }

    fun has(id: String): Boolean { version; return id in bindings }
    fun invoke(id: String): Boolean {
        val action = bindings[id]?.action ?: return false
        action()
        return true
    }
}

@Composable
internal fun BindThorPageAction(bindings: ThorPageActionBindings?, id: String, action: () -> Unit) {
    val owner = remember(bindings, id) { Any() }
    SideEffect { bindings?.register(owner, id, action) }
    DisposableEffect(bindings, owner) { onDispose { bindings?.remove(owner) } }
}

/** Small option pages explicitly opt into focus; headers, miniature player and docks stay out. */
@Composable
@UnstableApi
internal fun BindThorOptionFocus(
    vm: ThorShellViewModel,
    registry: ThorFocusRegistry,
    keys: List<String>,
    layout: ThorFocusLayout = ThorFocusLayout.Spatial,
    preferredKey: String? = keys.firstOrNull(),
) {
    val scene = vm.navigation.value.sceneKey
    val owner = remember(registry, scene) { Any() }
    SideEffect { registry.installScope(owner, ThorFocusScope("$scene:options", keys, layout)) }
    DisposableEffect(registry, owner) { onDispose { registry.removeScope(owner) } }
    LaunchedEffect(scene, keys, preferredKey) {
        withFrameNanos { }
        val remembered = vm.rememberedFocus(scene)?.takeIf { it in keys }
        registry.request(remembered ?: preferredKey) || registry.requestFirst()
    }
}

/** Every action shares the production button's business entry point and enabled condition. */
@UnstableApi
internal fun thorPageActions(
    vm: ThorShellViewModel,
    navigation: ThorNavigation,
    library: ThorLibraryState,
    playback: ThorPlaybackState,
    preferences: ThorPreferences,
    onLegacyRoute: (String) -> Unit,
    onRequestAirflow: (Boolean) -> Unit,
    onCalibrateAirflow: () -> Unit,
    isScanning: Boolean,
    airflowCalibrationLabel: String = "校准",
    bindings: ThorPageActionBindings? = null,
): List<ThorPageAction> = buildList {
    val scene = navigation.sceneKey
    val accountGeneration = library.account.generation
    fun action(id: String, title: String, enabled: Boolean = true, run: () -> Unit) {
        add(ThorPageAction(id, title, enabled) {
            if (enabled && vm.navigation.value.sceneKey == scene && vm.library.value.account.generation == accountGeneration) run()
        })
    }
    fun callback(id: String, title: String, enabled: Boolean = true) {
        action(id, title, enabled && bindings?.has(id) == true) { bindings?.invoke(id) }
    }
    fun searchAndMy() {
        action("header:search", "搜索") { vm.push(ThorPage.Search) }
        action("header:my", "我的") { vm.selectTab(ThorTab.My) }
    }
    fun libraryCategories() {
        listOf("专辑" to ThorCollection.CollectedAlbums, "歌曲" to ThorCollection.LikedSongs,
            "歌单" to ThorCollection.OwnedPlaylists).forEach { (title, collection) ->
            val selected = (navigation.page as? ThorPage.Collection)?.collection.let { current ->
                current == collection || collection == ThorCollection.OwnedPlaylists && current == ThorCollection.SavedPlaylists
            }
            action("collection:category:${collection.name}", title) { if (!selected) vm.replaceCollection(collection) }
        }
    }
    fun collectionMore(item: ThorItem, id: String) {
        action(id, if (item.key is ThorItemKey.Playlist) "歌单操作" else "专辑操作") {
            vm.openMenu(ThorMenuSubject(item, id), ThorMenuAnchor(24f, 100f))
        }
    }
    if (navigation.overlay == null && bindings?.has("catalog:picker-active") == true) {
        callback("page:back", "返回")
        return@buildList
    }
    when (navigation.overlay) {
        ThorOverlay.Player -> {
            val song = playback.current
            val entry = playback.currentEntryId
            val hasSong = playback.connected && song != null && !entry.isNullOrBlank()
            val editing = vm.seekPreview.value != null
            val liked = song?.let { current ->
                if (current.isLocal) library[ThorCollection.LikedSongs].any { it.key == current.toThorItem().key }
                else library.songLikes[current.id] == true
            } == true
            action("player:like", if (liked) "取消喜欢" else "喜欢", hasSong && !editing) {
                if (vm.playback.state.value.currentEntryId == entry) vm.likeCurrent()
            }
            action("player:more", "更多歌曲操作", hasSong && !editing) {
                if (song != null && vm.playback.state.value.currentEntryId == entry) {
                    vm.openMenu(ThorMenuSubject(song.toThorItem(), "player:more", queueEntryId = entry), ThorMenuAnchor(24f, 100f))
                }
            }
            action("player:progress", "编辑进度", hasSong && playback.durationMs > 0 && !editing) {
                if (vm.playback.state.value.currentEntryId == entry) vm.beginSeek()
            }
            action("player:mode", "播放模式 · ${playback.mode}", hasSong && !editing, vm.playback::switchMode)
            action("player:lyrics", "歌词定位", hasSong && !editing, vm::showLyrics)
            action("player:timer", "定时停止", hasSong && !editing, vm::showTimer)
        }
        ThorOverlay.Queue -> callback("queue:current", "定位当前", playback.queue.any { it.entryId == playback.currentEntryId })
        ThorOverlay.Lyrics -> callback("lyrics:current", "定位当前歌词", playback.current != null)
        ThorOverlay.Timer -> {
            listOf(15, 30, 45, 60, 90).forEach { minutes ->
                action("timer:$minutes", "$minutes 分钟", playback.connected) { vm.playback.startTimer(minutes); vm.back() }
            }
            action("timer:end", "本曲结束", playback.connected && playback.current != null) { vm.playback.startTimer(SleepTimer.END_OF_TRACK); vm.back() }
            action("timer:clear", "关闭定时", playback.timerActive) { vm.playback.clearTimer(); vm.back() }
        }
        null -> when (val page = navigation.page) {
            is ThorPage.Root -> when (page.tab) {
                ThorTab.Home -> {
                    val category = vm.homeCategory.value
                    ThorHomeCategory.entries.forEach { item ->
                        action("home:category:${item.name}", item.title) { vm.selectHomeCategory(item) }
                    }
                    when (category) {
                        ThorHomeCategory.Recommended -> action("home:daily", "每日推荐") { vm.openCollection(ThorCollection.DailySongs) }
                        ThorHomeCategory.Playlists -> callback("catalog:playlist-filter", "歌单分类 · ${vm.catalogFilters.value.playlistCategory}")
                        ThorHomeCategory.Artists -> {
                            callback("catalog:artist-area", "歌手地区")
                            callback("catalog:artist-type", "歌手类型")
                        }
                        ThorHomeCategory.Charts -> Unit
                    }
                    searchAndMy()
                    action("root:refresh", "刷新") { vm.refresh(category.collection) }
                }
                ThorTab.Discover -> {
                    searchAndMy()
                    action("root:refresh", "刷新") { vm.refresh(ThorCollection.Discover) }
                }
                ThorTab.Library -> {
                    listOf("专辑" to 1, "歌曲" to 0, "歌单" to 2).forEach { (title, category) ->
                        action("library:category:$category", title) { vm.selectLibraryCategory(category) }
                    }
                    searchAndMy()
                    if (vm.libraryCategory.value == 2) action("library:create", "新建歌单", run = vm::createPlaylist)
                    else action("library:import", if (isScanning) "停止扫描" else "导入音乐") { onLegacyRoute("local_music") }
                }
                ThorTab.My -> {
                    action("my:account", if (library.account.signedIn) "我的账号" else "登录网易云音乐") {
                        vm.openInternalRoute(if (library.account.signedIn) "account/home" else "account/login")
                    }
                    action("my:audio", "播放设置") { vm.push(ThorPage.AudioSettings) }
                    searchAndMy()
                }
            }
            is ThorPage.Collection -> {
                val collection = page.collection
                if (collection in setOf(ThorCollection.CollectedAlbums, ThorCollection.LikedSongs, ThorCollection.OwnedPlaylists, ThorCollection.SavedPlaylists)) libraryCategories()
                if (collection == ThorCollection.OwnedPlaylists) action("collection:create", "新建歌单", run = vm::createPlaylist)
                if (collection == ThorCollection.Downloads) action("collection:downloads", "管理下载") { onLegacyRoute("download_manage") }
                val local = collection in setOf(ThorCollection.LocalSongs, ThorCollection.LocalAlbums)
                if (local) action("collection:scan", if (isScanning) "停止扫描" else "扫描音乐") { onLegacyRoute("local_music") }
                action("collection:refresh", "刷新") { vm.refresh(collection) }
                if (collection == ThorCollection.DailySongs && !library.account.signedIn)
                    action("collection:login", "登录网易云音乐") { vm.openInternalRoute("account/login") }
                if (collection in setOf(ThorCollection.LocalSongs, ThorCollection.LikedSongs, ThorCollection.Downloads, ThorCollection.History, ThorCollection.DailySongs)) {
                    val enabled = library[collection].any { it.tracks.isNotEmpty() }
                    action("collection:shuffle", "L3 随机播放", enabled) { vm.playPage(true) }
                    action("collection:play", "R3 播放全部", enabled) { vm.playPage(false) }
                }
            }
            is ThorPage.Artist -> {
                val artist = vm.artistBrowse.value?.takeIf { it.item.key == page.item.key }
                ThorArtistSection.entries.forEach { section ->
                    action("artist:section:${section.name}", section.title) { vm.selectArtistSection(section) }
                }
                action("artist:refresh", "刷新", artist?.isLoading != true, vm::refreshArtist)
                if (artist?.section != ThorArtistSection.Albums) {
                    val enabled = artist?.items?.any { it.tracks.isNotEmpty() } == true
                    action("artist:shuffle", if (artist?.hasMore == true) "L3 随机已加载" else "L3 随机播放", enabled) { vm.playPage(true) }
                    action("artist:play", if (artist?.hasMore == true) "R3 播放已加载" else "R3 播放全部", enabled) { vm.playPage(false) }
                }
            }
            is ThorPage.Detail -> {
                collectionMore(page.item, "detail:more")
                action("detail:shuffle", "L3 随机播放", page.item.tracks.isNotEmpty()) { vm.playPage(true) }
                action("detail:play", "R3 播放全部", page.item.tracks.isNotEmpty()) { vm.playPage(false) }
                if (page.item.tracks.isEmpty()) action("detail:reload", "重新加载", !vm.detailLoading.value) { vm.open(page.item) }
            }
            ThorPage.Search -> {
                callback("search:edit", "编辑搜索词")
                callback("search:submit", if (vm.search.value.error != null) "重试搜索" else "搜索", vm.search.value.query.isNotBlank())
            }
            ThorPage.Appearance -> {
                ThorThemePack.entries.forEach { pack -> action("theme:${pack.name}", "材质 · ${pack.title}") { vm.updatePreferences { it.copy(theme = pack) } } }
                ThorAppearance.entries.forEach { appearance -> action("appearance:${appearance.name}", "显示模式 · ${appearance.title}") { vm.updatePreferences { it.copy(appearance = appearance) } } }
                ThorBackgroundMode.entries.forEach { mode -> action("background:mode:${mode.name}", "播放背景 · ${mode.title}") { vm.updatePreferences { it.copy(backgroundMode = mode) } } }
                ThorBackgroundIntensity.entries.forEach { intensity -> action("background:intensity:${intensity.name}", "背景强度 · ${intensity.title}", preferences.backgroundMode != ThorBackgroundMode.Clean) { vm.updatePreferences { it.copy(backgroundIntensity = intensity) } } }
                action("appearance:motion", "减少动态 · ${if (preferences.reduceMotion) "开启" else "关闭"}") { vm.updatePreferences { it.copy(reduceMotion = !it.reduceMotion) } }
                action("appearance:transparency", "减少透明 · ${if (preferences.reduceTransparency) "开启" else "关闭"}") { vm.updatePreferences { it.copy(reduceTransparency = !it.reduceTransparency) } }
            }
            ThorPage.Controls -> {
                action("controls:airflow", "吹气 · ${if (preferences.airflowEnabled) "开启" else "关闭"}") { onRequestAirflow(!preferences.airflowEnabled) }
                action("controls:calibrate", airflowCalibrationLabel, preferences.airflowEnabled, onCalibrateAirflow)
            }
            is ThorPage.Account -> addAll(thorAccountPageActions(page.route, bindings))
            ThorPage.AudioSettings -> callback("audio:equalizer", "均衡器")
            ThorPage.Equalizer -> callback("equalizer:reset", "恢复平直")
        }
    }
}

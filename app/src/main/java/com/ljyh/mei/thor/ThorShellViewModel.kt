package com.ljyh.mei.thor

import android.os.SystemClock
import com.ljyh.mei.thor.input.ThorExitGuard
import com.ljyh.mei.thor.input.ThorExitDecision

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.constants.AppAppearance
import com.ljyh.mei.constants.AppAppearanceKey
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.constants.ReduceTransparencyKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.thor.data.ThorMusicRepository
import com.ljyh.mei.thor.menu.*
import com.ljyh.mei.thor.model.*
import com.ljyh.mei.thor.playback.ThorPlaybackBridge
import com.ljyh.mei.thor.playback.ThorPlaybackDecision
import com.ljyh.mei.thor.playback.thorBulkPlayback
import com.ljyh.mei.thor.playback.thorSongTrackIndex
import com.ljyh.mei.thor.state.*
import com.ljyh.mei.thor.theme.decodeThorPreferences
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.lyric.LyricManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicLong

private val thorNoticeIds = AtomicLong()
data class ThorNotice(val message: String, val undoToken: String? = null, val success: Boolean = true,
    val id: Long = thorNoticeIds.incrementAndGet())
data class ThorSearchState(val query: String = "", val items: List<ThorItem> = emptyList(), val loading: Boolean = false,
    val error: String? = null, val searched: Boolean = false, val category: ThorSearchCategory = ThorSearchCategory.All,
    val failedCategories: Set<ThorSearchCategory> = emptySet()) {
    val visibleItems get() = thorVisibleSearchResults(items, category)
}

/** Screen-independent navigation and management. Neither composition owns business state. */
@UnstableApi
@HiltViewModel
class ThorShellViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ThorMusicRepository,
    private val savedState: SavedStateHandle,
    private val lyricManager: LyricManager,
) : ViewModel() {
    val library = repository.state
    val playback = ThorPlaybackBridge(viewModelScope) { showMessage(it, false) }
    private val mutablePlayerConnection = MutableStateFlow<PlayerConnection?>(null)
    val playerConnection = mutablePlayerConnection.asStateFlow()
    val lyrics = lyricManager.lyricData
    private val mutableNavigation = MutableStateFlow(ThorNavigation(tab = ThorTab.entries.firstOrNull { it.name == savedState.get<String>("thor.tab") } ?: ThorTab.Home))
    val navigation = mutableNavigation.asStateFlow()
    private val mutableHomeCategory = MutableStateFlow(ThorHomeCategory.entries.firstOrNull {
        it.name == savedState.get<String>("thor.homeCategory")
    } ?: ThorHomeCategory.Recommended)
    val homeCategory = mutableHomeCategory.asStateFlow()
    val catalogFilters = repository.catalogFilters
    private val artistBrowser = ThorArtistBrowser(viewModelScope, repository::artistProfile,
        repository::artistSongs, repository::artistAlbums)
    val artistBrowse = artistBrowser.current

    fun selectHomeCategory(category: ThorHomeCategory) {
        if (navigation.value.page != ThorPage.Root(ThorTab.Home) || navigation.value.overlay != null || menu.value != null) return
        clearExitGuard()
        if (category != homeCategory.value) {
            freshScene("root:Home:${category.name}")
            mutablePreview.value = null
            mutableHomeCategory.value = category
            savedState["thor.homeCategory"] = category.name
        }
        ensureHomeCategory()
    }

    private fun ensureHomeCategory() {
        val collection = homeCategory.value.collection
        if (collection !in library.value.collections && collection !in library.value.loading)
            repository.refresh(collection)
    }

    fun setPlaylistCategory(category: String) {
        clearExitGuard()
        mutablePreview.value = null
        freshScene("root:Discover")
        repository.setCatalogFilters(catalogFilters.value.copy(playlistCategory = category))
    }
    fun setArtistArea(area: Int) {
        clearExitGuard(); mutablePreview.value = null; freshScene("root:Home:Artists")
        repository.setCatalogFilters(catalogFilters.value.copy(artistArea = area))
    }
    fun setArtistType(type: Int) {
        clearExitGuard(); mutablePreview.value = null; freshScene("root:Home:Artists")
        repository.setCatalogFilters(catalogFilters.value.copy(artistType = type))
    }
    fun loadMore(collection: ThorCollection) = repository.loadMore(collection)
    fun selectArtistSection(section: ThorArtistSection) {
        val page = navigation.value.page as? ThorPage.Artist ?: return
        if (navigation.value.overlay != null || menu.value != null) return
        clearExitGuard()
        if (artistBrowse.value?.section != section) {
            freshScene("${page.key}:${section.name}")
            mutablePreview.value = page.item
        }
        artistBrowser.select(section)
    }
    fun refreshArtist() = artistBrowser.refresh()
    fun loadMoreArtist() = artistBrowser.loadMore()

    /** Horizontal commands change categories directly; the controller never enters a tab. */
    fun cycleBrowseCategory(delta: Int): Boolean {
        if (navigation.value.overlay != null || menu.value != null || textEditing.value || delta == 0) return false
        when (navigation.value.page) {
            ThorPage.Root(ThorTab.Home) -> {
                val categories = ThorHomeCategory.entries
                selectHomeCategory(categories[(homeCategory.value.ordinal + delta).mod(categories.size)])
            }
            is ThorPage.Artist -> {
                val sections = ThorArtistSection.entries
                val section = artistBrowse.value?.section ?: return false
                selectArtistSection(sections[(section.ordinal + delta).mod(sections.size)])
            }
            ThorPage.Search -> {
                val categories = ThorSearchCategory.entries
                selectSearchCategory(categories[(search.value.category.ordinal + delta).mod(categories.size)])
            }
            else -> return false
        }
        return true
    }
    private val mutableLibraryCategory = MutableStateFlow((savedState.get<Int>("thor.libraryCategory") ?: 0).coerceIn(0, 2))
    val libraryCategory = mutableLibraryCategory.asStateFlow()
    fun selectLibraryCategory(category: Int) {
        val selection = category.coerceIn(0, 2)
        clearExitGuard()
        if (selection != mutableLibraryCategory.value) freshScene("root:Library")
        mutableLibraryCategory.value = selection
        savedState["thor.libraryCategory"] = selection
    }
    private val mutablePreview = MutableStateFlow<ThorItem?>(null)
    val preview = mutablePreview.asStateFlow()
    private val mutableMenu = MutableStateFlow<ThorMenuState?>(null)
    val menu = mutableMenu.asStateFlow()
    private val mutableNotice = MutableStateFlow<ThorNotice?>(null)
    val notice = mutableNotice.asStateFlow()
    private val mutableSearch = MutableStateFlow(ThorSearchState())
    val search = mutableSearch.asStateFlow()
    private val mutableSearchEntry = MutableStateFlow(0L)
    val searchEntry = mutableSearchEntry.asStateFlow()
    private var searchJob: Job? = null
    private var searchRequestGeneration = 0L
    private var detailJob: Job? = null
    private var detailGeneration = 0L
    private var playRequestGeneration = 0L
    private var menuGeneration = 0L
    private var seekEntryId: String? = null
    private val mutableDetailLoading = MutableStateFlow(false)
    val detailLoading = mutableDetailLoading.asStateFlow()
    private val mutableSeek = MutableStateFlow<Long?>(null)
    val seekPreview = mutableSeek.asStateFlow()
    private val shoulderInput = ThorPlayerShoulderState()
    private val quickPlayerHistory = ThorQuickPlayerHistory()
    val textEditing = MutableStateFlow(false)
    private val focus = mutableMapOf<String, String>()
    private val listScroll = mutableMapOf<String, Pair<String, Int>>()
    private var handledAccountGeneration = -1L
    private val exitGuard = ThorExitGuard()
    private var exitResetJob: Job? = null

    fun clearExitGuard() {
        exitGuard.clear()
        exitResetJob?.cancel()
        exitResetJob = null
        if (notice.value?.message == "再按一次 B 退出") mutableNotice.value = null
    }

    private fun freshScene(scene: String) {
        focus.keys.removeAll { it == scene || it.startsWith("$scene:") }
        listScroll.keys.removeAll { it == scene || it.startsWith("$scene:") }
    }

    private fun decodePreferences(prefs: Preferences) = decodeThorPreferences(prefs)

    val preferences = context.dataStore.data.map(::decodePreferences)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThorPreferences())

    init {
        viewModelScope.launch {
            playback.state.map { it.current }.distinctUntilChangedBy { it?.let { song -> song.id to song.isLocal } }.collect { metadata ->
                metadata?.let { lyricManager.loadLyrics(it) }
            }
        }
        viewModelScope.launch {
            playback.state.map { it.currentEntryId }.distinctUntilChanged().collect { entry ->
                if (seekPreview.value != null && entry != seekEntryId) cancelSeek()
            }
        }
        viewModelScope.launch {
            library.map { it.account.generation }.distinctUntilChanged().collect(::applyAccountGeneration)
        }
        viewModelScope.launch {
            library.collect { state ->
                val current = menu.value ?: return@collect
                mutableMenu.value = current.syncPlaylistTargets(playlistKeys(current, state))
            }
        }
        viewModelScope.launch {
            library.collect { state ->
                val detail = navigation.value.page as? ThorPage.Detail ?: return@collect
                val collection = when (detail.item.key) {
                    is ThorItemKey.Album -> ThorCollection.CollectedAlbums
                    is ThorItemKey.Playlist -> if (!detail.item.owned) ThorCollection.SavedPlaylists else return@collect
                    else -> return@collect
                }
                if (state.collections.containsKey(collection) && collection !in state.loading && state.errors[collection] == null) {
                    val collected = state[collection].any { it.key == detail.item.key }
                    if (collected != detail.item.collected) navigate(navigation.value.replaceDetail(detail.item.copy(collected = collected)))
                }
            }
        }
    }

    fun attachPlayer(connection: PlayerConnection?) {
        mutablePlayerConnection.value = connection
        playback.attach(connection)
    }
    fun rememberFocus(scene: String, key: String) { focus[scene] = key }
    fun rememberedFocus(scene: String): String? = focus[scene]
    fun rememberListScroll(scene: String, firstKey: String, offset: Int) { listScroll[scene] = firstKey to offset }
    fun rememberedListScroll(scene: String): Pair<String, Int>? = listScroll[scene]
    fun preview(item: ThorItem) { mutablePreview.value = item }
    fun clearPreview() { mutablePreview.value = null }
    fun clearNotice() { mutableNotice.value = null }
    fun clearNoticeIfCurrent(id: Long) { mutableNotice.update { current -> current?.takeUnless { it.id == id } } }
    fun showMessage(message: String, success: Boolean = true) { mutableNotice.value = ThorNotice(message, success = success) }
    fun refresh(collection: ThorCollection? = null) = repository.refresh(collection)

    private fun navigate(value: ThorNavigation) {
        clearExitGuard()
        if (value.overlay != ThorOverlay.Player) cancelSeek()
        if (value.sceneKey != mutableNavigation.value.sceneKey) mutablePreview.value = null
        mutableNavigation.value = value
        savedState["thor.tab"] = value.tab.name
        (value.page as? ThorPage.Artist)?.let { artistBrowser.open(it.item) }
        if (value.page == ThorPage.Root(ThorTab.Home)) ensureHomeCategory()
        if (value.page == ThorPage.Root(ThorTab.Discover) && ThorCollection.Discover !in library.value.collections &&
            ThorCollection.Discover !in library.value.loading) repository.refresh(ThorCollection.Discover)
    }

    fun switchTab(delta: Int) { if (menu.value == null) navigate(navigation.value.switchTab(delta)) }
    fun selectTab(tab: ThorTab) { if (navigation.value.isRoot && menu.value == null) navigate(navigation.value.copy(tab = tab)) }
    fun push(page: ThorPage) {
        if (navigation.value.page.key != page.key) freshScene(page.key)
        navigate(navigation.value.push(page))
    }
    fun openInternalRoute(route: String): Boolean {
        val page = thorInternalPageForRoute(route) ?: return false
        push(page)
        return true
    }
    fun captureAccountTicket(account: ThorAccountState) = repository.captureAccountTicket(account)
    fun completeAccountLogin() {
        textEditing.value = false
        val callerTab = navigation.value.tab
        viewModelScope.launch {
            try {
                val account = kotlinx.coroutines.withTimeout(5_000) { repository.awaitCurrentAccountProjection() }
                applyAccountGeneration(account.generation)
                if (navigation.value.tab == callerTab) navigate(navigation.value.completeAccountLogin())
                repository.refresh()
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                showMessage("账号状态同步超时，请重试", false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { showMessage("账号状态同步失败，请重试", false) }
        }
    }
    private fun applyAccountGeneration(generation: Long) {
        if (generation == handledAccountGeneration) return
        handledAccountGeneration = generation
        playback.invalidateAccountAvailability()
        clearExitGuard()
        mutableMenu.value = null
        quickPlayerHistory.clear()
        cancelSeek()
        mutableNotice.value = null
        menuGeneration++
        mutablePreview.value = null
        mutableSearch.value = ThorSearchState()
        searchRequestGeneration++
        searchJob?.cancel()
        detailJob?.cancel()
        artistBrowser.invalidate()
        detailGeneration++
        playRequestGeneration++
        focus.clear()
        listScroll.clear()
        navigate(navigation.value.forChangedAccount())
    }
    fun togglePlayer() {
        if (menu.value != null || seekPreview.value != null || navigation.value.overlay != ThorOverlay.Player) showPlayer()
        else if (!restoreQuickPlayer()) navigate(navigation.value.toggle(ThorOverlay.Player))
    }
    fun showPlayer() {
        val target = quickPlayerHistory.enter(navigation.value, menu.value, library.value.account.generation)
        mutableMenu.value = null
        menuGeneration++
        textEditing.value = false
        cancelSeek()
        navigate(target)
    }
    private fun restoreQuickPlayer(): Boolean {
        val restored = quickPlayerHistory.leave(navigation.value, library.value.account.generation) ?: return false
        cancelSeek()
        navigate(restored.navigation)
        menuGeneration++
        mutableMenu.value = restored.menu?.let { draft -> draft.syncPlaylistTargets(playlistKeys(draft, library.value)) }
        restored.menu?.subject?.item?.let { mutablePreview.value = it }
        return true
    }
    fun toggleQueue() {
        if (menu.value != null) return
        if (navigation.value.overlay != ThorOverlay.Queue) freshScene("overlay:Queue")
        navigate(navigation.value.toggle(ThorOverlay.Queue))
    }
    fun showLyrics() {
        freshScene("overlay:Lyrics")
        navigate(navigation.value.copy(overlays = navigation.value.overlays + ThorOverlay.Lyrics))
    }
    fun showTimer() {
        freshScene("overlay:Timer")
        navigate(navigation.value.copy(overlays = navigation.value.overlays + ThorOverlay.Timer))
    }
    fun showQuality() {
        if (!playback.state.value.connected ||
            navigation.value.overlay == ThorOverlay.Quality) return
        freshScene("overlay:Quality")
        navigate(navigation.value.copy(overlays = navigation.value.overlays + ThorOverlay.Quality))
    }
    fun selectQuality(quality: MusicQuality) {
        if (navigation.value.overlay != ThorOverlay.Quality) return
        if (playback.changeQuality(quality)) back()
        else showMessage("正在连接播放器", false)
    }

    fun back(): Boolean {
        val menu = menu.value
        if (menu != null) {
            clearExitGuard()
            mutableMenu.value = if (menu.busy) null else menu.back()?.syncPlaylistTargets(playlistKeys(menu, library.value))
            if (mutableMenu.value == null) menuGeneration++
            return true
        }
        if (mutableSeek.value != null) { clearExitGuard(); cancelSeek(); return true }
        if (restoreQuickPlayer()) return true
        val previous = navigation.value.back()
        if (previous != null) { navigate(previous); return true }
        if (navigation.value.tab != ThorTab.Home) {
            navigate(navigation.value.copy(tab = ThorTab.Home,
                stacks = navigation.value.stacks - ThorTab.Home))
            return true
        }
        return when (exitGuard.onBack(SystemClock.uptimeMillis())) {
            ThorExitDecision.Exit -> { clearExitGuard(); false }
            ThorExitDecision.IgnoreRepeat -> true
            ThorExitDecision.Armed -> {
                showMessage("再按一次 B 退出")
                exitResetJob?.cancel()
                exitResetJob = viewModelScope.launch {
                    kotlinx.coroutines.delay(2_000)
                    clearExitGuard()
                }
                true
            }
        }
    }

    fun openCollection(collection: ThorCollection) { push(ThorPage.Collection(collection)); repository.refresh(collection) }
    fun replaceCollection(collection: ThorCollection) {
        val target = ThorPage.Collection(collection)
        if (navigation.value.page.key != target.key) freshScene(target.key)
        navigate(navigation.value.replaceCollection(collection))
        repository.refresh(collection)
    }
    fun open(item: ThorItem) {
        when (val key = item.key) {
            is ThorItemKey.Song -> {
                val generation = ++playRequestGeneration
                if (item.tracks.isNotEmpty()) playExactSong(item, key)
                else {
                    val accountGeneration = library.value.account.generation
                    viewModelScope.launch {
                        try {
                            val loaded = repository.load(item)
                            if (generation == playRequestGeneration && accountGeneration == library.value.account.generation) playExactSong(loaded, key)
                        } catch (cancellation: CancellationException) { throw cancellation }
                        catch (error: Exception) {
                            if (generation == playRequestGeneration && accountGeneration == library.value.account.generation)
                                showMessage(error.message ?: "歌曲读取失败", false)
                        }
                    }
                }
            }
            is ThorItemKey.QueueEntry -> selectQueueEntry(key.entryId)
            is ThorItemKey.Artist -> {
                push(ThorPage.Artist(item))
                mutablePreview.value = item
            }
            is ThorItemKey.Destination -> ThorCollection.entries.firstOrNull { it.name == key.id }?.let(::openCollection)
            else -> {
                push(ThorPage.Detail(item))
                mutablePreview.value = item
                val generation = ++detailGeneration
                detailJob?.cancel()
                detailJob = viewModelScope.launch {
                    mutableDetailLoading.value = true
                    try {
                        val loaded = repository.load(item)
                        if (generation == detailGeneration) {
                            navigate(navigation.value.replaceDetail(loaded))
                            mutablePreview.value = loaded
                        }
                    } catch (cancellation: CancellationException) { throw cancellation }
                    catch (error: Exception) { if (generation == detailGeneration) showMessage(error.message ?: "加载失败，请重试", false) }
                    finally { if (generation == detailGeneration) mutableDetailLoading.value = false }
                }
            }
        }
    }

    private fun playExactSong(item: ThorItem, key: ThorItemKey.Song) {
        val index = thorSongTrackIndex(item.tracks, key.id, key.local)
        if (index < 0) {
            showMessage(if (item.tracks.isEmpty()) "没有可播放的歌曲" else "歌曲信息已变化，请重新选择", false)
            return
        }
        play(item.tracks, index, item.description.ifBlank { item.title })
    }

    fun play(tracks: List<MediaMetadata>, index: Int = 0, title: String = "播放列表", shuffle: Boolean? = null) {
        playRequestGeneration++
        if (playback.play(tracks, index, title, shuffle)) showPlayer()
    }

    fun selectQueueEntry(entryId: String): Boolean {
        playRequestGeneration++
        if (!playback.select(entryId)) return false
        // QuickPlayerHistory collapses Q/P into one player while retaining the browsing return.
        showPlayer()
        return true
    }

    fun playPage(shuffle: Boolean): Boolean {
        if (menu.value != null || seekPreview.value != null || textEditing.value) return false
        val target = thorPagePlayback(navigation.value, library.value, artistBrowse.value) ?: return false
        when (val decision = thorBulkPlayback(target.tracks, shuffle)) {
            is ThorPlaybackDecision.Rejected -> {
                playRequestGeneration++
                showMessage(decision.message, false)
            }
            is ThorPlaybackDecision.Accepted -> play(decision.selection.tracks, decision.selection.index, target.title, shuffle)
        }
        return true
    }

    fun playFromLyric(positionMs: Long, expectedEntryId: String?) {
        val current = playback.state.value
        if (!current.connected || current.current == null || expectedEntryId.isNullOrBlank() ||
            current.currentEntryId != expectedEntryId
        ) return
        cancelSeek()
        playback.seekAndPlay(positionMs, expectedEntryId)
    }

    fun handleShoulder(right: Boolean, phase: ThorShoulderPhase) {
        if (navigation.value.isRoot && menu.value == null && !textEditing.value) {
            if (phase == ThorShoulderPhase.Press) switchTab(if (right) 1 else -1)
            return
        }
        val current = playback.state.value
        val available = navigation.value.overlay == ThorOverlay.Player && menu.value == null && !textEditing.value &&
            current.connected && current.currentEntryId != null && current.durationMs > 0
        when (shoulderInput.accept(right, phase, current.currentEntryId, available)) {
            ThorShoulderSeekAction.BackwardTap, ThorShoulderSeekAction.ForwardTap -> {
                val delta = if (right) 15_000L else -15_000L
                if (seekPreview.value != null) adjustSeek(delta)
                else playback.seek((playback.position() + delta).coerceIn(0, current.durationMs))
            }
            ThorShoulderSeekAction.Begin -> if (seekPreview.value == null) beginSeek()
            ThorShoulderSeekAction.BackwardStep -> adjustSeek(-500)
            ThorShoulderSeekAction.ForwardStep -> adjustSeek(500)
            ThorShoulderSeekAction.Apply -> applySeek()
            ThorShoulderSeekAction.Cancel -> clearSeekPreview()
            ThorShoulderSeekAction.None -> Unit
        }
    }

    fun setSearchQuery(query: String) {
        if (query == search.value.query) return
        searchRequestGeneration++
        searchJob?.cancel()
        mutableSearch.value = search.value.copy(query = query, loading = false, error = null)
    }
    fun selectSearchCategory(category: ThorSearchCategory) {
        if (navigation.value.page != ThorPage.Search || navigation.value.overlay != null || menu.value != null) return
        if (category == search.value.category) return
        clearExitGuard()
        freshScene(thorSearchResultsScene(searchEntry.value, category))
        mutablePreview.value = null
        mutableSearch.value = search.value.copy(category = category)
    }
    fun search(query: String = search.value.query) {
        val submitted = query.trim()
        if (submitted.isEmpty()) return
        freshScene("search")
        mutableSearchEntry.value++
        val generation = ++searchRequestGeneration
        val accountGeneration = library.value.account.generation
        searchJob?.cancel()
        mutableSearch.value = ThorSearchState(query = submitted, loading = true, searched = true, category = search.value.category)
        searchJob = viewModelScope.launch {
            try {
                val response = repository.searchResults(submitted)
                val items = thorMixedSearchResults(response.items, submitted)
                if (generation == searchRequestGeneration && accountGeneration == library.value.account.generation)
                    mutableSearch.value = ThorSearchState(submitted, items, searched = true, category = search.value.category,
                        failedCategories = response.failedCategories)
            } catch (cancellation: CancellationException) { throw cancellation }
            catch (error: Exception) {
                if (generation == searchRequestGeneration && accountGeneration == library.value.account.generation)
                    mutableSearch.value = ThorSearchState(submitted, error = error.message ?: "搜索失败", searched = true, category = search.value.category)
            }
        }
    }

    fun updatePreferences(change: (ThorPreferences) -> ThorPreferences) {
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                val next = change(decodePreferences(prefs))
                prefs[ThemeKey] = next.theme.name
                prefs[AppAppearanceKey] = when (next.appearance) { ThorAppearance.System -> AppAppearance.System; ThorAppearance.Day -> AppAppearance.Light; ThorAppearance.Night -> AppAppearance.Dark }.name
                prefs[ReduceMotionKey] = next.reduceMotion
                prefs[ReduceTransparencyKey] = next.reduceTransparency
                prefs[AirflowKey] = next.airflowEnabled
                prefs[BackgroundModeKey] = next.backgroundMode.name
                prefs[BackgroundIntensityKey] = next.backgroundIntensity.name
            }
        }
    }

    fun beginSeek() { seekEntryId = playback.state.value.currentEntryId; mutableSeek.value = playback.position() }
    fun adjustSeek(deltaMs: Long) { mutableSeek.value = ((mutableSeek.value ?: playback.position()) + deltaMs).coerceIn(0, playback.state.value.durationMs) }
    fun previewSeek(positionMs: Long) { if (mutableSeek.value == null) seekEntryId = playback.state.value.currentEntryId; mutableSeek.value = positionMs.coerceIn(0, playback.state.value.durationMs) }
    fun applySeek() { if (seekEntryId == playback.state.value.currentEntryId) mutableSeek.value?.let(playback::seek); cancelSeek() }
    private fun clearSeekPreview() { mutableSeek.value = null; seekEntryId = null }
    fun cancelSeek() { shoulderInput.reset(); clearSeekPreview() }

    fun openMenu(subject: ThorMenuSubject, anchor: ThorMenuAnchor) {
        if (subject.item.key is ThorItemKey.Destination) return
        if (notice.value?.success == false) mutableNotice.value = null
        menuGeneration++
        mutableMenu.value = ThorMenuState(subject.copy(currentQueueEntry = subject.queueEntryId != null && subject.queueEntryId == playback.state.value.currentEntryId), anchor)
    }
    fun closeMenu() { mutableMenu.value = null; menuGeneration++ }
    fun moveMenu(delta: Int, repeated: Boolean = false) {
        val current = menu.value ?: return
        mutableMenu.value = if (current.level == ThorMenuLevel.Playlists) current.movePlaylist(delta, playlistKeys(current, library.value))
            else current.move(delta, if (current.level == ThorMenuLevel.Actions) menuRows().size else 2, repeated)
    }
    fun selectMenu(index: Int) {
        val current = menu.value ?: return
        if (!current.busy && current.level == ThorMenuLevel.Actions) mutableMenu.value = current.copy(selection = index)
    }
    fun activatePlaylistTarget(target: ThorPlaylistTarget) {
        val current = menu.value ?: return
        if (current.busy || current.level != ThorMenuLevel.Playlists) return
        mutableMenu.value = current.selectPlaylistTarget(target, playlistKeys(current, library.value))
        activateMenu()
    }
    fun activateDeleteChoice(choice: ThorDeleteChoice, target: ThorItemKey.Playlist) {
        val current = menu.value ?: return
        if (current.busy || current.level != ThorMenuLevel.ConfirmDelete || current.subject.item.key != target) return
        mutableMenu.value = current.selectDeleteChoice(choice, target)
        activateMenu()
    }
    fun menuRows(): List<ThorMenuRow> {
        val menu = menu.value ?: return emptyList()
        return menuRowsFor(menu)
    }
    fun playlistTargets(): List<ThorItem> {
        val current = menu.value ?: return emptyList()
        return playlistTargets(current, library.value)
    }
    private fun playlistTargets(current: ThorMenuState, state: ThorLibraryState): List<ThorItem> {
        val localTracks = current.subject.item.tracks.any { it.isLocal }
        return state[ThorCollection.OwnedPlaylists].filter { item ->
            val key = item.key as? ThorItemKey.Playlist
            key != null && (!localTracks || key.local)
        }
    }
    private fun playlistKeys(current: ThorMenuState, state: ThorLibraryState) =
        playlistTargets(current, state).map { it.key as ThorItemKey.Playlist }
    fun setMenuName(name: String) {
        val current = menu.value ?: return
        if (!current.busy && current.level == ThorMenuLevel.Name) mutableMenu.value = current.copy(name = name.take(80))
    }

    fun activateMenu() {
        val current = menu.value ?: return
        if (current.busy) return
        when (current.level) {
            ThorMenuLevel.Name -> saveMenuName()
            ThorMenuLevel.ConfirmDelete -> when (current.deleteChoice()) {
                ThorDeleteChoice.Cancel -> mutableMenu.value = current.back()
                ThorDeleteChoice.Delete -> {
                    val target = current.confirmedDeleteKey() ?: return
                    menuOperation(current, loadTracks = false) { repository.deletePlaylist(target) }
                }
                null -> Unit
            }
            ThorMenuLevel.Playlists -> {
                when (val target = current.resolvePlaylistTarget(playlistKeys(current, library.value))) {
                    ThorPlaylistTarget.Create -> {
                        if (notice.value?.success == false) mutableNotice.value = null
                        mutableMenu.value = current.intoName(false)
                    }
                    is ThorPlaylistTarget.Existing -> menuOperation(current) { item ->
                        if (target.key !in playlistKeys(current, library.value)) ThorOperationResult(false, "所选歌单已不可用，请重新选择后重试")
                        else repository.addToPlaylist(target.key, item.tracks)
                    }
                    null -> showMessage("所选歌单已不可用，请重新选择后重试", false)
                }
            }
            ThorMenuLevel.Actions -> when (menuRows().getOrNull(current.selection)?.action) {
                ThorMenuAction.Artist -> {
                    mutableMenu.value = null
                    menuGeneration++
                    open(current.subject.item)
                }
                ThorMenuAction.AddToPlaylist -> {
                    mutableMenu.value = current.intoPlaylists(playlistKeys(current, library.value))
                    repository.refresh(ThorCollection.OwnedPlaylists)
                }
                ThorMenuAction.Rename -> {
                    if (notice.value?.success == false) mutableNotice.value = null
                    mutableMenu.value = current.copy(parentSelection = current.selection).intoName(true)
                }
                ThorMenuAction.Album -> {
                    var album: ThorItem? = null
                    menuOperation(current, onSuccess = { album?.let(::open) }) { item ->
                        album = albumForMenuSong(item)
                        ThorOperationResult(true, "")
                    }
                }
                ThorMenuAction.Like -> menuOperation(current) { item -> item.tracks.firstOrNull()?.let { repository.toggleLike(it) } ?: ThorOperationResult(false, "没有可管理的歌曲") }
                ThorMenuAction.Collect -> menuOperation(current, loadTracks = false) { item -> repository.setCollected(item, !current.subject.item.collected) }
                ThorMenuAction.PlayNext -> menuOperation(current) { item -> playback.enqueue(item.tracks, true) }
                ThorMenuAction.Enqueue -> menuOperation(current) { item -> playback.enqueue(item.tracks, false) }
                ThorMenuAction.RemoveFromPlaylist -> menuOperation(current) { item -> repository.removeFromPlaylist(current.subject.sourcePlaylist!!, item.tracks) }
                ThorMenuAction.RemoveFromQueue -> menuOperation(current, loadTracks = false) { playback.remove(current.subject.queueEntryId!!) }
                ThorMenuAction.SkipCurrent -> menuOperation(current, loadTracks = false) { playback.skip(current.subject.queueEntryId!!) }
                ThorMenuAction.Delete -> {
                    if (notice.value?.success == false) mutableNotice.value = null
                    mutableMenu.value = current.intoDeleteConfirmation()
                }
                null -> Unit
            }
        }
    }

    fun saveMenuName() {
        val current = menu.value ?: return
        if (current.busy || current.level != ThorMenuLevel.Name) return
        val name = current.name.trim()
        if (name.isEmpty()) { showMessage("请输入歌单名称", false); return }
        menuOperation(current, loadTracks = !current.renaming) { item ->
            if (current.renaming) repository.renamePlaylist(item.key as ThorItemKey.Playlist, name)
            else repository.createPlaylist(name, item.tracks, local = !library.value.account.signedIn || item.tracks.any { it.isLocal })
        }
    }

    fun createPlaylist() {
        if (notice.value?.success == false) mutableNotice.value = null
        val item = ThorItem(ThorItemKey.Destination("new-playlist"), "新建歌单")
        menuGeneration++
        mutableMenu.value = ThorMenuState(ThorMenuSubject(item, "new-playlist"), ThorMenuAnchor(24f, 100f),
            level = ThorMenuLevel.Name, playlistTarget = ThorPlaylistTarget.Create)
    }

    private fun menuOperation(
        current: ThorMenuState,
        loadTracks: Boolean = true,
        onSuccess: ((ThorItem) -> Unit)? = null,
        operation: suspend (ThorItem) -> ThorOperationResult,
    ) {
        val ticket = repository.captureAccountTicket(library.value.account)
        if (ticket == null) { showMessage("账号已变化，请稍后重新操作", false); return }
        if (notice.value?.success == false) mutableNotice.value = null
        mutableMenu.value = current.copy(busy = true)
        val generation = menuGeneration
        val action = if (current.level == ThorMenuLevel.Actions) menuRowsFor(current).getOrNull(current.selection)?.action else null
        viewModelScope.launch {
            try {
                val (item, result) = repository.withAccountTicket(ticket) {
                    val subject = current.subject.item
                    val needsTracks = subject.key is ThorItemKey.Album || subject.key is ThorItemKey.Playlist ||
                        (subject.key is ThorItemKey.Song && subject.tracks.isEmpty())
                    val loaded = if (loadTracks && needsTracks) repository.load(subject) else subject
                    // B may close this menu; the accepted account ticket stays authoritative.
                    check(repository.isTicketCurrent(ticket)) { "账号已变化，请重试" }
                    loaded to operation(loaded)
                }
                if (!repository.isTicketCurrent(ticket)) return@launch
                if (result.message.isNotBlank()) mutableNotice.value = ThorNotice(result.message, result.undoToken, result.success)
                if (result.success) {
                    if (generation == menuGeneration) mutableMenu.value = null
                    if (action == ThorMenuAction.Collect) {
                        val updated = current.subject.item.copy(collected = !current.subject.item.collected)
                        navigate(navigation.value.replaceDetail(updated))
                        if (preview.value?.key == updated.key) mutablePreview.value = updated
                    }
                    if (generation == menuGeneration && current.shouldPopDeletedDetail(
                        (navigation.value.page as? ThorPage.Detail)?.item?.key, navigation.value.overlay != null)) back()
                    if (current.level == ThorMenuLevel.Name && current.renaming) navigate(navigation.value.replaceDetail(current.subject.item.copy(title = current.name.trim())))
                    if (action == ThorMenuAction.RemoveFromPlaylist) refreshOpenDetail(current.subject.sourcePlaylist)
                    onSuccess?.invoke(item)
                } else if (generation == menuGeneration) mutableMenu.value = current.syncPlaylistTargets(playlistKeys(current, library.value))
            } catch (cancellation: CancellationException) { throw cancellation }
            catch (error: Exception) {
                if (!repository.isTicketCurrent(ticket)) return@launch
                if (generation == menuGeneration) mutableMenu.value = current.syncPlaylistTargets(playlistKeys(current, library.value))
                showMessage(error.message ?: "操作失败，请重试", false)
            }
        }
    }

    private fun isLiked(item: ThorItem): Boolean {
        val key = (item.key as? ThorItemKey.Song) ?: item.tracks.firstOrNull()?.let { ThorItemKey.Song(it.id, it.isLocal) } ?: return false
        return if (key.local) library.value[ThorCollection.LikedSongs].any { it.key == key }
        else library.value.songLikes[key.id] == true
    }

    private fun menuRowsFor(menu: ThorMenuState): List<ThorMenuRow> {
        val rows = menu.actionRows(isLiked(menu.subject.item))
        // Resource cards have a real song ID before their album metadata is loaded.
        if (menu.subject.item.key !is ThorItemKey.Song || menu.subject.item.tracks.isNotEmpty() ||
            rows.any { it.action == ThorMenuAction.Album }) return rows
        val index = rows.indexOfFirst { it.action == ThorMenuAction.AddToPlaylist }.let { if (it < 0) rows.size else it + 1 }
        return rows.toMutableList().apply { add(index, ThorMenuRow(ThorMenuAction.Album, "查看专辑")) }
    }

    private fun albumForMenuSong(item: ThorItem): ThorItem {
        val song = item.tracks.firstOrNull() ?: error("没有可查看的专辑")
        if (song.isLocal) return library.value[ThorCollection.LocalAlbums].firstOrNull { album ->
            album.tracks.any { it.isLocal && it.id == song.id }
        } ?: error("本地专辑已不存在，请重新扫描")
        check(song.album.id > 0) { "没有可查看的专辑" }
        return ThorItem(ThorItemKey.Album(song.album.id), song.album.title,
            song.artists.joinToString(" / ") { it.name }, song.coverUrl)
    }

    fun undo() {
        val token = notice.value?.undoToken ?: return
        viewModelScope.launch {
            val result = if (token.startsWith("queue:")) playback.undo(token) else repository.undo(token)
            mutableNotice.value = ThorNotice(result.message, result.undoToken, result.success)
            if (result.success && !token.startsWith("queue:")) refreshOpenDetail()
        }
    }

    private suspend fun refreshOpenDetail(only: ThorItemKey.Playlist? = null) {
        val detail = navigation.value.page as? ThorPage.Detail ?: return
        if (only != null && detail.item.key != only) return
        val generation = library.value.account.generation
        try {
            val loaded = repository.load(detail.item.copy(tracks = emptyList(), trackCount = null))
            if (generation == library.value.account.generation) navigate(navigation.value.replaceDetail(loaded))
        } catch (cancellation: CancellationException) { throw cancellation }
        catch (_: Exception) { if (generation == library.value.account.generation) showMessage("操作已完成，列表刷新失败，请重试", false) }
    }

    fun likeCurrent() {
        val current = playback.state.value.current ?: return
        viewModelScope.launch {
            val result = repository.toggleLike(current)
            mutableNotice.value = ThorNotice(result.message, result.undoToken, result.success)
        }
    }


    override fun onCleared() { playback.close(); super.onCleared() }

    private companion object {
        val ThemeKey = stringPreferencesKey("thor.theme")
        val ReduceMotionKey = booleanPreferencesKey("thor.reduceMotion")
        val AirflowKey = booleanPreferencesKey("thor.airflowEnabled")
        val BackgroundModeKey = stringPreferencesKey("thor.background.mode")
        val BackgroundIntensityKey = stringPreferencesKey("thor.background.intensity")
    }
}

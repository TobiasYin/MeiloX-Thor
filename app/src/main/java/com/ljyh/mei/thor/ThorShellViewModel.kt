package com.ljyh.mei.thor

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
import com.ljyh.mei.constants.ReduceTransparencyKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.thor.data.ThorMusicRepository
import com.ljyh.mei.thor.menu.*
import com.ljyh.mei.thor.model.*
import com.ljyh.mei.thor.playback.ThorPlaybackBridge
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

data class ThorNotice(val message: String, val undoToken: String? = null, val success: Boolean = true)
data class ThorSearchState(val query: String = "", val items: List<ThorItem> = emptyList(), val loading: Boolean = false, val error: String? = null, val searched: Boolean = false)

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
    val playback = ThorPlaybackBridge(viewModelScope)
    val lyrics = lyricManager.lyricData
    private val mutableNavigation = MutableStateFlow(ThorNavigation(tab = ThorTab.entries.firstOrNull { it.name == savedState.get<String>("thor.tab") } ?: ThorTab.Home))
    val navigation = mutableNavigation.asStateFlow()
    private val mutableLibraryCategory = MutableStateFlow((savedState.get<Int>("thor.libraryCategory") ?: 0).coerceIn(0, 2))
    val libraryCategory = mutableLibraryCategory.asStateFlow()
    fun selectLibraryCategory(category: Int) {
        val selection = category.coerceIn(0, 2)
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
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var detailGeneration = 0L
    private var playRequestGeneration = 0L
    private var menuGeneration = 0L
    private var seekEntryId: String? = null
    private val mutableDetailLoading = MutableStateFlow(false)
    val detailLoading = mutableDetailLoading.asStateFlow()
    private val mutableSeek = MutableStateFlow<Long?>(null)
    val seekPreview = mutableSeek.asStateFlow()
    val textEditing = MutableStateFlow(false)
    private val focus = mutableMapOf<String, String>()
    private val listScroll = mutableMapOf<String, Pair<String, Int>>()

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
            library.map { it.account.generation }.distinctUntilChanged().drop(1).collect {
                mutableMenu.value = null
                mutableNotice.value = null
                menuGeneration++
                mutablePreview.value = null
                mutableSearch.value = ThorSearchState()
                searchJob?.cancel()
                detailJob?.cancel()
                detailGeneration++
                playRequestGeneration++
                // Never leave a previous account's private detail visible after a switch.
                mutableNavigation.value = ThorNavigation(tab = navigation.value.tab)
            }
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

    fun attachPlayer(connection: PlayerConnection?) = playback.attach(connection)
    fun rememberFocus(scene: String, key: String) { focus[scene] = key }
    fun rememberedFocus(scene: String): String? = focus[scene]
    fun rememberListScroll(scene: String, firstKey: String, offset: Int) { listScroll[scene] = firstKey to offset }
    fun rememberedListScroll(scene: String): Pair<String, Int>? = listScroll[scene]
    fun preview(item: ThorItem) { mutablePreview.value = item }
    fun clearPreview() { mutablePreview.value = null }
    fun clearNotice() { mutableNotice.value = null }
    fun showMessage(message: String, success: Boolean = true) { mutableNotice.value = ThorNotice(message, success = success) }
    fun refresh(collection: ThorCollection? = null) = repository.refresh(collection)

    private fun navigate(value: ThorNavigation) {
        if (value.sceneKey != mutableNavigation.value.sceneKey) mutablePreview.value = null
        mutableNavigation.value = value
        savedState["thor.tab"] = value.tab.name
    }

    fun switchTab(delta: Int) { if (menu.value == null) navigate(navigation.value.switchTab(delta)) }
    fun selectTab(tab: ThorTab) { if (navigation.value.isRoot && menu.value == null) navigate(navigation.value.copy(tab = tab)) }
    fun push(page: ThorPage) { navigate(navigation.value.push(page)) }
    fun togglePlayer() { if (menu.value == null) { mutableSeek.value = null; navigate(navigation.value.toggle(ThorOverlay.Player)) } }
    fun showPlayer() { if (navigation.value.overlay != ThorOverlay.Player) navigate(navigation.value.copy(overlays = navigation.value.overlays + ThorOverlay.Player)) }
    fun toggleQueue() { if (menu.value == null) navigate(navigation.value.toggle(ThorOverlay.Queue)) }
    fun showLyrics() = navigate(navigation.value.copy(overlays = navigation.value.overlays + ThorOverlay.Lyrics))
    fun showTimer() = navigate(navigation.value.copy(overlays = navigation.value.overlays + ThorOverlay.Timer))

    fun back(): Boolean {
        val menu = menu.value
        if (menu != null) {
            mutableMenu.value = if (menu.busy) null else menu.back()?.syncPlaylistTargets(playlistKeys(menu, library.value))
            if (mutableMenu.value == null) menuGeneration++
            return true
        }
        if (mutableSeek.value != null) { cancelSeek(); return true }
        val previous = navigation.value.back() ?: return false
        navigate(previous)
        return true
    }

    fun openCollection(collection: ThorCollection) { push(ThorPage.Collection(collection)); repository.refresh(collection) }
    fun replaceCollection(collection: ThorCollection) {
        navigate(navigation.value.replaceCollection(collection))
        repository.refresh(collection)
    }
    fun open(item: ThorItem) {
        when (val key = item.key) {
            is ThorItemKey.Song -> {
                if (item.tracks.isNotEmpty()) play(item.tracks, 0, item.description.ifBlank { item.title })
                else {
                    val generation = ++playRequestGeneration
                    val accountGeneration = library.value.account.generation
                    viewModelScope.launch {
                        try {
                            val loaded = repository.load(item)
                            if (generation == playRequestGeneration && accountGeneration == library.value.account.generation) play(loaded.tracks, 0, loaded.title)
                        } catch (cancellation: CancellationException) { throw cancellation }
                        catch (error: Exception) { if (generation == playRequestGeneration) showMessage(error.message ?: "歌曲读取失败", false) }
                    }
                }
            }
            is ThorItemKey.QueueEntry -> playback.select(key.entryId)
            is ThorItemKey.Destination -> Unit
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

    fun play(tracks: List<MediaMetadata>, index: Int = 0, title: String = "播放列表", shuffle: Boolean? = null) {
        playRequestGeneration++
        if (!playback.play(tracks, index, title, shuffle)) showMessage(if (tracks.isEmpty()) "没有可播放的歌曲" else "播放器正在连接，请稍后重试", false)
    }

    fun setSearchQuery(query: String) { mutableSearch.value = search.value.copy(query = query) }
    fun search() {
        val query = search.value.query.trim()
        if (query.isEmpty()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            mutableSearch.value = search.value.copy(loading = true, error = null, searched = true)
            try {
                val items = repository.search(query)
                mutableSearch.value = ThorSearchState(query, items, searched = true)
            } catch (cancellation: CancellationException) { throw cancellation }
            catch (error: Exception) { mutableSearch.value = ThorSearchState(query, error = error.message ?: "搜索失败", searched = true) }
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
            }
        }
    }

    fun beginSeek() { seekEntryId = playback.state.value.currentEntryId; mutableSeek.value = playback.position() }
    fun adjustSeek(deltaMs: Long) { mutableSeek.value = ((mutableSeek.value ?: playback.position()) + deltaMs).coerceIn(0, playback.state.value.durationMs) }
    fun previewSeek(positionMs: Long) { if (mutableSeek.value == null) seekEntryId = playback.state.value.currentEntryId; mutableSeek.value = positionMs.coerceIn(0, playback.state.value.durationMs) }
    fun applySeek() { if (seekEntryId == playback.state.value.currentEntryId) mutableSeek.value?.let(playback::seek); cancelSeek() }
    fun cancelSeek() { mutableSeek.value = null; seekEntryId = null }

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
    }
}

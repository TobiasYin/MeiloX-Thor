package com.ljyh.mei.thor.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.constants.MusicQualityKey
import com.ljyh.mei.constants.UserAvatarUrlKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.constants.UserNicknameKey
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.SongPlaybackPrivilege
import com.ljyh.mei.data.model.withPlaybackPrivilege
import com.ljyh.mei.data.model.mergedSongPlaybackPrivilege
import com.ljyh.mei.data.model.withoutPlaybackAvailability
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.network.NeteaseSessionType
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.neteaseSessionType
import com.ljyh.mei.data.repository.HomeRepository
import com.ljyh.mei.data.repository.LibraryAccount
import com.ljyh.mei.data.repository.LibraryCollectionsRepository
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.data.repository.PlayerRepository
import com.ljyh.mei.data.repository.PlaylistRepository
import com.ljyh.mei.data.repository.SearchRepository
import com.ljyh.mei.data.repository.collectLibraryPages
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.di.repository.SongRepository
import com.ljyh.mei.thor.data.local.ThorFavoriteEntity
import com.ljyh.mei.thor.data.local.ThorPlaylistEntity
import com.ljyh.mei.thor.data.local.ThorPlaylistTrackEntity
import com.ljyh.mei.thor.model.*
import com.ljyh.mei.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The only Thor boundary allowed to inspect the existing account session. */
@Singleton
class ThorMusicRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val songs: SongRepository,
    private val collections: LibraryCollectionsRepository,
    private val playlists: PlaylistRepository,
    private val playerRepository: PlayerRepository,
    private val home: HomeRepository,
    private val searchRepository: SearchRepository,
    private val meloXRepository: MeloXRepository,
    private val catalog: ThorCatalogRepository,
    private val gson: Gson,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao = database.thorLocalDao()
    private val remote = MutableStateFlow(ThorLibraryState())
    private val collectionFlags = MutableStateFlow<Map<ThorItemKey, Boolean>>(emptyMap())
    private val likeOverrides = MutableStateFlow(ThorLikeOverrides())
    private val likeRevision = AtomicLong()
    private val accountFence = ThorAccountFence()
    private val jobs = mutableMapOf<String, Job>()
    private val revisions = mutableMapOf<String, Long>()
    private val mutableCatalogFilters = MutableStateFlow(ThorCatalogFilters())
    val catalogFilters: StateFlow<ThorCatalogFilters> = mutableCatalogFilters
    private data class CatalogCursor(val filters: ThorCatalogFilters, val offset: Int?, val cursor: Long?)
    /** Guarded by jobs; collection content and its next token are committed in the same critical section. */
    private val catalogCursors = mutableMapOf<ThorCollection, CatalogCursor>()
    private val undoActions = linkedMapOf<String, suspend () -> ThorOperationResult>()
    private val activeAccount get() = accountFence.account

    private data class LocalSnapshot(
        val songs: List<com.ljyh.mei.data.model.room.Song> = emptyList(),
        val favorites: List<ThorFavoriteEntity> = emptyList(),
        val playlists: List<ThorPlaylistEntity> = emptyList(),
        val members: List<ThorPlaylistTrackEntity> = emptyList(),
        val history: List<ThorItem> = emptyList(),
        val ready: Boolean = false,
    )

    private val local = combine(
        songs.getAllSong(), dao.observeFavorites(), dao.observePlaylists(), dao.observeTracks(), database.historyDao().getHistory(),
    ) { music, favorites, lists, members, history -> LocalSnapshot(music, favorites, lists, members,
        history.distinctBy { it.song.id }.map { it.song.toMediaMetadata().toThorItem() }, ready = true) }
        .retryWhen { _, attempt ->
            remote.update { it.copy(errors = it.errors + DEVICE_READ_COLLECTIONS.associateWith { "无法读取本地音乐，请重试" }) }
            delay((attempt + 1).coerceAtMost(10) * 1_000)
            true
        }.onEach {
            remote.update { current -> current.copy(errors = current.errors - DEVICE_READ_COLLECTIONS) }
        }.stateIn(scope, SharingStarted.Eagerly, LocalSnapshot())

    val state: StateFlow<ThorLibraryState> = combine(remote, local, playerRepository.songLikes, collectionFlags, likeOverrides) { network, device, likes, flags, pendingLikes ->
        project(network, device, likes, flags, pendingLikes)
    }.stateIn(scope, SharingStarted.Eagerly, ThorLibraryState())

    /** Login storage and its public projection are asynchronous; do not navigate against stale UI. */
    internal suspend fun awaitCurrentAccountProjection(): ThorAccountState {
        val preferences = context.dataStore.data.first()
        val id = preferences[UserIdKey]?.takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true }
        val credential = preferences[CookieKey].orEmpty()
        val expected = if (id != null && credential.isNotBlank()) LibraryAccount(id, credential) else null
        return state.first { projected ->
            accountFence.account == expected && projected.account.generation == accountFence.generation
        }.account
    }

    init {
        scope.launch {
            var initialized = false
            context.dataStore.data.collect { preferences ->
                val id = preferences[UserIdKey]?.takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true }
                val credential = preferences[CookieKey].orEmpty()
                val account = if (id != null && credential.isNotBlank()) LibraryAccount(id, credential) else null
                val changed = !initialized || activeAccount != account
                initialized = true
                if (changed) {
                    accountFence.changeAccount(account)
                    collectionFlags.value = emptyMap()
                    likeOverrides.update { it.forGeneration(accountFence.generation) }
                    playerRepository.resetLikeState()
                    synchronized(jobs) { jobs.values.forEach(Job::cancel); jobs.clear(); catalogCursors.clear() }
                }
                remote.update { old ->
                    val publicAccount = ThorAccountState(
                        userId = account?.userId,
                        nickname = preferences[UserNicknameKey].orEmpty().ifBlank { "本地音乐" },
                        avatarUri = preferences[UserAvatarUrlKey],
                        sessionType = when (preferences.neteaseSessionType()) {
                            NeteaseSessionType.Mobile -> ThorSessionType.Mobile
                            NeteaseSessionType.Web -> ThorSessionType.Web
                            NeteaseSessionType.None -> ThorSessionType.None
                        },
                        generation = accountFence.generation,
                    )
                    if (changed) ThorLibraryState(account = publicAccount)
                    else old.copy(account = publicAccount)
                }
                if (changed) {
                    refresh()
                    if (credential.isNotBlank() && account == null) {
                        val ticket = accountFence.ticketForCall()
                        // Keep observing account changes while the target device initializes.
                        scope.launch {
                            try {
                                ensureRemoteReady(ticket)
                                collections.restoreAccountIfNeeded()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                // Preserve the session. A refresh/sign-in can retry preparation.
                            }
                        }
                    }
                }
            }
        }
        scope.launch { collections.albumChanges.collect { refresh(ThorCollection.CollectedAlbums) } }
        scope.launch { playlists.collectionChanges.collect { refresh(ThorCollection.OwnedPlaylists) } }
        scope.launch { playerRepository.likeChanges.collect { refresh(ThorCollection.LikedSongs) } }
    }

    fun refresh(collection: ThorCollection? = null) {
        if (collection in CATALOG_COLLECTIONS) {
            fetchCatalog(checkNotNull(collection), append = false)
            return
        }
        // New directories load on entry. Refresh-all also updates directories already visited,
        // without eagerly issuing every catalog request at startup or after an account change.
        if (collection == null) {
            CATALOG_COLLECTIONS.filter { it in remote.value.collections }.forEach { fetchCatalog(it, append = false) }
        }
        if (collection == null || collection in ACCOUNT_PLAYLIST_COLLECTIONS) refreshAccountPlaylists()
        if (collection == null || collection == ThorCollection.CollectedAlbums) {
            fetch("albums", setOf(ThorCollection.CollectedAlbums)) { account ->
                val items = if (account == null) emptyList() else collectLibraryPages(key = { it.id.toString() }) {
                    offset -> collections.albums(account, offset)
                }.map { album -> ThorItem(
                    ThorItemKey.Album(album.id), album.title, album.artist.joinToString(" / ") { it.name },
                    album.cover, trackCount = album.size, collected = true,
                ) }
                mapOf(ThorCollection.CollectedAlbums to items)
            }
        }
        if (collection == null || collection == ThorCollection.Home) {
            fetch("home", setOf(ThorCollection.Home)) {
                val blocks = home.getHomePageResourceShow(refresh = true).valueOrThrow()
                mapOf(ThorCollection.Home to parseHomeResources(blocks.mapNotNull { it.dslData }, gson))
            }
        }
    }

    fun setCatalogFilters(filters: ThorCatalogFilters) {
        require(filters.artistArea in setOf(-1, 7, 96, 8, 16, 0))
        require(filters.artistType in setOf(-1, 1, 2, 3))
        val next = filters.copy(playlistCategory = filters.playlistCategory.trim().ifBlank { "全部" })
        val old = mutableCatalogFilters.value
        if (old == next) return
        val affected = CATALOG_COLLECTIONS.filter { !sameCatalogFilters(it, old, next) }.toSet()
        synchronized(jobs) {
            mutableCatalogFilters.value = next
            affected.forEach { collection ->
                val key = "catalog:" + collection.name
                jobs.remove(key)?.cancel()
                revisions[key] = (revisions[key] ?: 0L) + 1L
                catalogCursors.remove(collection)
            }
            remote.update { it.copy(collections = it.collections - affected, hasMore = it.hasMore - affected,
                loading = it.loading - affected, loadingMore = it.loadingMore - affected, errors = it.errors - affected) }
        }
        affected.forEach { fetchCatalog(it, append = false) }
    }

    fun loadMore(collection: ThorCollection) {
        if (collection in CATALOG_COLLECTIONS) fetchCatalog(collection, append = true)
    }

    private fun fetchCatalog(collection: ThorCollection, append: Boolean) {
        val ticket = accountFence.capture(remote.value.account) ?: return
        val key = "catalog:" + collection.name
        synchronized(jobs) {
            if (!accountFence.isCurrent(ticket) || remote.value.account.generation != ticket.generation) return
            val filters = mutableCatalogFilters.value
            // Publishing the page completes the request before its coroutine unwinds. A
            // legitimate next click must not be swallowed by that already-published Job.
            if (append && (collection in remote.value.loading || collection in remote.value.loadingMore)) return
            val previous = catalogCursors[collection]
            if (append && (collection !in remote.value.hasMore || previous == null ||
                    !sameCatalogFilters(collection, previous.filters, filters))) return
            val offset = if (append) previous?.offset ?: 0 else 0
            val cursor = if (append) previous?.cursor ?: 0L else 0L
            jobs[key]?.cancel()
            val revision = (revisions[key] ?: 0L) + 1L
            revisions[key] = revision
            if (!append) catalogCursors.remove(collection)
            remote.update { it.copy(
                loading = if (append) it.loading else it.loading + collection,
                loadingMore = if (append) it.loadingMore + collection else it.loadingMore - collection,
                hasMore = if (append) it.hasMore else it.hasMore - collection,
                errors = it.errors - collection) }
            jobs[key] = scope.launch {
                fun current() = accountFence.isCurrent(ticket) &&
                    remote.value.account.generation == ticket.generation &&
                    revisions[key] == revision && sameCatalogFilters(collection, filters, mutableCatalogFilters.value)
                try {
                    val page = accountFence.withTicket(ticket) {
                        if (collection == ThorCollection.DailySongs && ticket.account == null) error("登录后查看每日推荐")
                        ensureRemoteReady(ticket)
                        catalog.page(collection, filters, offset, cursor, ticket.account?.sessionFingerprint)
                    }
                    synchronized(jobs) {
                        if (!current()) return@synchronized
                        catalogCursors[collection] = CatalogCursor(filters, page.nextOffset, page.nextCursor)
                        remote.update { old ->
                            if (!current()) old else old.copy(
                                collections = old.collections + (collection to
                                    ((if (append) old[collection] else emptyList()) + page.items).distinctBy { it.key }),
                                hasMore = if (page.hasMore) old.hasMore + collection else old.hasMore - collection,
                                loading = old.loading - collection, loadingMore = old.loadingMore - collection,
                                errors = old.errors - collection)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    synchronized(jobs) {
                        remote.update { old -> if (!current()) old else old.copy(
                            loading = old.loading - collection, loadingMore = old.loadingMore - collection,
                            errors = old.errors + (collection to
                                if (collection == ThorCollection.DailySongs && ticket.account == null) "登录后查看每日推荐"
                                else "读取失败，请检查网络并重试")) }
                    }
                }
            }
        }
    }

    suspend fun artistProfile(item: ThorItem): ThorArtistProfile = withCatalogRead { ticket ->
        val profile = catalog.artistProfile(item, ticket.account?.sessionFingerprint)
        profile.copy(hotSongs = artistSourceHints(profile.hotSongs, ticket))
    }

    suspend fun artistSongs(id: Long, offset: Int): ThorCatalogPage = withCatalogRead { ticket ->
        val page = catalog.artistSongs(id, offset, ticket.account?.sessionFingerprint)
        page.copy(items = artistSourceHints(page.items, ticket))
    }

    private suspend fun artistSourceHints(items: List<ThorItem>, ticket: ThorAccountTicket): List<ThorItem> =
        resolveThorArtistSourceAvailability(
            items,
            context.dataStore.data.first()[MusicQualityKey] ?: MusicQuality.EXHIGH.text,
            checkCurrent = { accountFence.checkCurrent(ticket) },
            fetch = { ids, quality -> playlists.getRawSongUrlV1(ids, quality, ticket.account?.sessionFingerprint) },
        )

    suspend fun artistAlbums(id: Long, offset: Int): ThorCatalogPage = withCatalogRead { ticket ->
        catalog.artistAlbums(id, offset, ticket.account?.sessionFingerprint)
    }

    private suspend fun <T> withCatalogRead(action: suspend (ThorAccountTicket) -> T): T = withContext(Dispatchers.IO) {
        val ticket = accountFence.ticketForCall()
        check(accountFence.capture(state.value.account) === ticket) { "账号已变化，请重试" }
        accountFence.withTicket(ticket) {
            ensureRemoteReady(ticket)
            action(ticket)
        }
    }

    private fun refreshAccountPlaylists() = fetch("account-playlists", ACCOUNT_PLAYLIST_COLLECTIONS) { account ->
        if (account == null) return@fetch ACCOUNT_PLAYLIST_COLLECTIONS.associateWith { emptyList() }
        val all = collections.allPlaylists(account)
        val liked = all.firstOrNull { it.specialType == 5 && it.creator.userId.toString() == account.userId }
        val items = all.filterNot { it.specialType == 5 }.map { item -> ThorItem(
            key = ThorItemKey.Playlist(item.id.toString()), title = item.name,
            subtitle = item.creator.nickname, artworkUri = item.coverImgUrl, trackCount = item.trackCount,
            owned = item.creator.userId.toString() == account.userId,
            collected = item.creator.userId.toString() != account.userId,
        ) }
        val likedTracks = if (liked == null) emptyList() else {
            val detail = playlists.getPlaylistDetail(liked.id.toString(), account.sessionFingerprint).valueOrThrow()
            playlists.getCompletePlaylistTracks(detail, account.sessionFingerprint)
        }
        mapOf(
            ThorCollection.OwnedPlaylists to items.filter { it.owned },
            ThorCollection.SavedPlaylists to items.filterNot { it.owned },
            ThorCollection.LikedSongs to likedTracks.map { it.toThorItem().copy(collected = true) },
        )
    }

    private fun fetch(
        key: String,
        affected: Set<ThorCollection>,
        operation: suspend (LibraryAccount?) -> Map<ThorCollection, List<ThorItem>>,
    ) {
        val ticket = accountFence.capture(remote.value.account) ?: return
        val account = ticket.account
        val generation = ticket.generation
        val flagsAtStart = collectionFlags.value
        val likesAtStart = likeOverrides.value
        synchronized(jobs) {
            jobs[key]?.cancel()
            val revision = (revisions[key] ?: 0) + 1
            revisions[key] = revision
            remote.update {
                if (activeAccount != account || it.account.generation != generation) it
                else it.copy(loading = it.loading + affected, errors = it.errors - affected)
            }
            jobs[key] = scope.launch {
                try {
                    val result = accountFence.withTicket(ticket) {
                        ensureRemoteReady(ticket)
                        operation(account)
                    }
                    remote.update {
                        if (activeAccount != account || it.account.generation != generation ||
                            synchronized(jobs) { revisions[key] != revision }) it
                        else it.copy(collections = it.collections + result, loading = it.loading - affected,
                            errors = it.errors - affected)
                    }
                    collectionFlags.update { flags ->
                        if (activeAccount != account || remote.value.account.generation != generation ||
                            synchronized(jobs) { revisions[key] != revision }) flags
                        else flags.filter { (item, value) ->
                            flagsAtStart[item] != value ||
                                !((item is ThorItemKey.Album && ThorCollection.CollectedAlbums in affected) ||
                                    (item is ThorItemKey.Playlist && ThorCollection.SavedPlaylists in affected))
                        }
                    }
                    if (ThorCollection.LikedSongs in affected) {
                        likeOverrides.update { current ->
                            if (activeAccount != account || remote.value.account.generation != generation ||
                                synchronized(jobs) { revisions[key] != revision }) current
                            else acknowledgeThorLikes(current, likesAtStart, result[ThorCollection.LikedSongs].orEmpty(), generation)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    remote.update { old ->
                        if (activeAccount != account || old.account.generation != generation ||
                            synchronized(jobs) { revisions[key] != revision }) old
                        else old.copy(loading = old.loading - affected, errors = old.errors + affected.associateWith {
                                if (account == null && it in ACCOUNT_COLLECTIONS) "连接网易云后查看账号音乐库" else "读取失败，请检查网络并重试"
                            })
                    }
                }
            }
        }
    }

    fun captureAccountTicket(publicAccount: ThorAccountState): ThorAccountTicket? = accountFence.capture(publicAccount)
    fun isTicketCurrent(ticket: ThorAccountTicket): Boolean = accountFence.isCurrent(ticket)
    suspend fun <T> withAccountTicket(ticket: ThorAccountTicket, action: suspend () -> T): T =
        accountFence.withTicket(ticket, action)

    suspend fun load(item: ThorItem): ThorItem = withContext(Dispatchers.IO) {
        val ticket = accountFence.ticketForCall()
        val account = ticket.account
        fun checkReadAccount() = accountFence.checkCurrent(ticket)
        checkReadAccount()
        when (val key = item.key) {
            is ThorItemKey.Song -> if (key.local) {
                state.value[ThorCollection.LocalSongs].firstOrNull { it.key == key } ?: error("本地歌曲已不存在")
            } else {
                ensureRemoteReady(ticket)
                val tracks = playerRepository.getSongDetail(key.id.toString()).valueOrThrow()
                checkReadAccount()
                check(tracks.code == 200) { "歌曲读取失败" }
                tracks.toMediaMetadata().firstOrNull { it.id == key.id }?.toThorItem() ?: error("歌曲已不可用")
            }
            is ThorItemKey.Album -> {
                if (key.local) return@withContext state.value[ThorCollection.LocalAlbums].firstOrNull { it.key == key }
                    ?: error("本地专辑已不存在，请重新扫描")
                ensureRemoteReady(ticket)
                val detail = playlists.getAlbumDetail(key.id.toString()).valueOrThrow()
                checkReadAccount()
                check(detail.code == 200) { "专辑读取失败" }
                decorate(item.copy(title = detail.album.name, artworkUri = detail.album.picUrl,
                    subtitle = detail.album.artists.joinToString(" / ") { it.name },
                    description = detail.album.description.orEmpty(), trackCount = detail.songs.size,
                    tracks = detail.toMediaMetadata()))
            }
            is ThorItemKey.Playlist -> {
                if (key.local) return@withContext localPlaylist(key.id)
                ensureRemoteReady(ticket)
                val detail = playlists.getPlaylistDetail(key.id, account?.sessionFingerprint).valueOrThrow()
                val tracks = playlists.getCompletePlaylistTracks(detail, account?.sessionFingerprint)
                checkReadAccount()
                decorate(item.copy(title = detail.playlist.name, description = detail.playlist.description.orEmpty(),
                    artworkUri = detail.playlist.coverImgUrl, trackCount = tracks.size, tracks = tracks,
                    owned = account != null && detail.playlist.creator.userId.toString() == account.userId,
                    collected = detail.playlist.subscribed))
            }
            else -> item
        }
    }

    suspend fun search(query: String): List<ThorItem> = searchResults(query).items

    suspend fun searchResults(query: String): ThorSearchResponse = withContext(Dispatchers.IO) {
        val term = query.trim()
        if (term.isBlank()) return@withContext ThorSearchResponse(emptyList())
        val ticket = accountFence.ticketForCall()
        val account = ticket.account
        val generation = ticket.generation
        val device = state.value
        val matching = (device[ThorCollection.LocalSongs] + device[ThorCollection.LocalAlbums] +
            device[ThorCollection.OwnedPlaylists].filter { (it.key as? ThorItemKey.Playlist)?.local == true })
            .filter { "${it.title} ${it.subtitle} ${it.description}".contains(term, true) }
        ensureRemoteReady(ticket)
        val responses = coroutineScope { thorSearchRequests.map { (type, category) ->
            async { category to searchRepository.search(term, type, 40) }
        }.awaitAll() }
        currentCoroutineContext().ensureActive()
        accountFence.checkCurrent(ticket)
        checkAccount(account)
        check(remote.value.account.generation == generation) { "账号已变化，请重试" }
        val response = mergeThorSearchResponses(matching, responses, account?.userId, gson)
            .let { it.copy(items = it.items.map(::decorate)) }
        currentCoroutineContext().ensureActive()
        accountFence.checkCurrent(ticket)
        check(remote.value.account.generation == generation) { "账号已变化，请重试" }
        response
    }

    suspend fun toggleLike(song: MediaMetadata): ThorOperationResult = mutate { account ->
        if (song.isLocal || song.id <= 0 || account == null) {
            val old = dao.favorite(DEVICE_SCOPE, "song", song.id, song.isLocal)
            val next = if (old == null) favorite(song.toThorItem(), DEVICE_SCOPE) else null
            putFavorite(next, old)
            ok(if (next == null) "已取消喜欢" else "已喜欢", rememberUndo {
                mutate {
                    check(dao.favorite(DEVICE_SCOPE, "song", song.id, song.isLocal) == next) { "状态已变化，无法撤销" }
                    putFavorite(old, next); ok("已撤销")
                }
            })
        } else {
            requireAccount(account)
            val liked = playerRepository.toggleLike(song.id.toString(), account.sessionFingerprint).valueOrThrow()
            checkAccount(account)
            val generation = accountFence.ticketForCall().generation
            val pending = ThorLikeOverride(likeRevision.incrementAndGet(), song.toThorItem(), liked)
            likeOverrides.update { current ->
                if (activeAccount != account || remote.value.account.generation != generation) current
                else current.forGeneration(generation).let { it.copy(entries = it.entries + (song.id to pending)) }
            }
            remote.update { current ->
                if (activeAccount != account) return@update current
                val existing = current[ThorCollection.LikedSongs].filterNot { (it.key as? ThorItemKey.Song)?.id == song.id }
                current.copy(collections = current.collections + (ThorCollection.LikedSongs to
                    if (liked) listOf(song.toThorItem().copy(collected = true)) + existing else existing))
            }
            // The existing like-change event can be collected before the override
            // above is published. Start a read that can acknowledge this revision.
            refresh(ThorCollection.LikedSongs)
            ok(if (liked) "已喜欢" else "已取消喜欢", rememberUndo(guard = {
                check(playerRepository.songLikes.value[song.id.toString()] == liked) { "状态已变化，无法撤销" }
            }) { toggleLike(song) })
        }
    }

    suspend fun setCollected(item: ThorItem, collected: Boolean): ThorOperationResult = mutate { account ->
        val localKey = when (val key = item.key) {
            is ThorItemKey.Album -> key.local
            is ThorItemKey.Playlist -> key.local
            else -> return@mutate failure("此项目不能收藏")
        }
        if (item.owned) return@mutate failure("自己的歌单无需收藏")
        val before = decorate(item).collected
        if (localKey || account == null) {
            val record = favorite(item, DEVICE_SCOPE)
            val old = dao.favorite(record.scope, record.kind, record.itemId, record.local)
            val next = if (collected) record else null
            putFavorite(next, old ?: record)
            ok(if (collected) "已收藏" else "已取消收藏", rememberUndo {
                mutate {
                    check(dao.favorite(record.scope, record.kind, record.itemId, record.local) == next) { "状态已变化，无法撤销" }
                    putFavorite(old, record); ok("已撤销")
                }
            })
        } else {
            requireAccount(account)
            when (val key = item.key) {
                is ThorItemKey.Album -> collections.setAlbumSubscription(account, key.id, collected)
                is ThorItemKey.Playlist -> {
                    val result = if (collected) playlists.subscribePlaylist(key.id, expectedSession = account.sessionFingerprint)
                        else playlists.unSubscribePlaylist(key.id, expectedSession = account.sessionFingerprint)
                    check(result.valueOrThrow().code == 200) { "歌单收藏失败" }
                }
                else -> Unit
            }
            checkAccount(account)
            collectionFlags.update { if (activeAccount == account) it + (item.key to collected) else it }
            val target = if (item.key is ThorItemKey.Album) ThorCollection.CollectedAlbums else ThorCollection.SavedPlaylists
            remote.update { current ->
                if (activeAccount != account) return@update current
                val existing = current[target].filterNot { it.key == item.key }
                current.copy(collections = current.collections + (target to
                    if (collected) listOf(item.copy(collected = true)) + existing else existing))
            }
            refresh(ThorCollection.CollectedAlbums)
            refresh(ThorCollection.SavedPlaylists)
            ok(if (collected) "已收藏" else "已取消收藏", rememberUndo(guard = {
                check(decorate(item).collected == collected) { "状态已变化，无法撤销" }
            }) { setCollected(item, before) })
        }
    }

    suspend fun addToPlaylist(playlist: ThorItemKey.Playlist, tracks: List<MediaMetadata>): ThorOperationResult = mutate { account ->
        val incoming = tracks.distinctBy(::songIdentity)
        require(incoming.isNotEmpty()) { "没有可加入的歌曲" }
        if (playlist.local) {
            val before = dao.tracks(playlist.id)
            val added = dao.append(playlist.id, members(playlist.id, incoming))
            if (added == 0) return@mutate ok("歌曲已在歌单中")
            val after = dao.tracks(playlist.id)
            ok("已加入歌单", rememberUndo { mutate {
                check(dao.tracks(playlist.id) == after) { "歌单已变化，无法撤销" }
                dao.replace(playlist.id, before); ok("已撤销")
            } })
        } else {
            val signedIn = account ?: error("请先连接网易云")
            require(incoming.all { !it.isLocal && it.id > 0 }) { "本地歌曲请加入本地歌单" }
            requireAccount(signedIn)
            val detail = playlists.loadPlaylistForOrder(playlist.id, signedIn.userId.toLong(), signedIn.sessionFingerprint)
            val plan = thorPlaylistAppendPlan(detail.playlist.trackIds.map { it.id }, incoming.map { it.id })
            val byId = incoming.associateBy { it.id }
            val added = plan.added.map(byId::getValue)
            if (added.isEmpty()) return@mutate ok("歌曲已在歌单中")
            try {
                val expectedAfter = applyThorPlaylistAppend(plan,
                    addMissing = { remoteTrackMutation(signedIn, playlist.id, "add", added) },
                    readCurrent = { requirePlaylistSnapshot(signedIn, playlist.id, plan.expected.toSet()) },
                    saveOrder = { observed, expected -> saveRemotePlaylistOrder(signedIn, playlist.id, observed, expected) })
                ok("已加入歌单", rememberRemoteAppendUndo(signedIn, playlist.id, plan, added, expectedAfter))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Add can succeed before an order request fails. Do not roll back another
                // client's changes; offer Undo only for fresh membership whose original
                // members still retain their pre-add relative order.
                val actualAfter = try { requirePlaylistSnapshot(signedIn, playlist.id, plan.expected.toSet()) }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { null }
                val undo = actualAfter?.takeIf { hasUnchangedThorPlaylistBaseline(plan, it) }
                    ?.let { rememberRemoteAppendUndo(signedIn, playlist.id, plan, added, it) }
                ThorOperationResult(false, "歌曲可能已加入，但顺序未能确认，请刷新歌单查看", undoToken = undo)
            }
        }
    }

    private suspend fun saveRemotePlaylistOrder(account: LibraryAccount, id: String, observed: List<Long>, expected: List<Long>) {
        requireAccount(account)
        val generation = accountFence.ticketForCall().generation
        playlists.updatePlaylistTrackOrder(id, observed, expected, account.userId.toLong(), account.sessionFingerprint,
            isCurrent = { activeAccount == account && accountFence.generation == generation })
        checkAccount(account)
        refresh(ThorCollection.OwnedPlaylists)
    }

    private suspend fun rememberRemoteAppendUndo(
        account: LibraryAccount, id: String, plan: ThorPlaylistAppendPlan,
        added: List<MediaMetadata>, after: List<Long>,
    ): String = rememberUndo(guard = {
        requireAccount(account)
        check(requirePlaylistSnapshot(account, id, plan.expected.toSet()) == after) { "歌单已变化，无法撤销" }
    }) { mutate {
        remoteTrackMutation(account, id, "del", added)
        val restored = requirePlaylistSnapshot(account, id, plan.before.toSet())
        // Deleting only our additions should leave the protected original subsequence intact.
        // If another client reordered it during deletion, report the change rather than undoing it.
        check(restored == plan.before) { "歌单已变化，请刷新确认" }
        ok("已撤销")
    } }

    suspend fun removeFromPlaylist(playlist: ThorItemKey.Playlist, tracks: List<MediaMetadata>): ThorOperationResult = mutate { account ->
        if (playlist.local) {
            val before = dao.tracks(playlist.id)
            val ids = tracks.mapTo(hashSetOf(), ::songIdentity)
            val after = before.filterNot { it.songKey in ids }
            dao.replace(playlist.id, after)
            val savedAfter = dao.tracks(playlist.id)
            ok("已从歌单移除", rememberUndo { mutate {
                check(dao.tracks(playlist.id) == savedAfter) { "歌单已变化，无法撤销" }
                dao.replace(playlist.id, before); ok("已撤销")
            } })
        } else {
            val signedIn = account ?: error("请先连接网易云")
            require(tracks.all { !it.isLocal && it.id > 0 }) { "本地歌曲请加入本地歌单" }
            requireAccount(signedIn)
            val detail = playlists.loadPlaylistForOrder(playlist.id, signedIn.userId.toLong(), signedIn.sessionFingerprint)
            val previous = detail.playlist.trackIds.map { it.id }
            val removed = tracks.filter { it.id in previous }.distinctBy { it.id }
            if (removed.isEmpty()) return@mutate ok("歌曲已不在歌单中")
            remoteTrackMutation(signedIn, playlist.id, "del", removed)
            val expectedAfter = previous.toSet() - removed.map { it.id }.toSet()
            ok("已从歌单移除", rememberUndo { mutate {
                requirePlaylistSnapshot(signedIn, playlist.id, expectedAfter)
                remoteTrackMutation(signedIn, playlist.id, "add", removed)
                val restored = requirePlaylistSnapshot(signedIn, playlist.id, previous.toSet())
                val generation = remote.value.account.generation
                if (restored != previous) playlists.updatePlaylistTrackOrder(
                    playlist.id, restored, previous, signedIn.userId.toLong(), signedIn.sessionFingerprint,
                    isCurrent = { activeAccount == signedIn && remote.value.account.generation == generation },
                )
                refresh(ThorCollection.OwnedPlaylists)
                ok("已撤销")
            } })
        }
    }

    suspend fun createPlaylist(
        name: String, tracks: List<MediaMetadata> = emptyList(), local: Boolean = false,
    ): ThorOperationResult = mutate { capturedAccount ->
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "请输入歌单名称" }
        if (local || capturedAccount == null || tracks.any { it.isLocal || it.id <= 0 }) {
            val id = "thor-local-${UUID.randomUUID()}"
            val now = System.currentTimeMillis()
            val created = ThorPlaylistEntity(id, normalized, now, now)
            val initialTracks = members(id, tracks)
            dao.create(created, initialTracks)
            val undo = rememberUndo { mutate {
                check(dao.playlist(id) == created && dao.tracks(id) == initialTracks) { "歌单已修改，无法撤销创建" }
                dao.delete(id); ok("已撤销")
            } }
            return@mutate ThorOperationResult(true, "已创建本地歌单", undo, ThorItemKey.Playlist(id, true))
        }
        val account = capturedAccount ?: error("请先连接网易云")
        requireAccount(account)
        val result = playlists.createPlaylist(normalized, false, expectedSession = account.sessionFingerprint).valueOrThrow()
        check(result.code == 200 && result.id > 0) { "创建歌单失败" }
        val key = ThorItemKey.Playlist(result.id.toString())
        try {
            if (tracks.isNotEmpty()) {
                val plan = thorPlaylistAppendPlan(emptyList(), tracks.map { it.id })
                val byId = tracks.associateBy { it.id }
                applyThorPlaylistAppend(plan,
                    addMissing = { ids -> remoteTrackMutation(account, key.id, "add", ids.map(byId::getValue)) },
                    readCurrent = { requirePlaylistSnapshot(account, key.id, plan.expected.toSet()) },
                    saveOrder = { observed, expected -> saveRemotePlaylistOrder(account, key.id, observed, expected) })
            }
        } catch (error: Exception) {
            val removed = withContext(NonCancellable) { compensateEmptyPlaylist(account, key.id, normalized) }
            refresh(ThorCollection.OwnedPlaylists)
            if (error is CancellationException) throw error
            return@mutate ThorOperationResult(false,
                if (removed) "加入失败，新建的空歌单已撤销" else "歌单已创建，但加入或排序未能确认，请进入歌单查看",
                createdPlaylist = key.takeUnless { removed })
        }
        checkAccount(account)
        refresh(ThorCollection.OwnedPlaylists)
        ThorOperationResult(true, if (tracks.isEmpty()) "已创建歌单" else "已创建并加入歌单", createdPlaylist = key)
    }

    suspend fun renamePlaylist(playlist: ThorItemKey.Playlist, name: String): ThorOperationResult = mutate { account ->
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "请输入歌单名称" }
        if (playlist.local) {
            val previous = dao.playlist(playlist.id) ?: error("歌单已不存在")
            dao.rename(playlist.id, normalized, System.currentTimeMillis())
            val after = dao.playlist(playlist.id)
            ok("已重命名", rememberUndo { mutate {
                check(dao.playlist(playlist.id) == after) { "歌单已变化，无法撤销" }
                dao.rename(playlist.id, previous.name, System.currentTimeMillis()); ok("已撤销")
            } })
        } else {
            val signedIn = account ?: error("请先连接网易云")
            requireAccount(signedIn)
            val previous = playlists.loadPlaylistForOrder(playlist.id, signedIn.userId.toLong(), signedIn.sessionFingerprint)
            playlists.renamePlaylist(playlist.id, normalized, signedIn.userId.toLong(), signedIn.sessionFingerprint).valueOrThrow()
            checkAccount(signedIn)
            refresh(ThorCollection.OwnedPlaylists)
            ok("已重命名", rememberUndo(guard = {
                requireAccount(signedIn)
                val current = playlists.loadPlaylistForOrder(playlist.id, signedIn.userId.toLong(), signedIn.sessionFingerprint)
                check(current.playlist.name == normalized) { "歌单已变化，无法撤销" }
            }) { renamePlaylist(playlist, previous.playlist.name) })
        }
    }

    suspend fun deletePlaylist(playlist: ThorItemKey.Playlist): ThorOperationResult = mutate { account ->
        if (playlist.local) {
            val previous = dao.playlist(playlist.id) ?: error("歌单已不存在")
            val tracks = dao.tracks(playlist.id)
            dao.delete(playlist.id)
            ok("已删除歌单", rememberUndo { mutate { dao.create(previous, tracks); ok("已撤销") } })
        } else {
            val signedIn = account ?: error("请先连接网易云")
            requireAccount(signedIn)
            playlists.loadPlaylistForOrder(playlist.id, signedIn.userId.toLong(), signedIn.sessionFingerprint)
            check(playlists.deletePlaylist(playlist.id, signedIn.sessionFingerprint).valueOrThrow().code == 200) { "删除失败" }
            checkAccount(signedIn)
            refresh(ThorCollection.OwnedPlaylists)
            ok("已删除歌单")
        }
    }

    suspend fun undo(token: String): ThorOperationResult {
        val action = synchronized(undoActions) { undoActions.remove(token) } ?: return failure("此操作已失效")
        return try { action() } catch (error: CancellationException) { throw error }
        catch (_: Exception) { failure("状态已变化，无法撤销") }
    }

    private suspend fun remoteTrackMutation(account: LibraryAccount, id: String, op: String, tracks: List<MediaMetadata>) {
        require(tracks.isNotEmpty()) { "没有歌曲" }
        require(tracks.all { !it.isLocal && it.id > 0 }) { "本地歌曲请加入本地歌单" }
        requireAccount(account)
        val result = playlists.manipulateTrack(op, id, tracks.distinctBy { it.id }.joinToString(",") { it.id.toString() },
            expectedSession = account.sessionFingerprint).valueOrThrow()
        check(result.code == 200 || (op == "add" && result.code == 502)) { "歌单更新失败 (${result.code})" }
        checkAccount(account)
        refresh(ThorCollection.OwnedPlaylists)
    }

    private suspend fun requirePlaylistSnapshot(account: LibraryAccount, id: String, expected: Set<Long>): List<Long> {
        requireAccount(account)
        val current = playlists.loadPlaylistForOrder(id, account.userId.toLong(), account.sessionFingerprint)
            .playlist.trackIds.map { it.id }
        check(current.size == expected.size && current.toSet() == expected) { "歌单已变化，无法撤销" }
        return current
    }

    private suspend fun compensateEmptyPlaylist(account: LibraryAccount, id: String, originalName: String): Boolean = try {
        requireAccount(account)
        val current = playlists.loadPlaylistForOrder(id, account.userId.toLong(), account.sessionFingerprint)
        if (current.playlist.trackIds.isNotEmpty() || current.playlist.name != originalName) false else {
            playlists.deletePlaylist(id, account.sessionFingerprint).valueOrThrow().code == 200
        }
    } catch (_: Exception) { false }

    private suspend fun mutate(operation: suspend (LibraryAccount?) -> ThorOperationResult): ThorOperationResult {
        // Enter before dispatching to IO: ordinary callers also freeze identity before waiting.
        return try { accountFence.mutate(operation) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { failure(publicOperationError(error)) }
    }

    private suspend fun requireAccount(account: LibraryAccount) {
        checkAccount(account)
        val ticket = accountFence.ticketForCall()
        accountFence.checkCurrent(ticket)
        ensureRemoteReady(ticket)
        check(collections.accounts.first() == account) { "账号已变化，请重试" }
        checkAccount(account)
        accountFence.checkCurrent(ticket)
    }

    private suspend fun ensureRemoteReady(ticket: ThorAccountTicket) =
        awaitThorDeviceIdentity(accountFence, ticket, meloXRepository::ensureNeteaseDeviceIdentity)
    private fun checkAccount(account: LibraryAccount?) = check(activeAccount == account) { "账号已变化，请重试" }
    private suspend fun rememberUndo(
        guard: suspend () -> Unit = {},
        action: suspend () -> ThorOperationResult,
    ): String {
        val fencedAction = accountFence.freezeUndo(guard, action)
        return synchronized(undoActions) {
            val key = UUID.randomUUID().toString()
            undoActions[key] = fencedAction
            while (undoActions.size > 32) undoActions.remove(undoActions.keys.first())
            key
        }
    }

    private suspend fun putFavorite(value: ThorFavoriteEntity?, fallback: ThorFavoriteEntity?) {
        if (value != null) dao.putFavorite(value)
        else if (fallback != null) dao.removeFavorite(fallback.scope, fallback.kind, fallback.itemId, fallback.local)
    }

    private fun favorite(item: ThorItem, scope: String): ThorFavoriteEntity {
        val (kind, id, local) = when (val key = item.key) {
            is ThorItemKey.Song -> Triple("song", key.id, key.local)
            is ThorItemKey.Album -> Triple("album", key.id, key.local)
            is ThorItemKey.Playlist -> Triple("playlist", key.id.toLong(), key.local)
            else -> error("此项目不能收藏")
        }
        return ThorFavoriteEntity(scope, kind, id, local, item.title, item.subtitle, item.artworkUri,
            item.description, gson.toJson(item.tracks.map { it.withoutPlaybackAvailability() }), System.currentTimeMillis())
    }

    private fun decodeTracks(json: String): List<MediaMetadata> = runCatching {
        gson.fromJson<List<MediaMetadata>>(json, TRACKS_TYPE).orEmpty().map { it.withoutPlaybackAvailability() }
    }.getOrDefault(emptyList())

    private fun favoriteItem(record: ThorFavoriteEntity): ThorItem = ThorItem(
        key = when (record.kind) {
            "song" -> ThorItemKey.Song(record.itemId, record.local)
            "album" -> ThorItemKey.Album(record.itemId, record.local)
            else -> ThorItemKey.Playlist(record.itemId.toString(), record.local)
        },
        title = record.title, subtitle = record.subtitle, artworkUri = record.artworkUri,
        description = record.description, tracks = decodeTracks(record.tracksJson),
        trackCount = decodeTracks(record.tracksJson).size.takeIf { it > 0 }, collected = true,
    )

    private fun members(id: String, tracks: List<MediaMetadata>): List<ThorPlaylistTrackEntity> =
        tracks.distinctBy(::songIdentity).mapIndexed { index, song ->
            ThorPlaylistTrackEntity(id, songIdentity(song), index, gson.toJson(song.withoutPlaybackAvailability()))
        }

    private fun memberTrack(record: ThorPlaylistTrackEntity): MediaMetadata? = runCatching {
        gson.fromJson(record.metadataJson, MediaMetadata::class.java)?.withoutPlaybackAvailability()
    }.getOrNull()

    private suspend fun localPlaylist(id: String): ThorItem {
        val list = dao.playlist(id) ?: error("本地歌单已不存在")
        val tracks = dao.tracks(id).mapNotNull(::memberTrack)
        return playlistItem(list, tracks)
    }

    private fun playlistItem(list: ThorPlaylistEntity, tracks: List<MediaMetadata>) = ThorItem(
        ThorItemKey.Playlist(list.id, true), list.name, "本地歌单", tracks.firstOrNull()?.coverUrl,
        trackCount = tracks.size, tracks = tracks, owned = true,
    )

    private fun decorate(item: ThorItem): ThorItem {
        val collection = when (item.key) {
            is ThorItemKey.Album -> ThorCollection.CollectedAlbums
            is ThorItemKey.Playlist -> ThorCollection.SavedPlaylists
            else -> return item
        }
        val known = if (remote.value.collections.containsKey(collection)) state.value[collection].any { it.key == item.key }
            else item.collected
        return item.copy(collected = collectionFlags.value[item.key] ?: known)
    }

    private fun project(network: ThorLibraryState, device: LocalSnapshot, remoteLikes: Map<String, Boolean>, flags: Map<ThorItemKey, Boolean>, pendingLikes: ThorLikeOverrides): ThorLibraryState {
        val localTracks = device.songs.filter { it.sourceType == SourceType.LOCAL }.map { it.toMediaMetadata() }
        val downloaded = device.songs.filter { it.sourceType == SourceType.DOWNLOAD && !it.path.isNullOrBlank() }
            .map { it.toMediaMetadata().toThorItem() }
        val favorites = device.favorites.filter { it.scope == DEVICE_SCOPE && (!network.account.signedIn || it.local) }
        val favoriteSongs = favorites.filter { it.kind == "song" }.map(::favoriteItem)
        val favoriteAlbums = favorites.filter { it.kind == "album" }.map(::favoriteItem)
        val savedLists = favorites.filter { it.kind == "playlist" }.map(::favoriteItem)
        val localLists = device.playlists.map { list ->
            playlistItem(list, device.members.filter { it.playlistId == list.id }.mapNotNull(::memberTrack))
        }
        val cloudLiked = projectThorLikedSongs(network[ThorCollection.LikedSongs], pendingLikes, network.account.generation)
        val liked = (cloudLiked + favoriteSongs).distinctBy { it.key }
        val likedKeys = liked.mapTo(hashSetOf()) { it.key }
        val albumIds = (network[ThorCollection.CollectedAlbums] + favoriteAlbums).mapTo(hashSetOf()) { it.key }
        val localAlbums = localTracks.groupBy { localAlbumIdentity(it.album.title, it.artists.firstOrNull()?.name.orEmpty()) }
            .map { (id, tracks) -> ThorItem(ThorItemKey.Album(id, true), tracks.first().album.title.ifBlank { "未知专辑" },
                tracks.map { it.artists.joinToString(" / ") { a -> a.name } }.distinct().joinToString(" / "),
                tracks.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl, trackCount = tracks.size,
                tracks = tracks, collected = ThorItemKey.Album(id, true) in albumIds) }
        // This legacy-shaped map is a cloud-ID projection. Device IDs occupy a
        // separate namespace and must be checked through their full ThorItemKey.
        val likes = cloudLikeProjection(liked).toMutableMap()
        if (network.account.signedIn) remoteLikes.forEach { (id, value) -> id.toLongOrNull()?.let { likes[it] = value } }
        if (pendingLikes.generation == network.account.generation) pendingLikes.entries.forEach { (id, pending) -> likes[id] = pending.liked }
        val all = network.collections + mapOf(
            ThorCollection.LocalSongs to localTracks.map { it.toThorItem().copy(collected = ThorItemKey.Song(it.id, true) in likedKeys) },
            ThorCollection.LocalAlbums to localAlbums,
            ThorCollection.Downloads to downloaded,
            ThorCollection.LikedSongs to liked,
            ThorCollection.CollectedAlbums to (network[ThorCollection.CollectedAlbums] + favoriteAlbums).distinctBy { it.key },
            ThorCollection.OwnedPlaylists to (localLists + network[ThorCollection.OwnedPlaylists]),
            ThorCollection.SavedPlaylists to (network[ThorCollection.SavedPlaylists] + savedLists).distinctBy { it.key },
            ThorCollection.History to device.history,
            ThorCollection.Home to (device.history.take(10) + network[ThorCollection.Home]).distinctBy { it.key },
        )
        val loadingDevice = if (!device.ready && network.errors.keys.none { it in DEVICE_READ_COLLECTIONS }) DEVICE_READ_COLLECTIONS else emptySet()
        return network.copy(collections = all.mapValues { (_, items) -> items.map { item ->
            flags[item.key]?.let { item.copy(collected = it) } ?: item
        } }, songLikes = likes, loading = network.loading + loadingDevice)
    }

    private fun ok(message: String, undo: String? = null) = ThorOperationResult(true, message, undo)
    private fun failure(message: String) = ThorOperationResult(false, message)

    companion object {
        private const val DEVICE_SCOPE = "device"
        private val TRACKS_TYPE = object : TypeToken<List<MediaMetadata>>() {}.type
        private val ACCOUNT_PLAYLIST_COLLECTIONS = setOf(ThorCollection.OwnedPlaylists, ThorCollection.SavedPlaylists, ThorCollection.LikedSongs)
        private val CATALOG_COLLECTIONS = setOf(ThorCollection.RecommendedPlaylists, ThorCollection.Discover, ThorCollection.Toplists,
            ThorCollection.Artists, ThorCollection.DailySongs)
        private val ACCOUNT_COLLECTIONS = ACCOUNT_PLAYLIST_COLLECTIONS + ThorCollection.CollectedAlbums
        private val DEVICE_READ_COLLECTIONS = setOf(ThorCollection.LocalSongs, ThorCollection.LocalAlbums, ThorCollection.Downloads, ThorCollection.History)
    }
}

internal fun songIdentity(song: MediaMetadata): String = "${if (song.isLocal) "local" else "netease"}:${song.id}"

internal fun cloudLikeProjection(items: List<ThorItem>): Map<Long, Boolean> =
    items.flatMap { it.tracks }.filterNot { it.isLocal }.associate { it.id to true }

internal fun localAlbumIdentity(title: String, artist: String): Long {
    val text = "${title.trim().lowercase(java.util.Locale.ROOT)}\u0000${artist.trim().lowercase(java.util.Locale.ROOT)}"
    val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return bytes.take(8).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) } and Long.MAX_VALUE
}

internal fun hasCompleteTracks(item: ThorItem): Boolean = item.tracks.isNotEmpty() && item.trackCount == item.tracks.size

private fun <T> Resource<T>.valueOrThrow(): T = when (this) {
    is Resource.Success -> data
    is Resource.Error -> error(publicOperationError(IllegalStateException(message)))
    Resource.Loading -> error("正在加载，请稍后重试")
}

/** UI failures are intentionally bounded; transport exceptions may contain request details. */
private fun publicOperationError(error: Exception): String {
    val message = error.message.orEmpty()
    return when {
        message.contains("account", true) || message.contains("账号已变化") -> "账号已变化，请重新操作"
        message.contains("请先连接") || message.contains("authenticated session", true) -> "请先连接网易云"
        message.contains("ownership", true) -> "只能管理自己创建的歌单"
        message in setOf("请输入歌单名称", "没有可加入的歌曲", "没有歌曲", "本地歌曲请加入本地歌单",
            "歌单已不存在", "歌单已变化，无法撤销", "状态已变化，无法撤销", "歌单已修改，无法撤销创建") -> message
        else -> "操作失败，请检查连接后重试"
    }
}

/** Only project actual routable resources, never invent cards for unsupported modules. */
internal fun parseHomeResources(elements: List<JsonElement>, gson: Gson = Gson()): List<ThorItem> {
    val result = mutableListOf<ThorItem>()
    fun visit(element: JsonElement) {
        if (element.isJsonArray) { element.asJsonArray.forEach(::visit); return }
        if (!element.isJsonObject) return
        val obj = element.asJsonObject
        val id = obj.stringValue("resourceId")?.toLongOrNull()
        val title = obj.stringValue("title") ?: obj.stringValue("name")
        val type = obj.stringValue("resourceType").orEmpty().lowercase()
        if (id != null && id > 0 && !title.isNullOrBlank()) {
            val key = when {
                type.contains("playlist") || type == "list" -> ThorItemKey.Playlist(id.toString())
                type == "album" -> ThorItemKey.Album(id)
                type == "song" -> ThorItemKey.Song(id)
                else -> null
            }
            if (key != null) {
                val song = if (key is ThorItemKey.Song) homeSongMetadata(obj, key.id, gson) else null
                result += ThorItem(key, title, obj.stringValue("subTitle").orEmpty(),
                    obj.stringValue("coverImg") ?: obj.stringValue("picUrl"), tracks = listOfNotNull(song))
            }
        }
        obj.entrySet().forEach { visit(it.value) }
    }
    elements.forEach(::visit)
    return result.distinctBy { it.key }
}

/** Use only an actual complete song object supplied by home DSL; missing metadata stays unresolved. */
private fun homeSongMetadata(resource: JsonObject, id: Long, gson: Gson): MediaMetadata? = runCatching {
    fun JsonObject.objectValue(name: String) = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
    val song = resource.objectValue("resourceExtInfo")?.objectValue("songData")
        ?: resource.objectValue("songData") ?: return@runCatching null
    if (song.get("id")?.asLong != id || song.stringValue("name").isNullOrBlank() ||
        song.objectValue("al") == null || song.get("ar")?.isJsonArray != true || !song.has("dt")) return@runCatching null
    val decoded = gson.fromJson(song, PlaylistDetail.Playlist.Track::class.java)
    val metadata = decoded.toMediaMetadata()
    val resourcePrivilege = resource.objectValue("privilege")?.let {
        gson.fromJson(it, SongPlaybackPrivilege::class.java).let { privilege ->
            if (privilege.id == null) privilege.copy(id = id) else privilege
        }
    }
    if (resourcePrivilege == null) metadata else metadata.withPlaybackPrivilege(
        mergedSongPlaybackPrivilege(id, gson.fromJson(song.get("privilege"), SongPlaybackPrivilege::class.java), resourcePrivilege, decoded.st))
}.getOrNull()

/** Keep the accepted generation across SDK/registration suspension, including A -> B -> A. */
internal suspend fun awaitThorDeviceIdentity(
    fence: ThorAccountFence,
    ticket: ThorAccountTicket,
    prepare: suspend (expectedSession: String?) -> Unit,
) {
    fence.checkCurrent(ticket)
    prepare(ticket.account?.sessionFingerprint)
    fence.checkCurrent(ticket)
}

internal fun sameCatalogFilters(collection: ThorCollection, first: ThorCatalogFilters, second: ThorCatalogFilters): Boolean = when (collection) {
    ThorCollection.Discover -> first.playlistCategory == second.playlistCategory
    ThorCollection.Artists -> first.artistArea == second.artistArea && first.artistType == second.artistType
    else -> true
}

private fun JsonObject.stringValue(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }
    ?.asString?.takeIf { it.isNotBlank() && it != "null" }

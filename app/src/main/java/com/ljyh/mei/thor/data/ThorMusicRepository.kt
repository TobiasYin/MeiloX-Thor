package com.ljyh.mei.thor.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.UserAvatarUrlKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.constants.UserNicknameKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.api.toMediaData
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.network.NeteaseSessionType
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.neteaseSessionType
import com.ljyh.mei.data.repository.HomeRepository
import com.ljyh.mei.data.repository.LibraryAccount
import com.ljyh.mei.data.repository.LibraryCollectionsRepository
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
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val gson: Gson,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao = database.thorLocalDao()
    private val remote = MutableStateFlow(ThorLibraryState())
    private val collectionFlags = MutableStateFlow<Map<ThorItemKey, Boolean>>(emptyMap())
    private val likeOverrides = MutableStateFlow(ThorLikeOverrides())
    private val likeRevision = AtomicLong()
    private val mutationMutex = Mutex()
    private val jobs = mutableMapOf<String, Job>()
    private val revisions = mutableMapOf<String, Long>()
    private val undoActions = linkedMapOf<String, suspend () -> ThorOperationResult>()
    @Volatile private var activeAccount: LibraryAccount? = null

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
                    activeAccount = account
                    collectionFlags.value = emptyMap()
                    likeOverrides.update { it.forGeneration(remote.value.account.generation + 1) }
                    playerRepository.resetLikeState()
                    synchronized(jobs) { jobs.values.forEach(Job::cancel); jobs.clear() }
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
                        generation = old.account.generation + if (changed) 1 else 0,
                    )
                    if (changed) ThorLibraryState(account = publicAccount)
                    else old.copy(account = publicAccount)
                }
                if (changed) {
                    refresh()
                    if (credential.isNotBlank() && account == null) collections.restoreAccountIfNeeded()
                }
            }
        }
        scope.launch { collections.albumChanges.collect { refresh(ThorCollection.CollectedAlbums) } }
        scope.launch { playlists.collectionChanges.collect { refresh(ThorCollection.OwnedPlaylists) } }
        scope.launch { playerRepository.likeChanges.collect { refresh(ThorCollection.LikedSongs) } }
    }

    fun refresh(collection: ThorCollection? = null) {
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
        if (collection == null || collection == ThorCollection.Discover) {
            fetch("discover", setOf(ThorCollection.Discover)) {
                val result = playlists.getHighQualityPlaylist("全部", 60).valueOrThrow()
                check(result.code == 200) { "发现内容读取失败 (${result.code})" }
                mapOf(ThorCollection.Discover to result.playlists.map { item -> ThorItem(
                    ThorItemKey.Playlist(item.id.toString()), item.name, item.creator.nickname,
                    item.coverImgUrl, item.description.orEmpty(), item.trackCount,
                    collected = item.subscribed,
                ) })
            }
        }
        if (collection == null || collection == ThorCollection.Home) {
            fetch("home", setOf(ThorCollection.Home)) {
                val blocks = home.getHomePageResourceShow(refresh = true).valueOrThrow()
                mapOf(ThorCollection.Home to parseHomeResources(blocks.mapNotNull { it.dslData }))
            }
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
        val account = activeAccount
        val generation = remote.value.account.generation
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
                    val result = operation(account)
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

    suspend fun load(item: ThorItem): ThorItem = withContext(Dispatchers.IO) {
        val account = activeAccount
        val generation = remote.value.account.generation
        fun checkReadAccount() {
            checkAccount(account)
            check(remote.value.account.generation == generation) { "账号已变化，请重试" }
        }
        when (val key = item.key) {
            is ThorItemKey.Song -> if (key.local) {
                state.value[ThorCollection.LocalSongs].firstOrNull { it.key == key } ?: error("本地歌曲已不存在")
            } else if (item.tracks.isNotEmpty()) item else {
                val tracks = playerRepository.getSongDetail(key.id.toString()).valueOrThrow()
                checkReadAccount()
                check(tracks.code == 200) { "歌曲读取失败" }
                tracks.songs.firstOrNull { it.id == key.id }?.toMediaMetadata()?.toThorItem() ?: error("歌曲已不可用")
            }
            is ThorItemKey.Album -> {
                if (key.local) return@withContext state.value[ThorCollection.LocalAlbums].firstOrNull { it.key == key }
                    ?: error("本地专辑已不存在，请重新扫描")
                if (hasCompleteTracks(item)) return@withContext decorate(item)
                val detail = playlists.getAlbumDetail(key.id.toString()).valueOrThrow()
                checkReadAccount()
                check(detail.code == 200) { "专辑读取失败" }
                decorate(item.copy(title = detail.album.name, artworkUri = detail.album.picUrl,
                    subtitle = detail.album.artists.joinToString(" / ") { it.name },
                    description = detail.album.description.orEmpty(), trackCount = detail.songs.size,
                    tracks = detail.songs.map { it.toMediaMetadata() }))
            }
            is ThorItemKey.Playlist -> {
                if (key.local) return@withContext localPlaylist(key.id)
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

    suspend fun search(query: String): List<ThorItem> = withContext(Dispatchers.IO) {
        val term = query.trim()
        if (term.isBlank()) return@withContext emptyList()
        val account = activeAccount
        val generation = remote.value.account.generation
        val device = state.value
        val matching = (device[ThorCollection.LocalSongs] + device[ThorCollection.LocalAlbums] +
            device[ThorCollection.OwnedPlaylists].filter { (it.key as? ThorItemKey.Playlist)?.local == true })
            .filter { "${it.title} ${it.subtitle} ${it.description}".contains(term, true) }
        val responses = coroutineScope { listOf(1, 10, 1000).map { type ->
            async { searchRepository.search(term, type, 40) }
        }.awaitAll() }
        checkAccount(account)
        check(remote.value.account.generation == generation) { "账号已变化，请重试" }
        val online = responses.filterIsInstance<Resource.Success<com.ljyh.mei.data.model.api.SearchResult>>()
            .filter { it.data.code == 200 }.flatMap { response ->
                val result = response.data.result
                result.songs.orEmpty().map { it.toMediaData().toThorItem() } +
                    result.albums.orEmpty().map { album -> ThorItem(ThorItemKey.Album(album.id), album.name,
                        album.artists.joinToString(" / ") { it.name }, album.picUrl, album.description.orEmpty(), album.size) } +
                    result.playlists.orEmpty().map { playlist -> ThorItem(ThorItemKey.Playlist(playlist.id.toString()),
                        playlist.name, playlist.creator.nickname, playlist.coverImgUrl, trackCount = playlist.trackCount,
                        owned = account?.userId == playlist.creator.userId.toString()) }
            }
        if (online.isEmpty() && matching.isEmpty() && responses.any { it !is Resource.Success || it.data.code != 200 }) {
            error("搜索失败，请检查网络后重试")
        }
        (matching + online).distinctBy { it.key }.map(::decorate)
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
            val generation = currentCoroutineContext()[ExpectedThorAccount]?.generation ?: remote.value.account.generation
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
                    val result = if (collected) playlists.subscribePlaylist(key.id) else playlists.unSubscribePlaylist(key.id)
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
            val detail = playlists.loadPlaylistForOrder(playlist.id, signedIn.userId.toLong(), signedIn.sessionFingerprint)
            val previous = detail.playlist.trackIds.mapTo(hashSetOf()) { it.id }
            val added = incoming.filter { it.id !in previous }
            if (added.isEmpty()) return@mutate ok("歌曲已在歌单中")
            remoteTrackMutation(signedIn, playlist.id, "add", added)
            val expectedAfter = previous + added.map { it.id }
            ok("已加入歌单", rememberUndo { mutate {
                requirePlaylistSnapshot(signedIn, playlist.id, expectedAfter)
                remoteTrackMutation(signedIn, playlist.id, "del", added)
                ok("已撤销")
            } })
        }
    }

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
        val result = playlists.createPlaylist(normalized, false, expectedSession = account.sessionFingerprint).valueOrThrow()
        check(result.code == 200 && result.id > 0) { "创建歌单失败" }
        val key = ThorItemKey.Playlist(result.id.toString())
        try {
            if (tracks.isNotEmpty()) remoteTrackMutation(account, key.id, "add", tracks)
        } catch (error: Exception) {
            val removed = withContext(NonCancellable) { compensateEmptyPlaylist(account, key.id, normalized) }
            refresh(ThorCollection.OwnedPlaylists)
            if (error is CancellationException) throw error
            return@mutate ThorOperationResult(false,
                if (removed) "加入失败，新建的空歌单已撤销" else "歌单已创建，但加入失败；可进入歌单重试",
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
            val previous = playlists.loadPlaylistForOrder(playlist.id, signedIn.userId.toLong(), signedIn.sessionFingerprint)
            playlists.renamePlaylist(playlist.id, normalized, signedIn.userId.toLong(), signedIn.sessionFingerprint).valueOrThrow()
            checkAccount(signedIn)
            refresh(ThorCollection.OwnedPlaylists)
            ok("已重命名", rememberUndo(guard = {
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
        val result = playlists.manipulateTrack(op, id, tracks.distinctBy { it.id }.joinToString(",") { it.id.toString() }).valueOrThrow()
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
        // Undo retains the original context even if it waits behind another mutation.
        val expected = currentCoroutineContext()[ExpectedThorAccount] ?: ExpectedThorAccount(activeAccount, remote.value.account.generation)
        return withContext(Dispatchers.IO + expected) {
            mutationMutex.withLock {
                try {
                    checkAccount(expected.account)
                    check(remote.value.account.generation == expected.generation) { "账号已变化，请重试" }
                    expected.guard()
                    checkAccount(expected.account)
                    check(remote.value.account.generation == expected.generation) { "账号已变化，请重试" }
                    operation(expected.account)
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { failure(publicOperationError(error)) }
            }
        }
    }

    private suspend fun requireAccount(account: LibraryAccount) {
        checkAccount(account)
        val expected = currentCoroutineContext()[ExpectedThorAccount]
        if (expected != null) check(remote.value.account.generation == expected.generation) { "账号已变化，请重试" }
        check(collections.accounts.first() == account) { "账号已变化，请重试" }
        checkAccount(account)
        if (expected != null) check(remote.value.account.generation == expected.generation) { "账号已变化，请重试" }
    }
    private fun checkAccount(account: LibraryAccount?) = check(activeAccount == account) { "账号已变化，请重试" }
    private suspend fun rememberUndo(
        guard: suspend () -> Unit = {},
        action: suspend () -> ThorOperationResult,
    ): String {
        val expected = currentCoroutineContext()[ExpectedThorAccount] ?: ExpectedThorAccount(activeAccount, remote.value.account.generation)
        val fencedAction: suspend () -> ThorOperationResult = {
            withContext(ExpectedThorAccount(expected.account, expected.generation, guard)) { action() }
        }
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
            item.description, gson.toJson(item.tracks), System.currentTimeMillis())
    }

    private fun decodeTracks(json: String): List<MediaMetadata> = runCatching {
        gson.fromJson<List<MediaMetadata>>(json, TRACKS_TYPE).orEmpty()
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
            ThorPlaylistTrackEntity(id, songIdentity(song), index, gson.toJson(song))
        }

    private fun memberTrack(record: ThorPlaylistTrackEntity): MediaMetadata? = runCatching {
        gson.fromJson(record.metadataJson, MediaMetadata::class.java)
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
        private val ACCOUNT_COLLECTIONS = ACCOUNT_PLAYLIST_COLLECTIONS + ThorCollection.CollectedAlbums
        private val DEVICE_READ_COLLECTIONS = setOf(ThorCollection.LocalSongs, ThorCollection.LocalAlbums, ThorCollection.Downloads, ThorCollection.History)
    }
}

private class ExpectedThorAccount(
    val account: LibraryAccount?,
    val generation: Long,
    val guard: suspend () -> Unit = {},
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ExpectedThorAccount>
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
internal fun parseHomeResources(elements: List<JsonElement>): List<ThorItem> {
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
            if (key != null) result += ThorItem(key, title, obj.stringValue("subTitle").orEmpty(),
                obj.stringValue("coverImg") ?: obj.stringValue("picUrl"))
        }
        obj.entrySet().forEach { visit(it.value) }
    }
    elements.forEach(::visit)
    return result.distinctBy { it.key }
}

private fun JsonObject.stringValue(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }
    ?.asString?.takeIf { it.isNotBlank() && it != "null" }

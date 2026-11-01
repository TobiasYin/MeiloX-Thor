package com.ljyh.mei.thor.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.isExplicitlyUnavailable
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.extensions.togglePlayPause
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.playback.StableDeckPlayer
import com.ljyh.mei.playback.normalizeQueueMediaItems
import com.ljyh.mei.playback.playbackOrderIndices
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.playback.queueEntryId
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.model.ThorOperationResult
import com.ljyh.mei.thor.model.toThorItem
import com.ljyh.mei.constants.PreviousPlaybackKey
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID

data class ThorQueueItem(val entryId: String, val metadata: MediaMetadata, val pendingRemoval: Boolean = false) {
    val item: ThorItem get() = metadata.toThorItem().copy(key = ThorItemKey.QueueEntry(entryId))
}

data class ThorPlaybackState(
    val connected: Boolean = false,
    val current: MediaMetadata? = null,
    val currentEntryId: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queue: List<ThorQueueItem> = emptyList(),
    val next: ThorQueueItem? = null,
    val mode: String = "顺序播放",
    val timerActive: Boolean = false,
    val timerRemainingMs: Long = 0,
    val error: String? = null,
    val requestedQuality: MusicQuality = MusicQuality.EXHIGH,
    val actualQuality: MusicQuality? = null,
    val localQualitySource: Boolean = false,
)

/** Observes one existing connection. It never owns or replaces the playback service. */
@UnstableApi
class ThorPlaybackBridge(private val scope: CoroutineScope,
    private val onPlaybackRejected: (String) -> Unit = {},
) : Player.Listener {
    private val mutableState = MutableStateFlow(ThorPlaybackState())
    val state = mutableState.asStateFlow()
    private var connection: PlayerConnection? = null
    private var ticker: Job? = null
    private var failureObserver: Job? = null
    private val availabilityScope = ThorQueueAvailabilityScope()
    private var invalidateAvailabilityOnAttach = false
    private var visible = true
    private val deferredRemoval = mutableSetOf<String>()
    private val undoTracker = ThorQueueUndoTracker()
    private data class QueueUndo(val epoch: Long, val action: () -> Unit)
    private val undos = linkedMapOf<String, QueueUndo>()
    private var observedPlayer: Player? = null
    private var observedIdentityEpoch: Long? = null

    fun attach(connection: PlayerConnection?) {
        if (this.connection === connection) return
        this.connection?.player?.removeListener(this)
        ticker?.cancel()
        failureObserver?.cancel()
        resetQueueActions()
        observedPlayer = null
        observedIdentityEpoch = null
        this.connection = connection
        if (connection != null && invalidateAvailabilityOnAttach) {
            availabilityScope.invalidate(queueEntryIds(connection.player))
            invalidateAvailabilityOnAttach = false
        }
        connection?.player?.addListener(this)
        connection?.let(::observePlaybackFailures)
        refresh()
        updateTicker()
    }

    private fun observePlaybackFailures(connection: PlayerConnection) {
        val service = connection.service
        val gate = ThorPlaybackFailureGate(service.playbackFailure.value?.id)
        failureObserver = scope.launch {
            service.playbackFailure.collect { failure ->
                if (failure == null || !gate.accept(failure.id) || this@ThorPlaybackBridge.connection?.service !== service)
                    return@collect
                val current = connection.player.currentMediaItem
                if (failure.queueEntryId != null && failure.queueEntryId != current?.queueEntryId) return@collect
                if (failure.queueEntryId == null && failure.mediaId != null && failure.mediaId != current?.mediaId) return@collect
                onPlaybackRejected(failure.message)
            }
        }
    }

    /** Account hints expire without changing the current song, queue identities, or position. */
    fun invalidateAccountAvailability() {
        takePlaybackControl()
        val player = connection?.player
        if (player == null) invalidateAvailabilityOnAttach = true
        else availabilityScope.invalidate(queueEntryIds(player))
        refresh()
    }

    private fun queueEntryIds(player: Player): Set<String> =
        (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).queueEntryId }.toSet()

    private fun currentAccountMetadata(item: MediaItem): MediaMetadata =
        availabilityScope.metadata(item.queueEntryId, item.thorMetadata())

    private fun reject(message: String): Boolean { onPlaybackRejected(message); return false }

    /** Explicit user intent supersedes an asynchronous runtime-recovery candidate search. */
    private fun takePlaybackControl() { connection?.service?.cancelUnavailableRecovery() }

    fun setVisible(visible: Boolean) {
        this.visible = visible
        updateTicker()
    }

    private fun updateTicker() {
        ticker?.cancel()
        if (visible && connection != null) ticker = scope.launch {
            while (true) {
                refresh()
                delay(250)
            }
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (connection?.player !== player) return
        observeQueueIdentity(player)
        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) && deferredRemoval.isNotEmpty()) {
            val current = player.currentMediaItem?.queueEntryId
            val removable = deferredRemoval.filter { it != current }
            for (index in player.mediaItemCount - 1 downTo 0) {
                if (player.getMediaItemAt(index).queueEntryId in removable) player.removeMediaItem(index)
            }
            deferredRemoval.removeAll(removable.toSet())
        }
        refresh()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val player = connection?.player ?: return
        observeQueueIdentity(player)
        undoTracker.observeCurrent(mediaItem?.queueEntryId)
    }

    private fun resetQueueActions() {
        undoTracker.beginEpoch()
        undos.clear()
        deferredRemoval.clear()
    }

    private fun observeQueueIdentity(player: Player) {
        val identityEpoch = (player as? StableDeckPlayer)?.queueIdentityEpoch
        if (observedPlayer !== player || observedIdentityEpoch != identityEpoch) {
            resetQueueActions()
            observedPlayer = player
            observedIdentityEpoch = identityEpoch
        }
        undoTracker.observeCurrent(player.currentMediaItem?.queueEntryId)
    }

    private fun refresh() {
        val connection = connection ?: run {
            mutableState.value = ThorPlaybackState()
            return
        }
        val player = connection.player
        observeQueueIdentity(player)
        availabilityScope.retainOnly(queueEntryIds(player))
        val items = player.playbackOrderIndices().mapNotNull { index ->
            val item = player.getMediaItemAt(index)
            val entryId = item.queueEntryId ?: return@mapNotNull null
            ThorQueueItem(entryId, currentAccountMetadata(item), entryId in deferredRemoval)
        }
        val nextIndex = availableQueueTarget(player, forward = true, automatic = true) ?: C.INDEX_UNSET
        val nextId = if (nextIndex in 0 until player.mediaItemCount) player.getMediaItemAt(nextIndex).queueEntryId else null
        mutableState.value = ThorPlaybackState(
            connected = true,
            current = player.currentMediaItem?.let(::currentAccountMetadata),
            currentEntryId = player.currentMediaItem?.queueEntryId,
            playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: player.currentMediaItem?.metadata?.duration ?: 0,
            queue = items,
            next = items.firstOrNull { it.entryId == nextId },
            mode = when {
                player.shuffleModeEnabled -> "随机播放"
                player.repeatMode == Player.REPEAT_MODE_ONE -> "单曲循环"
                player.repeatMode == Player.REPEAT_MODE_ALL -> "列表循环"
                else -> "顺序播放"
            },
            timerActive = connection.service.sleepTimer.isActive,
            timerRemainingMs = connection.service.sleepTimer.remainingMillis,
            error = player.playerError?.localizedMessage,
            requestedQuality = connection.requestedMusicQuality.value,
            actualQuality = connection.currentMusicQuality.value,
            localQualitySource = connection.isPlayingLocalSource.value,
        )
    }

    fun position(): Long = connection?.player?.currentPosition?.coerceAtLeast(0) ?: 0
    /** Only an explicit choice changes the saved preference; resolved sources report separately. */
    fun changeQuality(quality: MusicQuality): Boolean {
        val connection = connection ?: return false
        takePlaybackControl()
        connection.changeQuality(quality)
        return true
    }
    fun toggle() {
        takePlaybackControl()
        val player = connection?.player ?: return
        if (!player.playWhenReady && player.currentMediaItem?.let(::currentAccountMetadata)?.isExplicitlyUnavailable == true) {
            reject(THOR_NO_PLAYBACK_PERMISSION); return
        }
        player.togglePlayPause(); refresh()
    }
    fun previous() {
        takePlaybackControl()
        val connection = connection ?: return
        val player = connection.player
        // Retain the existing setting that restarts a progressed current track on Previous.
        val restartCurrent = player.previousMediaItemIndex == C.INDEX_UNSET ||
            (!(connection.service.dataStore[PreviousPlaybackKey] ?: true) &&
                player.currentPosition > player.maxSeekToPreviousPosition)
        if (restartCurrent) {
            if (player.currentMediaItem?.let(::currentAccountMetadata)?.isExplicitlyUnavailable == true) {
                reject(THOR_NO_PLAYBACK_PERMISSION); return
            }
            connection.seekToPrevious(); return
        }
        val target = availableQueueTarget(player, forward = false) ?: run {
            reject("没有可播放的上一首"); return
        }
        if (target == player.previousMediaItemIndex) connection.seekToPrevious() else seekQueueIndex(connection, target)
    }
    fun next() {
        takePlaybackControl()
        val connection = connection ?: return
        val target = availableQueueTarget(connection.player, forward = true) ?: run {
            reject("没有可播放的下一首"); return
        }
        if (target == connection.player.nextMediaItemIndex) connection.seekToNext() else seekQueueIndex(connection, target)
    }

    private fun availableQueueTarget(player: Player, forward: Boolean, automatic: Boolean = false): Int? {
        val timeline = player.currentTimeline
        val repeat = if (!automatic && player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else player.repeatMode
        return thorAvailableQueueIndex(player.currentMediaItemIndex, minOf(player.mediaItemCount, timeline.windowCount),
            step = { index -> if (forward) timeline.getNextWindowIndex(index, repeat, player.shuffleModeEnabled)
                else timeline.getPreviousWindowIndex(index, repeat, player.shuffleModeEnabled) },
            available = { !currentAccountMetadata(player.getMediaItemAt(it)).isExplicitlyUnavailable })
    }

    private fun seekQueueIndex(connection: PlayerConnection, index: Int) {
        val player = connection.player
        val entry = player.getMediaItemAt(index).queueEntryId
        if (entry != null) connection.service.queueManager.seekToQueueEntry(entry)
        else { player.seekToDefaultPosition(index); player.playWhenReady = true }
    }
    fun switchMode() { takePlaybackControl(); connection?.switchPlayMode(); refresh() }
    fun seek(positionMs: Long) {
        takePlaybackControl()
        val duration = state.value.durationMs
        connection?.player?.seekTo(clampThorSeekPosition(positionMs, duration))
        refresh()
    }
    fun seekAndPlay(positionMs: Long, expectedEntryId: String? = null) {
        takePlaybackControl()
        val player = connection?.player ?: return
        val item = player.currentMediaItem ?: return
        if (currentAccountMetadata(item).isExplicitlyUnavailable) { reject(THOR_NO_PLAYBACK_PERMISSION); return }
        // The player may already have advanced before the published projection catches up.
        if (expectedEntryId != null && (expectedEntryId.isBlank() || item.queueEntryId != expectedEntryId)) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: item.metadata?.duration?.takeIf { it > 0 } ?: 0L
        player.seekAndPlayLyric(positionMs, duration)
        refresh()
    }
    fun startTimer(minutes: Int) { connection?.service?.sleepTimer?.start(minutes); refresh() }
    fun clearTimer() { connection?.service?.sleepTimer?.clear(); refresh() }
    fun select(entryId: String): Boolean {
        takePlaybackControl()
        if (entryId.isBlank()) return reject("队列已更新，请重新选择")
        val connection = connection ?: return reject("播放器正在连接，请稍后重试")
        val player = connection.player
        val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).queueEntryId == entryId }
            ?: return reject("队列已更新，请重新选择")
        if (currentAccountMetadata(player.getMediaItemAt(index)).isExplicitlyUnavailable) return reject(THOR_NO_PLAYBACK_PERMISSION)
        connection.service.queueManager.seekToQueueEntry(entryId)
        return true
    }
    fun skip(entryId: String): ThorOperationResult {
        takePlaybackControl()
        val connection = connection ?: return ThorOperationResult(false, "播放器尚未连接")
        if (connection.player.currentMediaItem?.queueEntryId != entryId) return ThorOperationResult(false, "该曲目已结束")
        val target = availableQueueTarget(connection.player, forward = true)
        if (target == null || target == connection.player.currentMediaItemIndex) return ThorOperationResult(false, "没有可播放的下一首")
        seekQueueIndex(connection, target)
        return ThorOperationResult(true, "已跳过此曲")
    }

    fun play(tracks: List<MediaMetadata>, index: Int, title: String, shuffle: Boolean? = null): Boolean {
        takePlaybackControl()
        return commitThorPlayback(thorSelectedPlayback(tracks, index), rejected = { reject(it) }) { selection ->
            val connection = connection ?: return reject("播放器正在连接，请稍后重试")
            resetQueueActions()
            connection.playQueue(ListQueue(
                id = "thor:${UUID.randomUUID()}", title = title,
                items = selection.tracks.map { it.id.toString() to it.toMediaItem() }, startIndex = selection.index,
            ), shuffle = shuffle ?: connection.player.shuffleModeEnabled,
                repeatMode = if (shuffle != null && connection.player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ALL else connection.player.repeatMode)
        }
    }

    fun enqueue(tracks: List<MediaMetadata>, next: Boolean): ThorOperationResult {
        takePlaybackControl()
        val eligible = thorPlayableTracks(tracks)
        if (eligible.isEmpty()) return ThorOperationResult(false, if (tracks.isEmpty()) "没有可播放的歌曲"
            else if (tracks.size == 1) THOR_NO_PLAYBACK_PERMISSION else "所选歌曲均无播放权限")
        val connection = connection ?: return ThorOperationResult(false, "播放器尚未连接")
        val player = connection.player
        observeQueueIdentity(player)
        // Queue edits run asynchronously in the existing owner. Assign identities before
        // dispatch, rather than diffing a timeline that may not have updated yet.
        val existingIds = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).queueEntryId }.toSet()
        val items = normalizeQueueMediaItems(eligible.map { it.toMediaItem() }, existingIds)
        val inserted = items.mapNotNull { it.queueEntryId }.toSet()
        val epoch = undoTracker.epoch
        if (next) connection.playNext(items) else connection.addToQueue(items)
        val token = registerUndo {
            val removable = undoTracker.insertionUndoTargets(epoch, inserted, player.currentMediaItem?.queueEntryId).orEmpty()
            for (index in player.mediaItemCount - 1 downTo 0) {
                if (player.getMediaItemAt(index).queueEntryId in removable) player.removeMediaItem(index)
            }
        }
        refresh()
        val skipped = tracks.size - eligible.size
        val message = (if (next) "已设为下一首播放" else "已加入队列") +
            if (skipped > 0) "，已跳过 $skipped 首无播放权限的歌曲" else ""
        return ThorOperationResult(true, message, token)
    }

    fun remove(entryId: String): ThorOperationResult {
        takePlaybackControl()
        val connection = connection ?: return ThorOperationResult(false, "播放器尚未连接")
        val player = connection.player
        observeQueueIdentity(player)
        val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).queueEntryId == entryId }
            ?: return ThorOperationResult(false, "该曲目已移出队列")
        val token = if (player.currentMediaItem?.queueEntryId == entryId) {
            deferredRemoval.add(entryId)
            registerUndo { deferredRemoval.remove(entryId) }
        } else {
            val removed = player.getMediaItemAt(index)
            val nextId = if (index + 1 < player.mediaItemCount) player.getMediaItemAt(index + 1).queueEntryId else null
            val orderBefore = player.playbackOrderIndices(true).mapNotNull { player.getMediaItemAt(it).queueEntryId }
            player.removeMediaItem(index)
            registerUndo {
                if ((0 until player.mediaItemCount).none { player.getMediaItemAt(it).queueEntryId == entryId }) {
                    val currentOrder = player.playbackOrderIndices(true).mapNotNull { player.getMediaItemAt(it).queueEntryId }.toMutableList()
                    val followingId = orderBefore.drop(orderBefore.indexOf(entryId) + 1).firstOrNull(currentOrder::contains)
                    val orderPosition = followingId?.let(currentOrder::indexOf) ?: currentOrder.size
                    currentOrder.add(orderPosition, entryId)
                    val insertion = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).queueEntryId == nextId } ?: player.mediaItemCount
                    player.addMediaItem(insertion, removed)
                    val positions = (0 until player.mediaItemCount).associateBy { player.getMediaItemAt(it).queueEntryId }
                    val order = currentOrder.mapNotNull(positions::get)
                    if (order.size == player.mediaItemCount) (player as? StableDeckPlayer)?.setPlaybackOrder(order)
                }
            }
        }
        refresh()
        return ThorOperationResult(true, if (entryId in deferredRemoval) "当前曲目播完后移出" else "已移出队列", token)
    }


    private fun registerUndo(action: () -> Unit): String {
        val token = "queue:${UUID.randomUUID()}"
        if (undos.size >= 20) undos.remove(undos.keys.first())
        undos[token] = QueueUndo(undoTracker.epoch, action)
        return token
    }

    fun undo(token: String): ThorOperationResult {
        takePlaybackControl()
        val player = connection?.player ?: return ThorOperationResult(false, "播放器尚未连接")
        observeQueueIdentity(player)
        val undo = undos.remove(token) ?: return ThorOperationResult(false, "此操作已无法撤销")
        if (undo.epoch != undoTracker.epoch) return ThorOperationResult(false, "播放队列已变化，无法撤销")
        undo.action()
        refresh()
        return ThorOperationResult(true, "已撤销")
    }

    fun close() { attach(null); resetQueueActions() }
}

private fun clampThorSeekPosition(positionMs: Long, durationMs: Long): Long =
    positionMs.coerceIn(0, durationMs.coerceAtLeast(0))

/** An unknown duration must not turn every lyric line into a seek to the beginning. */
@UnstableApi
internal fun Player.seekAndPlayLyric(positionMs: Long, durationMs: Long) {
    val target = if (durationMs > 0) clampThorSeekPosition(positionMs, durationMs) else positionMs.coerceAtLeast(0)
    seekTo(target)
    play()
}

private fun MediaItem.thorMetadata(): MediaMetadata = metadata ?: MediaMetadata(
    id = mediaId.toLongOrNull() ?: mediaId.hashCode().toLong(),
    title = mediaMetadata.title?.toString().orEmpty().ifBlank { "未命名歌曲" },
    coverUrl = mediaMetadata.artworkUri?.toString().orEmpty(),
    artists = listOf(MediaMetadata.Artist(0, mediaMetadata.artist?.toString().orEmpty())),
    duration = 0,
    album = MediaMetadata.Album(0, mediaMetadata.albumTitle?.toString().orEmpty()),
    isLocal = (mediaId.toLongOrNull() ?: 0) < 0,
)

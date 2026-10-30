package com.ljyh.mei.thor.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.extensions.togglePlayPause
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.playback.StableDeckPlayer
import com.ljyh.mei.playback.nextPlaybackMediaItemIndex
import com.ljyh.mei.playback.normalizeQueueMediaItems
import com.ljyh.mei.playback.playbackOrderIndices
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.playback.queueEntryId
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.model.ThorOperationResult
import com.ljyh.mei.thor.model.toThorItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

/** Observes one existing connection. It never owns or replaces the playback service. */
@UnstableApi
class ThorPlaybackBridge(private val scope: CoroutineScope) : Player.Listener {
    private val mutableState = MutableStateFlow(ThorPlaybackState())
    val state = mutableState.asStateFlow()
    private var connection: PlayerConnection? = null
    private var ticker: Job? = null
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
        resetQueueActions()
        observedPlayer = null
        observedIdentityEpoch = null
        this.connection = connection
        connection?.player?.addListener(this)
        refresh()
        updateTicker()
    }

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
        val items = player.playbackOrderIndices().mapNotNull { index ->
            val item = player.getMediaItemAt(index)
            val entryId = item.queueEntryId ?: return@mapNotNull null
            ThorQueueItem(entryId, item.thorMetadata(), entryId in deferredRemoval)
        }
        val nextIndex = player.nextPlaybackMediaItemIndex()
        val nextId = if (nextIndex in 0 until player.mediaItemCount) player.getMediaItemAt(nextIndex).queueEntryId else null
        mutableState.value = ThorPlaybackState(
            connected = true,
            current = player.currentMediaItem?.thorMetadata(),
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
        )
    }

    fun position(): Long = connection?.player?.currentPosition?.coerceAtLeast(0) ?: 0
    fun toggle() { connection?.player?.togglePlayPause(); refresh() }
    fun previous() { connection?.seekToPrevious() }
    fun next() { connection?.seekToNext() }
    fun switchMode() { connection?.switchPlayMode(); refresh() }
    fun seek(positionMs: Long) {
        val duration = state.value.durationMs
        connection?.player?.seekTo(positionMs.coerceIn(0, duration.coerceAtLeast(0)))
        refresh()
    }
    fun startTimer(minutes: Int) { connection?.service?.sleepTimer?.start(minutes); refresh() }
    fun clearTimer() { connection?.service?.sleepTimer?.clear(); refresh() }
    fun select(entryId: String) { connection?.service?.queueManager?.seekToQueueEntry(entryId) }
    fun skip(entryId: String): ThorOperationResult {
        val connection = connection ?: return ThorOperationResult(false, "播放器尚未连接")
        if (connection.player.currentMediaItem?.queueEntryId != entryId) return ThorOperationResult(false, "该曲目已结束")
        if (!connection.player.hasNextMediaItem()) return ThorOperationResult(false, "没有下一首")
        connection.seekToNext()
        return ThorOperationResult(true, "已跳过此曲")
    }

    fun play(tracks: List<MediaMetadata>, index: Int, title: String, shuffle: Boolean? = null): Boolean {
        val connection = connection ?: return false
        if (tracks.isEmpty() || index !in tracks.indices) return false
        resetQueueActions()
        connection.playQueue(ListQueue(
            id = "thor:${UUID.randomUUID()}", title = title,
            items = tracks.map { it.id.toString() to it.toMediaItem() }, startIndex = index,
        ), shuffle = shuffle ?: connection.player.shuffleModeEnabled,
            repeatMode = if (shuffle != null && connection.player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ALL else connection.player.repeatMode)
        return true
    }

    fun enqueue(tracks: List<MediaMetadata>, next: Boolean): ThorOperationResult {
        val connection = connection ?: return ThorOperationResult(false, "播放器尚未连接")
        if (tracks.isEmpty()) return ThorOperationResult(false, "没有可播放的歌曲")
        val player = connection.player
        observeQueueIdentity(player)
        // Queue edits run asynchronously in the existing owner. Assign identities before
        // dispatch, rather than diffing a timeline that may not have updated yet.
        val existingIds = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).queueEntryId }.toSet()
        val items = normalizeQueueMediaItems(tracks.map { it.toMediaItem() }, existingIds)
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
        return ThorOperationResult(true, if (next) "已设为下一首播放" else "已加入队列", token)
    }

    fun remove(entryId: String): ThorOperationResult {
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

private fun MediaItem.thorMetadata(): MediaMetadata = metadata ?: MediaMetadata(
    id = mediaId.toLongOrNull() ?: mediaId.hashCode().toLong(),
    title = mediaMetadata.title?.toString().orEmpty().ifBlank { "未命名歌曲" },
    coverUrl = mediaMetadata.artworkUri?.toString().orEmpty(),
    artists = listOf(MediaMetadata.Artist(0, mediaMetadata.artist?.toString().orEmpty())),
    duration = 0,
    album = MediaMetadata.Album(0, mediaMetadata.albumTitle?.toString().orEmpty()),
    isLocal = (mediaId.toLongOrNull() ?: 0) < 0,
)

package com.ljyh.mei.thor.menu

import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.model.ThorMenuSubject

data class ThorMenuAnchor(val x: Float, val y: Float)
enum class ThorMenuLevel { Actions, Playlists, Name }
enum class ThorMenuAction {
    Like, Collect, PlayNext, Enqueue, AddToPlaylist, Album, RemoveFromPlaylist, RemoveFromQueue, SkipCurrent, Rename, Delete,
}
data class ThorMenuRow(val action: ThorMenuAction, val title: String, val destructive: Boolean = false)

const val THOR_CREATE_PLAYLIST_TARGET_KEY = "menu:create-playlist"

/** A target identity survives loading/reordering and keeps local/remote playlist IDs distinct. */
sealed interface ThorPlaylistTarget {
    val stableKey: String
    data class Existing(val key: ThorItemKey.Playlist) : ThorPlaylistTarget {
        override val stableKey get() = key.stableId
    }
    data object Create : ThorPlaylistTarget {
        override val stableKey = THOR_CREATE_PLAYLIST_TARGET_KEY
    }
}

data class ThorMenuState(
    val subject: ThorMenuSubject,
    val anchor: ThorMenuAnchor,
    val level: ThorMenuLevel = ThorMenuLevel.Actions,
    val selection: Int = 0,
    val parentSelection: Int = 0,
    /** Layout cache only. Confirmation and memory always use playlistTarget. */
    val playlistSelection: Int = 0,
    val playlistTarget: ThorPlaylistTarget? = null,
    val name: String = "",
    val renaming: Boolean = false,
    val busy: Boolean = false,
) {
    fun move(delta: Int, count: Int): ThorMenuState = if (busy || count == 0 || level == ThorMenuLevel.Playlists) this else copy(selection = (selection + delta).mod(count))
    fun intoPlaylists(targets: List<ThorItemKey.Playlist> = emptyList()): ThorMenuState = copy(
        level = ThorMenuLevel.Playlists, parentSelection = selection,
        playlistTarget = playlistTarget ?: targets.firstOrNull()?.let { ThorPlaylistTarget.Existing(it) } ?: ThorPlaylistTarget.Create,
    ).syncPlaylistTargets(targets)

    fun syncPlaylistTargets(targets: List<ThorItemKey.Playlist>): ThorMenuState {
        val target = playlistTarget ?: return this
        val index = playlistChoices(targets).indexOf(target)
        // A disappeared target stays selected by identity, with no replacement highlighted.
        return copy(playlistSelection = index, selection = if (level == ThorMenuLevel.Playlists) index else selection)
    }

    fun selectPlaylistTarget(target: ThorPlaylistTarget, targets: List<ThorItemKey.Playlist>): ThorMenuState =
        if (busy || level != ThorMenuLevel.Playlists) this else copy(playlistTarget = target).syncPlaylistTargets(targets)

    fun movePlaylist(delta: Int, targets: List<ThorItemKey.Playlist>): ThorMenuState {
        if (busy || level != ThorMenuLevel.Playlists || delta == 0) return this
        val choices = playlistChoices(targets)
        val previous = choices.indexOf(playlistTarget)
        // Only explicit new navigation may choose another target after the old one disappears.
        val index = if (previous < 0) { if (delta < 0) choices.lastIndex else 0 }
            else (previous + delta).mod(choices.size)
        return selectPlaylistTarget(choices[index], targets)
    }

    fun resolvePlaylistTarget(targets: List<ThorItemKey.Playlist>): ThorPlaylistTarget? = when (val target = playlistTarget) {
        ThorPlaylistTarget.Create -> target
        is ThorPlaylistTarget.Existing -> target.takeIf { it.key in targets }
        null -> null
    }

    fun intoName(rename: Boolean): ThorMenuState = copy(
        level = ThorMenuLevel.Name, playlistSelection = if (level == ThorMenuLevel.Playlists) selection else playlistSelection, selection = 0,
        renaming = rename, name = if (rename) subject.item.title else "",
    )
    fun back(): ThorMenuState? = if (busy) this else when (level) {
        ThorMenuLevel.Actions -> null
        ThorMenuLevel.Playlists -> copy(level = ThorMenuLevel.Actions, playlistSelection = selection, selection = parentSelection)
        ThorMenuLevel.Name -> if (renaming) copy(level = ThorMenuLevel.Actions, selection = parentSelection)
            else copy(level = ThorMenuLevel.Playlists, selection = playlistSelection)
    }

    private fun playlistChoices(targets: List<ThorItemKey.Playlist>): List<ThorPlaylistTarget> =
        targets.map { ThorPlaylistTarget.Existing(it) } + ThorPlaylistTarget.Create
}

fun ThorMenuState.actionRows(liked: Boolean): List<ThorMenuRow> = buildList {
    val item = subject.item
    val song = item.key is ThorItemKey.Song || item.key is ThorItemKey.QueueEntry
    if (song) add(ThorMenuRow(ThorMenuAction.Like, if (liked) "取消喜欢" else "喜欢"))
    if (item.key is ThorItemKey.Album || item.key is ThorItemKey.Playlist && !item.owned) {
        add(ThorMenuRow(ThorMenuAction.Collect, if (item.collected) "取消收藏" else "收藏"))
    }
    add(ThorMenuRow(ThorMenuAction.PlayNext, "下一首播放"))
    add(ThorMenuRow(ThorMenuAction.Enqueue, "加入播放队列"))
    add(ThorMenuRow(ThorMenuAction.AddToPlaylist, "加入歌单"))
    if (song && item.tracks.firstOrNull()?.album?.id != null) add(ThorMenuRow(ThorMenuAction.Album, "查看专辑"))
    if (subject.sourcePlaylist != null) add(ThorMenuRow(ThorMenuAction.RemoveFromPlaylist, "从歌单移除", true))
    if (subject.queueEntryId != null) {
        if (subject.currentQueueEntry) add(ThorMenuRow(ThorMenuAction.SkipCurrent, "跳过此曲"))
        else add(ThorMenuRow(ThorMenuAction.RemoveFromQueue, "移出队列", true))
    }
    if (item.key is ThorItemKey.Playlist && item.owned) {
        add(ThorMenuRow(ThorMenuAction.Rename, "重命名"))
        add(ThorMenuRow(ThorMenuAction.Delete, "删除歌单", true))
    }
}

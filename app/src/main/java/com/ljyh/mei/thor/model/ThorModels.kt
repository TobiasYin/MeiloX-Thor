package com.ljyh.mei.thor.model

import com.ljyh.mei.data.model.MediaMetadata

/** Stable identities are deliberately distinct from display labels and list indices. */
sealed interface ThorItemKey {
    val stableId: String

    data class Song(val id: Long, val local: Boolean = false) : ThorItemKey {
        override val stableId get() = "song:$local:$id"
    }
    data class Album(val id: Long, val local: Boolean = false) : ThorItemKey {
        override val stableId get() = "album:$local:$id"
    }
    data class Playlist(val id: String, val local: Boolean = false) : ThorItemKey {
        override val stableId get() = "playlist:$local:$id"
    }
    data class QueueEntry(val entryId: String) : ThorItemKey {
        override val stableId get() = "queue:$entryId"
    }
    data class Destination(val id: String) : ThorItemKey {
        override val stableId get() = "destination:$id"
    }
}

data class ThorItem(
    val key: ThorItemKey,
    val title: String,
    val subtitle: String = "",
    val artworkUri: String? = null,
    val description: String = "",
    val trackCount: Int? = null,
    val tracks: List<MediaMetadata> = emptyList(),
    val owned: Boolean = false,
    val collected: Boolean = false,
) {
    val artwork get() = ThorArtworkSpec(key.stableId, artworkUri, artworkUri, title, subtitle)
}

fun MediaMetadata.toThorItem() = ThorItem(
    key = ThorItemKey.Song(id, isLocal),
    title = title,
    subtitle = artists.joinToString(" / ") { it.name },
    artworkUri = coverUrl.takeIf(String::isNotBlank),
    description = album.title,
    tracks = listOf(this),
)

data class ThorArtworkSpec(
    val key: String,
    val frontUri: String?,
    val backUri: String? = frontUri,
    val title: String = "",
    val artist: String = "",
)

enum class ThorSessionType { None, Web, Mobile }

/** Public account projection; credentials never cross the repository boundary. */
data class ThorAccountState(
    val userId: String? = null,
    val nickname: String = "本地音乐",
    val avatarUri: String? = null,
    val sessionType: ThorSessionType = ThorSessionType.None,
    val generation: Long = 0,
) {
    val signedIn get() = userId != null && sessionType != ThorSessionType.None
}

enum class ThorCollection(val title: String) {
    Home("首页"), Discover("发现"), LocalSongs("本地音乐"), LocalAlbums("本地专辑"),
    LikedSongs("喜欢的音乐"), CollectedAlbums("收藏专辑"),
    OwnedPlaylists("创建的歌单"), SavedPlaylists("收藏歌单"), Downloads("下载"), History("最近播放"),
}

data class ThorLibraryState(
    val account: ThorAccountState = ThorAccountState(),
    val collections: Map<ThorCollection, List<ThorItem>> = emptyMap(),
    val songLikes: Map<Long, Boolean> = emptyMap(),
    val loading: Set<ThorCollection> = emptySet(),
    val errors: Map<ThorCollection, String> = emptyMap(),
) {
    operator fun get(collection: ThorCollection): List<ThorItem> = collections[collection].orEmpty()
}

data class ThorOperationResult(
    val success: Boolean,
    val message: String,
    val undoToken: String? = null,
    val createdPlaylist: ThorItemKey.Playlist? = null,
)

enum class ThorThemePack(val title: String) { Ceramic("微砂陶瓷"), Glass("晶雾玻璃"), Titanium("钛金") }
enum class ThorAppearance(val title: String) { System("随系统"), Day("白天"), Night("夜间") }

data class ThorPreferences(
    val theme: ThorThemePack = ThorThemePack.Ceramic,
    val appearance: ThorAppearance = ThorAppearance.System,
    val reduceMotion: Boolean = false,
    val reduceTransparency: Boolean = false,
    val airflowEnabled: Boolean = false,
)

enum class ThorDirection { Up, Down, Left, Right }

/** One physical shoulder gesture. Press is immediate; only a short gesture emits Tap. */
enum class ThorShoulderPhase { Press, Tap, HoldStart, HoldStep, Release, Cancel }

sealed interface ThorCommand {
    data class Move(val direction: ThorDirection, val repeated: Boolean = false) : ThorCommand
    data class Shoulder(val right: Boolean, val phase: ThorShoulderPhase) : ThorCommand
    data object Confirm : ThorCommand
    data object Back : ThorCommand
    data object ContextMenu : ThorCommand
    data object TabPrevious : ThorCommand
    data object TabNext : ThorCommand
    data object TogglePlayer : ThorCommand
    data object ToggleQueue : ThorCommand
    data object PageActions : ThorCommand
    data object TogglePlayback : ThorCommand
    data object PreviousTrack : ThorCommand
    data object NextTrack : ThorCommand
    data object ShuffleAll : ThorCommand
    data object PlayAll : ThorCommand
}

/** Frozen when the menu opens, so changing focus cannot retarget a pending mutation. */
data class ThorMenuSubject(
    val item: ThorItem,
    val originFocusKey: String,
    val sourcePlaylist: ThorItemKey.Playlist? = null,
    val queueEntryId: String? = null,
    val currentQueueEntry: Boolean = false,
)

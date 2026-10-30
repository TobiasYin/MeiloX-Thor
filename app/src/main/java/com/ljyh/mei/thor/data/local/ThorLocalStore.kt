package com.ljyh.mei.thor.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Device data never masquerades as an authenticated account's remote collections. */
@Entity(tableName = "thor_favorite", primaryKeys = ["scope", "kind", "itemId", "local"])
data class ThorFavoriteEntity(
    val scope: String,
    val kind: String,
    val itemId: Long,
    val local: Boolean,
    val title: String,
    val subtitle: String,
    val artworkUri: String?,
    val description: String,
    val tracksJson: String,
    val createdAt: Long,
)

@Entity(tableName = "thor_local_playlist")
data class ThorPlaylistEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "thor_local_playlist_track",
    primaryKeys = ["playlistId", "songKey"],
    foreignKeys = [ForeignKey(
        entity = ThorPlaylistEntity::class,
        parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("playlistId")],
)
data class ThorPlaylistTrackEntity(
    val playlistId: String,
    val songKey: String,
    val position: Int,
    val metadataJson: String,
)

@Dao
interface ThorLocalDao {
    @Query("SELECT * FROM thor_favorite ORDER BY createdAt DESC")
    fun observeFavorites(): Flow<List<ThorFavoriteEntity>>

    @Query("SELECT * FROM thor_favorite WHERE scope=:scope AND kind=:kind AND itemId=:id AND local=:local")
    suspend fun favorite(scope: String, kind: String, id: Long, local: Boolean): ThorFavoriteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFavorite(item: ThorFavoriteEntity)

    @Query("DELETE FROM thor_favorite WHERE scope=:scope AND kind=:kind AND itemId=:id AND local=:local")
    suspend fun removeFavorite(scope: String, kind: String, id: Long, local: Boolean)

    @Query("SELECT * FROM thor_local_playlist ORDER BY updatedAt DESC, id")
    fun observePlaylists(): Flow<List<ThorPlaylistEntity>>

    @Query("SELECT * FROM thor_local_playlist_track ORDER BY playlistId, position")
    fun observeTracks(): Flow<List<ThorPlaylistTrackEntity>>

    @Query("SELECT * FROM thor_local_playlist WHERE id=:id")
    suspend fun playlist(id: String): ThorPlaylistEntity?

    @Query("SELECT * FROM thor_local_playlist_track WHERE playlistId=:id ORDER BY position")
    suspend fun tracks(id: String): List<ThorPlaylistTrackEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlaylist(playlist: ThorPlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTracks(tracks: List<ThorPlaylistTrackEntity>)

    @Query("UPDATE thor_local_playlist SET name=:name, updatedAt=MAX(updatedAt + 1, :time) WHERE id=:id")
    suspend fun rename(id: String, name: String, time: Long)

    @Query("UPDATE thor_local_playlist SET updatedAt=MAX(updatedAt + 1, :time) WHERE id=:id")
    suspend fun touch(id: String, time: Long)

    @Query("DELETE FROM thor_local_playlist WHERE id=:id")
    suspend fun delete(id: String)

    @Query("DELETE FROM thor_local_playlist_track WHERE playlistId=:id")
    suspend fun clearTracks(id: String)

    @Transaction
    suspend fun create(playlist: ThorPlaylistEntity, tracks: List<ThorPlaylistTrackEntity>) {
        insertPlaylist(playlist)
        insertTracks(tracks)
    }

    @Transaction
    suspend fun append(id: String, incoming: List<ThorPlaylistTrackEntity>): Int {
        check(playlist(id) != null) { "歌单已不存在" }
        val previous = tracks(id)
        val added = appendUniqueTracks(previous, incoming)
        insertTracks(added)
        if (added.isNotEmpty()) touch(id, System.currentTimeMillis())
        return added.size
    }

    @Transaction
    suspend fun replace(id: String, tracks: List<ThorPlaylistTrackEntity>) {
        check(playlist(id) != null) { "歌单已不存在" }
        clearTracks(id)
        insertTracks(tracks.mapIndexed { index, track -> track.copy(playlistId = id, position = index) })
        touch(id, System.currentTimeMillis())
    }
}

/** Preserve existing order and deduplicate by source identity, never by display title. */
internal fun appendUniqueTracks(
    existing: List<ThorPlaylistTrackEntity>,
    incoming: List<ThorPlaylistTrackEntity>,
): List<ThorPlaylistTrackEntity> {
    val seen = existing.mapTo(hashSetOf()) { it.songKey }
    var position = (existing.maxOfOrNull { it.position } ?: -1) + 1
    return incoming.filter { seen.add(it.songKey) }.map { it.copy(position = position++) }
}

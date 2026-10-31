package com.ljyh.mei.data.model
import com.google.gson.annotations.SerializedName


data class Tracks(
    @SerializedName("code")
    val code: Int,
    @SerializedName("privileges")
    val privileges: List<PlaylistDetail.Privilege>? = null,
    @SerializedName("songs")
    val songs: List<PlaylistDetail.Playlist.Track>
)
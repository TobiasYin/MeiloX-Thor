package com.ljyh.mei.thor.debug

import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.data.repository.LibraryCollectionsRepository
import com.ljyh.mei.data.repository.PlayerRepository
import com.ljyh.mei.data.repository.PlaylistRepository
import com.ljyh.mei.data.repository.SearchRepository
import com.ljyh.mei.thor.data.ThorMusicRepository
import com.ljyh.mei.thor.data.ThorCatalogRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Opt-in instrumentation uses the installed application's real repositories and session. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ThorLiveCatalogEntryPoint {
    fun meloXRepository(): MeloXRepository
    fun thorMusicRepository(): ThorMusicRepository
    fun thorCatalogRepository(): ThorCatalogRepository
    fun playerRepository(): PlayerRepository
    fun playlistRepository(): PlaylistRepository
    fun searchRepository(): SearchRepository
    fun libraryCollectionsRepository(): LibraryCollectionsRepository
}

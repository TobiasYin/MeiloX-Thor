package com.ljyh.mei.thor.state

import com.ljyh.mei.thor.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Per-artist catalogs survive album/player visits; invalidation rejects every old account read. */
internal class ThorArtistBrowser(
    private val scope: CoroutineScope,
    private val profile: suspend (ThorItem) -> ThorArtistProfile,
    private val songs: suspend (Long, Int) -> ThorCatalogPage,
    private val albums: suspend (Long, Int) -> ThorCatalogPage,
) {
    private val selected = MutableStateFlow<Long?>(null)
    private val states = MutableStateFlow<Map<Long, ThorArtistBrowseState>>(emptyMap())
    private val jobs = mutableMapOf<Pair<Long, ThorArtistSection>, Job>()
    private val revisions = mutableMapOf<Pair<Long, ThorArtistSection>, Long>()
    private var generation = 0L
    val current: StateFlow<ThorArtistBrowseState?> = combine(selected, states) { id, cache -> cache[id] }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun open(item: ThorItem) {
        val id = (item.key as? ThorItemKey.Artist)?.id ?: return
        states.update { cache -> if (id in cache) cache else cache + (id to ThorArtistBrowseState(item)) }
        selected.value = id
        val state = states.value.getValue(id)
        if (state.section !in state.pages && !state.isLoading) request(id, state.section, more = false)
    }

    fun select(section: ThorArtistSection) {
        val id = selected.value ?: return
        states.update { cache -> cache + (id to cache.getValue(id).copy(section = section)) }
        val state = states.value.getValue(id)
        if (section !in state.pages && !state.isLoading) request(id, section, more = false)
    }

    fun refresh() {
        val id = selected.value ?: return
        request(id, states.value.getValue(id).section, more = false)
    }

    fun loadMore() {
        val id = selected.value ?: return
        val state = states.value.getValue(id)
        if (state.hasMore && !state.isLoading) request(id, state.section, more = true)
    }

    fun invalidate() {
        generation++
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        revisions.clear()
        selected.value = null
        states.value = emptyMap()
    }

    private fun request(id: Long, section: ThorArtistSection, more: Boolean) {
        val before = states.value[id] ?: return
        val key = id to section
        if (more && section in before.loading) return
        val offset = if (more) before.nextOffsets[section] ?: return else 0
        jobs.remove(key)?.cancel()
        val version = (revisions[key] ?: 0L) + 1
        revisions[key] = version
        val account = generation
        fun isCurrent() = generation == account && revisions[key] == version && states.value[id] != null
        fun update(transform: (ThorArtistBrowseState) -> ThorArtistBrowseState) {
            if (isCurrent()) states.update { cache -> cache + (id to transform(cache.getValue(id))) }
        }
        update { it.copy(loading = it.loading + section, errors = it.errors - section) }
        jobs[key] = scope.launch {
            try {
                val artistProfile = if (section == ThorArtistSection.HotSongs) profile(before.item) else null
                val result = when (section) {
                    ThorArtistSection.HotSongs -> ThorCatalogPage(checkNotNull(artistProfile).hotSongs)
                    ThorArtistSection.Songs -> songs(id, offset)
                    ThorArtistSection.Albums -> albums(id, offset)
                }
                // An empty page claiming another page cannot progress a controller list safely.
                check(!result.hasMore || result.items.isNotEmpty()) { "没有读到更多内容，请重试" }
                check(result.nextOffset == null || result.nextOffset > offset) { "列表分页没有推进，请重试" }
                update { state -> state.copy(
                    item = artistProfile?.item ?: state.item,
                    pages = state.pages + (section to ((if (more) state.pages[section].orEmpty() else emptyList()) + result.items).distinctBy { it.key }),
                    nextOffsets = if (result.nextOffset != null) state.nextOffsets + (section to result.nextOffset)
                        else state.nextOffsets - section,
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { update { it.copy(errors = it.errors + (section to "加载失败，请重试")) } }
            finally { update { it.copy(loading = it.loading - section) } }
        }
    }
}

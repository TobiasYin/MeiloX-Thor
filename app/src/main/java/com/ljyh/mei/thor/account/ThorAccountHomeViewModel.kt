package com.ljyh.mei.thor.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.melox.AccountDetail
import com.ljyh.mei.data.model.melox.AccountPlaylist
import com.ljyh.mei.data.model.melox.AccountProfile
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.thor.model.ThorAccountState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class ThorAccountHomeState(
    val profile: AccountProfile? = null,
    val detail: AccountDetail? = null,
    val playlists: List<AccountPlaylist> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val generation: Long = -1,
)

/** Read-only account projection. The shared repository remains the sole session owner. */
@HiltViewModel
internal class ThorAccountHomeViewModel @Inject constructor(
    private val repository: MeloXRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ThorAccountHomeState())
    val state = mutableState.asStateFlow()
    private var account: ThorAccountState? = null
    private var revision = 0L
    private var job: Job? = null

    fun load(next: ThorAccountState, force: Boolean = false) {
        if (!force && account?.generation == next.generation && account?.userId == next.userId) return
        val changed = account?.generation != next.generation || account?.userId != next.userId
        account = next
        val request = ++revision
        job?.cancel()
        if (changed) mutableState.value = ThorAccountHomeState(generation = next.generation)
        if (!next.signedIn) return
        job = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            try {
                val profile = repository.accountProfile()
                ensureActive()
                check(profile.id.toString() == next.userId) { "账号已变更，请重新打开账户" }
                val (detail, playlists) = coroutineScope {
                    val detail = async { runCatching { repository.accountDetail(profile.id) }.getOrElse {
                        if (it is CancellationException) throw it
                        null
                    } }
                    val playlists = async { repository.accountPlaylists(profile.id) }
                    detail.await() to playlists.await()
                }
                if (revision == request) mutableState.value = ThorAccountHomeState(profile, detail, playlists, generation = next.generation)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (revision == request) mutableState.value = mutableState.value.copy(loading = false, error = failure.message ?: "账户加载失败")
            }
        }
    }
}

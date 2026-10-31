package com.ljyh.mei.thor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.ljyh.mei.thor.ThorNotice
import kotlinx.coroutines.delay

internal fun thorNoticeTimeoutMillis(success: Boolean, hasUndo: Boolean): Long? = when {
    !success -> null
    hasUndo -> 6_000L
    else -> 3_500L
}

internal suspend fun awaitThorNoticeDismiss(
    id: Long,
    success: Boolean,
    hasUndo: Boolean,
    currentId: () -> Long?,
    clearIfCurrent: (Long) -> Unit,
) {
    val timeout = thorNoticeTimeoutMillis(success, hasUndo) ?: return
    delay(timeout)
    if (currentId() == id) clearIfCurrent(id)
}

/** Each notice owns its timer; the ViewModel performs a final identity check when it expires. */
@Composable
internal fun ThorNoticeAutoDismiss(notice: ThorNotice?, clearIfCurrent: (Long) -> Unit) {
    val latestClear by rememberUpdatedState(clearIfCurrent)
    val latestId by rememberUpdatedState(notice?.id)
    LaunchedEffect(notice?.id) {
        val shown = notice ?: return@LaunchedEffect
        awaitThorNoticeDismiss(shown.id, shown.success, shown.undoToken != null, { latestId }, { latestClear(it) })
    }
}

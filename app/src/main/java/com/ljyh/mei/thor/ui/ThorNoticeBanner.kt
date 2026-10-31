package com.ljyh.mei.thor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.ljyh.mei.thor.ThorNotice
import com.ljyh.mei.thor.components.ThorSurfaceRole
import com.ljyh.mei.thor.components.thorMaterial
import com.ljyh.mei.thor.theme.LocalThorTheme

/** A resized IME viewport must not move an old notice's actions over the active search bar. */
@Composable
internal fun ThorNoticeBanner(
    notice: ThorNotice?,
    registry: ThorFocusRegistry,
    editingSearch: Boolean,
    onUndo: (Long) -> Unit,
    onClose: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (notice == null || editingSearch) return
    val theme = LocalThorTheme.current
    Row(modifier.testTag("thor-notice").fillMaxWidth().heightIn(min = 48.dp)
        .thorMaterial(ThorSurfaceRole.Overlay, cornerRadius = theme.controlRadius)
        .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(notice.message, Modifier.weight(1f), color = theme.text, fontSize = 14.sp, maxLines = 2)
        if (notice.undoToken != null) ThorButton(registry, "notice:undo", "撤销",
            Modifier.testTag("thor-notice-undo"), flat = true, onClick = { onUndo(notice.id) })
        ThorButton(registry, "notice:close", "", Modifier.testTag("thor-notice-close"),
            icon = Icons.Outlined.Close, flat = true, contentDescription = "关闭提示", onClick = { onClose(notice.id) })
    }
}

package com.ljyh.mei.thor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import com.ljyh.mei.thor.model.isPlaybackUnavailable
import com.ljyh.mei.thor.model.playbackUnavailableReason
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ljyh.mei.thor.components.ThorActionButton
import com.ljyh.mei.thor.components.ThorSurfaceRole
import com.ljyh.mei.thor.components.thorMaterial
import com.ljyh.mei.thor.menu.ThorMenuAnchor
import com.ljyh.mei.thor.model.ThorItem
import kotlinx.coroutines.flow.collect

@Composable
internal fun Modifier.thorFocus(
    registry: ThorFocusRegistry,
    key: String,
    item: ThorItem? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val requester = remember(key) { FocusRequester() }
    val entry = remember(key) { ThorFocusEntry(requester, onClick, item) }
    SideEffect { entry.action = onClick; entry.item = item; entry.enabled = enabled }
    DisposableEffect(registry, key) {
        registry.register(key, entry)
        onDispose { registry.unregister(key, entry) }
    }
    return this.focusRequester(requester)
        .focusProperties { canFocus = enabled && !registry.blocked && registry.isAllowed(key) }
        .onFocusChanged { if (it.isFocused) registry.select(key) }
        .onGloballyPositioned { entry.bounds = it.boundsInRoot() }
}

@Composable
internal fun ThorButton(
    registry: ThorFocusRegistry, key: String, label: String,
    modifier: Modifier = Modifier, icon: ImageVector? = null, primary: Boolean = false,
    selected: Boolean = false, enabled: Boolean = true, contentDescription: String? = null,
    flat: Boolean = false, round: Boolean = false, iconSize: Dp = 16.dp,
    contentPadding: PaddingValues? = null,
    onClick: () -> Unit,
) {
    val theme = com.ljyh.mei.thor.theme.LocalThorTheme.current
    val description = contentDescription ?: label.takeIf(String::isNotBlank) ?: when (icon?.name?.substringAfterLast('.')) {
        "ArrowBack", "ChevronLeft" -> "返回"; "PlayArrow" -> "播放"; "Pause" -> "暂停"
        "SkipPrevious" -> "上一首"; "SkipNext" -> "下一首"; "Close" -> "关闭"
        "MoreHoriz", "MoreVert" -> "更多操作"; "Search" -> "搜索"
        else -> "操作"
    }
    ThorActionButton(
        onClick = { if (enabled && !registry.blocked) { registry.select(key); onClick() } },
        modifier = modifier.then(if (label.isBlank() && icon != null) Modifier.size(48.dp) else Modifier)
            .thorFocus(registry, key, enabled = enabled, onClick = onClick)
            .semantics { if (label.isBlank() || contentDescription != null) this.contentDescription = description },
        primary = primary, selected = selected, enabled = enabled,
        contentPadding = contentPadding ?: if (label.isBlank()) PaddingValues(0.dp) else PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        role = when { flat -> ThorSurfaceRole.Flat; round && !primary -> ThorSurfaceRole.Transport; else -> null },
        shape = if (round) CircleShape else null,
        focused = enabled && registry.selectedKey == key,
    ) {
        if (icon != null) Icon(thorLineIcon(icon), null, Modifier.size(iconSize))
        if (label.isNotEmpty()) {
            if (icon != null) Spacer(Modifier.width(6.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = theme.typography.control, fontWeight = FontWeight.Normal)
        }
    }
}

@Composable
internal fun ThorCategoryButton(
    registry: ThorFocusRegistry, key: String, label: String, selected: Boolean,
    onClick: () -> Unit,
) {
    val theme = com.ljyh.mei.thor.theme.LocalThorTheme.current
    ThorActionButton(
        onClick = { if (!registry.blocked) { registry.select(key); onClick() } },
        modifier = Modifier.widthIn(min = 64.dp).height(48.dp)
            .thorFocus(registry, key, onClick = onClick)
            .semantics { this.selected = selected }
            .drawWithContent {
                drawContent()
                if (selected) drawLine(theme.accent, Offset(12.dp.toPx(), size.height - 1.5.dp.toPx()),
                    Offset(size.width - 12.dp.toPx(), size.height - 1.5.dp.toPx()), 3.dp.toPx())
            },
        role = ThorSurfaceRole.Flat,
        focused = registry.selectedKey == key,
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Text(label, color = if (selected) theme.selectedText else theme.secondary, fontSize = theme.typography.body,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
    }
}

@Composable
internal fun ThorCover(item: ThorItem?, modifier: Modifier = Modifier) {
    val theme = com.ljyh.mei.thor.theme.LocalThorTheme.current
    Box(modifier.clip(RoundedCornerShape(theme.smallRadius)).background(theme.inset).drawWithContent {
        drawContent()
        drawRoundRect(theme.contact, cornerRadius = CornerRadius(theme.smallRadius.toPx()), style = Stroke(1.dp.toPx()))
    }, contentAlignment = Alignment.Center) {
        if (!item?.artworkUri.isNullOrBlank()) AsyncImage(item?.artworkUri, item?.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(thorLineIcon(Icons.Outlined.Album), null, Modifier.size(16.dp), tint = theme.secondary)
    }
}

private class ThorRowPressPosition {
    var active: PressInteraction.Press? = null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ThorMusicRow(
    registry: ThorFocusRegistry, key: String, item: ThorItem,
    selected: Boolean = registry.selectedKey == key, trailing: String? = null,
    onClick: () -> Unit, onMenu: (ThorMenuAnchor) -> Unit,
    onFocused: () -> Unit = {},
    leadingIcon: ImageVector? = null,
    mark: ImageVector? = null,
) {
    val theme = com.ljyh.mei.thor.theme.LocalThorTheme.current
    val unavailable = item.isPlaybackUnavailable
    var bounds by remember(key) { mutableStateOf(Rect.Zero) }
    val interactions = remember(key) { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val press = remember(key) { ThorRowPressPosition() }
    val currentClick by rememberUpdatedState(onClick)
    val currentMenu by rememberUpdatedState(onMenu)
    val click = remember(key, registry) {
        { if (!registry.blocked) { registry.select(key); currentClick() } }
    }
    val longClick = remember(key, registry) {
        {
            if (!registry.blocked) {
                val local = press.active?.pressPosition?.takeIf { it.x.isFinite() && it.y.isFinite() }
                    ?: Offset(bounds.width / 2f, bounds.height / 2f)
                val anchor = ThorMenuAnchor(bounds.left + local.x, bounds.top + local.y)
                registry.select(key)
                currentMenu(anchor)
            }
        }
    }
    LaunchedEffect(interactions) {
        interactions.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> press.active = interaction
                is PressInteraction.Release -> if (press.active === interaction.press) press.active = null
                is PressInteraction.Cancel -> if (press.active === interaction.press) press.active = null
                else -> Unit
            }
        }
    }
    val configuration = LocalViewConfiguration.current
    val rowTouchSlopPx = with(LocalDensity.current) { 8.dp.toPx() }
    val rowConfiguration = remember(configuration, rowTouchSlopPx) {
        object : ViewConfiguration by configuration {
            override val longPressTimeoutMillis: Long = 460L
            override val touchSlop: Float = rowTouchSlopPx
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides rowConfiguration) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .thorFocus(registry, key, item, onClick = onClick)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .thorMaterial(ThorSurfaceRole.MusicRow, focused = selected, selected = selected, pressed = pressed)
            .semantics {
                this.selected = selected
                val states = buildList {
                    item.playbackUnavailableReason?.let(::add)
                    when (mark?.name?.substringAfterLast('.')) {
                        "Favorite" -> add("已喜欢")
                        "Bookmark" -> add("已收藏")
                        else -> Unit
                    }
                }
                if (states.isNotEmpty()) stateDescription = states.joinToString("，")
            }
            .combinedClickable(
                interactionSource = interactions,
                indication = null,
                enabled = !registry.blocked,
                onClick = click,
                onLongClick = longClick,
                onLongClickLabel = "更多操作",
            ).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leadingIcon != null) Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Icon(thorLineIcon(leadingIcon), null, Modifier.size(16.dp), tint = if (selected) theme.selectedText else theme.secondary)
        } else ThorCover(item, Modifier.size(36.dp).alpha(if (unavailable) .45f else 1f))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontSize = theme.typography.body, lineHeight = 21.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (unavailable) theme.secondary else theme.text)
            if (item.subtitle.isNotEmpty() && item.subtitle != item.title) Text(item.subtitle, fontSize = theme.typography.secondary, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = theme.secondary)
        }
        if (mark != null) Icon(thorLineIcon(mark), null, Modifier.size(16.dp), tint = theme.accent)
        if (unavailable) Text("不可播放", color = theme.secondary, fontSize = theme.typography.secondary, maxLines = 1)
        else if (trailing != null) Text(trailing, color = theme.secondary, fontSize = theme.typography.secondary, maxLines = 1)
        else Icon(thorLineIcon(Icons.Outlined.ChevronRight), null, Modifier.size(16.dp), tint = theme.secondary)
    }
    }
}

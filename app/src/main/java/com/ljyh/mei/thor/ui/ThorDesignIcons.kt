package com.ljyh.mei.thor.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Exact Lucide geometry exported from the approved prototype DOM. Circle/line/rounded-rect
 * elements are losslessly expressed as SVG path commands; the viewport and 2px round stroke
 * are unchanged. The DOM computes interaction glyphs at 16px, independent of their hit targets.
 * Copyright (c) 2026 Lucide Icons and Contributors; Feather-derived icons: Cole Bemis.
 * License notices: app/src/main/assets/licenses/lucide.LICENSE.
 * Export SHA-256: a9946e57cdeaeb4ff13887c3c9877cf8a402406d06518d2414288fa5d700032a
 */
internal object ThorDesignIcons {
    private val vectors = mutableMapOf<Pair<String, Boolean>, ImageVector>()
    internal fun icon(name: String, filled: Boolean = false): ImageVector = vectors.getOrPut(name to filled) {
        val geometry = requireNotNull(paths[name]) { "Unknown approved Thor icon: $name" }
        ImageVector.Builder("Thor.Lucide.$name", 16.dp, 16.dp, 24f, 24f).apply {
            geometry.forEach { data ->
                addPath(
                    pathData = PathParser().parsePathString(data).toNodes(),
                    fill = if (filled) SolidColor(Color.Black) else null,
                    stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
    }

    private val paths = mapOf(
        "chevron-left" to listOf(
            "m15 18-6-6 6-6",
        ),
        "chevron-right" to listOf(
            "m9 18 6-6-6-6",
        ),
        "play" to listOf(
            "M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z",
        ),
        "pause" to listOf(
            "M15 3H18A1 1 0 0 1 19 4V20A1 1 0 0 1 18 21H15A1 1 0 0 1 14 20V4A1 1 0 0 1 15 3Z",
            "M6 3H9A1 1 0 0 1 10 4V20A1 1 0 0 1 9 21H6A1 1 0 0 1 5 20V4A1 1 0 0 1 6 3Z",
        ),
        "skip-back" to listOf(
            "M17.971 4.285A2 2 0 0 1 21 6v12a2 2 0 0 1-3.029 1.715l-9.997-5.998a2 2 0 0 1-.003-3.432z",
            "M3 20V4",
        ),
        "skip-forward" to listOf(
            "M21 4v16",
            "M6.029 4.285A2 2 0 0 0 3 6v12a2 2 0 0 0 3.029 1.715l9.997-5.998a2 2 0 0 0 .003-3.432z",
        ),
        "heart" to listOf(
            "M2 9.5a5.5 5.5 0 0 1 9.591-3.676.56.56 0 0 0 .818 0A5.49 5.49 0 0 1 22 9.5c0 2.29-1.5 4-3 5.5l-5.492 5.313a2 2 0 0 1-3 .019L5 15c-1.5-1.5-3-3.2-3-5.5",
        ),
        "bookmark" to listOf(
            "M17 3a2 2 0 0 1 2 2v15a1 1 0 0 1-1.496.868l-4.512-2.578a2 2 0 0 0-1.984 0l-4.512 2.578A1 1 0 0 1 5 20V5a2 2 0 0 1 2-2z",
        ),
        "ellipsis" to listOf(
            "M13 12A1 1 0 1 0 11 12A1 1 0 1 0 13 12Z",
            "M20 12A1 1 0 1 0 18 12A1 1 0 1 0 20 12Z",
            "M6 12A1 1 0 1 0 4 12A1 1 0 1 0 6 12Z",
        ),
        "search" to listOf(
            "m21 21-4.34-4.34",
            "M19 11A8 8 0 1 0 3 11A8 8 0 1 0 19 11Z",
        ),
        "shuffle" to listOf(
            "m18 14 4 4-4 4",
            "m18 2 4 4-4 4",
            "M2 18h1.973a4 4 0 0 0 3.3-1.7l5.454-8.6a4 4 0 0 1 3.3-1.7H22",
            "M2 6h1.972a4 4 0 0 1 3.6 2.2",
            "M22 18h-6.041a4 4 0 0 1-3.3-1.8l-.359-.45",
        ),
        "repeat" to listOf(
            "m17 2 4 4-4 4",
            "M3 11v-1a4 4 0 0 1 4-4h14",
            "m7 22-4-4 4-4",
            "M21 13v1a4 4 0 0 1-4 4H3",
        ),
        "repeat-1" to listOf(
            "m17 2 4 4-4 4",
            "M3 11v-1a4 4 0 0 1 4-4h14",
            "m7 22-4-4 4-4",
            "M21 13v1a4 4 0 0 1-4 4H3",
            "M11 10h1v4",
        ),
        "align-left" to listOf(
            "M21 5H3",
            "M15 12H3",
            "M17 19H3",
        ),
        "clock-3" to listOf(
            "M22 12A10 10 0 1 0 2 12A10 10 0 1 0 22 12Z",
            "M12 6v6h4",
        ),
        "list-music" to listOf(
            "M16 5H3",
            "M11 12H3",
            "M11 19H3",
            "M21 16V5",
            "M21 16A3 3 0 1 0 15 16A3 3 0 1 0 21 16Z",
        ),
        "library" to listOf(
            "m16 6 4 14",
            "M12 6v14",
            "M8 8v12",
            "M4 4v16",
        ),
        "circle-minus" to listOf(
            "M22 12A10 10 0 1 0 2 12A10 10 0 1 0 22 12Z",
            "M8 12h8",
        ),
        "trash-2" to listOf(
            "M10 11v6",
            "M14 11v6",
            "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6",
            "M3 6h18",
            "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2",
        ),
        "pencil" to listOf(
            "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z",
            "m15 5 4 4",
        ),
        "pencil-line" to listOf(
            "M13 21h8",
            "m15 5 4 4",
            "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z",
        ),
        "plus" to listOf(
            "M5 12h14",
            "M12 5v14",
        ),
        "refresh-cw" to listOf(
            "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8",
            "M21 3v5h-5",
            "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16",
            "M8 16H3v5",
        ),
        "folder-open" to listOf(
            "m6 14 1.5-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5l-1.54 6a2 2 0 0 1-1.95 1.5H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H18a2 2 0 0 1 2 2v2",
        ),
        "disc-3" to listOf(
            "M22 12A10 10 0 1 0 2 12A10 10 0 1 0 22 12Z",
            "M6 12c0-1.7.7-3.2 1.8-4.2",
            "M14 12A2 2 0 1 0 10 12A2 2 0 1 0 14 12Z",
            "M18 12c0 1.7-.7 3.2-1.8 4.2",
        ),
        "x" to listOf(
            "M18 6 6 18",
            "m6 6 12 12",
        ),
        "maximize-2" to listOf(
            "M15 3h6v6",
            "m21 3-7 7",
            "m3 21 7-7",
            "M9 21H3v-6",
        ),
        "house" to listOf(
            "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8",
            "M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
        ),
        "compass" to listOf(
            "M22 12A10 10 0 1 0 2 12A10 10 0 1 0 22 12Z",
            "m16.24 7.76-1.804 5.411a2 2 0 0 1-1.265 1.265L7.76 16.24l1.804-5.411a2 2 0 0 1 1.265-1.265z",
        ),
        "user-round" to listOf(
            "M17 8A5 5 0 1 0 7 8A5 5 0 1 0 17 8Z",
            "M20 21a8 8 0 0 0-16 0",
        ),
        "download" to listOf(
            "M12 15V3",
            "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4",
            "m7 10 5 5 5-5",
        ),
        "music-2" to listOf(
            "M12 18A4 4 0 1 0 4 18A4 4 0 1 0 12 18Z",
            "M12 18V2l7 4",
        ),
        "info" to listOf(
            "M22 12A10 10 0 1 0 2 12A10 10 0 1 0 22 12Z",
            "M12 16v-4",
            "M12 8h.01",
        ),
        "link" to listOf(
            "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71",
            "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71",
        ),
        "gamepad-2" to listOf(
            "M6 11L10 11",
            "M8 9L8 13",
            "M15 12L15.01 12",
            "M18 10L18.01 10",
            "M17.32 5H6.68a4 4 0 0 0-3.978 3.59c-.006.052-.01.101-.017.152C2.604 9.416 2 14.456 2 16a3 3 0 0 0 3 3c1 0 1.5-.5 2-1l1.414-1.414A2 2 0 0 1 9.828 16h4.344a2 2 0 0 1 1.414.586L17 18c.5.5 1 1 2 1a3 3 0 0 0 3-3c0-1.545-.604-6.584-.685-7.258-.007-.05-.011-.1-.017-.151A4 4 0 0 0 17.32 5z",
        ),
        "palette" to listOf(
            "M12 22a1 1 0 0 1 0-20 10 9 0 0 1 10 9 5 5 0 0 1-5 5h-2.25a1.75 1.75 0 0 0-1.4 2.8l.3.4a1.75 1.75 0 0 1-1.4 2.8z",
            "M14 6.5A0.5 0.5 0 1 0 13 6.5A0.5 0.5 0 1 0 14 6.5Z",
            "M18 10.5A0.5 0.5 0 1 0 17 10.5A0.5 0.5 0 1 0 18 10.5Z",
            "M7 12.5A0.5 0.5 0 1 0 6 12.5A0.5 0.5 0 1 0 7 12.5Z",
            "M9 7.5A0.5 0.5 0 1 0 8 7.5A0.5 0.5 0 1 0 9 7.5Z",
        ),
        "settings-2" to listOf(
            "M14 17H5",
            "M19 7h-9",
            "M20 17A3 3 0 1 0 14 17A3 3 0 1 0 20 17Z",
            "M10 7A3 3 0 1 0 4 7A3 3 0 1 0 10 7Z",
        ),
        "hard-drive" to listOf(
            "M10 16h.01",
            "M2.212 11.577a2 2 0 0 0-.212.896V18a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-5.527a2 2 0 0 0-.212-.896L18.55 5.11A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z",
            "M21.946 12.013H2.054",
            "M6 16h.01",
        ),
        "audio-lines" to listOf(
            "M2 10v3",
            "M6 6v11",
            "M10 3v18",
            "M14 8v7",
            "M18 5v13",
            "M22 10v3",
        ),
        "crosshair" to listOf(
            "M22 12A10 10 0 1 0 2 12A10 10 0 1 0 22 12Z",
            "M22 12L18 12",
            "M6 12L2 12",
            "M12 6L12 2",
            "M12 22L12 18",
        ),
        "list-start" to listOf(
            "M3 5h6",
            "M3 12h13",
            "M3 19h13",
            "m16 8-3-3 3-3",
            "M21 19V7a2 2 0 0 0-2-2h-6",
        ),
        "list-end" to listOf(
            "M16 5H3",
            "M16 12H3",
            "M9 19H3",
            "m16 16-3 3 3 3",
            "M21 5v12a2 2 0 0 1-2 2h-6",
        ),
        "list-plus" to listOf(
            "M16 5H3",
            "M11 12H3",
            "M16 19H3",
            "M18 9v6",
            "M21 12h-6",
        ),
        "list-minus" to listOf(
            "M16 5H3",
            "M11 12H3",
            "M16 19H3",
            "M21 12h-6",
        ),
        "undo-2" to listOf(
            "M9 14 4 9l5-5",
            "M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11",
        ),
        "library-big" to listOf(
            "M4 3H10A1 1 0 0 1 11 4V20A1 1 0 0 1 10 21H4A1 1 0 0 1 3 20V4A1 1 0 0 1 4 3Z",
            "M7 3v18",
            "M20.4 18.9c.2.5-.1 1.1-.6 1.3l-1.9.7c-.5.2-1.1-.1-1.3-.6L11.1 5.1c-.2-.5.1-1.1.6-1.3l1.9-.7c.5-.2 1.1.1 1.3.6Z",
        ),
        "sliders-horizontal" to listOf(
            "M10 5H3",
            "M12 19H3",
            "M14 3v4",
            "M16 17v4",
            "M21 12h-9",
            "M21 19h-5",
            "M21 5h-7",
            "M8 10v4",
            "M8 12H3",
        ),
    )
}

/** Keep existing business call sites while selecting their exact approved visual assets. */
internal fun thorLineIcon(icon: ImageVector): ImageVector {
    val name = icon.name.substringAfterLast('.')
    val approved = when (name) {
        "ArrowBack", "ChevronLeft" -> "chevron-left"
        "ChevronRight" -> "chevron-right"
        "PlayArrow" -> "play"
        "Pause" -> "pause"
        "SkipPrevious" -> "skip-back"
        "SkipNext" -> "skip-forward"
        "Favorite", "FavoriteBorder" -> "heart"
        "Bookmark", "BookmarkBorder" -> "bookmark"
        "MoreHoriz", "MoreVert" -> "ellipsis"
        "Search" -> "search"
        "Shuffle" -> "shuffle"
        "Repeat" -> "repeat"
        "RepeatOne" -> "repeat-1"
        "Lyrics" -> "align-left"
        "Timer", "TimerOff", "History" -> "clock-3"
        "QueueMusic" -> "list-music"
        "LibraryMusic", "LibraryBooks" -> "library"
        "RemoveCircleOutline" -> "circle-minus"
        "DeleteOutline" -> "trash-2"
        "Edit" -> "pencil"
        "Add" -> "plus"
        "Refresh" -> "refresh-cw"
        "FolderOpen" -> "folder-open"
        "Album" -> "disc-3"
        "MyLocation" -> "crosshair"
        "Close" -> "x"
        "OpenInFull" -> "maximize-2"
        "Home" -> "house"
        "Explore" -> "compass"
        "Person", "AccountCircle" -> "user-round"
        "Download", "CloudDownload" -> "download"
        "MusicNote" -> "music-2"
        "Info" -> "info"
        "Link" -> "link"
        "Gamepad" -> "gamepad-2"
        "Palette" -> "palette"
        "Settings" -> "settings-2"
        else -> return icon
    }
    return ThorDesignIcons.icon(approved, filled = name == "Favorite" || name == "Bookmark")
}

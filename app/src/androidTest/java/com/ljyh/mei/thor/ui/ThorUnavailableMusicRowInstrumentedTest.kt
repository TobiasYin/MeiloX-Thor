package com.ljyh.mei.thor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.SongPlaybackAvailability
import com.ljyh.mei.data.model.api.SearchResult
import com.ljyh.mei.data.model.api.toMediaData
import com.ljyh.mei.thor.model.*
import com.ljyh.mei.thor.playback.commitThorPlayback
import com.ljyh.mei.thor.playback.thorSelectedPlayback
import com.ljyh.mei.thor.theme.LocalThorTheme
import com.ljyh.mei.thor.theme.ThorTheme
import com.ljyh.mei.thor.theme.ThorThemeTokens
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.pow

/** Real row/focus/material rendering, with the same accepted-plan gate used by native playback. */
@RunWith(AndroidJUnit4::class)
class ThorUnavailableMusicRowInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val registry = ThorFocusRegistry()
    private val commits = mutableListOf<Long>()
    private val rejections = mutableListOf<String>()
    private val menus = mutableListOf<String>()
    private lateinit var tokens: ThorThemeTokens
    private val reason = "当前歌曲暂不可播放"

    @Test fun deniedSongsAndQueueOccurrencesStayNavigableAndManageableWithoutPlaying() {
        val denied = song(1, "Blocked", SongPlaybackAvailability.Unavailable)
        val rows = listOf(denied.toThorItem(), denied.toThorItem().copy(key = ThorItemKey.QueueEntry("denied-occurrence"), title = "Queue blocked"),
            song(3, "Allowed").toThorItem())
        showRows(rows, likedFirst = true)
        compose.onAllNodesWithText("不可播放").assertCountEquals(2)
        compose.onNodeWithText("Blocked").assertIsFocused()
        val description = compose.onNodeWithText("Blocked").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertTrue(description.contains(reason))
        assertTrue(description.contains("已喜欢"))
        compose.runOnIdle {
            assertTrue(registry.confirm())
            assertTrue(commits.isEmpty())
            assertEquals(1, rejections.size)
            assertTrue(registry.move(ThorDirection.Down, false))
            assertTrue(registry.confirm())
            assertEquals(2, rejections.size)
            assertTrue(commits.isEmpty())
            assertTrue(registry.move(ThorDirection.Down, false))
            assertTrue(registry.confirm())
            assertEquals(listOf(3L), commits)
            assertTrue(registry.request(rowKey(0)))
        }
        compose.onNodeWithText("Blocked").performTouchInput { longClick(center, durationMillis = 600) }
        compose.runOnIdle {
            assertEquals(listOf(rowKey(0)), menus)
            assertEquals(listOf(3L), commits)
            assertEquals(2, rejections.size)
        }
    }

    @Test fun feeOnlyUnknownLocalFilesAndPodcastsRemainUnmarkedAndPassTheRealSelectionGate() {
        val feeOnly = Gson().fromJson("""{"id":11,"name":"VIP fixture","fee":1,"duration":1000,
            "artists":[],"album":{"id":2,"name":"Album","picId":1}}""", SearchResult.Result.Song::class.java)
            .toMediaData().copy(coverUrl = "")
        assertEquals(SongPlaybackAvailability.Unknown, feeOnly.availability)
        val rows = listOf(feeOnly,
            song(12, "Local fixture", SongPlaybackAvailability.Unavailable).copy(isLocal = true),
            song(13, "Podcast fixture", SongPlaybackAvailability.Unavailable).copy(isPodcast = true)).map { it.toThorItem() }
        showRows(rows)
        compose.onAllNodesWithText("不可播放").assertCountEquals(0)
        for (index in rows.indices) compose.runOnIdle {
            assertTrue(registry.request(rowKey(index)))
            assertTrue(registry.confirm())
        }
        compose.runOnIdle {
            assertTrue(rejections.isEmpty())
            assertEquals(listOf(11L, 12L, 13L), commits)
        }
    }

    @Test fun allMaterialDayNightModesKeepReadableDenialInkFocusAndRowGeometry() {
        val preferences = mutableStateOf(prefs(ThorThemePack.Ceramic, ThorAppearance.Day))
        showRows(listOf(song(1, "Blocked", SongPlaybackAvailability.Unavailable).toThorItem(),
            song(2, "Allowed").toThorItem()), preferences)
        var baseline: List<Rect>? = null
        for (pack in ThorThemePack.entries) for (appearance in listOf(ThorAppearance.Day, ThorAppearance.Night)) {
            compose.runOnIdle { preferences.value = prefs(pack, appearance) }
            compose.waitForIdle()
            compose.onNodeWithText("Blocked").assertIsFocused()
            compose.onNodeWithText("不可播放").assertIsDisplayed()
            val bounds = listOf("Blocked", "Allowed").map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
            baseline?.let { assertEquals("theme must not reshape row hit areas", it, bounds) } ?: run { baseline = bounds }
            val text = compose.onNodeWithText("Blocked", useUnmergedTree = true).captureToImage().toPixelMap()
            var nearest = Color.Transparent
            var nearestDistance = Float.POSITIVE_INFINITY
            for (y in 0 until text.height) for (x in 0 until text.width) {
                val pixel = text[x, y]
                val distance = distance(pixel, tokens.secondary)
                if (pixel.alpha > .95f && distance < nearestDistance) { nearest = pixel; nearestDistance = distance }
            }
            assertTrue("$pack/$appearance must render secondary title ink, not fade the entire title", nearestDistance < .06f)
            val background = text[text.width - 1, 0]
            assertTrue("$pack/$appearance title contrast", contrast(nearest, background) >= 4.5)
            val row = compose.onNodeWithText("Blocked").captureToImage().toPixelMap()
            var focusPixels = 0
            for (y in 0 until minOf(8, row.height)) for (x in row.width / 4 until row.width * 3 / 4) {
                if (distance(row[x, y], tokens.focus) < .10f) focusPixels++
            }
            assertTrue("$pack/$appearance must paint a focus outline in addition to the denial label", focusPixels > 10)
        }
        compose.runOnIdle {
            assertTrue(registry.confirm())
            assertTrue(commits.isEmpty())
            assertEquals(1, rejections.size)
        }
    }

    private fun showRows(rows: List<ThorItem>, preferences: MutableState<ThorPreferences> = mutableStateOf(
        prefs(ThorThemePack.Ceramic, ThorAppearance.Day)), likedFirst: Boolean = false,
    ) {
        compose.setContent {
            ThorTheme(preferences.value) {
                val theme = LocalThorTheme.current
                val owner = remember { Any() }
                val keys = remember(rows) { rows.indices.map(::rowKey) }
                SideEffect { tokens = theme; registry.installScope(owner, ThorFocusScope("fixture", keys)) }
                DisposableEffect(owner) { onDispose { registry.removeScope(owner) } }
                LaunchedEffect(keys) { withFrameNanos { }; registry.request(keys.first()) }
                Column(Modifier.width(400.dp).background(theme.background).padding(8.dp)) {
                    rows.forEachIndexed { index, item ->
                        ThorMusicRow(registry, keys[index], item, mark = if (likedFirst && index == 0) Icons.Outlined.Favorite else null,
                            onClick = {
                                commitThorPlayback(thorSelectedPlayback(rows.map { it.tracks.first() }, index),
                                    rejected = { rejections += it }) { selected -> commits += selected.tracks[selected.index].id }
                            }, onMenu = { menus += keys[index] })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun prefs(pack: ThorThemePack, appearance: ThorAppearance) =
        ThorPreferences(theme = pack, appearance = appearance, reduceMotion = true)
    private fun rowKey(index: Int) = "row:$index"
    private fun song(id: Long, title: String, availability: SongPlaybackAvailability = SongPlaybackAvailability.Unknown) =
        MediaMetadata(id, title, "", listOf(MediaMetadata.Artist(9, "Artist")), 1000,
            MediaMetadata.Album(2, "Album"), availability = availability,
            availabilityReason = if (availability == SongPlaybackAvailability.Unavailable) reason else null)
    private fun distance(a: Color, b: Color): Float = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)
    private fun contrast(a: Color, b: Color): Double {
        fun luminance(color: Color): Double {
            fun linear(value: Float): Double = if (value <= .04045f) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
            return .2126 * linear(color.red) + .7152 * linear(color.green) + .0722 * linear(color.blue)
        }
        val first = luminance(a); val second = luminance(b)
        return (maxOf(first, second) + .05) / (minOf(first, second) + .05)
    }
}

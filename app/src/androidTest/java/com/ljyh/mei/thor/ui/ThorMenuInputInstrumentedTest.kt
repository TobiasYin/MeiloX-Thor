package com.ljyh.mei.thor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.thor.menu.ThorMenuAnchor
import com.ljyh.mei.thor.menu.ThorMenuNameEditor
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.model.ThorPreferences
import com.ljyh.mei.thor.theme.ThorTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production controls with touch/IME input; no ViewModel, network or account fixtures. */
@RunWith(AndroidJUnit4::class)
class ThorMenuInputInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun heldPressSurvivesRecompositionAndReleaseCannotAlsoOpenThePlaylist() {
        val registry = ThorFocusRegistry()
        val menuOpen = mutableStateOf(false)
        val revision = mutableIntStateOf(0)
        var clicks = 0
        val anchors = mutableListOf<ThorMenuAnchor>()
        compose.setContent {
            ThorTheme(ThorPreferences(reduceMotion = true)) {
                SideEffect { registry.blocked = menuOpen.value }
                Column(Modifier.width(320.dp).padding(20.dp)) {
                    ThorMusicRow(registry, "playlist", ThorItem(ThorItemKey.Playlist("test", true), "Only playlist", "Revision ${revision.intValue}"),
                        onClick = { clicks++ }, onMenu = { anchors += it; menuOpen.value = true })
                    if (menuOpen.value) Text("Context open")
                }
            }
        }
        val row = compose.onNodeWithText("Only playlist")
        val bounds = row.fetchSemanticsNode().boundsInRoot
        val position = Offset(bounds.width * .3f, bounds.height * .6f)
        row.performTouchInput { down(position); repeat(12) { moveTo(position, delayMillis = 16L) } }
        compose.runOnIdle { revision.intValue++ }
        row.performTouchInput { repeat(26) { moveTo(position, delayMillis = 16L) } }
        compose.onNodeWithText("Context open").assertIsDisplayed()
        row.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(0, clicks)
            assertEquals(1, anchors.size)
            assertEquals(bounds.left + position.x, anchors.single().x, 1f)
            assertEquals(bounds.top + position.y, anchors.single().y, 1f)
            assertEquals("playlist", registry.selectedKey)
            menuOpen.value = false
        }
        row.performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, clicks)
            assertTrue(registry.confirm())
            assertEquals(2, clicks)
            assertEquals(1, anchors.size)
        }
    }

    @Test fun scrollingBeforeLongPressCancelsBothMenuAndClick() {
        var clicks = 0
        var menus = 0
        val registry = ThorFocusRegistry()
        compose.setContent {
            ThorTheme(ThorPreferences(reduceMotion = true)) {
                LazyColumn(Modifier.width(320.dp).height(160.dp)) {
                    items((0..5).toList()) { index ->
                        ThorMusicRow(registry, "row:$index", ThorItem(ThorItemKey.Playlist("$index", true), "Row $index"),
                            onClick = { clicks++ }, onMenu = { menus++ })
                    }
                }
            }
        }
        compose.onNodeWithText("Row 0").performTouchInput {
            down(center)
            moveBy(Offset(0f, -100f), delayMillis = 80L)
            advanceEventTime(600L)
            up()
        }
        compose.runOnIdle { assertEquals(0, clicks); assertEquals(0, menus) }
    }

    @Test fun characterByCharacterRenameIgnoresLaggingParentEchoAndSavesTheEntireLocalValue() {
        val echo = mutableStateOf("")
        var latestName = ""
        var savedName: String? = null
        val registry = ThorFocusRegistry()
        compose.setContent {
            ThorTheme(ThorPreferences(reduceMotion = true)) {
                Column(Modifier.width(500.dp).height(220.dp)) {
                    ThorMenuNameEditor("subject", true, echo.value, "保存", registry, true,
                        onNameChanged = { latestName = it; echo.value = it.dropLast(1) },
                        onSubmit = { savedName = latestName }, onCancel = {}, onEditingChanged = {})
                }
            }
        }
        val expected = "ThorQA20261030Renamed"
        expected.forEach { compose.onNode(hasSetTextAction()).performTextInput(it.toString()) }
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(expected)))
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(expected, latestName); assertEquals(expected, savedName) }
    }

    @Test fun nameLimitAndImeDoneUseTheSameLatestEightyCharacters() {
        var latestName = ""
        var savedName: String? = null
        val registry = ThorFocusRegistry()
        compose.setContent {
            ThorTheme(ThorPreferences(reduceMotion = true)) {
                Column(Modifier.width(500.dp).height(220.dp)) {
                    ThorMenuNameEditor("subject", false, "", "创建", registry, true,
                        onNameChanged = { latestName = it }, onSubmit = { savedName = latestName },
                        onCancel = {}, onEditingChanged = {})
                }
            }
        }
        val input = "中".repeat(40) + "A".repeat(60)
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput(input)
        field.performImeAction()
        compose.runOnIdle { assertEquals(input.take(80), latestName); assertEquals(input.take(80), savedName) }
    }

    @Test fun aNewSubjectStartsANewNameSessionInsteadOfKeepingThePreviousDraft() {
        val subject = mutableStateOf("first")
        val initial = mutableStateOf("First playlist")
        val registry = ThorFocusRegistry()
        compose.setContent {
            ThorTheme(ThorPreferences(reduceMotion = true)) {
                Column(Modifier.width(500.dp).height(220.dp)) {
                    ThorMenuNameEditor(subject.value, true, initial.value, "保存", registry, true,
                        onNameChanged = {}, onSubmit = {}, onCancel = {}, onEditingChanged = {})
                }
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("Unsaved draft")
        compose.runOnIdle { subject.value = "second"; initial.value = "Second playlist" }
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Second playlist")))
    }
}

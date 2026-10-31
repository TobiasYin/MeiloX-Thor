package com.ljyh.mei.thor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ljyh.mei.thor.components.ThorSurfaceRole
import com.ljyh.mei.thor.components.thorMaterial
import com.ljyh.mei.thor.theme.LocalThorTheme
import com.ljyh.mei.thor.model.ThorCommand

/** Editing owns A/B even though the result list remains the page's directional focus scope. */
internal fun handleThorSearchEditingCommand(
    command: ThorCommand,
    editing: Boolean,
    bindings: ThorPageActionBindings,
): Boolean {
    if (!editing) return false
    val action = when (command) {
        ThorCommand.Confirm -> "search:submit"
        ThorCommand.Back -> "search:finishEditing"
        else -> return false
    }
    bindings.invoke(action)
    return true
}

/** The real lower-screen editor, shared by touch, IME and controller page actions. */
@Composable
internal fun ThorSearchInput(
    query: String,
    loading: Boolean,
    registry: ThorFocusRegistry,
    pageActions: ThorPageActionBindings?,
    onQueryChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onEditingChange: (Boolean) -> Unit,
) {
    val theme = LocalThorTheme.current
    // The synchronous editor buffer is authoritative at the instant a button/IME event arrives.
    // A StateFlow projection or a preceding request must not substitute a different visible query.
    val editor = remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val latestSubmit by rememberUpdatedState(onSubmit)
    val latestEditingChange by rememberUpdatedState(onEditingChange)
    val latestLoading by rememberUpdatedState(loading)

    LaunchedEffect(query, focused) {
        if (!focused && query != editor.value.text) editor.value = TextFieldValue(query, TextRange(query.length))
    }
    val finishEditing: () -> Unit = {
        editor.value = editor.value.copy(composition = null)
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        latestEditingChange(false)
    }
    val submit: () -> Unit = {
        val submitted = editor.value.text.trim()
        if (submitted.isNotEmpty() && !latestLoading) {
            // Capture the visible text before ending IME composition or changing native focus.
            finishEditing()
            latestSubmit(submitted)
        }
    }
    BindThorPageAction(pageActions, "search:edit") { requester.requestFocus(); keyboard?.show() }
    BindThorPageAction(pageActions, "search:submit", submit)
    // Always installed: a fast B can arrive before the focused-state composition is applied.
    BindThorPageAction(pageActions, "search:finishEditing", finishEditing)
    DisposableEffect(Unit) { onDispose { latestEditingChange(false) } }

    Row(Modifier.fillMaxWidth().testTag("thor-search-bar")
        .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(
            value = editor.value,
            onValueChange = { next ->
                val changed = next.text != editor.value.text
                editor.value = next
                // Selection/composition updates are not query edits and must not cancel a search.
                if (changed) onQueryChange(next.text)
            },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                .testTag("thor-search-input").focusRequester(requester)
                .onFocusChanged { focused = it.isFocused; latestEditingChange(it.isFocused) }
                .semantics { contentDescription = "搜索音乐" }
                .thorMaterial(ThorSurfaceRole.Inset, focused = focused).padding(horizontal = 12.dp, vertical = 12.dp),
            singleLine = true,
            textStyle = TextStyle(color = theme.text, fontSize = 14.sp, lineHeight = 20.sp),
            cursorBrush = SolidColor(theme.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            decorationBox = { field -> Box {
                if (editor.value.text.isEmpty()) Text("歌曲、专辑、歌单", color = theme.secondary, fontSize = 14.sp)
                field()
            } },
        )
        ThorButton(registry, "search:submit", if (loading) "搜索中" else "搜索",
            Modifier.width(80.dp).testTag("thor-search-submit"), primary = true,
            enabled = editor.value.text.isNotBlank() && !loading, onClick = submit)
    }
}

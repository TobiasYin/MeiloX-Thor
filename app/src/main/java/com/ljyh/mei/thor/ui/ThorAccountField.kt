package com.ljyh.mei.thor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ljyh.mei.thor.theme.LocalThorTheme

/** One transient editor owner lets B cancel even before the next focus frame is delivered. */
internal class ThorAccountEditingSession {
    private var owner: Any? = null
    private var close: (() -> Unit)? = null
    fun begin(next: Any, onClose: () -> Unit) {
        if (owner !== next) finish()
        owner = next
        close = onClose
    }
    fun release(current: Any) { if (owner === current) { owner = null; close = null } }
    fun finish() {
        val callback = close
        owner = null
        close = null
        callback?.invoke()
    }
    fun hasActiveEditor(): Boolean = owner != null
}

/** Navigating onto the wrapper never starts an IME session; only explicit activation does. */
@Composable
internal fun ThorAccountField(
    registry: ThorFocusRegistry,
    key: String,
    label: String,
    value: String,
    onValue: (String) -> Unit,
    enabled: Boolean = true,
    secret: Boolean = false,
    keyboardType: KeyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
    onEditingChange: (Boolean) -> Unit,
    editingSession: ThorAccountEditingSession? = null,
) {
    val theme = LocalThorTheme.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val inputRequester = remember(key) { FocusRequester() }
    var editing by remember(key) { mutableStateOf(false) }
    var restoreWrapper by remember(key) { mutableStateOf(false) }
    var hadInputFocus by remember(key) { mutableStateOf(false) }
    val editorOwner = remember(key) { Any() }
    val changeEditing by rememberUpdatedState(onEditingChange)
    fun finish() {
        if (!editing) return
        editing = false
        hadInputFocus = false
        editingSession?.release(editorOwner)
        restoreWrapper = true
        changeEditing(false)
        keyboard?.hide()
    }
    fun activate() {
        if (!enabled) return
        editingSession?.begin(editorOwner, ::finish)
        registry.select(key)
        hadInputFocus = false
        restoreWrapper = false
        editing = true
        changeEditing(true)
    }
    LaunchedEffect(editing, restoreWrapper, enabled) {
        if (editing && enabled) {
            withFrameNanos { }
            inputRequester.requestFocus()
            keyboard?.show()
        } else if (restoreWrapper && enabled) {
            withFrameNanos { }
            if (!registry.blocked && registry.isAllowed(key) && editingSession?.hasActiveEditor() != true) registry.request(key)
            restoreWrapper = false
        }
    }
    DisposableEffect(key) {
        onDispose { editingSession?.release(editorOwner); if (editing) changeEditing(false) }
    }
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .testTag("account-field:$key")
        .thorFocus(registry, key, enabled = enabled, onClick = ::activate)
        .focusable(enabled = enabled && !editing)) {
        OutlinedTextField(value, onValue,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .testTag("account-input:$key")
                .focusProperties { canFocus = enabled && editing }
                .focusRequester(inputRequester)
                .onFocusChanged {
                    if (it.isFocused && editing) hadInputFocus = true
                    else if (!it.isFocused && editing && hadInputFocus) finish()
                },
            enabled = enabled,
            readOnly = !editing,
            label = { Text(label, fontSize = 13.sp) },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 15.sp),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { finish(); focus.clearFocus() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = theme.text, unfocusedTextColor = theme.text,
                focusedBorderColor = theme.focus,
                unfocusedBorderColor = if (registry.selectedKey == key) theme.focus else theme.outline,
                cursorColor = theme.accent))
        if (!editing) Box(Modifier.matchParentSize()
            .focusProperties { canFocus = false }
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClick = ::activate))
    }
}

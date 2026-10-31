package com.ljyh.mei.thor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import com.ljyh.mei.thor.model.ThorDirection
import com.ljyh.mei.thor.model.ThorItem

class ThorFocusEntry(
    val requester: FocusRequester,
    var action: () -> Unit,
    var item: ThorItem? = null,
    var bounds: Rect = Rect.Zero,
    var enabled: Boolean = true,
)

/** This registry belongs to the lower composition, never the service or a ViewModel. */
class ThorFocusRegistry {
    private val entries = linkedMapOf<String, ThorFocusEntry>()
    private var registrationVersion by mutableIntStateOf(0)
    private var scopeVersion by mutableIntStateOf(0)
    private var activeScope: Pair<Any, ThorFocusScope>? = null
    private var prepared: Triple<Any, String, ThorFocusEntry>? = null
    private var listMovement: Pair<Any, (ThorDirection, Boolean) -> Boolean>? = null
    var selectedKey by mutableStateOf<String?>(null)
    var blocked by mutableStateOf(false)
    var onFocus: (String, ThorItem?) -> Unit = { _, _ -> }
    val current get() = if (!isAllowed(selectedKey)) null else
        entries[selectedKey] ?: prepared?.takeIf { it.second == selectedKey }?.third

    fun installScope(owner: Any, scope: ThorFocusScope) {
        val normalized = scope.copy(keys = scope.keys.distinct().toList())
        val previous = activeScope
        if (previous?.first === owner && previous.second == normalized) return
        activeScope = owner to normalized
        scopeVersion++
        if (selectedKey !in normalized.keys) selectedKey = null
        if (prepared?.second !in normalized.keys) prepared = null
    }

    fun removeScope(owner: Any) {
        if (activeScope?.first !== owner) return
        activeScope = null
        scopeVersion++
        selectedKey = null
        prepared = null
    }

    fun isAllowed(key: String?): Boolean {
        scopeVersion // Native focus properties and restore jobs observe region ownership changes.
        return key != null && activeScope?.second?.keys?.contains(key) == true
    }
    fun register(key: String, entry: ThorFocusEntry) {
        entries[key] = entry
        registrationVersion++
        if (prepared?.second == key) prepared = null
    }
    fun unregister(key: String, entry: ThorFocusEntry) {
        if (entries[key] === entry) { entries.remove(key); registrationVersion++ }
    }
    fun contains(key: String?): Boolean {
        registrationVersion // Allows a pending focus job to await LazyColumn registration.
        return key != null && entries.containsKey(key)
    }
    fun select(key: String) {
        if (blocked || !isAllowed(key)) return
        if (prepared?.second != key) prepared = null
        selectedKey = key
        onFocus(key, current?.item)
    }
    fun confirm(): Boolean {
        if (blocked) return true
        val entry = current ?: return selectedKey != null
        if (entry.enabled) entry.action()
        return true
    }
    fun request(key: String?): Boolean {
        if (blocked || !isAllowed(key)) return false
        // A logical pending request prevents a parent fallback from stealing focus before scroll.
        val entry = entries[key] ?: return prepared?.second == key && key != null
        if (!entry.enabled) return false
        return entry.requester.requestFocus().also {
            if (it && key != null) { if (prepared?.second == key) prepared = null; select(key) }
        }
    }
    fun requestFirst(): Boolean = activeScope?.second?.keys?.any { request(it) } == true

    /** Keep exact action/identity available while LazyColumn realizes an offscreen focus target. */
    fun prepareFocus(owner: Any, key: String, item: ThorItem?, action: () -> Unit): Boolean {
        if (blocked || activeScope?.first !== owner || !isAllowed(key)) return false
        val fallbackBounds = current?.bounds ?: Rect.Zero
        prepared = Triple(owner, key, ThorFocusEntry(FocusRequester(), action, item, fallbackBounds))
        selectedKey = key
        onFocus(key, item)
        return true
    }
    fun clearPreparedFocus(owner: Any) { if (prepared?.first === owner) prepared = null }
    fun installListMove(owner: Any, handler: (ThorDirection, Boolean) -> Boolean) { listMovement = owner to handler }
    fun removeListMove(owner: Any) { if (listMovement?.first === owner) listMovement = null }
    fun move(direction: ThorDirection, repeated: Boolean): Boolean {
        if (blocked) return false
        val active = activeScope ?: return false
        val list = listMovement?.takeIf { it.first === active.first }
        if (list != null) return list.second(direction, repeated)
        val target = thorScopeTarget(active.second, selectedKey, direction,
            entries.mapValues { (_, entry) -> ThorFocusCandidate(entry.bounds, entry.enabled) }) ?: return false
        return request(target)
    }
}

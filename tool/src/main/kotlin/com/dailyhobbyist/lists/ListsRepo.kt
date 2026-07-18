package com.dailyhobbyist.lists

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Single source of truth for the whole node tree.
 * Everything is stored on-device in DataStore as one JSON string.
 */
object ListsRepo {

    private val KEY = stringPreferencesKey("lists_tree_json")
    private val json = Json { ignoreUnknownKeys = true }

    private var dataStore: DataStore<Preferences>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _root = MutableStateFlow(Node(id = ROOT_ID, name = "LISTS"))
    val root: StateFlow<Node> = _root

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    /** Call once with the screen's DataStore. Safe to call again — later calls are ignored. */
    fun init(ds: DataStore<Preferences>) {
        if (dataStore != null) return
        dataStore = ds
        scope.launch {
            try {
                val saved = ds.data.first()[KEY]
                if (saved != null) {
                    _root.value = json.decodeFromString<Node>(saved)
                }
            } catch (_: Exception) {
                // Corrupt or missing data — start fresh rather than crash.
            }
            _loaded.value = true
        }
    }

    private fun persist() {
        val ds = dataStore ?: return
        val snapshot = _root.value
        scope.launch {
            try {
                ds.edit { prefs -> prefs[KEY] = json.encodeToString(Node.serializer(), snapshot) }
            } catch (_: Exception) {
            }
        }
    }

    fun node(id: String): Node? = _root.value.find(id)

    fun addChild(parentId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _root.value = _root.value.withChildAdded(parentId, Node(id = newId(), name = trimmed))
        persist()
    }

    fun rename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _root.value = _root.value.withRenamed(id, trimmed)
        persist()
    }

    fun delete(id: String) {
        _root.value = _root.value.withDeleted(id)
        persist()
    }

    fun move(parentId: String, fromIndex: Int, toIndex: Int) {
        _root.value = _root.value.withChildMoved(parentId, fromIndex, toIndex)
        persist()
    }

    fun toggleChecked(id: String) {
        _root.value = _root.value.withUpdated(id) { it.copy(checked = !it.checked) }
        persist()
    }

    fun setChecklist(id: String, on: Boolean) {
        _root.value = _root.value.withUpdated(id) { it.copy(checklist = on) }
        persist()
    }

    /** Turn checklist on/off for [id] and everything nested inside it. */
    fun setChecklistAll(id: String, on: Boolean) {
        _root.value = _root.value.withUpdated(id) { it.withAllChecklist(on) }
        persist()
    }

    /** Remove all checked items directly inside [parentId]. */
    fun purgeChecked(parentId: String) {
        _root.value = _root.value.withUpdated(parentId) { parent ->
            parent.copy(children = parent.children.filter { !it.checked })
        }
        persist()
    }

    /** Remove all checked items inside [parentId] at every level. */
    fun purgeCheckedAll(parentId: String) {
        _root.value = _root.value.withUpdated(parentId) { it.withAllCheckedPurged() }
        persist()
    }

    /**
     * Apply sort labels from [labels] to the children of [parentId] and
     * reorder them: A1, A2, B1... first, then lettered-but-unnumbered items
     * after the numbered ones in their group, then unlabeled items last,
     * keeping original order for ties.
     */
    fun applySort(parentId: String, labels: Map<String, DraftLabel>) {
        _root.value = _root.value.withUpdated(parentId) { parent ->
            val updated = parent.children.map { child ->
                val d = labels[child.id]
                if (d?.letter != null) {
                    child.copy(sortLetter = d.letter, sortNumber = d.number)
                } else {
                    child.copy(sortLetter = null, sortNumber = null)
                }
            }
            val lettered = updated.filter { it.sortLetter != null }
                .sortedWith(compareBy({ it.sortLetter }, { it.sortNumber ?: Int.MAX_VALUE }))
            val unlabeled = updated.filter { it.sortLetter == null }
            parent.copy(children = lettered + unlabeled)
        }
        persist()
    }

    /** Remove all sort labels from the children of [parentId], keeping order. */
    fun clearSort(parentId: String) {
        _root.value = _root.value.withUpdated(parentId) { parent ->
            parent.copy(children = parent.children.map {
                it.copy(sortLetter = null, sortNumber = null)
            })
        }
        persist()
    }
}

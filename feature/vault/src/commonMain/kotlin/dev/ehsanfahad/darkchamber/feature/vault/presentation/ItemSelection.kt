package dev.ehsanfahad.darkchamber.feature.vault.presentation

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
// Not auto-imported in common source sets, and required on the JVM target: a value class without it
// does not compile there, while on the native targets the annotation is simply inert.
import kotlin.jvm.JvmInline

/**
 * A selection of items, and whether the screen is in selecting mode.
 *
 * **Deliberately knows nothing about what a selection is for.** It holds ids and a flag; filing,
 * shredding and exporting are the caller's business, performed with [ItemSelection.selectedIds].
 * Built standalone for that reason — selecting many things and doing one thing to them is wanted by
 * more than one feature, and a selection that knew about albums would have to be rewritten for the
 * next use.
 *
 * Not a screen: selecting is a mode the gallery enters in place, so this is a state holder the
 * gallery owns rather than a navigation child.
 */
internal class ItemSelection {

    private val _state = MutableValue(SelectionState())
    val state: Value<SelectionState> = _state

    // What the screen is currently showing, kept so Select all means "all of these" and so a
    // selection can be intersected against it. Ids only.
    private var visible: List<ItemKey> = emptyList()

    /**
     * Enter selecting mode with [id] selected — what a long-press does.
     *
     * Idempotent in mode: called while already selecting it behaves as [toggle], so a caller does
     * not have to branch on the mode it is already in.
     */
    fun start(id: ByteArray) {
        if (_state.value.active) {
            toggle(id)
        } else {
            _state.value = SelectionState(active = true, selected = setOf(id.toKey()))
        }
    }

    /** Add [id] if absent, remove it if present. A no-op outside selecting mode. */
    fun toggle(id: ByteArray) {
        val current = _state.value
        if (!current.active) return
        val key = id.toKey()
        val next = if (key in current.selected) current.selected - key else current.selected + key
        _state.value = current.copy(selected = next)
    }

    /**
     * Select everything on screen **right now** — which is the filtered list, not the whole vault.
     * That falls out of the design rather than being enforced: this only ever sees what the gallery
     * passed it, so Select all inside an album cannot reach beyond that album.
     */
    fun selectAll() {
        val current = _state.value
        if (!current.active) return
        _state.value = current.copy(selected = visible.toSet())
    }

    /** Leave selecting mode and drop everything selected. Back and Clear both land here. */
    fun clear() {
        _state.value = SelectionState()
    }

    /**
     * Take the live listing and **intersect the selection with it**.
     *
     * Items leave the list while selected: shredded on this device or another, or moved out of the
     * album being viewed. Without this the selection keeps ids that no longer exist, the count lies
     * about how many things an action will touch, and the action itself writes against dead rows.
     *
     * The mode survives an intersection that empties the selection. An empty selection the user
     * caused is a signal to leave — [clear] does exactly that — but one caused by the world changing
     * underneath them is not a request to tear the mode down mid-task; the bar stays, showing none
     * selected, and Select all is right there.
     */
    fun onItems(items: List<GalleryItem>) {
        visible = items.map { it.id.toKey() }
        val current = _state.value
        if (current.selected.isEmpty()) return
        val present = visible.toSet()
        val kept = current.selected.filterTo(LinkedHashSet()) { it in present }
        if (kept.size != current.selected.size) _state.value = current.copy(selected = kept)
    }

    /**
     * The selected ids, in the order they were selected, as `ByteArray` like every other id in this
     * project. The internal key type does not escape.
     */
    fun selectedIds(): List<ByteArray> = _state.value.selected.map { it.toByteArray() }
}

/**
 * Whether the gallery is selecting, and what is selected.
 *
 * [selected] is keyed by [ItemKey] rather than `ByteArray` — see that type for why — and is
 * insertion-ordered, so an action's report of what it did is deterministic rather than depending on
 * hash order.
 */
internal data class SelectionState(
    val active: Boolean = false,
    val selected: Set<ItemKey> = emptySet(),
) {
    val count: Int get() = selected.size

    /** Whether [id] is selected. Converts at the boundary so callers keep passing `ByteArray`. */
    fun contains(id: ByteArray): Boolean = id.toKey() in selected
}

/**
 * An item id usable as a map or set key.
 *
 * **`ByteArray` cannot be one.** It hashes and compares by identity, so a `Set<ByteArray>` accepts
 * the same id twice without noticing and every lookup against a freshly decoded id misses. Both
 * failures are silent: the count reads one too many, and tapping a selected tile fails to deselect
 * it. This project has been caught by it before — `GalleryItem` and `VaultItemSummary` are
 * hand-written rather than data classes for the same reason, and the test fakes key their maps by
 * `List<Byte>`.
 *
 * A wrapper rather than a bare `List<Byte>` so the intent survives: a list of bytes could be
 * anything, and the conversion is easy to forget at one call site out of six.
 */
@JvmInline
internal value class ItemKey(private val bytes: List<Byte>) {
    fun toByteArray(): ByteArray = bytes.toByteArray()
}

private fun ByteArray.toKey() = ItemKey(toList())

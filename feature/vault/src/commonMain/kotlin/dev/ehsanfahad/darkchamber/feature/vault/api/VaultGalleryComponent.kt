package dev.ehsanfahad.darkchamber.feature.vault.api

import com.arkivanov.decompose.value.Value
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind

/**
 * The vault gallery: the listing of everything in the open vault, plus the import affordance.
 *
 * Presentation follows the session gate's own style (docs/engineering/PATTERNS.md §3) — the
 * component *is* the state holder, exposing [Value] plus named intents, on a lifecycle-scoped
 * coroutine scope. It exists only inside the unlocked subtree, so locking destroys it along with
 * everything it holds; nothing here needs its own teardown.
 */
interface VaultGalleryComponent {
    val state: Value<GalleryState>

    /** Pick photos and videos from the system library. A cancelled picker is a no-op, not an error. */
    fun importPhotos()

    /** Pick documents. Same contract; a different platform picker (see [PhotoPicker]). */
    fun importFiles()

    /** Stop a running batch. What has already been imported stays imported. */
    fun cancelImport()

    /** Dismiss the finished-batch summary. */
    fun acknowledgeImport()

    /** Take the summary's offer to delete the imported originals. Opens the disclosure; deletes nothing. */
    fun requestOriginalDelete()

    /** Delete them, having shown the disclosure. Reports how many the platform actually took. */
    fun confirmOriginalDelete()

    /** Decline the disclosure. The only other way out of it — it cannot be dismissed. */
    fun keepOriginals()

    /**
     * Long-press a tile: enter selecting mode with that item selected, or — already selecting —
     * toggle it, so the caller does not branch on a mode it is already in.
     *
     * **Ignored while a batch is running.** The grid grows underneath an import, so a selection made
     * then is a selection of a moving target.
     */
    fun selectItem(id: ByteArray)

    /** Tap a tile while selecting: add it or remove it. A no-op outside selecting mode. */
    fun toggleItem(id: ByteArray)

    /** Select everything the screen is currently showing — which inside an album is that album. */
    fun selectAllItems()

    /** Leave selecting mode and drop the selection. Clear and back both land here. */
    fun clearSelection()

    /** Clear a surfaced [GalleryError] after the user acknowledges it. */
    fun dismissError()
}

/**
 * What the gallery is showing. Holds **ids and display metadata only** — never decrypted bytes — so
 * a retained state can never pin plaintext past a lock (docs/engineering/PATTERNS.md §3).
 *
 * [loading] starts `true` and drops on the first catalog emission, so an opening vault shows a
 * spinner rather than flashing "no items yet" at someone whose vault is full.
 */
data class GalleryState(
    val items: List<GalleryItem> = emptyList(),
    val loading: Boolean = true,
    val batch: ImportState = ImportState.Idle,
    val error: GalleryError? = null,
    val selection: GallerySelection = GallerySelection(),
    val scope: GalleryScope = GalleryScope.WholeVault,
) {
    /** Whether a batch is between the user asking for one and it ending. */
    val importing: Boolean get() = batch is ImportState.Picking || batch is ImportState.Running
}

/**
 * Which listing a gallery is showing — **not** the filter itself.
 *
 * It exists because an empty grid means three different things, and saying the wrong one is worse
 * than saying nothing: a vault with no files at all wants to be told how to add some, an album with
 * nothing in it is a perfectly ordinary album, and nothing unfiled means everything has been filed,
 * which is a success rather than an absence.
 *
 * The album's id is deliberately absent. Copy does not need it — an album's name is already on the
 * screen that led here — and a state that carried it would be holding an id for the sake of it.
 */
enum class GalleryScope {
    /** Everything in the vault, filed or not. The listing a vault opens on. */
    WholeVault,

    /** One album's contents. */
    Album,

    /** The items in no album. */
    Unfiled,
}

/**
 * What is selected, and whether the screen is in selecting mode.
 *
 * Ids only, like the rest of this state — a selection can never pin decrypted bytes past a lock.
 *
 * Not a data class: the generated `equals` would compare the [ids] arrays by identity, so two
 * selections of the same items would be unequal whenever the arrays were rebuilt. Nothing on today's
 * paths rebuilds an identical selection — a change is published only when the selection actually
 * changes — so this is the type's contract being correct rather than a bug being fixed; the point is
 * that a caller comparing two of these, or a `GalleryState` holding one, gets the answer it expects.
 * Equality runs through the content-keyed set below, which is also what makes [contains] a lookup
 * rather than a scan of every selected id per tile.
 */
class GallerySelection(
    val active: Boolean = false,
    val ids: List<ByteArray> = emptyList(),
) {
    private val keys: Set<List<Byte>> = ids.mapTo(LinkedHashSet()) { it.toList() }

    val count: Int get() = ids.size

    /**
     * Whether [id] is selected. Compares by **content**: `ByteArray` equality is identity, so a tile
     * asking about a freshly decoded id would never match an id selected a moment earlier.
     */
    fun contains(id: ByteArray): Boolean = id.toList() in keys

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GallerySelection) return false
        return active == other.active && keys == other.keys
    }

    override fun hashCode(): Int = 31 * active.hashCode() + keys.hashCode()

    /** A count and a flag; never an id's bytes (hard rule #4). */
    override fun toString(): String = "GallerySelection(active=$active, count=$count)"
}

/**
 * One tile. The plaintext index columns ([kind], [size], [createdAt]) always render; the name and
 * MIME come from [meta], which may be [GalleryItemMeta.Unreadable].
 */
class GalleryItem(
    val id: ByteArray,
    val kind: VaultItemKind,
    val size: Long,
    val createdAt: Long,
    val meta: GalleryItemMeta,
) {
    // Content equality, because [id] is a ByteArray: the generated data-class equals would compare
    // it by identity and every re-emission of an unchanged listing would look like a change.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GalleryItem) return false
        return id.contentEquals(other.id) &&
            kind == other.kind &&
            size == other.size &&
            createdAt == other.createdAt &&
            meta == other.meta
    }

    override fun hashCode(): Int {
        var result = id.contentHashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + meta.hashCode()
        return result
    }

    /** Sizes and id lengths only — never the decrypted name or MIME (hard rule #4). */
    override fun toString(): String =
        "GalleryItem(id=${id.size}B, kind=$kind, size=$size, createdAt=$createdAt, readable=${meta is GalleryItemMeta.Readable})"
}

/**
 * A tile's name and type, or the honest absence of them.
 *
 * Sealed on purpose: rendering has to branch, so a damaged item cannot be drawn as an ordinary one
 * by forgetting a null check. An item whose metadata will not open is **shown, not hidden** — the
 * catalog row exists, so silently dropping it would conceal tampering (docs/THREAT_MODEL.md).
 */
sealed interface GalleryItemMeta {
    /** The metadata opened and verified. */
    data class Readable(val name: String, val mime: String) : GalleryItemMeta {
        // Redacted like [GalleryItem]'s: these two fields are decrypted vault content, so the
        // generated data-class toString would print a file name into any log line that renders
        // this value (hard rule #4).
        override fun toString(): String = "Readable(name=${name.length} chars, mime=${mime.length} chars)"
    }

    /**
     * The metadata failed to open — corruption or tampering. The item still lists and can still be
     * shredded; only its name and type are unrecoverable. UI copy must not overstate which of the
     * two it was (hard rule #6).
     */
    data object Unreadable : GalleryItemMeta
}

/** A recoverable, non-fatal gallery failure, surfaced as honest copy (hard rule #6). */
enum class GalleryError {
    /** The import failed — an unreadable source, a full disk, or a catalog write that did not land. */
    ImportFailed,

    /** The listing itself failed; the vault is open but its catalog could not be read. */
    ListingFailed,
}

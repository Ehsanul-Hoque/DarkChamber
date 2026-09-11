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

    /** Pick a file and import it into the vault. A cancelled picker is a no-op, not an error. */
    fun importFile()

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
    val importing: Boolean = false,
    val error: GalleryError? = null,
)

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

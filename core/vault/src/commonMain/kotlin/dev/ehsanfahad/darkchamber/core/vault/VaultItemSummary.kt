package dev.ehsanfahad.darkchamber.core.vault

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind

/**
 * One row of a vault listing: the catalog's plaintext index columns joined to its decrypted
 * [ObjectMeta]. This is what features consume, so no feature needs `core:db` or has to repeat the
 * decrypt-and-map (docs/engineering/MODULES.md — SQLDelight row types never leave `core:db`).
 *
 * [meta] is an [Outcome] rather than a nullable, because a row whose metadata will not open is a
 * **damaged item that must still be listed**, carrying why. Dropping it would hide tampering, and
 * the plaintext columns ([size], [createdAt], [kind]) are still readable, so the item can be shown
 * and shredded even when its name cannot be recovered.
 */
class VaultItemSummary(
    val id: ByteArray,
    /**
     * The album this item is filed in, or `null` when it is in none.
     *
     * **Costs nothing to carry.** It is a plaintext index column, already returned by every item
     * query, so reading it decrypts nothing — which is the property that lets a listing name an
     * item's album without opening anything per item: the *name* is sealed in the albums table, so a
     * caller maps this id through the album listing it already has (docs/ARCHITECTURE.md §5.1).
     *
     * The id alone, never the name. A field that needed the name here would turn a fixed cost into
     * one that grows with the vault.
     */
    val albumId: ByteArray?,
    val kind: VaultItemKind,
    val size: Long,
    val createdAt: Long,
    val meta: Outcome<ObjectMeta>,
) {
    // Not a data class: the generated equals would compare [id] and [albumId] by identity, so every
    // re-emission of an unchanged listing would look like a change to the UI. Compared by content
    // instead.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultItemSummary) return false
        return id.contentEquals(other.id) &&
            // The module's shared nullable comparison (ObjectMeta.kt): both-null is equal, one-null
            // is not — which is the case a bare contentEquals gets wrong.
            albumId.contentEqualsOrBothNull(other.albumId) &&
            kind == other.kind &&
            size == other.size &&
            createdAt == other.createdAt &&
            meta == other.meta
    }

    override fun hashCode(): Int {
        var result = id.contentHashCode()
        result = 31 * result + (albumId?.contentHashCode() ?: 0)
        result = 31 * result + kind.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + meta.hashCode()
        return result
    }

    /**
     * Ids and sizes only — never the decrypted name or MIME (hard rule #4).
     *
     * [albumId] reports presence rather than its bytes. It is an opaque identifier rather than
     * content, but this is the redaction boundary for the whole row and an id is not worth printing.
     */
    override fun toString(): String =
        "VaultItemSummary(id=${id.size}B, album=${if (albumId == null) "absent" else "present"}, " +
            "kind=$kind, size=$size, createdAt=$createdAt, meta=${meta.redactedLabel()})"

    private fun Outcome<ObjectMeta>.redactedLabel(): String =
        when (this) {
            is Outcome.Ok -> "ok"
            is Outcome.Err -> "err"
        }
}

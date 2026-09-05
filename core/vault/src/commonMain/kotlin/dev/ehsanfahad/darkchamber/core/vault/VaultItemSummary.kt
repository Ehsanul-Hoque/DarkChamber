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
    val kind: VaultItemKind,
    val size: Long,
    val createdAt: Long,
    val meta: Outcome<ObjectMeta>,
) {
    // Not a data class: the generated equals would compare [id] by identity, so every re-emission of
    // an unchanged listing would look like a change to the UI. Compared by content instead.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultItemSummary) return false
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

    /** Ids and sizes only — never the decrypted name or MIME (hard rule #4). */
    override fun toString(): String =
        "VaultItemSummary(id=${id.size}B, kind=$kind, size=$size, createdAt=$createdAt, meta=${meta.redactedLabel()})"

    private fun Outcome<ObjectMeta>.redactedLabel(): String =
        when (this) {
            is Outcome.Ok -> "ok"
            is Outcome.Err -> "err"
        }
}

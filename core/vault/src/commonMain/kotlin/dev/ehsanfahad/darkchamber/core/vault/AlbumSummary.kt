package dev.ehsanfahad.darkchamber.core.vault

import dev.ehsanfahad.darkchamber.core.model.Outcome

/**
 * One row of an album listing: the album's plaintext index columns joined to its decrypted name,
 * plus the two things derived from the items it holds.
 *
 * [itemCount] and [coverItemId] come from the catalog's **plaintext** columns — an item's album and
 * its date — so listing albums decrypts one small blob per album and nothing per item. That is what
 * keeps this screen cheap in a large vault, and it is the property to defend: anything that makes
 * the album listing read item metadata turns a fixed cost into one that grows with the whole vault.
 *
 * [name] is an [Outcome] for the same reason [VaultItemSummary.meta] is. An album whose name will
 * not open is a **damaged album that must still be listed** — with its id, its count and its cover
 * intact — so the user can see it and delete it. Dropping it from the listing would hide tampering,
 * and an album that cannot be named is still an album holding files.
 *
 * It carries the name itself rather than the whole [AlbumMeta]: the one secret an album keeps is
 * what it is called. Should [AlbumMeta] grow a second field, this becomes a single
 * `Outcome<AlbumMeta>` rather than a second parallel `Outcome` — both fields come out of one
 * decrypt, so they can only ever succeed or fail together, and modelling that as two independent
 * results would invent a state that cannot happen.
 */
class AlbumSummary(
    val id: ByteArray,
    val name: Outcome<String>,
    val itemCount: Int,
    val coverItemId: ByteArray?,
    val createdAt: Long,
) {
    // Not a data class, for the reason VaultItemSummary is not one: the generated equals would
    // compare [id] and [coverItemId] by identity, so every re-emission of an unchanged listing would
    // look like a change. The album grid re-emits on any write to items — that is the whole point of
    // the cross-table notification it depends on — so this would be the common case, not a corner.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AlbumSummary) return false
        return id.contentEquals(other.id) &&
            name == other.name &&
            itemCount == other.itemCount &&
            coverItemId.contentEqualsOrBothNull(other.coverItemId) &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = id.contentHashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + itemCount.hashCode()
        result = 31 * result + (coverItemId?.contentHashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        return result
    }

    /**
     * Ids as sizes, counts and dates — never the decrypted name (hard rule #4), and never the cover
     * id's bytes, whose presence is the only part worth logging.
     */
    override fun toString(): String =
        "AlbumSummary(id=${id.size}B, name=${name.redactedLabel()}, itemCount=$itemCount, " +
            "cover=${if (coverItemId == null) "absent" else "present"}, createdAt=$createdAt)"

    private fun Outcome<String>.redactedLabel(): String =
        when (this) {
            is Outcome.Ok -> "ok"
            is Outcome.Err -> "err"
        }
}

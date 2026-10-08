package dev.ehsanfahad.darkchamber.feature.vault.api

/**
 * One album, as the album grid draws it.
 *
 * Ids and display values only, like [GalleryItem] — never decrypted bytes, so a retained state
 * cannot pin plaintext past a lock.
 *
 * [itemCount] and [coverItemId] come from the catalog's plaintext columns, so a grid of albums
 * decrypts one small blob per album and nothing per item. Worth knowing before adding anything to
 * this tile: a field that needs item metadata turns a fixed cost into one that grows with the vault.
 */
class AlbumTile(
    val id: ByteArray,
    val name: AlbumTileName,
    val itemCount: Int,
    val coverItemId: ByteArray?,
    val createdAt: Long,
) {
    // Content equality, because [id] and [coverItemId] are ByteArrays: the generated data-class
    // equals compares them by identity, so every re-emission of an unchanged grid would look like a
    // change. The album grid re-emits on any write to items — that is what keeps its counts live —
    // so this is the ordinary case rather than a corner.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AlbumTile) return false
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

    /** Counts and dates only — never the decrypted album name (hard rule #4). */
    override fun toString(): String =
        "AlbumTile(id=${id.size}B, name=${if (name is AlbumTileName.Readable) "readable" else "unreadable"}, " +
            "itemCount=$itemCount, cover=${if (coverItemId == null) "absent" else "present"}, createdAt=$createdAt)"
}

/**
 * An album's name, or the honest absence of it.
 *
 * Sealed for the reason [GalleryItemMeta] is: rendering has to branch, so an album whose name will
 * not open cannot be drawn as an ordinary one by forgetting a null check. Such an album is **shown,
 * not hidden** — it still holds files, and dropping it from the grid would conceal tampering while
 * stranding whatever is filed inside it.
 */
sealed interface AlbumTileName {
    /** The name opened and verified. */
    data class Readable(val value: String) : AlbumTileName {
        // The name is decrypted vault content, so the generated data-class toString would print it
        // into any log line that rendered this (hard rule #4).
        override fun toString(): String = "Readable(${value.length} chars)"
    }

    /**
     * The name failed to open — corruption or tampering. The album still lists, still shows its
     * count, and can still be deleted; only its name is unrecoverable. Copy must not claim to know
     * which of the two it was (hard rule #6).
     */
    data object Unreadable : AlbumTileName
}

private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean =
    if (this == null || other == null) this == null && other == null else contentEquals(other)

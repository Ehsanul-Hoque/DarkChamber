package dev.ehsanfahad.darkchamber.core.model

/**
 * Which items a listing covers: everything, one album, or the ones in no album at all.
 *
 * **Three states, not a nullable id.** `WHERE album_id = ?` never matches `NULL` in SQL, so bound
 * to `null` it returns nothing rather than the unfiled items — and a nullable parameter has already
 * spent its `null` on "no filter". The unfiled items therefore need a case of their own, and a
 * listing that cannot name them leaves anything not put in an album unreachable.
 *
 * Not to be confused with an album *destination*, where a nullable id is right: there `null`
 * unambiguously means "unfile these". The two look alike and mean different things — a destination
 * answers *where to put it*, a filter answers *what to show*, and only the filter has a third
 * answer.
 */
sealed interface ItemFilter {

    /** Every item in the vault, filed or not. */
    data object All : ItemFilter

    /** Only items with no album — what "Unfiled" shows. */
    data object Unfiled : ItemFilter

    /**
     * Only items filed in the album with this [id].
     *
     * Not a `data class`: [ByteArray] compares and hashes by identity, so the generated `equals`
     * would call two filters over the same album different. Equality is by content below, because
     * callers compare filters — a listing re-subscribes when its filter changes, and an identity
     * comparison would make every rebuild of the same filter look like a change.
     */
    class Album(val id: ByteArray) : ItemFilter {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Album && id.contentEquals(other.id))

        override fun hashCode(): Int = id.contentHashCode()

        /** Content-free: an album id is an opaque identifier, but `toString` stays terse anyway. */
        override fun toString(): String = "Album(${id.size} bytes)"
    }
}

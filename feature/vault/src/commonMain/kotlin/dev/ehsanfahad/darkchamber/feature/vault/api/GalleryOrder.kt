package dev.ehsanfahad.darkchamber.feature.vault.api

/**
 * The order a gallery listing is shown in.
 *
 * **Four values rather than a key plus a direction.** The pair reads tidier but admits combinations
 * the control never offers — and the comparator would have to answer for them anyway — while these
 * four make it one exhaustive `when`, and the control a list of four.
 *
 * [Newest] is the default and is exactly what every listing did before ordering existed, so a screen
 * nobody has touched behaves as it always has.
 *
 * Only date and name are here, and that is a constraint rather than a shortlist: the catalog's
 * plaintext columns could also order by size or kind, but an item's **name lives in its encrypted
 * metadata**, so a name order cannot be a query at all and is applied in memory to a listing that is
 * already decrypted (see `ObserveGallery`).
 */
enum class GalleryOrder {
    /** Most recently added first — what the catalog's own queries return. */
    Newest,

    /** Oldest first. */
    Oldest,

    /** By name, A to Z, ignoring case. */
    NameAsc,

    /** By name, Z to A, ignoring case. */
    NameDesc,
}

package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryOrder

/**
 * Put a listing in [order].
 *
 * A function rather than a step inside the listing stage, because this is the part with rules in it
 * and the rules are worth testing without a flow, a component or a session around them.
 *
 * **Every order is total.** The date and the name can both tie, so each comparison ends at the item's
 * id — and that matters more here than it sounds: the catalog's own queries carry an `id` tie-break
 * because a batch import writes many rows inside one millisecond, so equal `createdAt` is the
 * *ordinary* case. Without a final key SQLite's order and this one could each pick differently on
 * successive emissions, and the grid keys its tiles by id and animates the ones that arrive — so an
 * unstable tie order reshuffles the grid and replays that animation on items that never moved.
 */
internal fun List<GalleryItem>.ordered(order: GalleryOrder): List<GalleryItem> =
    sortedWith(comparatorFor(order))

private fun comparatorFor(order: GalleryOrder): Comparator<GalleryItem> =
    when (order) {
        // A tie within a date order is broken by name **ascending**, in both directions: the date is
        // what is being reversed, not the alphabet. This is what gives "newest, then name" without a
        // second control — a thirty-file import shares one timestamp, so it reads top to bottom
        // instead of shuffled.
        GalleryOrder.Newest -> compareByDescending<GalleryItem> { it.createdAt }.thenByName().thenById()
        GalleryOrder.Oldest -> compareBy<GalleryItem> { it.createdAt }.thenByName().thenById()

        // A tie within a name order is broken by date, newest first — the same default the vault
        // opens on, so two files called the same thing read in the order they arrived.
        GalleryOrder.NameAsc -> nameComparator(descending = false).thenByDateDescending().thenById()
        GalleryOrder.NameDesc -> nameComparator(descending = true).thenByDateDescending().thenById()
    }

/**
 * Compare by name, with every item whose name will not open placed **last** — in both directions.
 *
 * An unreadable item has no name, so any interleaved position would imply one. It is demoted here
 * and nowhere else: under a date order its plaintext columns are intact, so there is nothing honest
 * about moving it (see [comparatorFor]).
 */
private fun nameComparator(descending: Boolean): Comparator<GalleryItem> =
    Comparator { left, right ->
        val leftName = left.sortName()
        val rightName = right.sortName()
        when {
            leftName == null && rightName == null -> 0
            leftName == null -> 1
            rightName == null -> -1
            else -> leftName.compareTo(rightName).let { if (descending) -it else it }
        }
    }

private fun Comparator<GalleryItem>.thenByName(): Comparator<GalleryItem> =
    then(nameComparator(descending = false))

private fun Comparator<GalleryItem>.thenByDateDescending(): Comparator<GalleryItem> =
    thenByDescending { it.createdAt }

// Descending, to agree with the catalog's own `created_at DESC, id DESC` rather than contradict the
// query this is reordering. Lexicographic over unsigned bytes, which is how SQLite compares a BLOB —
// Kotlin's Byte is signed, so a raw comparison would sort 0x80 below 0x01.
private fun Comparator<GalleryItem>.thenById(): Comparator<GalleryItem> =
    then { left, right -> compareIdsDescending(left.id, right.id) }

private fun compareIdsDescending(left: ByteArray, right: ByteArray): Int {
    val shared = minOf(left.size, right.size)
    for (index in 0 until shared) {
        val difference = (left[index].toInt() and 0xFF) - (right[index].toInt() and 0xFF)
        if (difference != 0) return -difference
    }
    return -(left.size - right.size)
}

/**
 * The name to sort by, **case-folded**, or `null` when there is none.
 *
 * Case-insensitive because code-point order puts every capital ahead of every lowercase letter, so a
 * vault holding `Zebra` and `apple` would read as though it were unsorted. This is a case fold and a
 * code-point comparison, **not** locale-aware collation — adequate for A–Z, and worth knowing before
 * anyone reads it as a promise about alphabetical order in every script the app renders.
 */
private fun GalleryItem.sortName(): String? =
    when (val meta = meta) {
        is GalleryItemMeta.Readable -> meta.name.lowercase()
        GalleryItemMeta.Unreadable -> null
    }

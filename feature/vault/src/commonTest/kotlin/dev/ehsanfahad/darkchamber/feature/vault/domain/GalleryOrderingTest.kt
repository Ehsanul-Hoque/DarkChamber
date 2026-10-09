package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryOrder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How the gallery orders its tiles.
 *
 * The cases that carry weight are the **ties**. A batch import writes every row inside one
 * millisecond, so equal `createdAt` is the ordinary case rather than a corner — which is why a date
 * order breaks ties by name, and why the order has to be total: without a final key the grid can
 * reshuffle on a re-emission and replay the arrival animation on items that never moved.
 */
class GalleryOrderingTest {

    private fun item(
        id: Byte,
        name: String? = "a.txt",
        createdAt: Long = 100L,
    ) = GalleryItem(
        id = byteArrayOf(id),
        kind = VaultItemKind.FILE,
        size = 1L,
        createdAt = createdAt,
        meta = if (name == null) GalleryItemMeta.Unreadable else GalleryItemMeta.Readable(name, "text/plain"),
    )

    private fun List<GalleryItem>.ids(): List<Byte> = map { it.id.single() }

    @Test
    fun newest_puts_the_most_recent_first() {
        val items = listOf(item(1, createdAt = 10L), item(2, createdAt = 30L), item(3, createdAt = 20L))

        assertEquals(listOf<Byte>(2, 3, 1), items.ordered(GalleryOrder.Newest).ids())
    }

    @Test
    fun oldest_puts_the_most_recent_last() {
        val items = listOf(item(1, createdAt = 10L), item(2, createdAt = 30L), item(3, createdAt = 20L))

        assertEquals(listOf<Byte>(1, 3, 2), items.ordered(GalleryOrder.Oldest).ids())
    }

    @Test
    fun name_orders_run_both_ways() {
        val items = listOf(item(1, name = "beach.jpg"), item(2, name = "apple.txt"), item(3, name = "cat.png"))

        assertEquals(listOf<Byte>(2, 1, 3), items.ordered(GalleryOrder.NameAsc).ids())
        assertEquals(listOf<Byte>(3, 1, 2), items.ordered(GalleryOrder.NameDesc).ids())
    }

    @Test
    fun names_order_without_regard_to_case() {
        // Code-point ordering would put every capital ahead of every lowercase letter, so a vault
        // holding "Zebra" and "apple" would read as if it were unsorted.
        val items = listOf(item(1, name = "Zebra.txt"), item(2, name = "apple.txt"), item(3, name = "Mango.txt"))

        assertEquals(listOf<Byte>(2, 3, 1), items.ordered(GalleryOrder.NameAsc).ids())
    }

    @Test
    fun a_date_order_breaks_a_tie_by_name_ascending() {
        // The whole batch-import case: one millisecond, many rows. Ascending in both directions —
        // the date order is reversed, the alphabet is not.
        val items = listOf(item(1, name = "c.txt"), item(2, name = "a.txt"), item(3, name = "b.txt"))

        assertEquals(listOf<Byte>(2, 3, 1), items.ordered(GalleryOrder.Newest).ids())
        assertEquals(listOf<Byte>(2, 3, 1), items.ordered(GalleryOrder.Oldest).ids())
    }

    @Test
    fun a_date_order_still_leads_with_the_date() {
        // The name tie-break must never outrank the date it is breaking a tie within.
        val items = listOf(item(1, name = "a.txt", createdAt = 10L), item(2, name = "z.txt", createdAt = 20L))

        assertEquals(listOf<Byte>(2, 1), items.ordered(GalleryOrder.Newest).ids())
    }

    @Test
    fun a_name_order_breaks_a_tie_by_date_newest_first() {
        val items = listOf(
            item(1, name = "same.txt", createdAt = 10L),
            item(2, name = "same.txt", createdAt = 30L),
            item(3, name = "same.txt", createdAt = 20L),
        )

        assertEquals(listOf<Byte>(2, 3, 1), items.ordered(GalleryOrder.NameAsc).ids())
        assertEquals(listOf<Byte>(2, 3, 1), items.ordered(GalleryOrder.NameDesc).ids())
    }

    @Test
    fun identical_rows_are_separated_by_id_so_the_order_is_total() {
        val items = listOf(item(2), item(5), item(1))

        // Descending, matching the catalog's own `created_at DESC, id DESC` tie-break, so the
        // in-memory order agrees with the query it is reordering rather than contradicting it.
        assertEquals(listOf<Byte>(5, 2, 1), items.ordered(GalleryOrder.Newest).ids())
        assertEquals(listOf<Byte>(5, 2, 1), items.ordered(GalleryOrder.NameAsc).ids())
    }

    @Test
    fun the_id_tie_break_treats_bytes_as_unsigned_like_the_catalog_does() {
        // Kotlin's Byte is signed, so a raw comparison sorts 0x80 (-128) below 0x01 — while SQLite
        // compares a BLOB as unsigned bytes. The two must agree, or this reordering contradicts the
        // query it is reordering and the tie order flips between emissions.
        val high = GalleryItem(
            id = byteArrayOf(0x80.toByte()),
            kind = VaultItemKind.FILE,
            size = 1L,
            createdAt = 100L,
            meta = GalleryItemMeta.Readable("a.txt", "text/plain"),
        )
        val low = item(0x01)

        assertEquals(
            listOf(0x80.toByte(), 0x01.toByte()),
            listOf(low, high).ordered(GalleryOrder.Newest).ids(),
            "0x80 is the larger id, so a descending tie-break puts it first",
        )
    }

    @Test
    fun a_longer_id_outranks_the_prefix_it_shares() {
        val short = item(4)
        val long = GalleryItem(
            id = byteArrayOf(4, 0),
            kind = VaultItemKind.FILE,
            size = 1L,
            createdAt = 100L,
            meta = GalleryItemMeta.Readable("a.txt", "text/plain"),
        )

        assertEquals(
            listOf(2, 1),
            listOf(short, long).ordered(GalleryOrder.Newest).map { it.id.size },
            "with one id a prefix of the other, the longer sorts first under a descending tie-break",
        )
    }

    @Test
    fun an_item_with_no_readable_name_sorts_last_in_both_name_orders() {
        val items = listOf(item(1, name = null), item(2, name = "apple.txt"), item(3, name = "zebra.txt"))

        // It has no name, so any interleaved position would imply one.
        assertEquals(listOf<Byte>(2, 3, 1), items.ordered(GalleryOrder.NameAsc).ids())
        assertEquals(listOf<Byte>(3, 2, 1), items.ordered(GalleryOrder.NameDesc).ids())
    }

    @Test
    fun several_unreadable_items_keep_a_stable_order_among_themselves() {
        val items = listOf(
            item(1, name = null, createdAt = 10L),
            item(2, name = null, createdAt = 30L),
            item(3, name = "a.txt", createdAt = 20L),
        )

        assertEquals(listOf<Byte>(3, 2, 1), items.ordered(GalleryOrder.NameAsc).ids())
    }

    @Test
    fun a_date_order_treats_an_unreadable_item_like_any_other() {
        // Its plaintext columns are intact, so there is nothing honest about demoting it here.
        val items = listOf(
            item(1, name = "a.txt", createdAt = 10L),
            item(2, name = null, createdAt = 30L),
            item(3, name = "z.txt", createdAt = 20L),
        )

        assertEquals(listOf<Byte>(2, 3, 1), items.ordered(GalleryOrder.Newest).ids())
    }

    @Test
    fun ordering_an_already_ordered_list_changes_nothing() {
        val items = listOf(
            item(1, name = "b.txt", createdAt = 20L),
            item(2, name = "a.txt", createdAt = 10L),
            item(3, name = null, createdAt = 30L),
        )

        for (order in GalleryOrder.entries) {
            val once = items.ordered(order)
            assertEquals(once.ids(), once.ordered(order).ids(), "$order must be idempotent")
        }
    }

    @Test
    fun ordering_an_empty_or_single_listing_is_not_a_special_case() {
        assertEquals(emptyList(), emptyList<GalleryItem>().ordered(GalleryOrder.NameAsc).ids())
        assertEquals(listOf<Byte>(7), listOf(item(7)).ordered(GalleryOrder.NameDesc).ids())
    }
}

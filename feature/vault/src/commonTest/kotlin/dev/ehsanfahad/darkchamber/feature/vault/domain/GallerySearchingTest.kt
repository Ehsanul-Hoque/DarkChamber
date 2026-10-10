package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Filtering a listing by name.
 *
 * The decision with weight in it is what happens to a **damaged item**: a query cannot match a name
 * nobody can read, so such an item is excluded — but it is excluded *countably*, because a search
 * that silently dropped it would be a way to make tampering invisible. Keeping it in every result
 * was the other option and is a false positive by construction.
 */
class GallerySearchingTest {

    private fun item(
        id: Byte,
        name: String? = "a.txt",
        mime: String = "text/plain",
    ) = GalleryItem(
        id = byteArrayOf(id),
        kind = VaultItemKind.FILE,
        size = 1L,
        createdAt = 100L,
        meta = if (name == null) GalleryItemMeta.Unreadable else GalleryItemMeta.Readable(name, mime),
    )

    private fun List<GalleryItem>.ids(): List<Byte> = map { it.id.single() }

    @Test
    fun a_query_matches_anywhere_in_the_name() {
        val items = listOf(item(1, name = "holiday-tax.pdf"), item(2, name = "cat.png"))

        val matched = items.matching("tax")

        assertEquals(listOf<Byte>(1), matched.items.ids())
        assertEquals(0, matched.hiddenUnreadable, "nothing was damaged, so nothing was left out")
    }

    @Test
    fun matching_ignores_case_in_both_directions() {
        val items = listOf(item(1, name = "Receipt.PDF"))

        assertEquals(listOf<Byte>(1), items.matching("receipt").items.ids())
        assertEquals(listOf<Byte>(1), items.matching("RECEIPT").items.ids())
    }

    @Test
    fun a_blank_query_filters_nothing_at_all() {
        // Clearing the field must be the same code path as never having typed — including for
        // damaged items, which are only excluded by an actual search.
        val items = listOf(item(1, name = "a.txt"), item(2, name = null))

        val matched = items.matching("")

        assertEquals(listOf<Byte>(1, 2), matched.items.ids())
        assertEquals(0, matched.hiddenUnreadable, "nothing is being hidden when nothing is searched")
    }

    @Test
    fun a_whitespace_only_query_is_treated_as_blank() {
        val items = listOf(item(1, name = "a.txt"), item(2, name = null))

        val matched = items.matching("   ")

        assertEquals(listOf<Byte>(1, 2), matched.items.ids())
        assertEquals(0, matched.hiddenUnreadable)
    }

    @Test
    fun whitespace_around_a_query_is_ignored() {
        val items = listOf(item(1, name = "tax.pdf"))

        assertEquals(listOf<Byte>(1), items.matching("  tax  ").items.ids())
    }

    @Test
    fun whitespace_inside_a_query_still_counts() {
        val items = listOf(item(1, name = "alpine trip.jpg"), item(2, name = "alpinetrip.jpg"))

        assertEquals(listOf<Byte>(1), items.matching("alpine trip").items.ids())
    }

    @Test
    fun the_mime_type_is_never_matched() {
        // It sits in the same decrypted metadata, so matching it would be free — and wrong: someone
        // searching "tax" is not asking for every PDF.
        val items = listOf(item(1, name = "cat.png", mime = "image/png"))

        val matched = items.matching("png")

        assertEquals(listOf<Byte>(1), matched.items.ids(), "the name's extension is part of the name")
        assertEquals(emptyList(), items.matching("image").items.ids(), "the MIME type is not searched")
    }

    @Test
    fun a_damaged_item_is_left_out_of_a_search_and_counted() {
        val items = listOf(item(1, name = "tax.pdf"), item(2, name = null), item(3, name = null))

        val matched = items.matching("tax")

        assertEquals(listOf<Byte>(1), matched.items.ids(), "a name nobody can read cannot match")
        assertEquals(2, matched.hiddenUnreadable, "both damaged items have to be accounted for")
    }

    @Test
    fun a_search_that_matches_nothing_still_accounts_for_damaged_items() {
        val items = listOf(item(1, name = "cat.png"), item(2, name = null))

        val matched = items.matching("tax")

        assertEquals(emptyList(), matched.items.ids())
        assertEquals(1, matched.hiddenUnreadable)
    }

    @Test
    fun filtering_preserves_the_order_it_was_given() {
        // Ordering is a separate rule applied separately: this must not quietly reorder a listing.
        val items = listOf(item(3, name = "c-tax.txt"), item(1, name = "a-tax.txt"), item(2, name = "b-tax.txt"))

        assertEquals(listOf<Byte>(3, 1, 2), items.matching("tax").items.ids())
    }

    @Test
    fun an_empty_listing_searches_to_an_empty_listing() {
        val matched = emptyList<GalleryItem>().matching("tax")

        assertEquals(emptyList(), matched.items.ids())
        assertEquals(0, matched.hiddenUnreadable)
    }
}

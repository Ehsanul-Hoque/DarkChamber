package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.foundation.lazy.grid.LazyGridState
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryOrder
import dev.ehsanfahad.darkchamber.feature.vault.api.GallerySearch
import dev.ehsanfahad.darkchamber.feature.vault.domain.matching
import dev.ehsanfahad.darkchamber.feature.vault.domain.ordered
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Where the grid is left when the browse controls change.
 *
 * The three rules behind this all came off a device, and all three are silent when broken: a
 * re-order that moves the viewport to the far end, a capture armed by re-choosing the order already
 * in force and collected by the next listing to arrive for any reason, and a capture collected by the
 * composition that armed it — which is still showing the old listing. None of them is visible to a
 * reader of the layout, and this project has no UI tests, so they are checked here against a
 * [LazyGridState] driven by hand.
 *
 * `requestScrollToItem` on a grid that is not attached to a composition still records the position it
 * was asked for, which is all these assertions read.
 */
class BrowsingScrollTest {

    private fun item(id: Byte, name: String) = GalleryItem(
        id = byteArrayOf(id),
        kind = VaultItemKind.FILE,
        size = 1L,
        createdAt = 1L,
        meta = GalleryItemMeta.Readable(name, "text/plain"),
    )

    /** A fresh listing instance every call — a new emission is exactly a new instance. */
    private fun listing() = listOf(item(1, "a.txt"), item(2, "b.txt"))

    /** An empty one, built the way the listing builds it rather than written out as a literal. */
    private fun emptyListing() = emptyList<GalleryItem>().ordered(GalleryOrder.Newest).matching("").items

    private fun scrolled(index: Int, offset: Int) =
        LazyGridState(firstVisibleItemIndex = index, firstVisibleItemScrollOffset = offset)

    @Test
    fun a_re_order_puts_the_grid_back_where_it_was() {
        val grid = scrolled(7, 3)
        val scroll = BrowsingScroll(grid)
        scroll.onListingComposed(listing())

        scroll.rememberPlaceBeforeReordering(from = GalleryOrder.Newest, to = GalleryOrder.NameAsc)
        // The grid moves itself as the new order is measured — the device-measured symptom this
        // whole holder exists for.
        grid.requestScrollToItem(54, 0)
        scroll.onListingComposed(listing())

        assertEquals(7, grid.firstVisibleItemIndex, "back at the tile the user was looking at")
        assertEquals(3, grid.firstVisibleItemScrollOffset)
    }

    /**
     * **The composition that arms the capture must not collect it.** Choosing an order recomposes the
     * screen at once, while the listing it will produce has not arrived — so the side effect runs with
     * the *old* list still in hand, and consuming the capture there would restore a position the grid
     * has not left yet and leave the real move uncorrected.
     */
    @Test
    fun the_capture_survives_the_composition_that_armed_it() {
        val grid = scrolled(7, 3)
        val scroll = BrowsingScroll(grid)
        val shown = listing()
        scroll.onListingComposed(shown)

        scroll.rememberPlaceBeforeReordering(from = GalleryOrder.Newest, to = GalleryOrder.NameAsc)
        grid.requestScrollToItem(54, 0)
        scroll.onListingComposed(shown) // same listing: nothing new has arrived

        assertEquals(54, grid.firstVisibleItemIndex, "nothing restored yet")
        assertTrue(scroll.isReordering, "and the re-order is still in flight")

        scroll.onListingComposed(listing())

        assertEquals(7, grid.firstVisibleItemIndex, "restored by the listing that actually arrived")
        assertFalse(scroll.isReordering)
    }

    /**
     * **Choosing the order already in force arms nothing.** The controls are a state flow, so an equal
     * value is conflated and no listing is produced at all — an armed capture would simply wait, and
     * the next listing to arrive for any other reason would collect it. On a device that showed as
     * filing one item sending the grid back to where it had been when the order was re-chosen.
     */
    @Test
    fun choosing_the_order_already_in_force_arms_nothing() {
        val grid = scrolled(7, 3)
        val scroll = BrowsingScroll(grid)
        scroll.onListingComposed(listing())

        scroll.rememberPlaceBeforeReordering(from = GalleryOrder.Newest, to = GalleryOrder.Newest)

        assertFalse(scroll.isReordering, "no re-order is happening")

        grid.requestScrollToItem(0, 0) // the user scrolls to the top
        scroll.onListingComposed(listing()) // an item is filed, so a listing arrives

        assertEquals(0, grid.firstVisibleItemIndex, "and it leaves the user where they are")
    }

    /**
     * Listings arrive for imports, filings, renames and shreds. None of those is a re-order, and none
     * of them may move the grid.
     */
    @Test
    fun a_listing_that_arrives_for_another_reason_does_not_move_the_grid() {
        val grid = scrolled(12, 5)
        val scroll = BrowsingScroll(grid)

        scroll.onListingComposed(listing())
        scroll.onListingComposed(listing())

        assertEquals(12, grid.firstVisibleItemIndex)
        assertEquals(5, grid.firstVisibleItemScrollOffset)
        assertFalse(scroll.isReordering)
    }

    /**
     * **Every empty listing is the same instance**, which is the fact the rule below exists for.
     *
     * Pinned through the real pipeline rather than asserted about the standard library, because what
     * matters is what this listing does: ordering an empty list returns the one shared empty list, and
     * an empty query hands it straight on. A later change to either — a different sort, a copy made
     * for safety — would make the holder's identity check start working for empty listings, and this
     * test is what would say so.
     */
    @Test
    fun an_empty_listing_is_the_same_instance_every_time() {
        val first = emptyList<GalleryItem>().ordered(GalleryOrder.Newest).matching("").items
        val second = emptyList<GalleryItem>().ordered(GalleryOrder.NameAsc).matching("").items

        assertSame(first, second, "nothing here can tell one empty emission from the next")
    }

    /**
     * **A grid with nothing in it has no position to hold, so choosing an order arms nothing.**
     *
     * Without this the capture is armed and never collected: the listing that answers the new order is
     * empty too, and an empty listing is the same instance as the last one — so the holder cannot see
     * it arrive. The capture would then wait for the next listing with items in it, which is an import
     * or a filing, and spend a stale position on it while having suppressed that listing's arrival
     * animation in the meantime.
     *
     * Reachable: the controls row stays up over an empty listing while the search field is open, so
     * the order control is there to be tapped.
     */
    @Test
    fun an_order_chosen_over_an_empty_listing_arms_nothing() {
        val grid = scrolled(0, 0)
        val scroll = BrowsingScroll(grid)
        scroll.onListingComposed(emptyListing())

        scroll.rememberPlaceBeforeReordering(from = GalleryOrder.Newest, to = GalleryOrder.NameAsc)

        assertFalse(scroll.isReordering, "nothing is being re-ordered on a grid with nothing in it")

        // The listing answering the new order: empty, and so the very same instance.
        scroll.onListingComposed(emptyListing())
        // Items arrive at last — an import into the album that was empty — and the user is somewhere
        // in them by the time the next listing lands.
        grid.requestScrollToItem(9, 4)
        scroll.onListingComposed(listing())

        assertEquals(9, grid.firstVisibleItemIndex, "no capture was waiting to be spent on this")
        assertFalse(scroll.isReordering, "and nothing was left in flight")
    }

    /**
     * A changed **query** is the opposite case to a changed order: the result set is new, so its first
     * match is the one worth seeing — and a held offset can sit past the end of a short result, which
     * reads as matches having been dropped.
     */
    @Test
    fun a_changed_query_puts_the_grid_back_at_the_top() {
        val grid = scrolled(12, 5)
        val scroll = BrowsingScroll(grid)
        scroll.onSearch(GallerySearch.Closed)
        scroll.onListingComposed(listing())

        scroll.onSearch(GallerySearch.Open(query = "al"))
        scroll.onListingComposed(listing())

        assertEquals(0, grid.firstVisibleItemIndex)
        assertEquals(0, grid.firstVisibleItemScrollOffset)
    }

    /**
     * **The first search seen is not a change.** It is the state the screen opened in, and treating it
     * as a change would scroll a restored screen to the top for no reason the user did.
     */
    @Test
    fun the_first_search_seen_moves_nothing() {
        val grid = scrolled(12, 5)
        val scroll = BrowsingScroll(grid)

        scroll.onSearch(GallerySearch.Open(query = "al"))
        scroll.onListingComposed(listing())

        assertEquals(12, grid.firstVisibleItemIndex, "nothing changed; it was already there")
    }

    /**
     * **Opening or closing an empty field is not a change either**, so it does not move the grid —
     * the same rule [scrollsToTop] holds, checked here through the holder that applies it.
     */
    @Test
    fun opening_an_empty_field_moves_nothing() {
        val grid = scrolled(12, 5)
        val scroll = BrowsingScroll(grid)
        scroll.onSearch(GallerySearch.Closed)
        scroll.onListingComposed(listing())

        scroll.onSearch(GallerySearch.Open(query = ""))
        scroll.onListingComposed(listing())

        assertEquals(12, grid.firstVisibleItemIndex)
    }
}

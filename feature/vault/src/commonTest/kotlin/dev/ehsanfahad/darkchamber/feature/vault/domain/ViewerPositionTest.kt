package dev.ehsanfahad.darkchamber.feature.vault.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The viewer's pager follows an item, not an index: the listing under it can change while it is
 * open (an import finishing, a delete), and the page on screen must stay the same item.
 *
 * Every id here is a fresh array — `id(n)` builds a new one each call — because the listing's ids
 * arrive decoded from SQLite and are never the instances the screen held before.
 */
class ViewerPositionTest {
    private fun id(n: Int) = byteArrayOf(n.toByte())

    private fun listing(vararg ns: Int) = ns.map(::id)

    @Test
    fun the_viewer_opens_on_the_item_that_was_tapped() {
        assertEquals(ViewerPosition.Showing(id(7), 2), startPosition(id(7), listing(3, 5, 7, 9)))
        assertEquals(ViewerPosition.Showing(id(3), 0), startPosition(id(3), listing(3, 5, 7, 9)))
    }

    /**
     * Gone before the first listing arrived (shredded in the meantime): close rather than open on a
     * neighbour — a viewer showing something other than what was tapped would be a surprise.
     */
    @Test
    fun a_tapped_item_that_is_already_gone_closes_the_viewer() {
        assertEquals(ViewerPosition.Close, startPosition(id(4), listing(3, 5, 7)))
        assertEquals(ViewerPosition.Close, startPosition(id(4), emptyList()))
    }

    /**
     * New items arriving ahead of the one on screen move it along the listing; the page must move
     * with it. The old index (1) now holds a different item, so staying on the index would be wrong.
     */
    @Test
    fun the_page_stays_on_its_item_when_items_arrive_ahead_of_it() {
        val showing = ViewerPosition.Showing(id(5), 1)

        assertEquals(ViewerPosition.Showing(id(5), 3), showing.following(listing(1, 2, 3, 5, 7)))
    }

    @Test
    fun an_unchanged_listing_leaves_the_page_where_it_is() {
        assertEquals(ViewerPosition.Showing(id(5), 1), ViewerPosition.Showing(id(5), 1).following(listing(3, 5, 7)))
    }

    /**
     * The item on screen was deleted: show the one that took its place — the next one along, in the
     * direction the user was reading. Not the previous (index 0 here), and not "close".
     */
    @Test
    fun a_vanished_item_gives_way_to_the_one_now_in_its_place() {
        val showing = ViewerPosition.Showing(id(5), 1)

        assertEquals(ViewerPosition.Showing(id(7), 1), showing.following(listing(3, 7, 9)))
    }

    /** …unless it was the last: then there is nothing after it, and the one before is shown. */
    @Test
    fun a_vanished_last_item_gives_way_to_the_one_before() {
        val showing = ViewerPosition.Showing(id(9), 2)

        assertEquals(ViewerPosition.Showing(id(5), 1), showing.following(listing(3, 5)))
    }

    /**
     * Several gone at once, including everything from the page onwards: the page lands on the new
     * last item, never on an index past the end.
     */
    @Test
    fun a_listing_that_shrank_past_the_page_lands_on_its_new_last_item() {
        val showing = ViewerPosition.Showing(id(9), 4)

        assertEquals(ViewerPosition.Showing(id(3), 0), showing.following(listing(3)))
    }

    /**
     * A position is the same position only at the same index too: the pager scrolls by index, so a
     * state holder that collapsed "same item, new index" as unchanged would leave the page behind.
     */
    @Test
    fun two_positions_are_equal_only_for_the_same_item_at_the_same_index() {
        assertEquals(ViewerPosition.Showing(id(5), 1), ViewerPosition.Showing(id(5), 1), "same item by content, same index")
        assertNotEquals<ViewerPosition>(ViewerPosition.Showing(id(5), 1), ViewerPosition.Showing(id(5), 3), "a moved item")
        assertNotEquals<ViewerPosition>(ViewerPosition.Showing(id(5), 1), ViewerPosition.Showing(id(6), 1), "a different item")
    }

    @Test
    fun an_empty_listing_closes_the_viewer() {
        assertEquals(ViewerPosition.Close, ViewerPosition.Showing(id(5), 1).following(emptyList()))
    }
}

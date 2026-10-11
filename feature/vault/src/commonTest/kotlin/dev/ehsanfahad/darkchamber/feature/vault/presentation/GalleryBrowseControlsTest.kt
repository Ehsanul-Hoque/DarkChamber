package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumState
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTileName
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryOrder
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryScope
import dev.ehsanfahad.darkchamber.feature.vault.api.GallerySearch
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryState
import dev.ehsanfahad.darkchamber.feature.vault.api.ImportState
import dev.ehsanfahad.darkchamber.feature.vault.api.OriginalDelete
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When the browse controls are on screen.
 *
 * Pulled out of the composable deliberately: there are no UI tests in this project, so a condition
 * written into a layout is a condition nothing checks — and three of the four cases that hide this
 * row were found by asking what sorting would mean on a screen that has no listing.
 */
class GalleryBrowseControlsTest {

    private val item = GalleryItem(
        id = byteArrayOf(1),
        kind = VaultItemKind.FILE,
        size = 1L,
        createdAt = 1L,
        meta = GalleryItemMeta.Readable("a.txt", "text/plain"),
    )

    private fun listing() = GalleryState(items = listOf(item), loading = false)

    @Test
    fun a_listing_gets_the_controls() {
        assertTrue(listing().showsBrowseControls())
    }

    /**
     * An opening vault has no clause of its own and does not need one — it is an empty listing, and
     * that is the point being recorded here. `loading` is only ever written `false`, so no state can
     * hold items while still loading; a `!loading` term in the predicate would be unsatisfiable, and
     * a test written to "cover loading" would in fact be covering the empty case twice.
     */
    @Test
    fun an_opening_vault_does_not() {
        assertFalse(GalleryState(loading = true).showsBrowseControls())
    }

    @Test
    fun a_listing_that_could_not_be_read_does_not() {
        // **With items still on screen**, which is the reachable version of this state: the failure
        // leaves the last good listing where it was, so an empty list here would be testing the
        // empty-listing rule a second time instead of this one.
        assertFalse(listing().copy(listingUnreadable = true).showsBrowseControls())
    }

    @Test
    fun an_album_that_is_no_longer_there_does_not() {
        // That screen is a notice and one way out, not a listing.
        assertFalse(listing().copy(album = AlbumState.Gone).showsBrowseControls())
    }

    @Test
    fun an_album_that_is_there_still_does() {
        // An album's contents are a listing like any other, and searching inside one is the point of
        // searching the listing you are standing on.
        val state = listing().copy(album = AlbumState.Present(AlbumTileName.Readable("Trip")))

        assertTrue(state.showsBrowseControls())
    }

    @Test
    fun an_empty_listing_does_not() {
        // Nothing to order and nothing to find.
        assertFalse(listing().copy(items = emptyList()).showsBrowseControls())
    }

    /**
     * **The exception the predicate exists for.** A search that matched nothing leaves the listing
     * empty too — and hiding the row then takes away the only way to clear the query, leaving the
     * grid stuck empty with nothing on screen to explain it.
     */
    @Test
    fun a_search_that_matched_nothing_keeps_the_controls() {
        val state = listing().copy(
            items = emptyList(),
            search = GallerySearch.Open(query = "zz", hiddenUnreadable = 0),
        )

        assertTrue(state.showsBrowseControls())
    }

    /**
     * A closed search says nothing about items it left out — **not because the count is zero, but
     * because there is no count**: nothing was filtered, so nothing was excluded. The distinction
     * matters because the obvious implementation reads a number off a state that does not have one.
     */
    @Test
    fun a_closed_search_says_nothing_about_what_was_left_out() {
        assertNull(GallerySearch.Closed.hiddenNote())
    }

    @Test
    fun an_open_search_that_left_nothing_out_says_nothing_either() {
        // The ordinary case: every name in the listing opened, so the results are the whole answer.
        assertNull(GallerySearch.Open(query = "a", hiddenUnreadable = 0).hiddenNote())
    }

    /**
     * And when it did leave something out, it says so — the exclusion is the point, because a search
     * that drops unreadable rows silently is a way to make tampering invisible.
     */
    @Test
    fun an_open_search_reports_what_it_could_not_read() {
        val note = GallerySearch.Open(query = "a", hiddenUnreadable = 2).hiddenNote()

        assertEquals(GalleryFormat.hiddenUnreadable(2), note)
        assertNotNull(note)
    }

    /**
     * **Open is not filtering**, and the difference is load-bearing twice over: it decides whether a
     * finished batch is told its results may be hidden, and it decides what an empty grid blames. A
     * field the user opened and typed nothing into narrows the listing by exactly nothing.
     */
    @Test
    fun a_closed_search_filters_nothing() {
        assertFalse(GallerySearch.Closed.isFiltering())
    }

    @Test
    fun an_open_field_filters_only_once_something_is_typed() {
        assertFalse(GallerySearch.Open(query = "").isFiltering(), "opening is not filtering")
        assertFalse(GallerySearch.Open(query = "   ").isFiltering(), "and whitespace is not a query")
        assertTrue(GallerySearch.Open(query = "al").isFiltering())
    }

    /**
     * Which of the empty-grid messages the screen asks for.
     *
     * The precedence between them belongs to the formatter, which has its own tests; what is checked
     * here is the part that was wrong — **which state each argument comes from**. An empty listing
     * under an open-but-blank field was blamed on the query, because the call site asked whether the
     * field was open rather than whether it filtered.
     */
    @Test
    fun an_emptied_album_under_an_open_field_still_explains_the_album() {
        // Reachable: open the search in an album, type nothing, select everything and remove it.
        val state = GalleryState(
            items = emptyList(),
            loading = false,
            scope = GalleryScope.Album,
            search = GallerySearch.Open(query = ""),
        )

        val message = state.emptyListingMessage()

        assertEquals(GalleryFormat.emptyListing(GalleryScope.Album, unreadable = false), message)
        assertFalse("typed" in message, "nothing was typed, so nothing can be blamed on it: $message")
    }

    @Test
    fun an_empty_grid_under_a_query_says_the_query_did_it() {
        val state = GalleryState(
            items = emptyList(),
            loading = false,
            scope = GalleryScope.Album,
            search = GallerySearch.Open(query = "al"),
        )

        assertEquals(
            GalleryFormat.emptyListing(GalleryScope.Album, unreadable = false, searching = true),
            state.emptyListingMessage(),
        )
    }

    @Test
    fun a_listing_that_could_not_be_read_outranks_both() {
        // A failed read keeps the last good items on screen, so the honest fixture for "empty *and*
        // failed" is a listing that failed before anything arrived.
        val state = GalleryState(
            items = emptyList(),
            loading = false,
            scope = GalleryScope.Unfiled,
            listingUnreadable = true,
            search = GallerySearch.Open(query = "al"),
        )

        assertEquals(
            GalleryFormat.emptyListing(GalleryScope.Unfiled, unreadable = true),
            state.emptyListingMessage(),
        )
    }

    private fun finished(imported: Int = 2) =
        GalleryState(items = listOf(item), loading = false, batch = ImportState.Finished(imported, emptyList()))

    /**
     * Newest-first used to make this free: a new item appeared at the top, where the user was already
     * looking. Both controls undo that premise, so a finished batch offers the way back to seeing it.
     */
    @Test
    fun a_filtered_screen_offers_to_clear_the_search() {
        val state = finished().copy(search = GallerySearch.Open(query = "al"))

        assertEquals(ImportOffer.ClearSearch, state.importOffer())
    }

    @Test
    fun a_reordered_screen_offers_newest_first() {
        assertEquals(ImportOffer.ShowNewestFirst, finished().copy(order = GalleryOrder.NameAsc).importOffer())
    }

    /**
     * Both at once clears the search first: a **hidden** item cannot be scrolled to, so reordering
     * would move something that is not on the screen at all.
     */
    @Test
    fun both_at_once_clears_the_search_first() {
        val state = finished().copy(
            order = GalleryOrder.NameAsc,
            search = GallerySearch.Open(query = "al"),
        )

        assertEquals(ImportOffer.ClearSearch, state.importOffer())
    }

    /**
     * And once the search is cleared, the **other** offer takes its place: clearing stops anything
     * being hidden, which leaves the items merely further down. Two taps for the two problems, which
     * is the consequence of ordering them rather than an oversight — a single action cannot both
     * unhide and reposition.
     */
    @Test
    fun clearing_the_search_leaves_the_ordering_offer_standing() {
        val both = finished().copy(order = GalleryOrder.NameAsc, search = GallerySearch.Open(query = "al"))

        val afterClearing = both.copy(search = GallerySearch.Closed)

        assertEquals(ImportOffer.ShowNewestFirst, afterClearing.importOffer())
    }

    @Test
    fun an_ordinary_screen_offers_nothing() {
        // Newest first with nothing typed: the import is at the top, which is the premise this whole
        // offer exists to restore. An offer here would be an instruction to fix what is not broken.
        assertNull(finished().importOffer())
    }

    /**
     * An **open but empty** field filters nothing, so there is nothing to clear and the results are
     * not filtered — saying they are would be the one claim this copy makes, made falsely.
     */
    @Test
    fun an_open_field_with_nothing_typed_is_not_a_filter() {
        assertNull(finished().copy(search = GallerySearch.Open(query = "   ")).importOffer())
    }

    @Test
    fun a_batch_that_added_nothing_offers_nothing() {
        // There is nothing to go and look at, so pointing at the grid would be pointing at no change.
        val state = finished(imported = 0).copy(search = GallerySearch.Open(query = "al"))

        assertNull(state.importOffer())
    }

    /**
     * What taking an offer does — the other end of [importOffer], and the last decision this cycle
     * left inside a layout.
     *
     * No rule in it, which is exactly why it is worth pinning: a 1:1 dispatch is where a swapped
     * branch hides. "Clear search" quietly reordering the grid would be a control doing something
     * other than what its own label says, and with no UI tests nothing else can see it.
     */
    @Test
    fun clearing_the_search_is_what_the_clear_offer_does() {
        var closed = 0
        var ordered: GalleryOrder? = null

        ImportOffer.ClearSearch.take(onCloseSearch = { closed++ }, onChooseOrder = { ordered = it })

        assertEquals(1, closed)
        assertNull(ordered, "and it leaves the order alone — clearing a filter is not a reorder")
    }

    @Test
    fun the_other_offer_puts_the_newest_first_and_leaves_the_query() {
        var closed = 0
        var ordered: GalleryOrder? = null

        ImportOffer.ShowNewestFirst.take(onCloseSearch = { closed++ }, onChooseOrder = { ordered = it })

        assertEquals(GalleryOrder.Newest, ordered)
        assertEquals(0, closed, "nothing was hidden, so there is no query to discard")
    }

    /**
     * When the summary carries its destructive row at all.
     *
     * The row is the one control on this card that can lose something outside the vault, and it has
     * moved: it used to sit where the picker had been a moment earlier, so repeated importing built
     * muscle memory for a spot that then opened the delete disclosure. A condition, not a layout
     * detail — so it is tested rather than read.
     */
    @Test
    fun a_batch_with_deletable_originals_offers_to_delete_them() {
        val batch = ImportState.Finished(imported = 2, failed = emptyList(), deletableOriginals = 2)

        assertTrue(batch.offersOriginalDelete())
    }

    @Test
    fun a_batch_with_nothing_deletable_offers_nothing() {
        // The ordinary case — every photo-library item, every iOS item, most cloud documents — so
        // the row is *absent* rather than a disabled control owing an explanation.
        assertFalse(ImportState.Finished(imported = 2, failed = emptyList()).offersOriginalDelete())
    }

    @Test
    fun an_offer_already_answered_is_not_made_again() {
        // Once asked, the disclosure owns the decision: an offer still standing beside it would be a
        // second way into something that has to be answered exactly once.
        val batch = ImportState.Finished(imported = 1, failed = emptyList(), deletableOriginals = 1)

        for (answered in listOf(OriginalDelete.Confirming, OriginalDelete.Deleting, OriginalDelete.Done(1, 1))) {
            assertFalse(batch.copy(originalDelete = answered).offersOriginalDelete(), "$answered")
        }
    }

    @Test
    fun a_screen_with_no_finished_batch_offers_nothing() {
        // Mid-import the grid is still filling, and afterwards the card is gone — the offer belongs
        // to the summary, which is the one place that says something was added.
        val state = GalleryState(items = listOf(item), loading = false, order = GalleryOrder.NameAsc)

        assertNull(state.importOffer())
    }
}

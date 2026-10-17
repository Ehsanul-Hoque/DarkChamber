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

    // --- which actions a screen offers on the album itself ------------------------------------
    //
    // These moved out of the layout when the album's own row was removed: the name was already in
    // the bar above it, and rename and delete became an overflow menu inside the controls row. A
    // menu is easier to get wrong than a row was — a row simply omitted a control it had no callback
    // for, while a menu lists what it is told to list — so what it offers is decided here.

    private fun album(name: AlbumTileName = AlbumTileName.Readable("Trip")) =
        listing().copy(album = AlbumState.Present(name))

    @Test
    fun an_album_offers_renaming_and_deleting() {
        val actions = album().albumActions()

        assertTrue(actions.rename)
        assertTrue(actions.delete)
    }

    /**
     * **An album whose name will not open cannot be renamed, but can still be deleted.** The rule
     * the row already had, kept rather than re-decided: there is nothing to rename *from*, so the
     * dialog would prefill with a placeholder nobody has seen — while an album nobody can read the
     * name of is the one most likely to want removing, and removing it destroys none of its items.
     */
    @Test
    fun an_album_whose_name_will_not_open_offers_only_deleting() {
        val actions = album(AlbumTileName.Unreadable).albumActions()

        assertFalse(actions.rename, "nothing to rename from")
        assertTrue(actions.delete, "and this is the album most likely to want deleting")
    }

    @Test
    fun an_album_that_is_no_longer_there_offers_neither() {
        // That screen is a notice and one way out. Offering to rename or delete what is already gone
        // is the menu version of the mistake the browse controls avoid by not being there at all.
        val actions = listing().copy(album = AlbumState.Gone).albumActions()

        assertFalse(actions.rename)
        assertFalse(actions.delete)
        assertFalse(actions.any)
    }

    @Test
    fun the_listings_that_are_not_an_album_offer_neither() {
        // The whole vault cannot be renamed and Unfiled is a view rather than a thing, so neither
        // has an album to act on — `album` is null on both.
        assertFalse(listing().albumActions().any)
    }

    // --- whether the row itself is there --------------------------------------------------------

    @Test
    fun the_row_is_there_whenever_the_browse_controls_are() {
        assertTrue(listing().showsControlsRow())
    }

    /**
     * **The reason this predicate is not just the browse one.** An empty album hides the browse
     * controls — nothing to order, nothing to find — and before this change that took the album's
     * actions off the screen with them. Deleting an album matters most when it is empty or when its
     * listing would not read, so the row stays for the overflow alone.
     */
    @Test
    fun an_empty_album_keeps_the_row_for_its_own_actions() {
        val state = album().copy(items = emptyList())

        assertFalse(state.showsBrowseControls(), "nothing to browse")
        assertTrue(state.showsControlsRow(), "but the album's actions still have to be reachable")
    }

    @Test
    fun an_album_whose_listing_would_not_read_keeps_the_row() {
        val state = album().copy(listingUnreadable = true)

        assertFalse(state.showsBrowseControls())
        assertTrue(state.showsControlsRow())
    }

    @Test
    fun a_missing_album_has_no_row_at_all() {
        assertFalse(listing().copy(album = AlbumState.Gone).showsControlsRow())
    }

    @Test
    fun an_empty_whole_vault_still_has_no_row() {
        // The case that would break if the row were simply always shown: nothing to browse and no
        // album to act on, so the row would be an empty strip above an empty grid.
        assertFalse(listing().copy(items = emptyList()).showsControlsRow())
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
     * Whether a change to the search should put the grid back at the top.
     *
     * The grid holds its position when the **order** changes, which is what was asked for. A changed
     * **query** is a different thing: the result set is new and its first match is the interesting
     * one, so a held offset can leave the viewport past the end of a short result — which is exactly
     * what happened while verifying the search, and read as matches being dropped.
     *
     * What counts as a change is the query that actually *filters*, not the text in the field: opening
     * the field filters nothing, whitespace filters nothing, and a trailing space is a typing artefact
     * the matcher trims. So each of those leaves the grid alone, while gaining or losing a real query
     * moves it.
     */
    @Test
    fun opening_or_closing_an_empty_field_leaves_the_grid_alone() {
        assertFalse(scrollsToTop(GallerySearch.Closed, GallerySearch.Open(query = "")))
        assertFalse(scrollsToTop(GallerySearch.Open(query = ""), GallerySearch.Closed))
        assertFalse(scrollsToTop(GallerySearch.Open(query = ""), GallerySearch.Open(query = "   ")))
    }

    @Test
    fun gaining_or_losing_a_query_puts_the_grid_back_at_the_top() {
        assertTrue(scrollsToTop(GallerySearch.Open(query = ""), GallerySearch.Open(query = "al")))
        assertTrue(scrollsToTop(GallerySearch.Open(query = "al"), GallerySearch.Open(query = "")))
        assertTrue(scrollsToTop(GallerySearch.Open(query = "al"), GallerySearch.Closed))
        assertTrue(scrollsToTop(GallerySearch.Open(query = "al"), GallerySearch.Open(query = "alp")))
    }

    @Test
    fun a_query_that_matches_the_same_items_leaves_the_grid_alone() {
        // The matcher trims, so these two narrow the listing identically — the grid did not change
        // under the viewport and moving it would lose the user's place for nothing.
        assertFalse(scrollsToTop(GallerySearch.Open(query = "al"), GallerySearch.Open(query = "al ")))
        assertFalse(scrollsToTop(GallerySearch.Open(query = "al"), GallerySearch.Open(query = "al")))
    }

    @Test
    fun the_count_of_unreadable_items_is_not_a_change_to_the_query() {
        // It travels with the listing rather than with the field, so it can change while the query
        // has not — and the grid must not jump because a count arrived.
        val before = GallerySearch.Open(query = "al", hiddenUnreadable = 0)
        val after = GallerySearch.Open(query = "al", hiddenUnreadable = 2)

        assertFalse(scrollsToTop(before, after))
    }

    /**
     * What the order menu offers, and which one it marks.
     *
     * Pulled out of the menu for two reasons that have both already bitten this screen. The list
     * comes from the enum so a fifth order cannot be choosable nowhere — the layout's own comment
     * worried about exactly that and nothing could check it. And **exactly one** option is marked,
     * which is the property the menu was getting wrong: it marked the order in force by colour alone,
     * so there was no state for a reader or a test to see.
     */
    @Test
    fun the_menu_offers_every_order_exactly_once() {
        val offered = orderOptions(GalleryOrder.Newest).map { it.order }

        assertEquals(GalleryOrder.entries, offered)
    }

    @Test
    fun exactly_one_option_is_marked_and_it_is_the_one_in_force() {
        for (current in GalleryOrder.entries) {
            val options = orderOptions(current)

            assertEquals(1, options.count { it.inForce }, "one mark, whichever order is in force: $current")
            assertEquals(current, options.single { it.inForce }.order, "and it is $current")
        }
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

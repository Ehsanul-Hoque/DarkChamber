package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumState
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTileName
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.GallerySearch
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryState
import kotlin.test.Test
import kotlin.test.assertFalse
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
}

package dev.ehsanfahad.darkchamber.feature.vault.presentation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.backhandler.BackDispatcher
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.IdleVaultImports
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryOrder
import dev.ehsanfahad.darkchamber.feature.vault.api.GallerySearch
import dev.ehsanfahad.darkchamber.feature.vault.damagedMeta
import dev.ehsanfahad.darkchamber.feature.vault.domain.CreateAlbum
import dev.ehsanfahad.darkchamber.feature.vault.domain.DeleteAlbum
import dev.ehsanfahad.darkchamber.feature.vault.domain.FileItems
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveAlbum
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveAlbums
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveGallery
import dev.ehsanfahad.darkchamber.feature.vault.domain.RenameAlbum
import dev.ehsanfahad.darkchamber.feature.vault.readableMeta
import dev.ehsanfahad.darkchamber.feature.vault.summary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Browsing, as the gallery exposes it: the order it has been asked for and the name being searched
 * for, both held by this screen and by nothing above it.
 *
 * The ordering and matching rules themselves are tested where they live (`GalleryOrderingTest`,
 * `GallerySearchingTest`), and the subscription they run downstream of is pinned in
 * `ObserveGalleryTest`. This is about the wiring: that an intent reaches the listing stage, that the
 * published state says what the controls row has to draw, and that none of it re-reads the catalog.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryBrowseTest {

    private val lifecycle = LifecycleRegistry().apply { resume() }
    private val backDispatcher = BackDispatcher()

    private fun TestScope.component(vault: FakeVaultItems): DefaultVaultGalleryComponent =
        DefaultVaultGalleryComponent(
            componentContext = DefaultComponentContext(lifecycle, backHandler = backDispatcher),
            observeGallery = ObserveGallery(vault),
            imports = IdleVaultImports(),
            // Albums and filing, unused here: this is the whole-vault listing and nothing is selected.
            observeAlbum = ObserveAlbum(vault),
            renameAlbum = RenameAlbum(vault, ioContext = UnconfinedTestDispatcher(testScheduler)),
            deleteAlbum = DeleteAlbum(vault, ioContext = UnconfinedTestDispatcher(testScheduler)),
            observeAlbums = ObserveAlbums(vault),
            createAlbum = CreateAlbum(vault, ioContext = UnconfinedTestDispatcher(testScheduler)),
            fileItems = FileItems(vault, ioContext = UnconfinedTestDispatcher(testScheduler)),
            mainContext = UnconfinedTestDispatcher(testScheduler),
            progressInterval = Duration.ZERO,
            pickingDelay = Duration.ZERO,
            stageGrace = Duration.ZERO,
        )

    /** Three items whose `createdAt` follows their id, so the newest is the highest id. */
    private fun FakeVaultItems.emit() {
        summaries.value = listOf(
            summary(byteArrayOf(1), readableMeta(name = "tax.pdf"), createdAt = 1L),
            summary(byteArrayOf(2), readableMeta(name = "alps.jpg"), createdAt = 2L),
            summary(byteArrayOf(3), readableMeta(name = "beach.png"), createdAt = 3L),
        )
    }

    private fun DefaultVaultGalleryComponent.names(): List<String> =
        state.value.items.map { (it.meta as GalleryItemMeta.Readable).name }

    @Test
    fun the_listing_opens_newest_first_with_nothing_searched() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit()

        assertEquals(GalleryOrder.Newest, component.state.value.order)
        assertEquals(GallerySearch.Closed, component.state.value.search)
        assertEquals(listOf("beach.png", "alps.jpg", "tax.pdf"), component.names())
    }

    @Test
    fun choosing_an_order_republishes_the_listing_in_it() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit()

        component.chooseOrder(GalleryOrder.NameAsc)

        assertEquals(GalleryOrder.NameAsc, component.state.value.order)
        assertEquals(listOf("alps.jpg", "beach.png", "tax.pdf"), component.names())
    }

    @Test
    fun opening_a_search_shows_an_empty_field_and_changes_nothing_yet() = runTest {
        // Opening is not filtering: the field appears, and the grid underneath it is untouched until
        // something is typed.
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit()

        component.openSearch()

        assertEquals(GallerySearch.Open(query = "", hiddenUnreadable = 0), component.state.value.search)
        assertEquals(3, component.state.value.items.size)
    }

    @Test
    fun typing_narrows_the_published_listing() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit()

        component.openSearch()
        component.editSearch("al")

        assertEquals("al", (component.state.value.search as GallerySearch.Open).query)
        assertEquals(listOf("alps.jpg"), component.names())
    }

    @Test
    fun a_search_keeps_the_order_that_was_chosen() = runTest {
        // Both controls apply to the same listing, so the results of a search come back in the order
        // the user picked rather than the catalog's.
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit()

        component.chooseOrder(GalleryOrder.NameDesc)
        component.openSearch()
        component.editSearch(".p")

        assertEquals(listOf("tax.pdf", "beach.png"), component.names())
    }

    @Test
    fun closing_a_search_restores_the_listing_and_leaves_no_query_behind() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit()

        component.openSearch()
        component.editSearch("al")
        component.closeSearch()

        assertEquals(GallerySearch.Closed, component.state.value.search)
        // The query goes with the field: a filter still running behind a closed search would hide
        // items with nothing on screen to explain why.
        assertEquals(3, component.state.value.items.size)
    }

    @Test
    fun re_opening_a_search_keeps_what_was_typed() = runTest {
        // The field *is* the control once search is open, so this can only be a re-tap — and
        // re-opening must not quietly discard a query the user is still working with.
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit()

        component.openSearch()
        component.editSearch("al")
        component.openSearch()

        assertEquals("al", (component.state.value.search as GallerySearch.Open).query)
        assertEquals(listOf("alps.jpg"), component.names())
    }

    @Test
    fun typing_while_the_search_is_closed_changes_nothing() = runTest {
        // Nothing can reach this today, and that is the point: a query held with no field showing it
        // would filter the grid with no way to see why, or to clear it.
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit()

        component.editSearch("al")

        assertEquals(GallerySearch.Closed, component.state.value.search)
        assertEquals(3, component.state.value.items.size)
    }

    @Test
    fun the_number_of_items_a_search_cannot_read_lands_on_the_open_search() = runTest {
        // A damaged item has no name to match, so a search has to leave it out — and say that it
        // did. Dropping it silently would let a search conceal the one row worth looking at.
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.summaries.value = listOf(
            summary(byteArrayOf(1), readableMeta(name = "alps.jpg")),
            summary(byteArrayOf(2), damagedMeta()),
        )

        component.openSearch()
        component.editSearch("al")

        val search = component.state.value.search as GallerySearch.Open
        assertEquals(1, search.hiddenUnreadable)
        assertEquals(listOf("alps.jpg"), component.names())
    }

    @Test
    fun an_open_search_never_prints_what_was_typed() = runTest {
        // The query is a fragment of a file name the user retyped — decrypted vault content, like
        // the name itself (hard rule 4). It reaches a log line through the state's own toString.
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit()

        component.openSearch()
        component.editSearch("alps")

        val printed = component.state.value.toString()
        assertFalse("alps" in printed, "a query must never be printed, was $printed")
        assertTrue("4 chars" in printed, "but its length is safe to say, was $printed")
    }

    @Test
    fun browsing_never_re_reads_the_catalog() = runTest {
        // The whole performance design of these two controls: the subscription is keyed by the
        // filter alone, so ordering and searching re-sort rows already in memory instead of
        // re-running the query and re-opening every row's metadata.
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit()

        component.chooseOrder(GalleryOrder.NameAsc)
        component.openSearch()
        component.editSearch("a")
        component.editSearch("al")
        component.chooseOrder(GalleryOrder.Oldest)
        component.closeSearch()

        assertEquals(1, vault.observeCalls, "six browse intents must not re-subscribe")
    }
}

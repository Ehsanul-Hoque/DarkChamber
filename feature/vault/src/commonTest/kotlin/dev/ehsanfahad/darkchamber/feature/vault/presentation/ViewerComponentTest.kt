package dev.ehsanfahad.darkchamber.feature.vault.presentation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.vault.VaultItemSummary
import dev.ehsanfahad.darkchamber.feature.vault.ExplodingVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryOrder
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage
import dev.ehsanfahad.darkchamber.feature.vault.domain.GalleryControls
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveGallery
import dev.ehsanfahad.darkchamber.feature.vault.domain.VaultItems
import dev.ehsanfahad.darkchamber.feature.vault.readableMeta
import dev.ehsanfahad.darkchamber.feature.vault.summary
import com.arkivanov.essenty.lifecycle.destroy
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.ManualDispatcher
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerDeleteStage
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerNotice
import dev.ehsanfahad.darkchamber.feature.vault.domain.ShredItem
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveAlbum
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTileName
import dev.ehsanfahad.darkchamber.core.vault.AlbumSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The viewer's state holder, with every dispatcher injected. The listing is a fake catalog the tests
 * change as the real one would; a page load is a function the tests control.
 */
class ViewerComponentTest {
    private val lifecycle = LifecycleRegistry().apply { resume() }

    /** A fresh array per call: production decodes ids out of SQLite, never the same instance twice. */
    private fun id(n: Int) = byteArrayOf(n.toByte(), 0x5A)

    private fun item(n: Int, createdAt: Long = n * 10L, name: String = "photo-$n.jpg"): VaultItemSummary =
        summary(id(n), readableMeta(name, "image/jpeg"), createdAt = createdAt)

    /** Every id `onBack` reported, as content. */
    private val backs = mutableListOf<List<Byte>>()

    private fun TestScope.viewer(
        items: VaultItems,
        start: Int,
        filter: ItemFilter = ItemFilter.All,
        controls: GalleryControls = GalleryControls(),
        load: suspend (GalleryItem, Int) -> LoadedPage<String> = { _, _ -> LoadedPage.Page(ViewerPage.CantShow) },
        shred: suspend (ByteArray) -> Outcome<Unit> = { Outcome.Ok(Unit) },
    ) = ViewerComponent(
        componentContext = DefaultComponentContext(lifecycle),
        observeGallery = ObserveGallery(items),
        observeAlbum = ObserveAlbum(items),
        load = load,
        shred = shred,
        filter = filter,
        controls = controls,
        startId = id(start),
        onBack = { backs += it.toList() },
        mainContext = UnconfinedTestDispatcher(testScheduler),
    )

    private fun ViewerComponent<*>.shownIds() = state.value.items.map { it.id.toList() }

    private fun ViewerComponent<*>.shownId() = state.value.items[state.value.index].id.toList()

    // ---- the listing and the position --------------------------------------------------------------

    @Test
    fun it_opens_on_the_item_that_was_tapped() = runTest {
        val items = FakeVaultItems().apply { summaries.value = listOf(item(1), item(2), item(3)) }

        val viewer = viewer(items, start = 2)

        assertFalse(viewer.state.value.loading)
        assertEquals(listOf(3, 2, 1).map { id(it).toList() }, viewer.shownIds(), "newest first, as the grid")
        assertEquals(1, viewer.state.value.index)
        assertEquals(id(2).toList(), viewer.shownId())
    }

    /** The point of reusing the gallery's pipeline: the same filter, order and search give the same sequence. */
    @Test
    fun the_sequence_is_the_grids_for_the_same_filter_order_and_search() = runTest {
        val album = ItemFilter.Album(byteArrayOf(7))
        val items = FakeVaultItems()
        items.summariesByFilter[album] = MutableStateFlow(
            listOf(item(1, name = "beach.jpg"), item(2, name = "aunt.jpg"), item(3, name = "beacon.jpg"), item(4, name = "cat.jpg")),
        )
        val controls = GalleryControls(order = GalleryOrder.NameAsc, query = "bea")

        val viewer = viewer(items, start = 1, filter = album, controls = controls)
        val grid = ObserveGallery(items)(album, flowOf(controls)).first().items.map { it.id.toList() }

        assertEquals(listOf(1, 3).map { id(it).toList() }, grid, "the fixture discriminates: A–Z and a search")
        assertEquals(grid, viewer.shownIds())
    }

    /** A background import landing ahead of it moves the item's index; the page stays on the item. */
    @Test
    fun the_page_follows_its_item_when_the_listing_changes() = runTest {
        val items = FakeVaultItems().apply { summaries.value = listOf(item(1), item(2), item(3)) }
        val viewer = viewer(items, start = 2)

        items.summaries.value = listOf(item(1), item(2), item(3), item(4))

        assertEquals(id(2).toList(), viewer.shownId())
        assertEquals(2, viewer.state.value.index)
    }

    @Test
    fun when_the_item_shown_is_deleted_the_next_one_takes_its_place() = runTest {
        val items = FakeVaultItems().apply { summaries.value = listOf(item(1), item(2), item(3)) }
        val viewer = viewer(items, start = 2)

        items.summaries.value = listOf(item(1), item(3))

        assertEquals(1, viewer.state.value.index)
        assertEquals(id(1).toList(), viewer.shownId(), "newest first: 3, 2, 1 → 3, 1; the one after 2 is 1")
    }

    @Test
    fun an_empty_listing_closes_the_viewer_once_reporting_the_last_item_shown() = runTest {
        val items = FakeVaultItems().apply { summaries.value = listOf(item(1), item(2)) }
        viewer(items, start = 2)

        items.summaries.value = emptyList()
        items.summaries.value = listOf(item(5)) // anything after closing must not report a second time
        items.summaries.value = emptyList()

        assertEquals(listOf(id(2).toList()), backs)
    }

    /** Shredded between the tap and the first listing: opening on a neighbour would show something nobody asked for. */
    @Test
    fun a_start_item_already_gone_closes_the_viewer() = runTest {
        val items = FakeVaultItems().apply { summaries.value = listOf(item(1), item(3)) }

        viewer(items, start = 2)

        assertEquals(listOf(id(2).toList()), backs)
    }

    @Test
    fun back_reports_the_item_last_shown() = runTest {
        val items = FakeVaultItems().apply { summaries.value = listOf(item(1), item(2), item(3)) }
        val viewer = viewer(items, start = 3)

        viewer.showPage(2)
        viewer.back()
        viewer.back()

        assertEquals(listOf(id(1).toList()), backs, "once, with the page the user had swiped to")
    }

    @Test
    fun a_page_outside_the_listing_is_ignored() = runTest {
        val items = FakeVaultItems().apply { summaries.value = listOf(item(1), item(2)) }
        val viewer = viewer(items, start = 2)

        viewer.showPage(5)
        viewer.showPage(-1)

        assertEquals(id(2).toList(), viewer.shownId())
    }

    /** One subscription for the viewer's life: every re-subscription re-decrypts every row's details. */
    @Test
    fun the_listing_is_subscribed_once() = runTest {
        val items = FakeVaultItems().apply { summaries.value = listOf(item(1), item(2), item(3)) }
        val viewer = viewer(items, start = 2)

        viewer.showPage(0)
        items.summaries.value = listOf(item(1), item(2), item(3), item(4))
        viewer.showPage(2)

        assertEquals(1, items.observeCalls)
    }

    // ---- which album an item is in -----------------------------------------------------------------

    /**
     * The grid's own album listing fills in an item's album only on the whole vault — inside an album
     * the question does not arise there. The viewer's Details does ask it, so from inside an album
     * every item carries that album's name, and follows a rename.
     */
    @Test
    fun viewed_from_inside_an_album_every_item_carries_that_albums_name() = runTest {
        val albumId = byteArrayOf(7)
        val album = ItemFilter.Album(albumId.copyOf())
        val items = FakeVaultItems()
        items.summariesByFilter[album] = MutableStateFlow(listOf(item(1), item(2)))
        items.albums.value = listOf(albumSummary(albumId.copyOf(), Outcome.Ok("Holiday")))

        val viewer = viewer(items, start = 1, filter = album)

        assertEquals(
            listOf(AlbumTileName.Readable("Holiday"), AlbumTileName.Readable("Holiday")),
            viewer.state.value.items.map { it.album },
        )

        items.albums.value = listOf(albumSummary(albumId.copyOf(), Outcome.Err(VaultError.Crypto.IntegrityFailure)))
        assertEquals(AlbumTileName.Unreadable, viewer.state.value.items.first().album, "a name that will not open says so")
    }

    @Test
    fun the_unfiled_listing_names_no_album() = runTest {
        val items = FakeVaultItems()
        items.summariesByFilter[ItemFilter.Unfiled] = MutableStateFlow(listOf(item(1)))
        items.albums.value = listOf(albumSummary(byteArrayOf(7), Outcome.Ok("Holiday")))

        val viewer = viewer(items, start = 1, filter = ItemFilter.Unfiled)

        assertNull(viewer.state.value.items.single().album)
    }

    private fun albumSummary(id: ByteArray, name: Outcome<String>) =
        AlbumSummary(id = id, name = name, itemCount = 0, coverItemId = null, createdAt = 1L)

    // ---- the pages: what is decoded, and when ------------------------------------------------------

    /**
     * A page load the test controls: it records every call, and either answers at once or waits on a
     * gate the test opens — so the middle of a load is a place a test can stand.
     */
    private class Loads(private val gated: Boolean = false) {
        val calls = mutableListOf<Pair<List<Byte>, Int>>()
        val cancelled = mutableListOf<List<Byte>>()
        val gates = mutableMapOf<List<Byte>, CompletableDeferred<LoadedPage<String>>>()

        suspend fun load(item: GalleryItem, target: Int): LoadedPage<String> {
            val key = item.id.toList()
            calls += key to target
            if (!gated) return LoadedPage.Shown("picture of ${item.id[0]}")
            val gate = CompletableDeferred<LoadedPage<String>>().also { gates[key] = it }
            return try {
                gate.await()
            } catch (e: CancellationException) {
                cancelled += key
                throw e
            }
        }

        fun loadedIds() = calls.map { it.first }
    }

    private fun key(n: Int) = id(n).toListKey()

    @Test
    fun nothing_is_decoded_until_the_viewport_is_measured() = runTest {
        val items = FakeVaultItems().apply { summaries.value = listOf(item(1), item(2), item(3)) }
        val loads = Loads()
        val viewer = viewer(items, start = 2, load = loads::load)

        assertEquals(emptyList(), loads.calls, "the decode is sized from the viewport")

        viewer.viewportMeasured(widthPx = 1_080, heightPx = 2_400)

        assertEquals(setOf(3, 2, 1).map { id(it).toList() }.toSet(), loads.loadedIds().toSet())
        assertTrue(loads.calls.all { it.second == 4_800 }, "twice the viewport's long edge")
    }

    /** At most one page either side of the one shown: a full-screen bitmap is tens of megabytes. */
    @Test
    fun only_the_page_shown_and_one_either_side_are_decoded() = runTest {
        val items = FakeVaultItems().apply { summaries.value = (1..6).map { item(it) } }
        val loads = Loads()
        val viewer = viewer(items, start = 4, load = loads::load) // newest first: 6 5 4 3 2 1 → index 2

        viewer.viewportMeasured(1_080, 2_400)

        assertEquals(setOf(5, 4, 3).map { id(it).toList() }.toSet(), loads.loadedIds().toSet())
        assertEquals(setOf(key(5), key(4), key(3)), viewer.state.value.pages.keys)
        assertEquals(setOf(key(5), key(4), key(3)), viewer.pictures.value.keys)
    }

    @Test
    fun at_the_first_page_only_it_and_the_next_are_decoded() = runTest {
        val items = FakeVaultItems().apply { summaries.value = (1..4).map { item(it) } }
        val loads = Loads()
        val viewer = viewer(items, start = 4, load = loads::load)

        viewer.viewportMeasured(1_080, 2_400)

        assertEquals(setOf(4, 3).map { id(it).toList() }.toSet(), loads.loadedIds().toSet())
    }

    @Test
    fun a_page_shows_loading_until_its_load_answers_then_what_it_answered() = runTest {
        val items = FakeVaultItems().apply { summaries.value = listOf(item(1)) }
        val loads = Loads(gated = true)
        val viewer = viewer(items, start = 1, load = loads::load)
        viewer.viewportMeasured(1_080, 2_400)

        assertEquals(ViewerPage.Loading, viewer.state.value.pages[key(1)])

        loads.gates.getValue(id(1).toList()).complete(LoadedPage.Shown("the picture"))

        assertEquals(ViewerPage.Picture, viewer.state.value.pages[key(1)])
        assertEquals("the picture", viewer.pictures.value[key(1)])
    }

    @Test
    fun a_page_that_is_not_a_picture_says_why_and_holds_no_picture() = runTest {
        val items = FakeVaultItems().apply { summaries.value = listOf(item(1)) }
        val viewer = viewer(items, start = 1, load = { _, _ -> LoadedPage.Page(ViewerPage.Unverified) })

        viewer.viewportMeasured(1_080, 2_400)

        assertEquals(ViewerPage.Unverified, viewer.state.value.pages[key(1)])
        assertEquals(emptyMap(), viewer.pictures.value)
    }

    /**
     * Swiping on: the page that falls out of the window is cancelled if still loading and loses its
     * picture; the one coming in is loaded; the ones still in the window are not loaded again.
     */
    @Test
    fun swiping_moves_the_window_cancelling_and_dropping_what_left_it() = runTest {
        val items = FakeVaultItems().apply { summaries.value = (1..5).map { item(it) } }
        val loads = Loads(gated = true)
        val viewer = viewer(items, start = 4, load = loads::load) // 5 4 3 2 1 → index 1: window 5 4 3
        viewer.viewportMeasured(1_080, 2_400)
        loads.gates.getValue(id(4).toList()).complete(LoadedPage.Shown("four"))
        loads.gates.getValue(id(3).toList()).complete(LoadedPage.Shown("three"))
        // 5 is still loading when the user swipes away from it.

        viewer.showPage(2) // window 4 3 2

        assertEquals(listOf(id(5).toList()), loads.cancelled, "the load that left the window was cancelled")
        assertEquals(setOf(key(4), key(3), key(2)), viewer.state.value.pages.keys)
        assertEquals(setOf(key(4), key(3)), viewer.pictures.value.keys)
        assertEquals(1, loads.loadedIds().count { it == id(4).toList() }, "a page still in the window is not decoded again")
        assertTrue(id(2).toList() in loads.loadedIds())

        viewer.showPage(4) // window 2 1: 4 and 3 leave with their pictures

        assertEquals(emptySet(), viewer.pictures.value.keys - setOf(key(2), key(1)))
    }

    /** A delete or an import changes the neighbours; the window is recomputed from the new listing. */
    @Test
    fun a_listing_change_reloads_the_new_neighbours() = runTest {
        val items = FakeVaultItems().apply { summaries.value = (1..4).map { item(it) } }
        val loads = Loads()
        val viewer = viewer(items, start = 3, load = loads::load) // 4 3 2 1 → window 4 3 2
        viewer.viewportMeasured(1_080, 2_400)

        items.summaries.value = listOf(item(1), item(3), item(4)) // 2 deleted: 4 3 1 → window 4 3 1

        assertEquals(setOf(key(4), key(3), key(1)), viewer.state.value.pages.keys)
        assertTrue(id(1).toList() in loads.loadedIds())
    }

    /** A lock destroys the subtree: loads under way stop, and no picture is held any longer. */
    @Test
    fun destroying_the_viewer_cancels_its_loads_and_drops_its_pictures() = runTest {
        val items = FakeVaultItems().apply { summaries.value = listOf(item(1), item(2)) }
        val loads = Loads(gated = true)
        val viewer = viewer(items, start = 2, load = loads::load)
        viewer.viewportMeasured(1_080, 2_400)
        loads.gates.getValue(id(2).toList()).complete(LoadedPage.Shown("two"))

        lifecycle.destroy()

        assertEquals(listOf(id(1).toList()), loads.cancelled)
        assertEquals(emptyMap(), viewer.pictures.value)
    }

    // ---- delete ------------------------------------------------------------------------------------

    @Test
    fun asking_to_delete_opens_the_confirmation_and_cancelling_deletes_nothing() = runTest {
        val items = FakeVaultItems().apply { summaries.value = listOf(item(1), item(2)) }
        val shreds = mutableListOf<List<Byte>>()
        val viewer = viewer(items, start = 2, shred = { shreds += it.toList(); Outcome.Ok(Unit) })

        viewer.confirmDelete() // nothing asked yet
        assertEquals(emptyList(), shreds, "a confirm with no confirmation open deletes nothing")

        viewer.requestDelete()
        assertEquals(ViewerDeleteStage.Asking, viewer.state.value.delete)

        viewer.cancelDelete()
        assertNull(viewer.state.value.delete)
        assertEquals(emptyList(), shreds)
    }

    /**
     * Confirmed: the item shown — the one swiped to, not the one tapped — is shredded once, the
     * confirmation reports it is under way and cannot be called back, and the result is reported.
     * The vault's listing then drops it and the next one slides in.
     */
    @Test
    fun confirming_shreds_the_item_shown_once_and_the_next_one_takes_its_place() = runTest {
        val items = FakeVaultItems().apply { summaries.value = listOf(item(1), item(2), item(3)) }
        val shreds = mutableListOf<List<Byte>>()
        val gate = CompletableDeferred<Unit>()
        val viewer = viewer(items, start = 3, shred = { id ->
            shreds += id.toList()
            gate.await()
            items.summaries.value = items.summaries.value.filterNot { it.id.contentEquals(id) }
            Outcome.Ok(Unit)
        })
        viewer.showPage(1) // 3 2 1 → item 2

        viewer.requestDelete()
        viewer.confirmDelete()
        viewer.confirmDelete() // a double tap
        assertEquals(ViewerDeleteStage.Deleting, viewer.state.value.delete)
        viewer.cancelDelete() // too late to call back
        assertEquals(ViewerDeleteStage.Deleting, viewer.state.value.delete)

        gate.complete(Unit)

        assertEquals(listOf(id(2).toList()), shreds)
        assertNull(viewer.state.value.delete)
        assertEquals(ViewerNotice.Deleted, viewer.state.value.notice)
        assertEquals(id(1).toList(), viewer.shownId())
    }

    @Test
    fun a_delete_the_vault_refused_says_so_and_keeps_the_item() = runTest {
        val items = FakeVaultItems().apply { summaries.value = listOf(item(1), item(2)) }
        val viewer = viewer(items, start = 2, shred = { Outcome.Err(VaultError.Io(RuntimeException("disk"))) })

        viewer.requestDelete()
        viewer.confirmDelete()

        assertNull(viewer.state.value.delete)
        assertEquals(ViewerNotice.DeleteFailed, viewer.state.value.notice)
        assertEquals(id(2).toList(), viewer.shownId())

        viewer.dismissNotice()
        assertNull(viewer.state.value.notice)
    }

    /** The shred blocks on the catalog and the disk; it runs on the injected dispatcher, and a throw is a refusal. */
    @Test
    fun the_shred_runs_on_the_injected_dispatcher_and_a_throw_is_a_refusal() = runTest {
        val io = ManualDispatcher()
        var onIo = false
        val items = object : VaultItems by FakeVaultItems() {
            override fun shred(id: ByteArray): Outcome<Unit> {
                onIo = io.isRunning
                throw IllegalStateException("vault session is closed")
            }
        }
        var result: Outcome<Unit>? = null

        launch { result = ShredItem(items, io)(id(1)) }
        runCurrent()
        io.drain()
        advanceUntilIdle()

        assertTrue(onIo)
        assertIs<Outcome.Err>(result)
    }

    /** A catalog that cannot be read leaves nothing to show; the viewer goes back rather than sitting empty. */
    @Test
    fun a_listing_that_fails_closes_the_viewer() = runTest {
        viewer(ExplodingVaultItems(), start = 2)

        assertEquals(listOf(id(2).toList()), backs)
    }
}

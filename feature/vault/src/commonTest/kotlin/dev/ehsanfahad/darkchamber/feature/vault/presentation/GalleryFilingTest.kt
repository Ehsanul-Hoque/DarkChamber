package dev.ehsanfahad.darkchamber.feature.vault.presentation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.vault.AlbumSummary
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.ManualDispatcher
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumDraftProblem
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTileName
import dev.ehsanfahad.darkchamber.feature.vault.api.Filing
import dev.ehsanfahad.darkchamber.feature.vault.api.FilingDestination
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryError
import dev.ehsanfahad.darkchamber.feature.vault.api.ImportState
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultImports
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Filing selected items: into an album, or out of the one they are in.
 *
 * Two things are load-bearing. It is a **move** — an item is in at most one album — so the chooser never
 * offers the album the items are already in, and nothing anywhere copies. And the report says what
 * actually happened, because items can leave the vault between being selected and being moved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryFilingTest {

    private val lifecycle = LifecycleRegistry().apply { resume() }

    private val thisAlbum = byteArrayOf(1, 1)
    private val otherAlbum = byteArrayOf(2, 2)

    private fun TestScope.component(
        vault: FakeVaultItems,
        filter: ItemFilter = ItemFilter.All,
        ioContext: CoroutineContext = UnconfinedTestDispatcher(testScheduler),
    ): DefaultVaultGalleryComponent =
        DefaultVaultGalleryComponent(
            componentContext = DefaultComponentContext(lifecycle),
            observeGallery = ObserveGallery(vault),
            imports = QuietImports(),
            observeAlbum = ObserveAlbum(vault),
            renameAlbum = RenameAlbum(vault, ioContext = ioContext),
            deleteAlbum = DeleteAlbum(vault, ioContext = ioContext),
            observeAlbums = ObserveAlbums(vault),
            createAlbum = CreateAlbum(vault, ioContext = ioContext),
            fileItems = FileItems(vault, ioContext = ioContext),
            mainContext = UnconfinedTestDispatcher(testScheduler),
            progressInterval = Duration.ZERO,
            pickingDelay = Duration.ZERO,
            stageGrace = Duration.ZERO,
            filter = filter,
        )

    /**
     * An album as the catalog would report it — **with a copy of the id**, which is the whole point.
     *
     * A real listing decodes its ids out of SQLite, so the array a row carries is never the array the
     * screen was constructed with. Handing the same instance to both would let an identity comparison
     * pass, and the exclusion below is exactly a comparison of those two ids.
     */
    private fun album(id: ByteArray, name: String) =
        AlbumSummary(id.copyOf(), Outcome.Ok(name), itemCount = 0, coverItemId = null, createdAt = 1L)

    /** Two items in the listing, both selected — the ordinary starting point for every action here. */
    private fun DefaultVaultGalleryComponent.selectTwo(vault: FakeVaultItems) {
        vault.summaries.value = listOf(
            summary(byteArrayOf(5), readableMeta()),
            summary(byteArrayOf(6), readableMeta()),
        )
        selectItem(byteArrayOf(5))
        selectItem(byteArrayOf(6))
    }

    private fun DefaultVaultGalleryComponent.choosing(): Filing.Choosing =
        assertIs<Filing.Choosing>(state.value.filing)

    @Test
    fun the_chooser_lists_the_albums_items_can_move_to() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(album(otherAlbum, "Invoices"), album(thisAlbum, "Receipts"))
        val component = component(vault)
        advanceUntilIdle()

        component.selectTwo(vault)
        component.startFiling()
        advanceUntilIdle()

        assertEquals(
            listOf(AlbumTileName.Readable("Invoices"), AlbumTileName.Readable("Receipts")),
            component.choosing().albums.map { it.name },
        )
    }

    /**
     * **Never the album the items are already in.** Moving them there is nothing, and offering it invites
     * a tap that would report a move that did not happen.
     */
    @Test
    fun the_chooser_leaves_out_the_album_being_viewed() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(album(thisAlbum, "Receipts"), album(otherAlbum, "Invoices"))
        val component = component(vault, filter = ItemFilter.Album(thisAlbum))
        advanceUntilIdle()

        component.selectTwo(vault)
        component.startFiling()
        advanceUntilIdle()

        assertEquals(
            listOf(AlbumTileName.Readable("Invoices")),
            component.choosing().albums.map { it.name },
            "the album these items are already in is not somewhere to move them",
        )
    }

    @Test
    fun nothing_selected_opens_no_chooser() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(album(otherAlbum, "Invoices"))
        val component = component(vault)
        advanceUntilIdle()

        component.startFiling()
        advanceUntilIdle()

        assertNull(component.state.value.filing, "there is nothing to move")
    }

    @Test
    fun choosing_an_album_moves_the_selection_into_it_and_reports_what_happened() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(album(otherAlbum, "Invoices"))
        val component = component(vault)
        advanceUntilIdle()

        component.selectTwo(vault)
        component.startFiling()
        advanceUntilIdle()
        component.chooseAlbum(otherAlbum)
        advanceUntilIdle()

        assertEquals(
            listOf(listOf<Byte>(5), listOf<Byte>(6)) to listOf<Byte>(2, 2),
            vault.assignments.single(),
            "exactly the selected items, into the chosen album",
        )
        val moved = assertIs<Filing.Moved>(component.state.value.filing)
        assertEquals(2, moved.moved)
        assertEquals(2, moved.requested)
        assertEquals(FilingDestination.Album, moved.destination)
    }

    /** Selecting mode ends with the move: the items have gone where they were sent. */
    @Test
    fun a_completed_move_leaves_selecting_mode() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(album(otherAlbum, "Invoices"))
        val component = component(vault)
        advanceUntilIdle()

        component.selectTwo(vault)
        component.startFiling()
        advanceUntilIdle()
        component.chooseAlbum(otherAlbum)
        advanceUntilIdle()

        assertEquals(false, component.state.value.selection.active, "the mode is over")
        assertEquals(0, component.state.value.selection.count)
    }

    /**
     * A partial move is reported with both numbers. The skipped ones are items that left the vault
     * between being selected and being moved — reporting the request as the result would claim a move
     * that did not happen.
     */
    @Test
    fun a_partial_move_is_reported_honestly() = runTest {
        val vault = FakeVaultItems(assignedOf = { it - 1 })
        vault.albums.value = listOf(album(otherAlbum, "Invoices"))
        val component = component(vault)
        advanceUntilIdle()

        component.selectTwo(vault)
        component.startFiling()
        advanceUntilIdle()
        component.chooseAlbum(otherAlbum)
        advanceUntilIdle()

        val moved = assertIs<Filing.Moved>(component.state.value.filing)
        assertEquals(2, moved.requested)
        assertEquals(1, moved.moved, "one of them was gone, and the report says so")
    }

    /** Taking items out of an album is the same write with no destination. */
    @Test
    fun unfiling_moves_the_selection_out_of_its_album() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault, filter = ItemFilter.Album(thisAlbum))
        advanceUntilIdle()

        component.selectTwo(vault)
        component.unfileSelection()
        advanceUntilIdle()

        assertEquals(null, vault.assignments.single().second, "out of the album, not into another")
        val moved = assertIs<Filing.Moved>(component.state.value.filing)
        assertEquals(FilingDestination.Unfiled, moved.destination)
        assertEquals(false, component.state.value.selection.active)
    }

    @Test
    fun unfiling_nothing_does_nothing() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault, filter = ItemFilter.Album(thisAlbum))
        advanceUntilIdle()

        component.unfileSelection()
        advanceUntilIdle()

        assertTrue(vault.assignments.isEmpty())
        assertNull(component.state.value.filing)
    }

    /**
     * A refused move keeps the selection. Nothing moved, so the next move is to try again rather than to
     * pick everything out of a grid a second time.
     */
    @Test
    fun a_refused_move_is_surfaced_and_the_selection_survives() = runTest {
        val vault = FakeVaultItems(assignFailure = VaultError.Crypto.IntegrityFailure)
        vault.albums.value = listOf(album(otherAlbum, "Invoices"))
        val component = component(vault)
        advanceUntilIdle()

        component.selectTwo(vault)
        component.startFiling()
        advanceUntilIdle()
        component.chooseAlbum(otherAlbum)
        advanceUntilIdle()

        assertEquals(GalleryError.FilingFailed, component.state.value.error)
        assertNull(component.state.value.filing, "no report, because nothing moved")
        assertEquals(2, component.state.value.selection.count, "still selected, ready to retry")
    }

    @Test
    fun the_report_is_dismissable() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault, filter = ItemFilter.Album(thisAlbum))
        advanceUntilIdle()

        component.selectTwo(vault)
        component.unfileSelection()
        advanceUntilIdle()
        assertIs<Filing.Moved>(component.state.value.filing)

        component.acknowledgeFiling()

        assertNull(component.state.value.filing)
    }

    /** Only from the chooser: a stray call with no chooser open writes nothing. */
    @Test
    fun choosing_an_album_outside_the_chooser_does_nothing() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)
        advanceUntilIdle()

        component.selectTwo(vault)
        component.chooseAlbum(otherAlbum)
        advanceUntilIdle()

        assertTrue(vault.assignments.isEmpty())
    }

    /**
     * A vault with no albums is the case the create-in-flow exists for: the user has just been asked to
     * choose, and without this they would face an empty list and a cancel button.
     *
     * The album is made **and** the selection moves into it, in one step.
     */
    @Test
    fun an_album_can_be_created_and_filed_into_in_one_step() = runTest {
        val vault = FakeVaultItems(createResult = Outcome.Ok(byteArrayOf(7, 7)))
        val component = component(vault)
        advanceUntilIdle()

        component.selectTwo(vault)
        component.startFiling()
        advanceUntilIdle()
        assertTrue(component.choosing().albums.isEmpty(), "nothing to choose, which is the premise here")

        component.startFilingCreate()
        component.editFilingAlbumName("  Holiday ")
        component.confirmFilingCreate()
        advanceUntilIdle()

        assertEquals(listOf("Holiday"), vault.createdNames, "trimmed, like every other album name")
        assertEquals(
            listOf(listOf<Byte>(5), listOf<Byte>(6)) to listOf<Byte>(7, 7),
            vault.assignments.single(),
            "into the album that was just made",
        )
        assertIs<Filing.Moved>(component.state.value.filing)
    }

    @Test
    fun an_empty_name_in_the_chooser_is_refused_without_creating_anything() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)
        advanceUntilIdle()

        component.selectTwo(vault)
        component.startFiling()
        component.startFilingCreate()
        component.editFilingAlbumName("   ")
        component.confirmFilingCreate()
        advanceUntilIdle()

        val creating = assertNotNull(component.choosing().creating, "the field stays open to be fixed")
        assertEquals(AlbumDraftProblem.NameMissing, creating.problem)
        assertTrue(vault.createdNames.isEmpty())
        assertTrue(vault.assignments.isEmpty(), "and nothing was moved anywhere")
    }

    @Test
    fun a_failed_create_in_the_chooser_keeps_the_typed_name() = runTest {
        val vault = FakeVaultItems(createResult = Outcome.Err(VaultError.Crypto.IntegrityFailure))
        val component = component(vault)
        advanceUntilIdle()

        component.selectTwo(vault)
        component.startFiling()
        component.startFilingCreate()
        component.editFilingAlbumName("Holiday")
        component.confirmFilingCreate()
        advanceUntilIdle()

        val creating = assertNotNull(component.choosing().creating)
        assertEquals(AlbumDraftProblem.CreateFailed, creating.problem)
        assertEquals("Holiday", creating.name)
        assertTrue(vault.assignments.isEmpty(), "no album, so nowhere to move anything")
    }

    /**
     * A double tap on "Create and move" makes one album, not two. Nothing else would catch it: duplicate
     * names are allowed by design.
     */
    @Test
    fun a_second_create_while_one_is_running_creates_one_album() = runTest {
        val io = ManualDispatcher()
        val vault = FakeVaultItems()
        val component = component(vault, ioContext = io)
        advanceUntilIdle()

        component.selectTwo(vault)
        component.startFiling()
        component.startFilingCreate()
        component.editFilingAlbumName("Holiday")
        component.confirmFilingCreate()
        assertEquals(true, assertNotNull(component.choosing().creating).saving, "the window under test")

        component.confirmFilingCreate()
        io.drain()
        advanceUntilIdle()

        assertEquals(listOf("Holiday"), vault.createdNames, "one album, not two")
    }

    /**
     * The chooser closes when its selection goes.
     *
     * It can go without the user cancelling: back clears the selection, and an item can leave the
     * listing from elsewhere. Either way a chooser with nothing to move is an empty question.
     */
    @Test
    fun clearing_the_selection_closes_the_chooser() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(album(otherAlbum, "Invoices"))
        val component = component(vault)
        advanceUntilIdle()

        component.selectTwo(vault)
        component.startFiling()
        advanceUntilIdle()
        assertIs<Filing.Choosing>(component.state.value.filing)

        component.clearSelection()

        assertNull(component.state.value.filing)
    }

    @Test
    fun cancelling_the_chooser_moves_nothing() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(album(otherAlbum, "Invoices"))
        val component = component(vault)
        advanceUntilIdle()

        component.selectTwo(vault)
        component.startFiling()
        component.cancelFiling()
        advanceUntilIdle()

        assertNull(component.state.value.filing)
        assertTrue(vault.assignments.isEmpty())
        assertEquals(2, component.state.value.selection.count, "the selection is still there")
    }
}

/** A batch that stays idle: these tests are about filing, not importing. */
private class QuietImports : VaultImports {
    override val state: StateFlow<ImportState> = MutableStateFlow(ImportState.Idle)

    override fun importPhotos(albumId: ByteArray?) = Unit

    override fun importFiles(albumId: ByteArray?) = Unit

    override fun cancel() = Unit

    override fun acknowledge() = Unit

    override fun requestOriginalDelete() = Unit

    override fun confirmOriginalDelete() = Unit

    override fun keepOriginals() = Unit
}

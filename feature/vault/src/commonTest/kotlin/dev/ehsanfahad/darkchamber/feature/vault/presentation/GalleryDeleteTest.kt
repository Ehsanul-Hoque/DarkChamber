package dev.ehsanfahad.darkchamber.feature.vault.presentation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.backhandler.BackDispatcher
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.IdleVaultImports
import dev.ehsanfahad.darkchamber.feature.vault.ManualDispatcher
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryError
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryState
import dev.ehsanfahad.darkchamber.feature.vault.api.GallerySelection
import dev.ehsanfahad.darkchamber.feature.vault.api.SelectionDelete
import dev.ehsanfahad.darkchamber.feature.vault.domain.CreateAlbum
import dev.ehsanfahad.darkchamber.feature.vault.domain.DeleteAlbum
import dev.ehsanfahad.darkchamber.feature.vault.domain.FileItems
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveAlbum
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveAlbums
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveGallery
import dev.ehsanfahad.darkchamber.feature.vault.domain.RenameAlbum
import dev.ehsanfahad.darkchamber.feature.vault.domain.ShredItems
import dev.ehsanfahad.darkchamber.feature.vault.domain.VaultItems
import dev.ehsanfahad.darkchamber.feature.vault.readableMeta
import dev.ehsanfahad.darkchamber.feature.vault.summary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Deleting the selected items from the grid: a confirmation with the count, one write over exactly the
 * selection, the selection ending, and the result reported as the count actually deleted — or, refused,
 * nothing deleted and the selection kept.
 */
class GalleryDeleteTest {
    private val lifecycle = LifecycleRegistry().apply { resume() }

    private fun TestScope.component(
        vault: FakeVaultItems,
        shred: suspend (List<ByteArray>) -> Outcome<Long> = ShredItems(vault, UnconfinedTestDispatcher(testScheduler))::invoke,
    ): DefaultVaultGalleryComponent {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return DefaultVaultGalleryComponent(
            componentContext = DefaultComponentContext(lifecycle, backHandler = BackDispatcher()),
            observeGallery = ObserveGallery(vault),
            imports = IdleVaultImports(),
            observeAlbum = ObserveAlbum(vault),
            renameAlbum = RenameAlbum(vault, ioContext = dispatcher),
            deleteAlbum = DeleteAlbum(vault, ioContext = dispatcher),
            observeAlbums = ObserveAlbums(vault),
            createAlbum = CreateAlbum(vault, ioContext = dispatcher),
            fileItems = FileItems(vault, ioContext = dispatcher),
            shredItems = shred,
            mainContext = dispatcher,
            progressInterval = Duration.ZERO,
            pickingDelay = Duration.ZERO,
            stageGrace = Duration.ZERO,
        )
    }

    private fun FakeVaultItems.emit(vararg ids: Byte) {
        summaries.value = ids.map { summary(id = byteArrayOf(it), meta = readableMeta()) }
    }

    /** Selected by tapping fresh arrays, as the screen does — never the instances the listing holds. */
    private fun DefaultVaultGalleryComponent.select(vararg ids: Byte) {
        selectItem(byteArrayOf(ids.first()))
        ids.drop(1).forEach { toggleItem(byteArrayOf(it)) }
    }

    @Test
    fun asking_to_delete_opens_the_confirmation_with_the_count() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit(1, 2, 3)

        component.requestDeleteSelection()
        assertNull(component.state.value.deletion, "nothing selected, nothing to ask")

        component.select(1, 3)
        component.requestDeleteSelection()

        assertEquals(SelectionDelete.Asking(2), component.state.value.deletion)
    }

    @Test
    fun cancelling_deletes_nothing_and_keeps_the_selection() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit(1, 2, 3)
        component.select(1, 3)

        component.confirmDeleteSelection() // no confirmation open yet
        component.requestDeleteSelection()
        component.cancelDeleteSelection()

        assertNull(component.state.value.deletion)
        assertEquals(emptyList(), vault.shredBatches)
        assertEquals(2, component.state.value.selection.count)
    }

    /**
     * Confirmed: one write over exactly the selected ids, the confirmation showing it under way and
     * deaf to a cancel or a second tap; then the selection ends and the result is the count deleted.
     */
    @Test
    fun confirming_deletes_exactly_the_selection_once_and_ends_it() = runTest {
        val vault = FakeVaultItems()
        val gate = CompletableDeferred<Unit>()
        val component = component(vault, shred = { ids ->
            gate.await()
            vault.shredMany(ids)
        })
        vault.emit(1, 2, 3)
        component.select(1, 3)

        component.requestDeleteSelection()
        component.confirmDeleteSelection()
        component.confirmDeleteSelection() // a double tap
        assertEquals(SelectionDelete.Deleting(2), component.state.value.deletion)
        component.cancelDeleteSelection() // too late to call back
        assertEquals(SelectionDelete.Deleting(2), component.state.value.deletion)

        gate.complete(Unit)

        assertEquals(listOf(listOf(listOf<Byte>(1), listOf<Byte>(3))), vault.shredBatches)
        assertEquals(SelectionDelete.Deleted(2), component.state.value.deletion)
        assertTrue(!component.state.value.selection.active, "the selection ended")

        component.acknowledgeDeletion()
        assertNull(component.state.value.deletion)
    }

    /** One of the two was already gone by the time the write ran: the report says one, not two. */
    @Test
    fun the_report_is_the_count_deleted_not_the_count_asked_for() = runTest {
        val vault = FakeVaultItems(onShredMany = { Outcome.Ok(1) })
        val component = component(vault)
        vault.emit(1, 2)
        component.select(1, 2)

        component.requestDeleteSelection()
        component.confirmDeleteSelection()

        assertEquals(SelectionDelete.Deleted(1), component.state.value.deletion)
    }

    @Test
    fun a_refused_delete_says_nothing_was_deleted_and_keeps_the_selection() = runTest {
        val vault = FakeVaultItems(onShredMany = { Outcome.Err(VaultError.Io(RuntimeException("disk"))) })
        val component = component(vault)
        vault.emit(1, 2, 3)
        component.select(1, 3)

        component.requestDeleteSelection()
        component.confirmDeleteSelection()

        assertNull(component.state.value.deletion)
        assertEquals(GalleryError.DeleteFailed, component.state.value.error)
        assertEquals(2, component.state.value.selection.count, "kept for another try")
    }

    /** A move under way owns the bottom of the screen; a delete is not started on top of it. */
    @Test
    fun a_delete_is_not_asked_for_while_a_move_runs() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit(1, 2)
        component.select(1)
        component.startFiling()

        component.requestDeleteSelection()

        assertNull(component.state.value.deletion)
    }

    // ---- the bottom bar --------------------------------------------------------------------------

    @Test
    fun a_delete_under_way_or_its_report_takes_the_bottom_bar() {
        val selecting = GalleryState(selection = GallerySelection(active = true, ids = listOf(byteArrayOf(1))))
        assertEquals(GalleryBottomBar.Selection, selecting.copy(deletion = SelectionDelete.Asking(1)).bottomBar(), "the dialog sits over the bar")
        assertEquals(GalleryBottomBar.DeleteReport, selecting.copy(deletion = SelectionDelete.Deleting(1)).bottomBar())
        assertEquals(GalleryBottomBar.DeleteReport, GalleryState(deletion = SelectionDelete.Deleted(1)).bottomBar())
    }

    // ---- the use case ------------------------------------------------------------------------------

    @Test
    fun an_empty_selection_deletes_nothing_and_touches_nothing() = runTest {
        val vault = FakeVaultItems()
        assertEquals(Outcome.Ok(0L), ShredItems(vault, UnconfinedTestDispatcher(testScheduler))(emptyList()))
        assertEquals(emptyList(), vault.shredBatches)
    }

    /** The write blocks on the catalog and the disk; it runs on the injected dispatcher, and a throw is a refusal. */
    @Test
    fun the_write_runs_on_the_injected_dispatcher_and_a_throw_is_a_refusal() = runTest {
        val io = ManualDispatcher()
        var onIo = false
        val vault = object : VaultItems by FakeVaultItems() {
            override fun shredMany(ids: List<ByteArray>): Outcome<Long> {
                onIo = io.isRunning
                throw IllegalStateException("vault session is closed")
            }
        }
        var result: Outcome<Long>? = null

        launch { result = ShredItems(vault, io)(listOf(byteArrayOf(1))) }
        runCurrent()
        io.drain()
        advanceUntilIdle()

        assertTrue(onIo)
        assertIs<Outcome.Err>(result)
    }

    @Test
    fun a_cancellation_stays_a_cancellation() = runTest {
        val vault = object : VaultItems by FakeVaultItems() {
            override fun shredMany(ids: List<ByteArray>): Outcome<Long> = throw CancellationException("locked")
        }
        assertFailsWith<CancellationException> { ShredItems(vault, UnconfinedTestDispatcher(testScheduler))(listOf(byteArrayOf(1))) }
    }
}

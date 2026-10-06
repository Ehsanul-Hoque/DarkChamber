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
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumDelete
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumDraftProblem
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumState
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTileName
import dev.ehsanfahad.darkchamber.feature.vault.api.ImportState
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultImports
import dev.ehsanfahad.darkchamber.feature.vault.domain.DeleteAlbum
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveAlbum
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * The two things an album-scoped gallery can do to the album itself: rename it, and delete it.
 *
 * The behaviour that matters most is the delete's shape. Deleting an album keeps every file in it,
 * which is the opposite of what folder semantics lead people to expect, so the confirmation is where
 * that gets corrected — and that only works if nothing can delete an album without it having been
 * shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryAlbumActionsTest {

    private val lifecycle = LifecycleRegistry().apply { resume() }

    private val albumId = byteArrayOf(1, 2)

    private fun TestScope.component(
        vault: FakeVaultItems,
        filter: ItemFilter = ItemFilter.Album(albumId),
        ioContext: CoroutineContext = UnconfinedTestDispatcher(testScheduler),
        onLeaveAlbum: () -> Unit = {},
    ): DefaultVaultGalleryComponent =
        DefaultVaultGalleryComponent(
            componentContext = DefaultComponentContext(lifecycle),
            observeGallery = ObserveGallery(vault),
            imports = IdleImports(),
            observeAlbum = ObserveAlbum(vault),
            renameAlbum = RenameAlbum(vault, ioContext = ioContext),
            deleteAlbum = DeleteAlbum(vault, ioContext = ioContext),
            mainContext = UnconfinedTestDispatcher(testScheduler),
            progressInterval = Duration.ZERO,
            pickingDelay = Duration.ZERO,
            stageGrace = Duration.ZERO,
            filter = filter,
            onLeaveAlbum = onLeaveAlbum,
        )

    /** The album the screen is showing, as the catalog would report it. */
    private fun FakeVaultItems.hasAlbum(name: String = "Receipts", itemCount: Int = 0) {
        albums.value = listOf(
            AlbumSummary(albumId, Outcome.Ok(name), itemCount, coverItemId = null, createdAt = 1L),
        )
    }

    private fun DefaultVaultGalleryComponent.present(): AlbumState.Present =
        assertIs<AlbumState.Present>(state.value.album)

    @Test
    fun an_album_scoped_screen_publishes_the_albums_name() = runTest {
        val vault = FakeVaultItems()
        vault.hasAlbum("Receipts")
        val component = component(vault)
        advanceUntilIdle()

        assertEquals(AlbumTileName.Readable("Receipts"), component.present().name)
    }

    /**
     * The whole-vault listing has **no album state at all** — not an empty one. There is nothing to
     * rename and nothing to delete, and a screen handed a heading would draw one.
     */
    @Test
    fun the_whole_vault_listing_has_no_album() = runTest {
        val vault = FakeVaultItems()
        vault.hasAlbum("Receipts")
        val component = component(vault, filter = ItemFilter.All)
        advanceUntilIdle()

        assertNull(component.state.value.album)
    }

    /** Nor does Unfiled, which is a view of the items in no album rather than an album. */
    @Test
    fun the_unfiled_listing_has_no_album() = runTest {
        val vault = FakeVaultItems()
        vault.hasAlbum("Receipts")
        val component = component(vault, filter = ItemFilter.Unfiled)
        advanceUntilIdle()

        assertNull(component.state.value.album)
    }

    /** A rename starts from what the album is called: the common edit is fixing a typo, not retyping. */
    @Test
    fun a_rename_is_prefilled_with_the_current_name() = runTest {
        val vault = FakeVaultItems()
        vault.hasAlbum("Receipts")
        val component = component(vault)
        advanceUntilIdle()

        component.startAlbumRename()

        assertEquals("Receipts", assertNotNull(component.present().rename).name)
    }

    /**
     * An album whose name will not open offers **no rename**, because there is nothing to start from —
     * and offering to replace a name the user has never seen is offering an edit they cannot judge.
     * Deleting it stays available, which is the action that actually helps there.
     */
    @Test
    fun an_unreadable_name_cannot_be_renamed_but_can_be_deleted() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(
            AlbumSummary(
                albumId,
                Outcome.Err(VaultError.Crypto.IntegrityFailure),
                itemCount = 2,
                coverItemId = null,
                createdAt = 1L,
            ),
        )
        val component = component(vault)
        advanceUntilIdle()

        component.startAlbumRename()
        assertNull(component.present().rename, "there is no name to start a rename from")

        component.requestAlbumDelete()
        assertEquals(AlbumDelete.Confirming, component.present().delete, "deleting is still offered")
    }

    @Test
    fun a_confirmed_rename_reaches_the_vault_trimmed_and_closes_the_dialog() = runTest {
        val vault = FakeVaultItems()
        vault.hasAlbum("Receipts")
        val component = component(vault)
        advanceUntilIdle()

        component.startAlbumRename()
        component.editAlbumName("  Invoices ")
        component.confirmAlbumRename()
        advanceUntilIdle()

        assertEquals(listOf(albumId.toList() to "Invoices"), vault.renames)
        assertNull(component.present().rename, "the dialog closes on success")
    }

    @Test
    fun an_emptied_rename_field_is_refused_with_a_message_and_no_call() = runTest {
        val vault = FakeVaultItems()
        vault.hasAlbum("Receipts")
        val component = component(vault)
        advanceUntilIdle()

        component.startAlbumRename()
        component.editAlbumName("   ")
        component.confirmAlbumRename()
        advanceUntilIdle()

        val draft = assertNotNull(component.present().rename, "the dialog stays open to be fixed")
        assertEquals(AlbumDraftProblem.NameMissing, draft.problem)
        assertTrue(vault.renames.isEmpty(), "the album keeps the name it had")
    }

    /**
     * A refused rename says so with its own problem — not the create's — and keeps what was typed,
     * because the next move after a failure is to try the same name again.
     */
    @Test
    fun a_failed_rename_is_surfaced_with_the_typed_name_kept() = runTest {
        val vault = FakeVaultItems(renameResult = Outcome.Err(VaultError.Crypto.IntegrityFailure))
        vault.hasAlbum("Receipts")
        val component = component(vault)
        advanceUntilIdle()

        component.startAlbumRename()
        component.editAlbumName("Invoices")
        component.confirmAlbumRename()
        advanceUntilIdle()

        val draft = assertNotNull(component.present().rename)
        assertEquals(AlbumDraftProblem.RenameFailed, draft.problem)
        assertEquals("Invoices", draft.name)
        assertFalse(draft.saving)
    }

    /**
     * **Asking to delete deletes nothing.** The confirmation is the only place the user is told that
     * their files survive this, so a delete that skipped it would be a delete carried out on an
     * expectation that is the exact opposite of what happens.
     */
    @Test
    fun requesting_a_delete_opens_the_confirmation_and_writes_nothing() = runTest {
        val vault = FakeVaultItems()
        vault.hasAlbum("Receipts", itemCount = 3)
        val component = component(vault)
        advanceUntilIdle()

        component.requestAlbumDelete()
        advanceUntilIdle()

        assertEquals(AlbumDelete.Confirming, component.present().delete)
        assertTrue(vault.deletedAlbums.isEmpty(), "nothing is deleted until the confirmation is taken")
    }

    /** And nothing can delete without it: a bare confirm, with no confirmation shown, is a no-op. */
    @Test
    fun a_delete_cannot_happen_without_the_confirmation() = runTest {
        val vault = FakeVaultItems()
        vault.hasAlbum("Receipts")
        val component = component(vault)
        advanceUntilIdle()

        component.confirmAlbumDelete()
        advanceUntilIdle()

        assertTrue(vault.deletedAlbums.isEmpty())
        assertNull(component.present().delete)
    }

    @Test
    fun a_confirmed_delete_removes_the_album_and_leaves_the_screen() = runTest {
        val vault = FakeVaultItems()
        vault.hasAlbum("Receipts")
        var left = 0
        val component = component(vault, onLeaveAlbum = { left++ })
        advanceUntilIdle()

        component.requestAlbumDelete()
        component.confirmAlbumDelete()
        advanceUntilIdle()

        assertEquals(listOf(albumId.toList()), vault.deletedAlbums)
        assertEquals(1, left, "the screen it was is gone, so it leaves")
    }

    /**
     * A failed delete stays put and says so. It does **not** leave: the album may well still be there,
     * and popping back to a grid that still shows it would report success.
     */
    @Test
    fun a_failed_delete_stays_and_does_not_leave() = runTest {
        val vault = FakeVaultItems(deleteResult = Outcome.Err(VaultError.Crypto.IntegrityFailure))
        vault.hasAlbum("Receipts")
        var left = 0
        val component = component(vault, onLeaveAlbum = { left++ })
        advanceUntilIdle()

        component.requestAlbumDelete()
        component.confirmAlbumDelete()
        advanceUntilIdle()

        assertEquals(AlbumDelete.Failed, component.present().delete)
        assertEquals(0, left, "nothing has been proven gone, so the screen stays")
    }

    @Test
    fun declining_the_confirmation_closes_it() = runTest {
        val vault = FakeVaultItems()
        vault.hasAlbum("Receipts")
        val component = component(vault)
        advanceUntilIdle()

        component.requestAlbumDelete()
        component.cancelAlbumDelete()

        assertNull(component.present().delete)
    }

    /**
     * While the two writes run, neither button works and the dialog cannot be dismissed — the delete
     * cannot be called back, and a dialog that closed would be claiming it had been.
     *
     * Driven on a held dispatcher because the window is *inside* the write.
     */
    @Test
    fun a_delete_in_flight_cannot_be_cancelled_or_repeated() = runTest {
        val io = ManualDispatcher()
        val vault = FakeVaultItems()
        vault.hasAlbum("Receipts")
        val component = component(vault, ioContext = io)
        advanceUntilIdle()

        component.requestAlbumDelete()
        component.confirmAlbumDelete()
        assertEquals(AlbumDelete.Deleting, component.present().delete, "this is the window under test")

        component.cancelAlbumDelete()
        component.confirmAlbumDelete()

        assertEquals(AlbumDelete.Deleting, component.present().delete)
        io.drain()
        advanceUntilIdle()
        assertEquals(listOf(albumId.toList()), vault.deletedAlbums, "one delete, not two")
    }

    /**
     * An album that disappears from elsewhere is **reported**, not left as an empty grid.
     *
     * This is the case the album listing exists on this screen for: its items are unfiled rather than
     * destroyed, so the item grid empties and looks exactly like an album that was always empty.
     */
    @Test
    fun an_album_that_disappears_is_reported_rather_than_shown_as_empty() = runTest {
        val vault = FakeVaultItems()
        vault.hasAlbum("Receipts", itemCount = 1)
        vault.summaries.value = listOf(summary(byteArrayOf(5), readableMeta()))
        val component = component(vault)
        advanceUntilIdle()
        assertIs<AlbumState.Present>(component.state.value.album)

        // Deleted from somewhere else: the album row goes, and its items leave this listing.
        vault.albums.value = emptyList()
        vault.summaries.value = emptyList()
        advanceUntilIdle()

        assertEquals(AlbumState.Gone, component.state.value.album)
    }

    /** The one action on that notice is leaving, and it goes out through the host's callback. */
    @Test
    fun leaving_a_missing_album_goes_through_the_host() = runTest {
        val vault = FakeVaultItems()
        var left = 0
        val component = component(vault, onLeaveAlbum = { left++ })
        advanceUntilIdle()

        component.leaveAlbum()

        assertEquals(1, left)
    }

    /**
     * An album that has gone takes an open rename with it. There is nothing left to rename, and a
     * dialog floating over the notice would be offering an edit that cannot land.
     */
    @Test
    fun an_album_that_disappears_drops_an_open_rename() = runTest {
        val vault = FakeVaultItems()
        vault.hasAlbum("Receipts")
        val component = component(vault)
        advanceUntilIdle()

        component.startAlbumRename()
        assertNotNull(component.present().rename)

        vault.albums.value = emptyList()
        advanceUntilIdle()

        assertEquals(AlbumState.Gone, component.state.value.album)
    }

    /**
     * An item arriving does not disturb an open rename.
     *
     * Imports land in albums, so this is an ordinary thing to happen while someone is typing — and the
     * album listing re-emits for every one of them.
     */
    @Test
    fun an_item_change_leaves_an_open_rename_alone() = runTest {
        val vault = FakeVaultItems()
        vault.hasAlbum("Receipts", itemCount = 1)
        val component = component(vault)
        advanceUntilIdle()

        component.startAlbumRename()
        component.editAlbumName("Invoi")

        vault.summaries.value = listOf(summary(byteArrayOf(5), readableMeta()))
        vault.hasAlbum("Receipts", itemCount = 2)
        advanceUntilIdle()

        assertEquals(1, component.state.value.items.size, "the listing still landed")
        assertEquals("Invoi", assertNotNull(component.present().rename).name, "and the draft survived it")
    }

    /** Editing the field clears the complaint about the text that was there before. */
    @Test
    fun editing_the_rename_clears_the_problem() = runTest {
        val vault = FakeVaultItems()
        vault.hasAlbum("Receipts")
        val component = component(vault)
        advanceUntilIdle()

        component.startAlbumRename()
        component.editAlbumName(" ")
        component.confirmAlbumRename()
        advanceUntilIdle()
        assertEquals(AlbumDraftProblem.NameMissing, assertNotNull(component.present().rename).problem)

        component.editAlbumName("I")

        assertNull(assertNotNull(component.present().rename).problem, "the message went with the text")
    }

    /**
     * A **name** arriving from the listing updates the heading without disturbing an open draft.
     *
     * Pinned here rather than left to the comment that claims it: on today's paths a name can only
     * change as a result of the rename this screen just made, by which point the dialog has closed —
     * so nothing else would notice if the draft were dropped, and this is the contract rather than a
     * bug being fixed.
     */
    @Test
    fun a_name_arriving_from_the_listing_keeps_an_open_draft() = runTest {
        val vault = FakeVaultItems()
        vault.hasAlbum("Receipts")
        val component = component(vault)
        advanceUntilIdle()

        component.startAlbumRename()
        component.editAlbumName("Invoi")

        vault.hasAlbum("Something else")
        advanceUntilIdle()

        assertEquals(AlbumTileName.Readable("Something else"), component.present().name, "the heading moved")
        assertEquals("Invoi", assertNotNull(component.present().rename).name, "the draft did not")
    }

    /** A rename landing is visible on the heading, and it arrives through the listing. */
    @Test
    fun a_rename_shows_up_on_the_heading_through_the_listing() = runTest {
        val vault = FakeVaultItems()
        vault.hasAlbum("Receipts")
        val component = component(vault)
        advanceUntilIdle()

        component.startAlbumRename()
        component.editAlbumName("Invoices")
        component.confirmAlbumRename()
        advanceUntilIdle()

        assertEquals(
            AlbumTileName.Readable("Receipts"),
            component.present().name,
            "the fake catalog has not re-emitted yet, and nothing else writes the heading",
        )

        vault.hasAlbum("Invoices")
        advanceUntilIdle()

        assertEquals(AlbumTileName.Readable("Invoices"), component.present().name)
    }

    /** Album intents on a listing with no album behind it touch nothing. */
    @Test
    fun the_album_intents_are_no_ops_on_the_whole_vault_listing() = runTest {
        val vault = FakeVaultItems()
        vault.hasAlbum("Receipts")
        var left = 0
        val component = component(vault, filter = ItemFilter.All, onLeaveAlbum = { left++ })
        advanceUntilIdle()

        component.startAlbumRename()
        component.editAlbumName("Something")
        component.confirmAlbumRename()
        component.requestAlbumDelete()
        component.confirmAlbumDelete()
        advanceUntilIdle()

        assertNull(component.state.value.album)
        assertTrue(vault.renames.isEmpty())
        assertTrue(vault.deletedAlbums.isEmpty())
        assertEquals(0, left)
    }
}

/** A batch that never does anything: these tests are about the album, not about importing. */
private class IdleImports : VaultImports {
    override val state: StateFlow<ImportState> = MutableStateFlow(ImportState.Idle)

    override fun importPhotos() = Unit

    override fun importFiles() = Unit

    override fun cancel() = Unit

    override fun acknowledge() = Unit

    override fun requestOriginalDelete() = Unit

    override fun confirmOriginalDelete() = Unit

    override fun keepOriginals() = Unit
}

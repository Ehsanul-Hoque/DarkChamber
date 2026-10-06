package dev.ehsanfahad.darkchamber.feature.vault.presentation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.vault.AlbumSummary
import dev.ehsanfahad.darkchamber.feature.vault.ExplodingVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.ManualDispatcher
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumDraftProblem
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumsError
import dev.ehsanfahad.darkchamber.feature.vault.domain.CreateAlbum
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveAlbums
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The album grid's component, and chiefly the one thing it can change: making an album.
 *
 * Every test drives the real use case over a fake vault rather than a stubbed component, because the
 * behaviour worth pinning spans the two — where the empty name is caught, what the dialog does with a
 * refusal, and whether a second tap can make a second album.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultVaultAlbumsComponentTest {

    private val lifecycle = LifecycleRegistry().apply { resume() }

    private fun TestScope.component(
        vault: FakeVaultItems,
        // Defaults to a context the test scheduler drives, so a create completes within the test. A
        // ManualDispatcher is passed by the tests that need to stand *inside* a running create.
        ioContext: CoroutineContext = UnconfinedTestDispatcher(testScheduler),
    ): DefaultVaultAlbumsComponent =
        DefaultVaultAlbumsComponent(
            componentContext = DefaultComponentContext(lifecycle),
            observeAlbums = ObserveAlbums(vault),
            createAlbum = CreateAlbum(vault, ioContext = ioContext),
            mainContext = UnconfinedTestDispatcher(testScheduler),
            onOpenAlbum = {},
            onOpenUnfiled = {},
        )

    /**
     * A catalog that will not read is **reported**, not rendered as a vault with no albums — the
     * fail-closed rule's exact target. The failure arrives as a throw inside the flow, which is the
     * listing's documented contract.
     */
    @Test
    fun a_broken_listing_is_reported_rather_than_shown_as_an_empty_grid() = runTest {
        val component = DefaultVaultAlbumsComponent(
            componentContext = DefaultComponentContext(lifecycle),
            observeAlbums = ObserveAlbums(ExplodingVaultItems()),
            createAlbum = CreateAlbum(ExplodingVaultItems(), ioContext = UnconfinedTestDispatcher(testScheduler)),
            mainContext = UnconfinedTestDispatcher(testScheduler),
            onOpenAlbum = {},
            onOpenUnfiled = {},
        )
        advanceUntilIdle()

        assertEquals(AlbumsError.ListingFailed, component.state.value.error)
        assertFalse(component.state.value.loading, "it has stopped waiting")
        assertTrue(component.state.value.listingUnreadable, "and the grid knows why it is empty")

        // Dismissing the banner acknowledges the message, not the condition: the failure ended the flow,
        // so nothing will arrive afterwards, and a screen that forgot would put "No albums yet" back up
        // over a vault whose albums it never managed to read.
        component.dismissError()
        assertNull(component.state.value.error, "the banner is the user's to dismiss")
        assertTrue(component.state.value.listingUnreadable, "why the grid is empty is not")
    }

    @Test
    fun the_grid_has_no_draft_until_one_is_asked_for() = runTest {
        val component = component(FakeVaultItems())

        assertNull(component.state.value.draft, "no dialog until the affordance is used")

        component.startCreate()

        assertEquals("", assertNotNull(component.state.value.draft).name, "it opens empty")
    }

    /**
     * A second tap on the affordance keeps what was typed.
     *
     * The dialog covers the affordance, so arriving here twice means a stray touch — and answering a
     * stray touch by discarding a half-typed name is the worst of the three available behaviours.
     */
    @Test
    fun starting_a_create_twice_keeps_what_was_typed() = runTest {
        val component = component(FakeVaultItems())

        component.startCreate()
        component.editAlbumName("Receip")
        component.startCreate()

        assertEquals("Receip", assertNotNull(component.state.value.draft).name)
    }

    @Test
    fun a_confirmed_name_creates_the_album_and_closes_the_dialog() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)

        component.startCreate()
        component.editAlbumName("  Receipts ")
        component.confirmCreate()
        advanceUntilIdle()

        assertEquals(listOf("Receipts"), vault.createdNames, "trimmed on the way down")
        assertNull(component.state.value.draft, "the dialog closes on success")
    }

    /**
     * The new album arrives through the **listing**, not from the create's return value.
     *
     * That is what makes one album created on this screen look exactly like one created anywhere
     * else, and it is why the success path above does not insert anything into state.
     */
    @Test
    fun a_created_album_appears_through_the_listing() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)

        component.startCreate()
        component.editAlbumName("Receipts")
        component.confirmCreate()
        advanceUntilIdle()

        assertTrue(component.state.value.albums.isEmpty(), "the fake catalog has not emitted yet")

        // What the real catalog's notification would do: re-emit with the row that now exists.
        vault.albums.value = listOf(
            AlbumSummary(
                id = byteArrayOf(42),
                name = Outcome.Ok("Receipts"),
                itemCount = 0,
                coverItemId = null,
                createdAt = 1L,
            ),
        )
        advanceUntilIdle()

        assertEquals(1, component.state.value.albums.size, "the listing carries it, nothing else does")
    }

    /**
     * An empty name is refused with something to read, not swallowed.
     *
     * The dialog stays open — it is where the fix happens — and the vault is never asked, which is the
     * half of this the component cannot see on its own.
     */
    @Test
    fun an_empty_name_is_refused_with_a_message_and_no_call() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)

        component.startCreate()
        component.confirmCreate()
        advanceUntilIdle()

        val draft = assertNotNull(component.state.value.draft, "the dialog stays open to be fixed")
        assertEquals(AlbumDraftProblem.NameMissing, draft.problem)
        assertFalse(draft.saving, "it is not still trying")
        assertTrue(vault.createdNames.isEmpty(), "nothing was attempted")
    }

    /** Editing the name clears the complaint about the name that was there before. */
    @Test
    fun editing_the_name_clears_the_problem() = runTest {
        val component = component(FakeVaultItems())

        component.startCreate()
        component.confirmCreate()
        advanceUntilIdle()
        assertEquals(AlbumDraftProblem.NameMissing, assertNotNull(component.state.value.draft).problem)

        component.editAlbumName("R")

        assertNull(assertNotNull(component.state.value.draft).problem, "the message went with the text")
    }

    /**
     * A refused create is surfaced, and the typed name survives it.
     *
     * Closing the dialog would be the easy answer and the wrong one: the name is still worth keeping,
     * because the ordinary next move after a failure is to try it again.
     */
    @Test
    fun a_failed_create_is_surfaced_with_the_name_kept() = runTest {
        val vault = FakeVaultItems(createResult = Outcome.Err(VaultError.Crypto.IntegrityFailure))
        val component = component(vault)

        component.startCreate()
        component.editAlbumName("Receipts")
        component.confirmCreate()
        advanceUntilIdle()

        val draft = assertNotNull(component.state.value.draft, "a failure keeps the dialog up")
        assertEquals(AlbumDraftProblem.CreateFailed, draft.problem)
        assertEquals("Receipts", draft.name, "the name is kept so it can be retried")
        assertFalse(draft.saving)
    }

    /**
     * A double tap on Create makes one album.
     *
     * **Nothing else catches this.** Duplicate names are allowed by design, so the vault would happily
     * write a second "Receipts" — the guard in the component is the only thing between a twitchy tap
     * and two albums the user has to delete one of.
     *
     * Driven on a held dispatcher because the second tap has to land *inside* the first create, which
     * is a window a context that ran to completion would never leave open.
     */
    @Test
    fun a_second_confirm_while_one_is_running_does_not_create_twice() = runTest {
        val io = ManualDispatcher()
        val vault = FakeVaultItems()
        val component = component(vault, ioContext = io)

        component.startCreate()
        component.editAlbumName("Receipts")
        component.confirmCreate()

        assertTrue(assertNotNull(component.state.value.draft).saving, "this is the window under test")

        component.confirmCreate()
        io.drain()
        advanceUntilIdle()

        assertEquals(listOf("Receipts"), vault.createdNames, "one album, not two")
        assertNull(component.state.value.draft)
    }

    /** Cancelling abandons the draft. */
    @Test
    fun cancelling_closes_the_dialog() = runTest {
        val component = component(FakeVaultItems())

        component.startCreate()
        component.editAlbumName("Receipts")
        component.cancelCreate()

        assertNull(component.state.value.draft)
    }

    /**
     * Cancel is refused while the create is in flight, and so is a keystroke.
     *
     * The vault is already writing. A dialog that closed would be claiming the create was called off,
     * and a name changed under it would mean the album gets created as one thing while the field shows
     * another.
     */
    @Test
    fun a_create_in_flight_cannot_be_cancelled_or_edited() = runTest {
        val io = ManualDispatcher()
        val vault = FakeVaultItems()
        val component = component(vault, ioContext = io)

        component.startCreate()
        component.editAlbumName("Receipts")
        component.confirmCreate()

        component.cancelCreate()
        component.editAlbumName("Something else")

        val draft = assertNotNull(component.state.value.draft, "it cannot be called back, so it stays")
        assertEquals("Receipts", draft.name, "and it cannot be renamed underneath the write")

        io.drain()
        advanceUntilIdle()
        assertEquals(listOf("Receipts"), vault.createdNames)
    }

    /**
     * A listing emission does not close an open dialog.
     *
     * The listing re-emits on **every** catalog change — an import finishing elsewhere, the create
     * this dialog is about to make — so rebuilding the state wholesale on each one would take the
     * dialog away from someone mid-word.
     */
    @Test
    fun a_listing_emission_leaves_an_open_draft_alone() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)

        component.startCreate()
        component.editAlbumName("Receip")

        vault.albums.value = listOf(
            AlbumSummary(
                id = byteArrayOf(1),
                name = Outcome.Ok("Holiday"),
                itemCount = 2,
                coverItemId = null,
                createdAt = 1L,
            ),
        )
        advanceUntilIdle()

        assertEquals(1, component.state.value.albums.size, "the listing still landed")
        assertEquals("Receip", assertNotNull(component.state.value.draft).name, "and the draft survived it")
    }
}

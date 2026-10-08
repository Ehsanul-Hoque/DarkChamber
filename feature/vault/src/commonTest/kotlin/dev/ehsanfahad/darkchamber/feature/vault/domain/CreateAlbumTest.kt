package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.ManualDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Creating an album from what a user typed.
 *
 * The rule under test is where validation lives: the vault treats a blank name as a caller bug and
 * throws, so **this** layer is the one that has to turn an empty field into an answer. Every test here
 * runs against a fake that keeps the vault's precondition, so a use case that stopped validating
 * fails rather than quietly passing a blank name down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateAlbumTest {

    @Test
    fun a_usable_name_reaches_the_vault_and_the_new_id_comes_back() = runTest {
        val vault = FakeVaultItems(createResult = Outcome.Ok(byteArrayOf(9, 9)))

        val result = CreateAlbum(vault, ioContext = UnconfinedTestDispatcher(testScheduler))("Receipts")

        assertEquals(listOf("Receipts"), vault.createdNames)
        assertContentEquals(byteArrayOf(9, 9), assertIs<CreateAlbumResult.Created>(result).id)
    }

    /**
     * Surrounding whitespace is removed before the name is stored, not merely tolerated.
     *
     * `"Holiday "` and `"Holiday"` are the same name as far as anyone looking at the grid can tell,
     * and the album list is sorted by name — so keeping the space would put two apparently identical
     * albums in two unrelated places for a reason invisible on screen.
     */
    @Test
    fun a_name_is_trimmed_before_it_is_stored() = runTest {
        val vault = FakeVaultItems()

        val result = CreateAlbum(vault, ioContext = UnconfinedTestDispatcher(testScheduler))("  Holiday\n")

        assertIs<CreateAlbumResult.Created>(result)
        assertEquals(listOf("Holiday"), vault.createdNames, "stored trimmed, not as typed")
    }

    /**
     * An empty field is refused **here**, and the vault is never asked.
     *
     * Not an `Outcome.Err`: nothing failed. The user has not finished typing, which is a thing this
     * layer can say next to the field and the vault cannot say at all — it would throw.
     */
    @Test
    fun an_empty_name_is_refused_without_reaching_the_vault() = runTest {
        val vault = FakeVaultItems()

        val result = CreateAlbum(vault, ioContext = UnconfinedTestDispatcher(testScheduler))("")

        assertIs<CreateAlbumResult.NameMissing>(result)
        assertTrue(vault.createdNames.isEmpty(), "nothing was attempted, so nothing was called")
    }

    /**
     * Whitespace alone is nothing, by the same rule the trim implies: it reduces to an empty name, so
     * it is refused rather than stored. An album called "   " cannot be identified, told apart from
     * another one, or described to anyone.
     */
    @Test
    fun a_name_of_only_whitespace_is_nothing() = runTest {
        val vault = FakeVaultItems()

        val result = CreateAlbum(vault, ioContext = UnconfinedTestDispatcher(testScheduler))("   \t ")

        assertIs<CreateAlbumResult.NameMissing>(result)
        assertTrue(vault.createdNames.isEmpty(), "three spaces is not a name")
    }

    /**
     * A vault that refuses comes back as a failure carrying its error — the surface needs the code to
     * decide what to say, and the log needs it to record anything at all.
     */
    @Test
    fun a_refused_create_comes_back_as_a_failure_carrying_the_error() = runTest {
        val vault = FakeVaultItems(createResult = Outcome.Err(VaultError.Crypto.IntegrityFailure))

        val result = CreateAlbum(vault, ioContext = UnconfinedTestDispatcher(testScheduler))("Receipts")

        assertEquals(VaultError.Crypto.IntegrityFailure, assertIs<CreateAlbumResult.Failed>(result).error)
    }

    /**
     * The create runs on the injected context, not on the caller's thread.
     *
     * It is small — one seal and one row — but both are blocking, and the caller here is the thread
     * drawing the grid. Proven by holding the dispatcher: while nothing has run on it the vault has
     * not been touched, which a context that was ignored could not arrange.
     */
    @Test
    fun the_create_runs_on_the_injected_context() = runTest {
        val io = ManualDispatcher()
        val vault = FakeVaultItems()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            CreateAlbum(vault, ioContext = io)("Receipts")
        }

        assertTrue(vault.createdNames.isEmpty(), "nothing may happen before the io dispatcher runs")
        io.drain()
        job.join()

        assertEquals(listOf("Receipts"), vault.createdNames)
    }
}

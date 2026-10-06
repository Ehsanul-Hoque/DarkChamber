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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Renaming an album. The same name rule as creating — which is the point of it being shared — and a
 * different failure, because an album that could not be renamed still answers to its old name.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RenameAlbumTest {

    private val id = byteArrayOf(1, 2, 3)

    @Test
    fun a_usable_name_reaches_the_vault_with_the_album_id() = runTest {
        val vault = FakeVaultItems()

        val result = RenameAlbum(vault, ioContext = UnconfinedTestDispatcher(testScheduler))(id, "Receipts")

        assertIs<RenameAlbumResult.Renamed>(result)
        assertEquals(listOf(id.toList() to "Receipts"), vault.renames)
    }

    /** Trimmed on the way down, by the rule creating shares — so a rename cannot smuggle in a name a create would refuse. */
    @Test
    fun a_name_is_trimmed_before_it_is_stored() = runTest {
        val vault = FakeVaultItems()

        RenameAlbum(vault, ioContext = UnconfinedTestDispatcher(testScheduler))(id, "  Holiday\t")

        assertEquals(listOf(id.toList() to "Holiday"), vault.renames)
    }

    @Test
    fun a_blank_name_is_refused_without_reaching_the_vault() = runTest {
        val vault = FakeVaultItems()

        val result = RenameAlbum(vault, ioContext = UnconfinedTestDispatcher(testScheduler))(id, "   ")

        assertIs<RenameAlbumResult.NameMissing>(result)
        assertTrue(vault.renames.isEmpty(), "the album keeps the name it had, and nothing was attempted")
    }

    /**
     * A refusal carries the error. That includes an album that is no longer there — the vault reports
     * a missing id rather than quietly creating one, and this is the path that surfaces it.
     */
    @Test
    fun a_refused_rename_comes_back_as_a_failure_carrying_the_error() = runTest {
        val vault = FakeVaultItems(renameResult = Outcome.Err(VaultError.Crypto.IntegrityFailure))

        val result = RenameAlbum(vault, ioContext = UnconfinedTestDispatcher(testScheduler))(id, "Receipts")

        assertEquals(VaultError.Crypto.IntegrityFailure, assertIs<RenameAlbumResult.Failed>(result).error)
    }

    @Test
    fun the_rename_runs_on_the_injected_context() = runTest {
        val io = ManualDispatcher()
        val vault = FakeVaultItems()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            RenameAlbum(vault, ioContext = io)(id, "Receipts")
        }

        assertTrue(vault.renames.isEmpty(), "nothing may happen before the io dispatcher runs")
        io.drain()
        job.join()

        assertEquals(1, vault.renames.size)
    }
}

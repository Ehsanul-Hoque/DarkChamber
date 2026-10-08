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
 * Deleting an album. Thin on purpose — there is no text to validate — so what is worth pinning is that
 * it reaches the vault with the right id and off the caller's thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteAlbumTest {

    private val id = byteArrayOf(7, 7)

    @Test
    fun the_delete_reaches_the_vault_with_the_album_id() = runTest {
        val vault = FakeVaultItems()

        val result = DeleteAlbum(vault, ioContext = UnconfinedTestDispatcher(testScheduler))(id)

        assertIs<Outcome.Ok<Unit>>(result)
        assertEquals(listOf(id.toList()), vault.deletedAlbums)
    }

    @Test
    fun a_refused_delete_comes_back_as_a_failure() = runTest {
        val vault = FakeVaultItems(deleteResult = Outcome.Err(VaultError.Crypto.IntegrityFailure))

        val result = DeleteAlbum(vault, ioContext = UnconfinedTestDispatcher(testScheduler))(id)

        assertEquals(VaultError.Crypto.IntegrityFailure, assertIs<Outcome.Err>(result).error)
    }

    /**
     * Off the caller's thread. This one is **two** catalog writes rather than one — the items are
     * unfiled and then the album row is removed — so it is the heaviest of the three album writes and
     * the least acceptable on the thread drawing the screen.
     */
    @Test
    fun the_delete_runs_on_the_injected_context() = runTest {
        val io = ManualDispatcher()
        val vault = FakeVaultItems()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) { DeleteAlbum(vault, ioContext = io)(id) }

        assertTrue(vault.deletedAlbums.isEmpty(), "nothing may happen before the io dispatcher runs")
        io.drain()
        job.join()

        assertEquals(listOf(id.toList()), vault.deletedAlbums)
    }
}

package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.vault.AssignSummary
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.ManualDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Moving selected items into an album, or out of one.
 *
 * One use case for both directions, because they are one write with a different argument: an item's
 * album is a nullable column and `null` is the unfiled state rather than a separate operation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileItemsTest {

    private val ids = listOf(byteArrayOf(1), byteArrayOf(2))
    private val album = byteArrayOf(9)

    private fun TestScope.fileItems(vault: FakeVaultItems) =
        FileItems(vault, ioContext = UnconfinedTestDispatcher(testScheduler))

    private fun summaryOf(done: Outcome<AssignSummary>): AssignSummary =
        assertIs<Outcome.Ok<AssignSummary>>(done).value

    @Test
    fun the_items_and_the_destination_reach_the_vault() = runTest {
        val vault = FakeVaultItems()

        val done = fileItems(vault)(ids, album)

        assertEquals(2, summaryOf(done).assigned)
        assertEquals(
            listOf<Byte>(1) to listOf<Byte>(2),
            vault.assignments.single().first.let { it[0] to it[1] },
            "these items",
        )
        assertEquals(listOf<Byte>(9), vault.assignments.single().second, "that album")
    }

    /** `null` is the destination that means "no album", not a different call. */
    @Test
    fun a_null_destination_unfiles_them() = runTest {
        val vault = FakeVaultItems()

        fileItems(vault)(ids, null)

        assertEquals(null, vault.assignments.single().second)
    }

    /**
     * An empty selection is refused rather than written.
     *
     * The catalog would accept it and notify every listener for a write that changed nothing, and the
     * screen above would report "nothing moved" as though something had been attempted.
     */
    @Test
    fun an_empty_selection_is_not_written() = runTest {
        val vault = FakeVaultItems()

        val done = fileItems(vault)(emptyList(), album)

        assertTrue(vault.assignments.isEmpty(), "nothing was asked for, so nothing was written")
        assertEquals(0, summaryOf(done).requested)
    }

    /**
     * Fewer moved than asked for is reported as such. An item shredded between being selected and being
     * moved is an ordinary race — skipped, not failed — and a caller that cannot see the difference can
     * only report a half-move as a clean one.
     */
    @Test
    fun a_partial_move_keeps_both_numbers() = runTest {
        val vault = FakeVaultItems(assignedOf = { it - 1 })

        val summary = summaryOf(fileItems(vault)(ids, album))

        assertEquals(2, summary.requested)
        assertEquals(1, summary.assigned)
        assertEquals(1, summary.skipped)
    }

    @Test
    fun a_refused_move_comes_back_as_a_failure() = runTest {
        val vault = FakeVaultItems(assignFailure = VaultError.Crypto.IntegrityFailure)

        val done = fileItems(vault)(ids, album)

        assertEquals(VaultError.Crypto.IntegrityFailure, assertIs<Outcome.Err>(done).error)
    }

    @Test
    fun the_write_runs_on_the_injected_context() = runTest {
        val io = ManualDispatcher()
        val vault = FakeVaultItems()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) { FileItems(vault, ioContext = io)(ids, album) }

        assertTrue(vault.assignments.isEmpty(), "nothing may happen before the io dispatcher runs")
        io.drain()
        job.join()

        assertEquals(1, vault.assignments.size)
    }
}

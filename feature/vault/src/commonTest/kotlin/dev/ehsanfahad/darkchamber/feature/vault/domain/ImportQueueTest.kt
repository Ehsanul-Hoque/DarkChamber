package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.CancellingVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.FakePickedFile
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.api.FailedImport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The batch: a sequence of single-file imports, and the rules about what one bad file costs.
 *
 * Sequential by construction (spec D2) — files are encrypted one at a time, because running several
 * in parallel would multiply the bounded-RAM guarantee that streaming exists to provide, for very
 * little on a CPU-bound phone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportQueueTest {

    private fun queue(vault: VaultItems) =
        ImportQueue(ImportFile(vault = vault, ioContext = UnconfinedTestDispatcher()))

    @Test
    fun files_are_imported_in_the_order_they_were_picked() = runTest {
        val vault = FakeVaultItems()
        val files = listOf(FakePickedFile(name = "a.jpg"), FakePickedFile(name = "b.jpg"), FakePickedFile(name = "c.jpg"))

        val summary = queue(vault)(files)

        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), vault.importedNames)
        assertEquals(3, summary.imported)
        assertEquals(emptyList(), summary.failed)
        assertTrue(files.all { it.closed }, "every grant is released")
    }

    /** One unreadable file in fifty must cost the user that file and nothing else. */
    @Test
    fun a_failed_file_is_recorded_and_the_batch_carries_on() = runTest {
        val vault = FakeVaultItems(failFor = setOf("broken.jpg"))
        val files = listOf(
            FakePickedFile(name = "first.jpg"),
            FakePickedFile(name = "broken.jpg"),
            FakePickedFile(name = "last.jpg"),
        )

        val summary = queue(vault)(files)

        assertEquals(listOf("first.jpg", "broken.jpg", "last.jpg"), vault.importedNames, "order intact")
        assertEquals(2, summary.imported)
        assertEquals(listOf("broken.jpg"), summary.failed.map { it.name })
        assertTrue(files.all { it.closed }, "a failure mid-batch leaks no grants")
    }

    @Test
    fun an_empty_pick_is_a_no_op() = runTest {
        val vault = FakeVaultItems()

        val summary = queue(vault)(emptyList())

        assertEquals(0, summary.imported)
        assertEquals(emptyList(), summary.failed)
        assertEquals(0, vault.importCalls)
    }

    /**
     * A lock cancels the scope the batch runs on. Cancellation is not a failed file: recording it
     * would tell the user their photo could not be imported when in fact they locked the vault.
     * The files the batch never reached still hold platform grants, so they must be released here —
     * the import use case only ever closes the one it was handed.
     */
    @Test
    fun cancellation_propagates_and_releases_every_remaining_grant() = runTest {
        val files = listOf(
            FakePickedFile(name = "started.jpg"),
            FakePickedFile(name = "never.jpg"),
            FakePickedFile(name = "also-never.jpg"),
        )

        assertFailsWith<CancellationException> { queue(CancellingVaultItems())(files) }

        assertTrue(files.all { it.closed }, "a cancelled batch must not leak the grants it never used")
    }

    @Test
    fun progress_reports_each_file_with_a_fraction_when_the_size_is_known() = runTest {
        // The container reads each payload twice, so the callback runs to 2 * size; the fraction the
        // caller draws must still run 0 → 1 exactly once.
        val vault = FakeVaultItems(bytesPerImport = listOf(4L, 8L))
        val files = listOf(FakePickedFile(name = "clip.mp4", size = 4L))
        val seen = mutableListOf<ImportProgress>()

        queue(vault)(files) { seen += it }

        assertEquals(listOf(0, 0, 0), seen.map { it.index }, "one file, always index 0")
        assertEquals(listOf(1, 1, 1), seen.map { it.total })
        assertEquals("clip.mp4", seen.first().name)
        assertEquals(listOf(null, 0.5f, 1f), seen.map { it.fileFraction }, "0 → 1 across both passes")
    }

    /**
     * Before the first byte is counted there is nothing to draw a bar from, so the file starts
     * *indeterminate* rather than at a confident zero.
     *
     * A file the platform has to fetch before we can read it — a cloud document the provider
     * downloads on demand — spends that whole wait between these two emissions, reporting nothing.
     * A bar pinned at 0% for four minutes says "stuck"; an indeterminate one says "working", which
     * is the only thing we actually know. For a local file this state lasts one read.
     */
    @Test
    fun a_file_that_has_not_started_arriving_has_no_fraction_yet() = runTest {
        val vault = FakeVaultItems(bytesPerImport = listOf(2L))
        val files = listOf(FakePickedFile(name = "from-the-cloud.mov", size = 4L))
        val seen = mutableListOf<ImportProgress>()

        queue(vault)(files) { seen += it }

        assertEquals(null, seen.first().fileFraction, "no bytes yet means no fraction, not zero")
        assertEquals("from-the-cloud.mov", seen.first().name, "it still says which file is running")
        assertEquals(0.25f, seen.last().fileFraction, "and it becomes determinate once bytes arrive")
    }

    @Test
    fun progress_reports_no_fraction_when_the_platform_gave_no_size() = runTest {
        val vault = FakeVaultItems(bytesPerImport = listOf(4L))
        val files = listOf(FakePickedFile(name = "remote.mov", size = null))
        val seen = mutableListOf<ImportProgress>()

        queue(vault)(files) { seen += it }

        // Null, never a guessed denominator: a bar that moves against an invented total misreports
        // progress (hard rule #6). The caller renders this indeterminate.
        assertTrue(seen.all { it.fileFraction == null }, "an unsized file has no fraction, got $seen")
        assertTrue(seen.isNotEmpty(), "it still reports which file is running")
    }

    @Test
    fun progress_never_goes_backwards_within_a_file() = runTest {
        val vault = FakeVaultItems(bytesPerImport = listOf(1L, 2L, 3L, 10L))
        val files = listOf(FakePickedFile(name = "big.bin", size = 5L))
        val seen = mutableListOf<Float>()

        queue(vault)(files) { p -> p.fileFraction?.let { seen += it } }

        seen.zipWithNext { a, b -> assertTrue(b >= a, "fraction went backwards: $a then $b") }
        assertTrue(seen.last() <= 1f, "an over-reported byte count must not push the bar past full")
    }

    @Test
    fun a_file_name_never_reaches_a_log_line() {
        val progress = ImportProgress(index = 1, total = 4, name = "tax-return.pdf", fileFraction = 0.5f)
        val summary = ImportSummary(imported = 1, failed = listOf(FailedImport("passport.jpg", VaultError.Crypto.IntegrityFailure)))

        // Both types carry decrypted vault content, so the generated data-class toString would print
        // a file name into any log line that renders them (hard rule #4).
        assertFalse(progress.toString().contains("tax-return"), "got $progress")
        assertFalse(summary.toString().contains("passport"), "got $summary")
    }
}

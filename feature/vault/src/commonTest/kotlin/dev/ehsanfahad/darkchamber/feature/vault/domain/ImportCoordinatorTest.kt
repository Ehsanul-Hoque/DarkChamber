package dev.ehsanfahad.darkchamber.feature.vault.domain

import app.cash.turbine.test
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.FakeFilePicker
import dev.ehsanfahad.darkchamber.feature.vault.FakePhotoPicker
import dev.ehsanfahad.darkchamber.feature.vault.FakePickedFile
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.api.FailedImport
import dev.ehsanfahad.darkchamber.feature.vault.api.ImportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The batch as something a screen can watch but does not own (spec D7). The coordinator drives a
 * picker, then the queue, and publishes one state a UI can render without knowing any of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportCoordinatorTest {

    /**
     * Always built from the test's **own** scheduler: a dispatcher carrying a scheduler of its own
     * is never advanced by `runTest`, and the batch would simply never run.
     *
     * Unconfined by default, which runs a batch to completion inline — convenient for asserting
     * where it ended up. Tests that need to observe a batch *while* it runs pass a queueing
     * dispatcher instead, because the state is a `StateFlow`: run inline, every intermediate value
     * is conflated away before anything can collect it.
     */
    private fun TestScope.coordinator(
        vault: FakeVaultItems,
        photos: FakePhotoPicker = FakePhotoPicker(),
        files: FakeFilePicker = FakeFilePicker(),
        dispatcher: TestDispatcher = UnconfinedTestDispatcher(testScheduler),
    ): ImportCoordinator {
        return ImportCoordinator(
            photoPicker = photos,
            filePicker = files,
            queue = ImportQueue(ImportFile(vault = vault, ioContext = dispatcher)),
            scope = CoroutineScope(dispatcher),
        )
    }

    @Test
    fun a_batch_runs_from_idle_through_picking_and_running_to_finished() = runTest {
        val vault = FakeVaultItems()
        val photos = FakePhotoPicker(listOf(FakePickedFile(name = "a.jpg", size = 2L), FakePickedFile(name = "b.jpg")))
        val subject = coordinator(vault, photos = photos, dispatcher = StandardTestDispatcher(testScheduler))

        subject.state.test {
            assertEquals(ImportState.Idle, awaitItem(), "nothing is happening until asked")

            subject.importPhotos()

            assertEquals(ImportState.Picking, awaitItem(), "the picker is up")

            // Each file announces itself before any byte of it is read.
            val first = awaitItem()
            assertIs<ImportState.Running>(first)
            assertEquals(0, first.index)
            assertEquals(2, first.total)
            assertEquals("a.jpg", first.name)

            // Progress and the second file follow; only the terminal state is worth pinning exactly.
            var last = awaitItem()
            while (last !is ImportState.Finished) last = awaitItem()
            assertEquals(2, last.imported)
            assertEquals(emptyList(), last.failed)
        }
    }

    @Test
    fun an_empty_pick_returns_to_idle_without_an_error() = runTest {
        val vault = FakeVaultItems()
        val subject = coordinator(vault, photos = FakePhotoPicker(emptyList()))

        subject.state.test {
            assertEquals(ImportState.Idle, awaitItem())

            subject.importPhotos()

            assertEquals(ImportState.Picking, awaitItem())
            // Back to Idle, *not* Finished(0): a batch that never started has nothing to report, and
            // a summary saying "imported 0" would read as a failure to someone who just changed
            // their mind.
            assertEquals(ImportState.Idle, awaitItem())
        }
        assertEquals(0, vault.importCalls)
    }

    @Test
    fun a_failed_file_is_reported_in_the_summary_without_failing_the_batch() = runTest {
        val vault = FakeVaultItems(failFor = setOf("broken.jpg"))
        val files = FakeFilePicker(
            listOf(FakePickedFile(name = "good.jpg"), FakePickedFile(name = "broken.jpg")),
        )
        val subject = coordinator(vault, files = files)

        subject.importFiles()

        val state = subject.state.value
        assertIs<ImportState.Finished>(state)
        assertEquals(1, state.imported)
        assertEquals(listOf("broken.jpg"), state.failed.map { it.name })
    }

    @Test
    fun a_picker_that_cannot_be_shown_leaves_no_batch_running() = runTest {
        val vault = FakeVaultItems()
        val subject = coordinator(
            vault,
            photos = FakePhotoPicker(failure = IllegalStateException("no view controller")),
        )

        subject.importPhotos()

        assertEquals(ImportState.Idle, subject.state.value, "a picker that never opened imported nothing")
        assertFalse(subject.isRunning, "and left nothing running")
    }

    @Test
    fun a_second_request_while_one_batch_runs_is_ignored() = runTest {
        val vault = FakeVaultItems()
        val photos = FakePhotoPicker(listOf(FakePickedFile()))
        // Queueing, so the first batch is still pending when the second tap arrives.
        val subject = coordinator(vault, photos = photos, dispatcher = StandardTestDispatcher(testScheduler))

        // Two taps before anything has run. Two pickers would be two batches against one vault —
        // and on Android, two activity-result registrations racing for the same screen.
        subject.importPhotos()
        subject.importPhotos()
        advanceUntilIdle()

        assertEquals(1, photos.calls, "the second tap does not open a second picker")
        assertEquals(1, vault.importCalls)

        // Once it has finished, though, the user can import again.
        subject.importPhotos()
        advanceUntilIdle()
        assertEquals(2, photos.calls, "a finished batch does not block the next one")
    }

    @Test
    fun acknowledging_a_summary_returns_to_idle() = runTest {
        val vault = FakeVaultItems()
        val subject = coordinator(vault, photos = FakePhotoPicker(listOf(FakePickedFile())))

        subject.importPhotos()
        assertIs<ImportState.Finished>(subject.state.value)

        subject.acknowledge()

        assertEquals(ImportState.Idle, subject.state.value, "the summary is dismissible, not sticky")
    }

    /**
     * A user cancel is not a lock: the session stays open, what was already imported is kept, and
     * the file that was interrupted is **not** listed as having failed — nothing was wrong with it.
     */
    @Test
    fun cancelling_stops_the_batch_and_reports_what_was_imported() = runTest {
        lateinit var subject: ImportCoordinator
        val vault = FakeVaultItems(onImport = { if (it == "second.jpg") subject.cancel() })
        val picked = listOf(
            FakePickedFile(name = "first.jpg"),
            FakePickedFile(name = "second.jpg"),
            FakePickedFile(name = "third.jpg"),
        )
        val files = FakeFilePicker(picked)
        subject = coordinator(vault, files = files)

        subject.importFiles()

        val state = subject.state.value
        assertIs<ImportState.Finished>(state)
        assertEquals(1, state.imported, "what landed before the cancel is kept")
        assertEquals(emptyList(), state.failed, "an interrupted file is not a broken one")
        assertEquals(listOf("first.jpg", "second.jpg"), vault.importedNames, "the batch stopped, not ran on")
        // The files the cancel stopped the batch short of still hold platform grants — on iOS a
        // security-scoped resource, which leaks for the life of the process if it is not handed
        // back. A user cancel has to release them just as a lock does.
        assertTrue(picked.all { it.closed }, "a cancelled batch releases every grant it took")
    }

    @Test
    fun a_file_name_never_reaches_a_log_line() {
        val running = ImportState.Running(index = 0, total = 3, name = "passport.jpg", fileFraction = 0.25f)
        val finished = ImportState.Finished(
            imported = 2,
            failed = listOf(FailedImport("tax-return.pdf", VaultError.Crypto.IntegrityFailure)),
        )

        // Both carry decrypted vault content; the generated data-class toString would print a file
        // name into any log line that renders them (hard rule #4, cycle 1's GalleryItem precedent).
        assertFalse(running.toString().contains("passport"), "got $running")
        assertFalse(finished.toString().contains("tax-return"), "got $finished")
        assertTrue(running.toString().contains("total=3"), "the useful parts still render")
    }
}

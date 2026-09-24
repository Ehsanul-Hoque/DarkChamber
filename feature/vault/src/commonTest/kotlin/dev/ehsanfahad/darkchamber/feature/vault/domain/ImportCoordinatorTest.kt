package dev.ehsanfahad.darkchamber.feature.vault.domain

import app.cash.turbine.test
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.CancellingVaultItems
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
    /**
     * Tapping Stop has to show up in the state *at once*, not whenever the batch next gets a word in.
     *
     * The flag alone is not enough. A stop takes effect on the next read of the payload, and the
     * platform decides when that is: a file a cloud provider is still fetching can sit inside one
     * blocking read for minutes, emitting nothing. If the only sign of the tap is the batch ending,
     * the card sits there unchanged — Stop button and all — and the tap looks lost.
     */
    @Test
    fun stopping_shows_in_the_state_before_the_batch_has_a_chance_to_react() = runTest {
        lateinit var subject: ImportCoordinator
        // Cancels from inside the import of the only file, then keeps running: exactly the case
        // where the batch cannot report anything for a while.
        val seen = mutableListOf<ImportState>()
        val vault = FakeVaultItems(
            onImport = {
                subject.cancel()
                seen += subject.state.value
            },
        )
        subject = coordinator(vault, files = FakeFilePicker(listOf(FakePickedFile(name = "slow.mov"))))

        subject.importFiles()

        val duringImport = assertIs<ImportState.Running>(seen.single())
        assertTrue(duringImport.stopping, "the tap must be visible while the batch is still running")
        assertEquals("slow.mov", duringImport.name, "and it is still the same file it was showing")
    }

    @Test
    fun a_running_batch_is_not_stopping_until_it_is_asked_to() = runTest {
        val seen = mutableListOf<ImportState>()
        lateinit var subject: ImportCoordinator
        val vault = FakeVaultItems(onImport = { seen += subject.state.value })
        subject = coordinator(vault, files = FakeFilePicker(listOf(FakePickedFile(name = "quick.jpg"))))

        subject.importFiles()

        assertFalse(
            assertIs<ImportState.Running>(seen.single()).stopping,
            "an untouched batch must not render as stopping",
        )
    }

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

    /**
     * The offer to delete originals, which the summary can only make for files that landed.
     *
     * The count is what the screen draws; the handles behind it stay here, because deleting needs
     * the picked file itself and a screen must never hold one — it would outlive the session that
     * is allowed to have it.
     */
    @Test
    fun a_finished_batch_says_how_many_originals_can_be_deleted() = runTest {
        val vault = FakeVaultItems()
        val picked = listOf(
            FakePickedFile(name = "from-downloads.pdf", deletable = true),
            FakePickedFile(name = "from-the-library.jpg", deletable = false),
            FakePickedFile(name = "also-downloads.pdf", deletable = true),
        )
        val subject = coordinator(vault, files = FakeFilePicker(picked))

        subject.importFiles()

        val state = assertIs<ImportState.Finished>(subject.state.value)
        assertEquals(3, state.imported)
        assertEquals(2, state.deletableOriginals, "two of the three came from somewhere that deletes")
        assertEquals(
            listOf("from-downloads.pdf", "also-downloads.pdf"),
            subject.deletableOriginals.map { it.name },
            "and the handles to delete by are held here, not handed to a screen",
        )
    }

    @Test
    fun a_batch_with_nothing_deletable_offers_none() = runTest {
        val subject = coordinator(FakeVaultItems(), files = FakeFilePicker(listOf(FakePickedFile())))

        subject.importFiles()

        // The ordinary case: every photo-library item, every iOS item, most cloud documents. Zero
        // has to mean "no offer at all" — not an offer of nothing.
        assertEquals(0, assertIs<ImportState.Finished>(subject.state.value).deletableOriginals)
        assertEquals(emptyList(), subject.deletableOriginals)
    }

    @Test
    fun acknowledging_the_summary_discards_the_retained_handles() = runTest {
        val picked = FakePickedFile(name = "from-downloads.pdf", deletable = true)
        val subject = coordinator(FakeVaultItems(), files = FakeFilePicker(listOf(picked)))
        subject.importFiles()

        subject.acknowledge()

        // A dismissed summary is gone, so the offer it carried is gone too. Holding the handles on
        // would leave a delete that no longer has a summary to authorise it.
        assertEquals(emptyList(), subject.deletableOriginals)
        assertEquals(0, picked.deleteRequests, "and dismissing deletes nothing")
    }

    @Test
    fun a_new_batch_replaces_the_offer_the_last_one_left() = runTest {
        lateinit var subject: ImportCoordinator
        var heldWhileTheSecondBatchRan: Int? = null
        // Read from inside the second batch, because that is the only window where the two
        // behaviours differ: whichever way it is written, the new summary ends up describing the
        // new batch. What a stale handle buys is the *gap* — a running import with the last batch's
        // offer still sitting behind it, out of reach of the summary that authorised it.
        val vault = FakeVaultItems(
            onImport = { name ->
                if (name == "from-the-library.jpg") heldWhileTheSecondBatchRan = subject.deletableOriginals.size
            },
        )
        subject = coordinator(
            vault,
            photos = FakePhotoPicker(listOf(FakePickedFile(name = "from-downloads.pdf", deletable = true))),
            files = FakeFilePicker(listOf(FakePickedFile(name = "from-the-library.jpg", deletable = false))),
        )
        subject.importPhotos()
        assertEquals(1, subject.deletableOriginals.size, "the first batch left an offer")

        subject.importFiles()

        assertEquals(0, heldWhileTheSecondBatchRan, "starting a batch drops the previous offer")
        assertEquals(emptyList(), subject.deletableOriginals)
        assertEquals(0, assertIs<ImportState.Finished>(subject.state.value).deletableOriginals)
    }

    /**
     * A lock cancels the scope the batch runs on, which is how a lock ends an import. The batch
     * publishes no summary — this object is going away with the session — so no offer is left
     * behind, and the handles it would have been made of are never retained.
     */
    @Test
    fun a_batch_a_lock_interrupted_leaves_no_offer_behind() = runTest {
        val picked = FakePickedFile(name = "from-downloads.pdf", deletable = true)
        // Stands in for the lock: the import throws CancellationException from inside the read, the
        // way a cancelled scope makes it.
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val subject = ImportCoordinator(
            photoPicker = FakePhotoPicker(),
            filePicker = FakeFilePicker(listOf(picked)),
            queue = ImportQueue(ImportFile(vault = CancellingVaultItems(), ioContext = dispatcher)),
            scope = CoroutineScope(dispatcher),
        )

        subject.importFiles()

        assertEquals(emptyList(), subject.deletableOriginals, "a locked batch leaves nothing to delete by")
        assertFalse(subject.state.value is ImportState.Finished, "and no summary to offer it from")
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

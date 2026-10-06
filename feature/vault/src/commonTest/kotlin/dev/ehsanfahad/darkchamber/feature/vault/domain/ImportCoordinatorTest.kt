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
import dev.ehsanfahad.darkchamber.feature.vault.api.OriginalDelete
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
 * The batch as something a screen can watch but does not own. The coordinator drives a
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
    /**
     * Unfiled, which is what an import from the whole-vault listing asks for and what every test here
     * but the destination ones wants. Named rather than a bare `null` at thirty call sites, so the
     * reader is not left wondering which argument the null is.
     */
    private val unfiled: ByteArray? = null

    private fun TestScope.coordinator(
        vault: FakeVaultItems,
        photos: FakePhotoPicker = FakePhotoPicker(),
        files: FakeFilePicker = FakeFilePicker(),
        dispatcher: TestDispatcher = UnconfinedTestDispatcher(testScheduler),
        // Separated only by the tests that need to catch a delete *mid-flight*: a queueing
        // dispatcher here leaves the deletes pending until the test advances the scheduler, which is
        // the window where the disclosure is still on screen.
        deleteDispatcher: TestDispatcher = dispatcher,
    ): ImportCoordinator {
        return ImportCoordinator(
            photoPicker = photos,
            filePicker = files,
            // Both the encrypt and the picked-file column reads happen off the caller's thread, and
            // the test drives both on its own scheduler.
            queue = ImportQueue(
                importFile = ImportFile(vault = vault, ioContext = dispatcher),
                ioContext = dispatcher,
            ),
            scope = CoroutineScope(dispatcher),
            // Deleting an original is a blocking provider call, so it runs off the caller's thread
            // like the encrypt does — and, like the encrypt, the test drives it on its own scheduler.
            ioContext = deleteDispatcher,
        )
    }

    /**
     * The destination given at the pick reaches **every** file in the batch.
     *
     * Checked per file rather than once: the album belongs to the pick, so a batch that filed its first
     * file and forgot for the rest would be the exact failure this is built to prevent.
     */
    @Test
    fun the_destination_given_at_the_pick_is_where_every_file_lands() = runTest {
        val album = byteArrayOf(4, 2)
        val vault = FakeVaultItems()
        val photos = FakePhotoPicker(listOf(FakePickedFile(name = "a.jpg"), FakePickedFile(name = "b.jpg")))
        val subject = coordinator(vault, photos = photos)

        subject.importPhotos(album)
        advanceUntilIdle()

        assertEquals(2, vault.importedAlbums.size, "both files, or this proves nothing about the second")
        assertTrue(
            vault.importedAlbums.all { it?.contentEquals(album) == true },
            "every file in one batch goes to the same album",
        )
    }

    /** A document pick carries its destination the same way, since it is the same batch underneath. */
    @Test
    fun a_file_pick_carries_its_destination_too() = runTest {
        val album = byteArrayOf(4, 2)
        val vault = FakeVaultItems()
        val files = FakeFilePicker(FakePickedFile(name = "notes.pdf"))
        val subject = coordinator(vault, files = files)

        subject.importFiles(album)
        advanceUntilIdle()

        assertTrue(vault.importedAlbums.single()?.contentEquals(album) == true)
    }

    /** No destination means unfiled, which is what a whole-vault or Unfiled import asks for. */
    @Test
    fun a_pick_with_no_destination_leaves_the_batch_unfiled() = runTest {
        val vault = FakeVaultItems()
        val photos = FakePhotoPicker(listOf(FakePickedFile(name = "a.jpg")))
        val subject = coordinator(vault, photos = photos)

        subject.importPhotos(unfiled)
        advanceUntilIdle()

        assertEquals(listOf<ByteArray?>(null), vault.importedAlbums)
    }

    /**
     * A second batch does not inherit the first one's destination.
     *
     * The destination lives in the call rather than on the coordinator, which is what makes this true —
     * a field would be one more thing a later batch could find stale, and the symptom would be photos
     * silently landing in an album the user had left.
     */
    @Test
    fun a_later_batch_does_not_inherit_the_earlier_destination() = runTest {
        val album = byteArrayOf(4, 2)
        val vault = FakeVaultItems()
        val photos = FakePhotoPicker(listOf(FakePickedFile(name = "a.jpg")))
        val subject = coordinator(vault, photos = photos)

        subject.importPhotos(album)
        advanceUntilIdle()
        subject.importPhotos(unfiled)
        advanceUntilIdle()

        assertEquals(2, vault.importedAlbums.size)
        assertTrue(vault.importedAlbums[0]?.contentEquals(album) == true, "the first went to the album")
        assertEquals(null, vault.importedAlbums[1], "the second went nowhere, as it was asked to")
    }

    @Test
    fun a_batch_runs_from_idle_through_picking_and_running_to_finished() = runTest {
        val vault = FakeVaultItems()
        val photos = FakePhotoPicker(listOf(FakePickedFile(name = "a.jpg", size = 2L), FakePickedFile(name = "b.jpg")))
        val subject = coordinator(vault, photos = photos, dispatcher = StandardTestDispatcher(testScheduler))

        subject.state.test {
            assertEquals(ImportState.Idle, awaitItem(), "nothing is happening until asked")

            subject.importPhotos(unfiled)

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

            subject.importPhotos(unfiled)

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

        subject.importFiles(unfiled)

        val state = subject.state.value
        assertIs<ImportState.Finished>(state)
        assertEquals(1, state.imported)
        assertEquals(listOf("broken.jpg"), state.failed.map { it.name })
    }

    /**
     * A picker that cannot be shown used to leave the batch at idle, silently, so the button that
     * had just been tapped simply looked inert.
     *
     * It gets a state of its own rather than a failed-file entry: no file was chosen and no file
     * failed, so borrowing the failure banner would name a victim that does not exist (hard rule #6).
     */
    @Test
    fun a_picker_that_cannot_be_shown_says_so() = runTest {
        val vault = FakeVaultItems()
        val subject = coordinator(
            vault,
            photos = FakePhotoPicker(failure = IllegalStateException("no view controller")),
        )

        subject.importPhotos(unfiled)

        assertEquals(ImportState.PickerUnavailable, subject.state.value, "the tap must not vanish")
        assertFalse(subject.isRunning, "and it left nothing running")
        assertEquals(0, vault.importCalls, "a picker that never opened imported nothing")
    }

    @Test
    fun the_picker_failure_notice_is_dismissible() = runTest {
        val subject = coordinator(
            FakeVaultItems(),
            files = FakeFilePicker(failure = IllegalStateException("no activity")),
        )

        subject.importFiles(unfiled)
        assertEquals(ImportState.PickerUnavailable, subject.state.value)

        subject.acknowledge()

        assertEquals(ImportState.Idle, subject.state.value, "the notice is dismissible, not sticky")
    }

    @Test
    fun retrying_after_a_picker_failure_works() = runTest {
        val vault = FakeVaultItems()
        val photos = FakePhotoPicker(failure = IllegalStateException("no view controller"))
        val subject = coordinator(vault, photos = photos)

        subject.importPhotos(unfiled)
        assertEquals(ImportState.PickerUnavailable, subject.state.value)

        // The notice is not a running batch, so it must not block the next attempt — a picker that
        // failed once is the kind of thing a second tap fixes.
        subject.importPhotos(unfiled)

        assertEquals(2, photos.calls, "the failure notice must not latch the affordance shut")
    }

    /**
     * Backing out of a picker is not a failure and must raise nothing. The two are told apart by
     * *how* the picker returned: an empty list is a choice, a throw is the picker never opening.
     */
    @Test
    fun a_cancelled_pick_still_goes_quietly_to_idle() = runTest {
        val subject = coordinator(FakeVaultItems(), photos = FakePhotoPicker(emptyList()))

        subject.importPhotos(unfiled)

        assertEquals(ImportState.Idle, subject.state.value, "changing your mind is not a failure")
    }

    @Test
    fun a_second_request_while_one_batch_runs_is_ignored() = runTest {
        val vault = FakeVaultItems()
        val photos = FakePhotoPicker(listOf(FakePickedFile()))
        // Queueing, so the first batch is still pending when the second tap arrives.
        val subject = coordinator(vault, photos = photos, dispatcher = StandardTestDispatcher(testScheduler))

        // Two taps before anything has run. Two pickers would be two batches against one vault —
        // and on Android, two activity-result registrations racing for the same screen.
        subject.importPhotos(unfiled)
        subject.importPhotos(unfiled)
        advanceUntilIdle()

        assertEquals(1, photos.calls, "the second tap does not open a second picker")
        assertEquals(1, vault.importCalls)

        // Once it has finished, though, the user can import again.
        subject.importPhotos(unfiled)
        advanceUntilIdle()
        assertEquals(2, photos.calls, "a finished batch does not block the next one")
    }

    @Test
    fun acknowledging_a_summary_returns_to_idle() = runTest {
        val vault = FakeVaultItems()
        val subject = coordinator(vault, photos = FakePhotoPicker(listOf(FakePickedFile())))

        subject.importPhotos(unfiled)
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

        subject.importFiles(unfiled)

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

        subject.importFiles(unfiled)

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

        subject.importFiles(unfiled)

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

        subject.importFiles(unfiled)

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

        subject.importFiles(unfiled)

        // The ordinary case: every photo-library item, every iOS item, most cloud documents. Zero
        // has to mean "no offer at all" — not an offer of nothing.
        assertEquals(0, assertIs<ImportState.Finished>(subject.state.value).deletableOriginals)
        assertEquals(emptyList(), subject.deletableOriginals)
    }

    @Test
    fun acknowledging_the_summary_discards_the_retained_handles() = runTest {
        val picked = FakePickedFile(name = "from-downloads.pdf", deletable = true)
        val subject = coordinator(FakeVaultItems(), files = FakeFilePicker(listOf(picked)))
        subject.importFiles(unfiled)

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
        subject.importPhotos(unfiled)
        assertEquals(1, subject.deletableOriginals.size, "the first batch left an offer")

        subject.importFiles(unfiled)

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

        subject.importFiles(unfiled)

        assertEquals(emptyList(), subject.deletableOriginals, "a locked batch leaves nothing to delete by")
        assertFalse(subject.state.value is ImportState.Finished, "and no summary to offer it from")
    }

    /**
     * The delete offer, which is the one thing in this batch that destroys something.
     *
     * Every path below exists to keep one property true: an original is deleted only after the user
     * has seen what deleting does and said yes to *that*. The disclosure is not a confirmation
     * dialog protecting against a mis-tap — it is where the limits of the deletion get stated, so
     * skipping it would mean deleting on a promise the user was never shown (hard rule #6).
     */
    @Test
    fun asking_to_delete_opens_the_disclosure_and_deletes_nothing_yet() = runTest {
        val picked = FakePickedFile(name = "from-downloads.pdf", deletable = true)
        val subject = coordinator(FakeVaultItems(), files = FakeFilePicker(listOf(picked)))
        subject.importFiles(unfiled)

        subject.requestOriginalDelete()

        assertEquals(OriginalDelete.Confirming, finished(subject).originalDelete)
        assertEquals(0, picked.deleteRequests, "the disclosure comes first, always")
    }

    @Test
    fun there_is_nothing_to_ask_about_when_nothing_can_be_deleted() = runTest {
        val picked = FakePickedFile(name = "from-the-library.jpg", deletable = false)
        val subject = coordinator(FakeVaultItems(), files = FakeFilePicker(listOf(picked)))
        subject.importFiles(unfiled)

        subject.requestOriginalDelete()

        // The ordinary case. No offer means no disclosure either — not a dialog explaining that
        // there is nothing to do.
        assertEquals(OriginalDelete.NotAsked, finished(subject).originalDelete)
        assertEquals(0, picked.deleteRequests)
    }

    @Test
    fun confirming_deletes_every_offered_original_and_reports_how_many_went() = runTest {
        val picked = listOf(
            FakePickedFile(name = "one.pdf", deletable = true),
            FakePickedFile(name = "two.pdf", deletable = true),
        )
        val subject = coordinator(FakeVaultItems(), files = FakeFilePicker(picked))
        subject.importFiles(unfiled)
        subject.requestOriginalDelete()

        subject.confirmOriginalDelete()

        assertEquals(OriginalDelete.Done(deleted = 2, requested = 2), finished(subject).originalDelete)
        assertTrue(picked.all { it.deleteRequests == 1 }, "each original asked for exactly once")
    }

    /**
     * The flag is an advertisement, not a promise: a provider can refuse the call it said it would
     * take. Reporting two of three is the honest outcome — claiming three would be telling someone
     * their file is gone when it is still there.
     */
    @Test
    fun a_provider_that_refuses_is_reported_as_a_partial_result() = runTest {
        val picked = listOf(
            FakePickedFile(name = "one.pdf", deletable = true),
            FakePickedFile(name = "two.pdf", deletable = true, deleteRefuses = true),
            FakePickedFile(name = "three.pdf", deletable = true),
        )
        val subject = coordinator(FakeVaultItems(), files = FakeFilePicker(picked))
        subject.importFiles(unfiled)
        subject.requestOriginalDelete()

        subject.confirmOriginalDelete()

        assertEquals(OriginalDelete.Done(deleted = 2, requested = 3), finished(subject).originalDelete)
    }

    @Test
    fun confirming_without_the_disclosure_deletes_nothing() = runTest {
        val picked = FakePickedFile(name = "from-downloads.pdf", deletable = true)
        val subject = coordinator(FakeVaultItems(), files = FakeFilePicker(listOf(picked)))
        subject.importFiles(unfiled)

        subject.confirmOriginalDelete() // no requestOriginalDelete() first

        assertEquals(0, picked.deleteRequests, "the disclosure is the authorisation, not a formality")
        assertEquals(OriginalDelete.NotAsked, finished(subject).originalDelete)
    }

    @Test
    fun keeping_the_originals_is_an_explicit_choice_that_deletes_nothing() = runTest {
        val picked = FakePickedFile(name = "from-downloads.pdf", deletable = true)
        val subject = coordinator(FakeVaultItems(), files = FakeFilePicker(listOf(picked)))
        subject.importFiles(unfiled)
        subject.requestOriginalDelete()

        subject.keepOriginals()

        assertEquals(0, picked.deleteRequests)
        // Back to the offer rather than gone: declining is a decision about this dialog, not a
        // decision never to be asked again.
        assertEquals(OriginalDelete.NotAsked, finished(subject).originalDelete)
        assertEquals(1, subject.deletableOriginals.size, "the offer is still there to take")
    }

    @Test
    fun confirming_twice_deletes_each_original_once() = runTest {
        val picked = FakePickedFile(name = "from-downloads.pdf", deletable = true)
        val subject = coordinator(FakeVaultItems(), files = FakeFilePicker(listOf(picked)))
        subject.importFiles(unfiled)
        subject.requestOriginalDelete()

        subject.confirmOriginalDelete()
        subject.confirmOriginalDelete()

        assertEquals(1, picked.deleteRequests, "a second tap must not ask the provider again")
        assertEquals(OriginalDelete.Done(deleted = 1, requested = 1), finished(subject).originalDelete)
    }

    @Test
    fun a_summary_whose_originals_were_deleted_is_still_dismissible() = runTest {
        val picked = FakePickedFile(name = "from-downloads.pdf", deletable = true)
        val subject = coordinator(FakeVaultItems(), files = FakeFilePicker(listOf(picked)))
        subject.importFiles(unfiled)
        subject.requestOriginalDelete()
        subject.confirmOriginalDelete()

        subject.acknowledge()

        assertEquals(ImportState.Idle, subject.state.value)
        assertEquals(emptyList(), subject.deletableOriginals, "and nothing is left to delete twice")
    }

    /**
     * While the deletes are actually running, the dialog is still on screen — and it must not be
     * answerable a second time.
     *
     * The window is the length of a batch of provider calls, which for twenty documents is seconds
     * rather than frames. Leaving the buttons live through it meant tapping **Keep them** closed the
     * disclosure while the deletes carried on: the user chose to keep and the files went anyway,
     * which is the plainest kind of lie a control can tell (hard rule #6).
     */
    @Test
    fun keeping_them_while_the_delete_runs_cannot_take_the_choice_back() = runTest {
        val picked = FakePickedFile(name = "from-downloads.pdf", deletable = true)
        val subject = coordinator(
            FakeVaultItems(),
            files = FakeFilePicker(listOf(picked)),
            deleteDispatcher = StandardTestDispatcher(testScheduler),
        )
        subject.importFiles(unfiled)
        subject.requestOriginalDelete()
        subject.confirmOriginalDelete() // launched, not yet run

        assertEquals(OriginalDelete.Deleting, finished(subject).originalDelete)

        subject.keepOriginals()

        // The state has to *say* it is deleting, not merely refuse the tap. A dialog whose buttons
        // still look live while they do nothing is the thing the running-import card already goes
        // out of its way to avoid.
        assertEquals(OriginalDelete.Deleting, finished(subject).originalDelete, "still deleting")
        advanceUntilIdle()
        assertEquals(OriginalDelete.Done(deleted = 1, requested = 1), finished(subject).originalDelete)
        assertEquals(1, picked.deleteRequests)
    }

    @Test
    fun a_summary_cannot_be_dismissed_out_from_under_a_running_delete() = runTest {
        val picked = FakePickedFile(name = "from-downloads.pdf", deletable = true)
        val subject = coordinator(
            FakeVaultItems(),
            files = FakeFilePicker(listOf(picked)),
            deleteDispatcher = StandardTestDispatcher(testScheduler),
        )
        subject.importFiles(unfiled)
        subject.requestOriginalDelete()
        subject.confirmOriginalDelete()

        subject.acknowledge()

        // Dismissing would not stop the deletes — they run on a captured list — so it would only
        // hide them, and the outcome would be reported to nobody.
        assertEquals(OriginalDelete.Deleting, finished(subject).originalDelete)
        advanceUntilIdle()
        assertEquals(OriginalDelete.Done(deleted = 1, requested = 1), finished(subject).originalDelete)
    }

    @Test
    fun the_offer_cannot_be_reopened_while_its_delete_runs() = runTest {
        val picked = FakePickedFile(name = "from-downloads.pdf", deletable = true)
        val subject = coordinator(
            FakeVaultItems(),
            files = FakeFilePicker(listOf(picked)),
            deleteDispatcher = StandardTestDispatcher(testScheduler),
        )
        subject.importFiles(unfiled)
        subject.requestOriginalDelete()
        subject.confirmOriginalDelete()

        subject.requestOriginalDelete()

        assertEquals(OriginalDelete.Deleting, finished(subject).originalDelete)
        advanceUntilIdle()
        assertEquals(1, picked.deleteRequests, "and nothing was asked for twice")
    }

    private fun finished(subject: ImportCoordinator): ImportState.Finished =
        assertIs(subject.state.value, "the batch should have finished")

    @Test
    fun a_file_name_never_reaches_a_log_line() {
        val running = ImportState.Running(index = 0, total = 3, name = "passport.jpg", fileFraction = 0.25f)
        val finished = ImportState.Finished(
            imported = 2,
            failed = listOf(FailedImport("tax-return.pdf", VaultError.Crypto.IntegrityFailure)),
        )

        // Both carry decrypted vault content; the generated data-class toString would print a file
        // name into any log line that renders them (hard rule #4, as GalleryItem already does).
        assertFalse(running.toString().contains("passport"), "got $running")
        assertFalse(finished.toString().contains("tax-return"), "got $finished")
        assertTrue(running.toString().contains("total=3"), "the useful parts still render")
    }
}

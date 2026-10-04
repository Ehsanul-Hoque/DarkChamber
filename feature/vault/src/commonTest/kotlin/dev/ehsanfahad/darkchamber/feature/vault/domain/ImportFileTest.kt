package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.BlockingPickedFile
import dev.ehsanfahad.darkchamber.feature.vault.CancellingVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.DetailsThrowingPickedFile
import dev.ehsanfahad.darkchamber.feature.vault.FakePickedFile
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.ManualDispatcher
import dev.ehsanfahad.darkchamber.feature.vault.RecordingPickedFile
import dev.ehsanfahad.darkchamber.feature.vault.ThrowingVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.TrackedPickedFile
import dev.ehsanfahad.darkchamber.feature.vault.awaitTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.io.RawSource
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ImportFileTest {

    private fun importFile(vault: VaultItems) =
        ImportFile(vault = vault, ioContext = UnconfinedTestDispatcher())

    /**
     * The destination reaches the vault, where the filing happens as the row is written. Doing it
     * here rather than assigning afterwards is what keeps the item from appearing unfiled first: the
     * gallery re-emits on that write, so a two-step import would show the file land in the wrong
     * place and then move.
     */
    @Test
    fun the_album_a_file_is_imported_into_is_passed_to_the_vault() = runTest {
        val album = byteArrayOf(0xA1.toByte(), 0xB2.toByte())
        val vault = FakeVaultItems()

        importFile(vault)(FakePickedFile(name = "photo.jpg"), albumId = album)

        assertEquals(1, vault.importedAlbums.size)
        assertTrue(vault.importedAlbums.single().contentEquals(album), "the same album, unchanged")
    }

    @Test
    fun a_file_imported_with_no_album_is_left_unfiled() = runTest {
        val vault = FakeVaultItems()

        importFile(vault)(FakePickedFile(name = "photo.jpg"))

        assertEquals(listOf<ByteArray?>(null), vault.importedAlbums, "the default is the whole-vault import")
    }

    @Test
    fun a_picked_file_is_imported_under_its_own_name_and_mime() = runTest {
        val picked = FakePickedFile(name = "notes.pdf", mime = "application/pdf")
        val vault = FakeVaultItems()

        val result = importFile(vault)(picked)

        assertIs<ImportResult.Imported>(result)
        assertEquals(1, vault.importCalls)
        assertEquals("notes.pdf", vault.lastName)
        assertEquals("application/pdf", vault.lastMime)
        assertTrue(picked.opens > 0, "the importer is handed a working opener")
        assertTrue(picked.closed, "the platform grant is released")
    }

    @Test
    fun a_file_whose_size_the_platform_will_not_report_imports_like_any_other() = runTest {
        // Cloud providers routinely decline to give a size for a file that is not downloaded yet.
        // That costs the caller its progress denominator, and nothing else: the import itself never
        // needed the number, so an unknown size must never become a reason to refuse the file.
        val picked = FakePickedFile(name = "remote.mov", mime = "video/quicktime", size = null)
        val vault = FakeVaultItems()

        val result = importFile(vault)(picked)

        assertIs<ImportResult.Imported>(result)
        assertEquals("remote.mov", vault.lastName)
        assertTrue(picked.closed, "the platform grant is released")
    }

    @Test
    fun a_failed_import_carries_the_precise_error_and_still_releases_the_grant() = runTest {
        val picked = FakePickedFile()
        val vault = FakeVaultItems(importResult = Outcome.Err(VaultError.Crypto.IntegrityFailure))

        val result = importFile(vault)(picked)

        assertIs<ImportResult.Failed>(result)
        assertEquals(VaultError.Crypto.IntegrityFailure, result.error, "the vault's error is not flattened")
        assertTrue(picked.closed, "a failed import must not leak the grant")
    }

    @Test
    fun a_thrown_import_is_mapped_and_still_releases_the_grant() = runTest {
        val picked = FakePickedFile()

        val result = importFile(ThrowingVaultItems())(picked)

        assertIs<ImportResult.Failed>(result)
        assertIs<VaultError.Unexpected>(result.error, "a throw that escaped a lower layer is Unexpected")
        assertTrue(picked.closed, "the grant is released even when the import throws")
    }

    /**
     * A lock cancels the scope the import runs on. Cancellation is not an import failure: swallowing
     * it into `Failed` would keep the coroutine running past its cancellation and raise a phantom
     * error banner on a component that is already being torn down.
     */
    @Test
    fun cancellation_propagates_and_still_releases_the_grant() = runTest {
        val picked = FakePickedFile()
        val importer = ImportFile(vault = CancellingVaultItems(), ioContext = UnconfinedTestDispatcher())

        assertFailsWith<CancellationException> { importer(picked) }
        assertTrue(picked.closed, "a cancelled import must still release the grant")
    }

    @Test
    fun the_source_it_opens_is_handed_to_the_vault() = runTest {
        val picked = FakePickedFile()
        val vault = FakeVaultItems()

        val result = importFile(vault)(picked)

        assertIs<ImportResult.Imported>(result)
        // The vault no longer performs the first open itself, because that is the open that blocks.
        // If nothing arrives here the vault falls back to opening the payload, which is correct but
        // puts the unstoppable wait back where it cannot be abandoned.
        assertTrue(vault.handedFirst != null, "the vault must be handed the already-open source")
    }

    /**
     * The defect the abandonable open exists to fix.
     *
     * A provider may sit inside `openSource()` for minutes — Drive downloads the entire file there —
     * and during that time the import performs no reads, so the per-read stop guard has nothing to
     * land on. The tap was visible immediately and did nothing until the download finished.
     */
    @Test
    fun a_stop_during_a_blocked_open_ends_the_file_without_waiting_for_it() = runTest {
        val picked = BlockingPickedFile()
        val vault = FakeVaultItems()
        // Never released: the "download" is still running when the test ends, exactly as it would be
        // on a device.
        val importer = ImportFile(vault = vault, ioContext = Dispatchers.Default, pollInterval = 1.milliseconds)

        val result = importer(picked, isActive = { false })

        assertIs<ImportResult.Stopped>(result, "a stopped fetch is not a failure and not an import")
        assertEquals(0, vault.importCalls, "nothing may be imported from a file we never received")
        assertTrue(picked.closed, "the grant is released even though the open never returned")

        // Everything above was asserted while the open was still blocked, which is the state under
        // test. Released only now, and only so the fake stops occupying a dispatcher thread for the
        // rest of the suite: it busy-waits, and `Dispatchers.Default` on a two-core runner has two
        // threads to give.
        picked.released = true
    }

    /**
     * We cannot cancel the provider's transfer — it runs in its own process and no API stops it — so
     * the descriptor it eventually hands over must be closed rather than left to the garbage
     * collector. On Android that is an open file descriptor per abandoned import.
     */
    @Test
    fun an_abandoned_open_is_closed_when_it_finally_arrives() = runTest {
        val picked = BlockingPickedFile()
        val importer = ImportFile(
            vault = FakeVaultItems(),
            ioContext = Dispatchers.Default,
            pollInterval = 1.milliseconds,
        )

        val result = importer(picked, isActive = { false })
        assertIs<ImportResult.Stopped>(result)

        // The download "finishes" after the batch has already given up on it.
        picked.released = true

        awaitTrue("the abandoned source to be delivered") { picked.delivered != null }
        awaitTrue("the abandoned source to be closed") { picked.delivered?.closed == true }
    }

    /**
     * The wait is an `await` with a timeout on it, not a sleep followed by a look.
     *
     * A source that is already there has to resume the import at once. A loop that slept first would
     * charge **every** file the poll interval — five seconds across a fifty-file batch of local
     * photos, every one of which opens in microseconds — for a wait that only a provider ever makes
     * anyone do.
     */
    @Test
    fun an_open_that_returns_at_once_waits_for_nothing() = runTest {
        val picked = FakePickedFile()
        // An interval no import may ever be made to sit through, so any sleep at all is visible on
        // the test clock.
        val importer = ImportFile(
            vault = FakeVaultItems(),
            ioContext = UnconfinedTestDispatcher(testScheduler),
            pollInterval = 10.minutes,
        )

        val before = currentTime
        val result = importer(picked)

        assertIs<ImportResult.Imported>(result)
        assertEquals(before, currentTime, "a local file must not be charged the poll interval")
    }

    /**
     * A lock landing in the hand-off itself still releases the descriptor.
     *
     * Opening the payload here rather than inside the vault created a window that did not exist
     * before: between this side opening the source and the vault taking ownership of it there is a
     * dispatch, and a dispatch is a cancellation point. The vault closes the handed source on every
     * path it takes — but it cannot close one it was never given, and releasing the picked file
     * releases the platform grant, not a stream already opened against it. So the descriptor is this
     * side's to release for exactly as long as the hand-off has not completed.
     *
     * Driven one dispatch at a time because the window *is* the gap between two hops: a dispatcher
     * that ran until idle would carry the import past it.
     */
    @Test
    fun a_source_already_opened_is_closed_when_the_handoff_is_cancelled() = runTest {
        val io = ManualDispatcher()
        val picked = TrackedPickedFile()
        val vault = FakeVaultItems()
        val importer = ImportFile(vault = vault, ioContext = io)

        val job = launch(UnconfinedTestDispatcher(testScheduler)) { importer(picked) }
        // The open, which succeeds: control comes back with the source in hand and the import's own
        // hop onto the dispatcher queued but not yet begun.
        io.runNext()
        assertEquals(1, picked.opened.size, "the open has to have succeeded for this to be the window")

        job.cancel()
        io.runNext()
        job.join()

        assertEquals(0, vault.importCalls, "the hand-off must not have completed, or this is a different window")
        assertTrue(picked.opened.single().closed, "a source the vault never received is still ours to close")
    }

    /**
     * Asking the file about itself is a provider query, so the fallback asks from the import thread.
     *
     * A batch resolves these up front and hands them in, which is why no caller in the app takes
     * this path today. The default is still the one a *new* caller gets for free, and on Android each
     * of these three reads is a blocking binder round-trip into the provider that owns the file —
     * which a cloud provider may take its time over. Left on the caller's thread, the default would
     * quietly re-arm the stall that resolving them up front was done to remove.
     */
    @Test
    fun the_details_fallback_is_read_on_the_import_thread() = runTest {
        val io = ManualDispatcher()
        val picked = RecordingPickedFile(onImportDispatcher = { io.isRunning })
        val importer = ImportFile(vault = FakeVaultItems(), ioContext = io)

        // `StandardTestDispatcher`, emphatically not unconfined: an unconfined coroutine resumes
        // *inline*, so a read made on this side would run on the stack of whoever delivered the
        // resumption — which is the manual dispatcher — and `isRunning` would report true for a read
        // that never went near the import thread. A queued dispatcher keeps the two apart, and
        // nothing of this test survives without it.
        //
        // No `facts` argument: the path a caller that has no snapshot of its own takes.
        val job = launch(StandardTestDispatcher(testScheduler)) { importer(picked) }
        // Each side hands work to the other, so they are driven alternately. `runCurrent`, not
        // `advanceUntilIdle`: the wait for the open re-arms a timeout every interval, so advancing
        // until idle never finds an idle moment and simply spins. Nothing here needs the clock to
        // move — the open completes long before its first timeout — and a bounded number of turns
        // cannot hang, it can only fail the assertion below.
        repeat(6) {
            testScheduler.runCurrent()
            io.drain()
        }
        testScheduler.runCurrent()

        assertTrue(job.isCompleted, "the import has to have finished for the reads to all be in")
        assertTrue(picked.columnReads > 0, "the details have to have been read for this to prove anything")
        assertEquals(0, picked.columnReadsElsewhere, "every provider query belongs on the import thread")
    }

    /**
     * And if asking throws, the source already opened is still released.
     *
     * The ownership flag has to be raised *after* the details are resolved, not before: these reads
     * are provider calls and can fail on their own, and a failure between raising the flag and
     * calling the vault would leave the descriptor with nobody responsible for it — the same hole as
     * a cancelled hand-off, reached by a different route.
     */
    @Test
    fun a_source_is_closed_when_reading_the_details_fails() = runTest {
        val picked = DetailsThrowingPickedFile()
        val vault = FakeVaultItems()
        val importer = ImportFile(vault = vault, ioContext = UnconfinedTestDispatcher(testScheduler))

        val result = importer(picked)

        assertIs<ImportResult.Failed>(result)
        assertEquals(0, vault.importCalls, "the vault was never reached, so it closed nothing")
        assertTrue(picked.opened.single().closed, "the descriptor is still ours to release")
    }

    @Test
    fun an_open_that_fails_is_reported_as_the_same_io_failure_as_before() = runTest {
        val picked = UnopenablePickedFile()
        val vault = FakeVaultItems()

        val result = importFile(vault)(picked)

        assertIs<ImportResult.Failed>(result)
        // The vault used to perform this open and report Io. Moving it out here must not change what
        // the user is shown for a provider that cannot produce a stream.
        assertIs<VaultError.Io>(result.error, "a provider that will not open is an Io failure")
        assertEquals(0, vault.importCalls, "there is nothing to import without a source")
        assertTrue(picked.closed, "the grant is released even when the open failed")
    }

    /**
     * A vault error raised while the caller no longer wants the file is the stop arriving mid-encrypt
     * — the guarded source throws out of a read and the vault reports the only thing it can see, a
     * failure. Deciding which it was belongs here, where the stop flag is.
     */
    @Test
    fun a_vault_error_after_a_stop_is_reported_as_stopped_rather_than_failed() = runTest {
        val picked = FakePickedFile()
        val vault = FakeVaultItems(importResult = Outcome.Err(VaultError.Io(RuntimeException("aborted"))))

        val result = importFile(vault)(picked, isActive = { false })

        assertIs<ImportResult.Stopped>(result, "a stop is not a broken file")
    }
}

/** A provider that cannot produce a stream at all — a document that moved, a grant that expired. */
private class UnopenablePickedFile : dev.ehsanfahad.darkchamber.feature.vault.api.PickedFile {
    override val name: String = "gone.pdf"
    override val mime: String = "application/pdf"
    override val size: Long? = null

    var closed = false
        private set

    override fun openSource(): RawSource = throw kotlinx.io.IOException("the provider returned no stream")

    override fun close() {
        closed = true
    }
}

package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.BlockingPickedFile
import dev.ehsanfahad.darkchamber.feature.vault.CancellingVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.FakePickedFile
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.ThrowingVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.awaitTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.RawSource
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ImportFileTest {

    private fun importFile(vault: VaultItems) =
        ImportFile(vault = vault, ioContext = UnconfinedTestDispatcher())

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
     * The finding this whole cycle exists for.
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

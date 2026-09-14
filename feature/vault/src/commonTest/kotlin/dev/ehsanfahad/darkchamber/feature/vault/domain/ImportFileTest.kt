package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.FakePickedFile
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.ThrowingVaultItems
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.RawSource
import kotlin.coroutines.cancellation.CancellationException
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
}

/** A vault seam that behaves like an import interrupted by a lock cancelling the scope. */
private class CancellingVaultItems : VaultItems {
    override fun observe(albumId: ByteArray?) = throw UnsupportedOperationException("not used")

    override fun import(name: String, mime: String, openSource: () -> RawSource) =
        throw CancellationException("the session was torn down mid-import")
}

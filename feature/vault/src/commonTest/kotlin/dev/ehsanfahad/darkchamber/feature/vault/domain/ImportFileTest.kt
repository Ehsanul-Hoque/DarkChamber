package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.FakeFilePicker
import dev.ehsanfahad.darkchamber.feature.vault.FakePickedFile
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.ThrowingVaultItems
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ImportFileTest {

    private fun importFile(picker: FakeFilePicker, vault: VaultItems) =
        ImportFile(picker = picker, vault = vault, ioContext = UnconfinedTestDispatcher())

    @Test
    fun a_picked_file_is_imported_under_its_own_name_and_mime() = runTest {
        val picked = FakePickedFile(name = "notes.pdf", mime = "application/pdf")
        val vault = FakeVaultItems()

        val result = importFile(FakeFilePicker(picked), vault)()

        assertIs<ImportResult.Imported>(result)
        assertEquals(1, vault.importCalls)
        assertEquals("notes.pdf", vault.lastName)
        assertEquals("application/pdf", vault.lastMime)
        assertTrue(picked.opens > 0, "the importer is handed a working opener")
        assertTrue(picked.closed, "the platform grant is released")
    }

    /** Changing your mind is an ordinary outcome, not a failure banner. */
    @Test
    fun cancelling_the_picker_imports_nothing_and_is_not_an_error() = runTest {
        val vault = FakeVaultItems()

        val result = importFile(FakeFilePicker(result = null), vault)()

        assertIs<ImportResult.Cancelled>(result)
        assertEquals(0, vault.importCalls, "nothing is imported on cancel")
    }

    @Test
    fun a_failed_import_carries_the_precise_error_and_still_releases_the_grant() = runTest {
        val picked = FakePickedFile()
        val vault = FakeVaultItems(importResult = Outcome.Err(VaultError.Crypto.IntegrityFailure))

        val result = importFile(FakeFilePicker(picked), vault)()

        assertIs<ImportResult.Failed>(result)
        assertEquals(VaultError.Crypto.IntegrityFailure, result.error, "the vault's error is not flattened")
        assertTrue(picked.closed, "a failed import must not leak the grant")
    }

    @Test
    fun a_thrown_import_is_mapped_and_still_releases_the_grant() = runTest {
        val picked = FakePickedFile()

        val result = importFile(FakeFilePicker(picked), ThrowingVaultItems())()

        assertIs<ImportResult.Failed>(result)
        assertIs<VaultError.Unexpected>(result.error, "a throw that escaped a lower layer is Unexpected")
        assertTrue(picked.closed, "the grant is released even when the import throws")
    }

    /** A picker that cannot even be shown is a failure, but there is no grant to release. */
    @Test
    fun a_picker_that_fails_to_open_is_a_failure_not_a_crash() = runTest {
        val vault = FakeVaultItems()

        val result = importFile(FakeFilePicker(failure = IllegalStateException("no activity")), vault)()

        assertIs<ImportResult.Failed>(result)
        assertIs<VaultError.Unexpected>(result.error)
        assertEquals(0, vault.importCalls)
    }
}

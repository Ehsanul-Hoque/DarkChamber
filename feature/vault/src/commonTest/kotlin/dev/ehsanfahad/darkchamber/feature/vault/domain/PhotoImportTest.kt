package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.feature.vault.FakePhotoPicker
import dev.ehsanfahad.darkchamber.feature.vault.FakePickedFile
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The photo picker is a **second seam**, not a mode on the file picker — but it deliberately hands
 * back the same `PickedFile`, so both pickers feed one batch. These tests pin that: nothing about
 * the queue, the import or the progress rules may learn which picker a file came from.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoImportTest {

    private fun queue(vault: FakeVaultItems) =
        ImportQueue(ImportFile(vault = vault, ioContext = UnconfinedTestDispatcher()))

    @Test
    fun photos_import_through_the_same_queue_as_files() = runTest {
        val vault = FakeVaultItems()
        val picker = FakePhotoPicker(
            listOf(FakePickedFile(name = "IMG_0001.HEIC", mime = "image/heic"), FakePickedFile(name = "IMG_0002.HEIC")),
        )

        val summary = queue(vault)(picker.pickPhotos())

        assertEquals(2, summary.imported)
        assertEquals(listOf("IMG_0001.HEIC", "IMG_0002.HEIC"), vault.importedNames)
        assertEquals(1, picker.calls)
    }

    @Test
    fun an_empty_photo_pick_is_a_cancel_and_imports_nothing() = runTest {
        val vault = FakeVaultItems()
        val picker = FakePhotoPicker(emptyList())

        val summary = queue(vault)(picker.pickPhotos())

        assertEquals(0, summary.imported)
        assertEquals(emptyList(), summary.failed)
        assertEquals(0, vault.importCalls)
    }

    /**
     * A photo the platform will not size is the everyday case, not the exception: a picked library
     * asset often reports nothing until it is materialised. It must import with an indeterminate
     * fraction rather than be refused or given an invented total (hard rule #6).
     */
    @Test
    fun a_photo_with_no_size_imports_with_no_fraction() = runTest {
        val vault = FakeVaultItems(bytesPerImport = listOf(3L, 6L))
        val picker = FakePhotoPicker(listOf(FakePickedFile(name = "IMG_0003.HEIC", size = null)))
        val seen = mutableListOf<ImportProgress>()

        val summary = queue(vault)(picker.pickPhotos()) { seen += it }

        assertEquals(1, summary.imported)
        assertTrue(seen.isNotEmpty(), "the file is still announced")
        assertTrue(seen.all { it.fileFraction == null }, "no fraction without a size, got $seen")
    }
}

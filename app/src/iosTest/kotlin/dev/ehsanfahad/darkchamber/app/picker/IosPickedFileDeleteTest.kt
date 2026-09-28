package dev.ehsanfahad.darkchamber.app.picker

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * iOS never offers to delete an original, and this pins that it stays that way.
 *
 * It is a product decision rather than a gap: `PHPickerViewController` was chosen *because* it needs
 * no photo-library authorization, and deleting from the library would need exactly that. Anyone
 * later wiring up a `PHPhotoLibrary` change request has to come through here and decide it
 * deliberately, rather than discovering that the seam had been quietly promising something.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPickedFileDeleteTest {

    private lateinit var workDir: Path

    @BeforeTest
    fun setUp() {
        workDir = Path(SystemTemporaryDirectory, "picked-delete-${NSUUID().UUIDString}")
        SystemFileSystem.createDirectories(workDir)
    }

    @AfterTest
    fun tearDown() {
        if (SystemFileSystem.exists(workDir)) deleteRecursively(workDir)
    }

    @Test
    fun a_picked_file_never_offers_to_delete_the_original() {
        assertFalse(pickedFile(fileWithBytes()).deletable)
    }

    @Test
    fun asking_anyway_reports_nothing_happened_and_leaves_the_file_alone() {
        val path = fileWithBytes()

        val deleted = pickedFile(path).deleteOriginal()

        assertFalse(deleted, "reporting a deletion that did not happen is the worse failure")
        assertTrue(SystemFileSystem.exists(path), "and the user's file is still where they left it")
    }

    /** A real file on disk, so "nothing was deleted" is checkable rather than merely returned. */
    private fun fileWithBytes(): Path =
        Path(workDir, "holiday.jpg").also { path ->
            SystemFileSystem.sink(path).buffered().use { it.writeString("some photo bytes") }
        }

    private fun pickedFile(path: Path): IosPickedFile =
        IosPickedFile(NSURL.fileURLWithPath(path.toString()), path.toString(), scoped = false)

    private fun deleteRecursively(path: Path) {
        val metadata = SystemFileSystem.metadataOrNull(path) ?: return
        if (metadata.isDirectory) SystemFileSystem.list(path).forEach(::deleteRecursively)
        SystemFileSystem.delete(path, mustExist = false)
    }
}

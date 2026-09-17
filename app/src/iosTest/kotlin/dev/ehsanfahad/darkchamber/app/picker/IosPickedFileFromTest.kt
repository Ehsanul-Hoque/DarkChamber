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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The copy branch of the photo picker: what happens when `PHPickerViewController` can only hand back
 * a *copy* of an asset (an iCloud original it had to download) rather than the file in place.
 *
 * The thing under test is **where that copy lands**. Hard rule #1 allows plaintext in app-private
 * `tmp/` because that directory is wiped on lock and on launch; a copy anywhere else is plaintext
 * with no wipe behind it.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPickedFileFromTest {

    private lateinit var workDir: Path
    private lateinit var scratchDir: Path

    @BeforeTest
    fun setUp() {
        workDir = Path(SystemTemporaryDirectory, "picked-${NSUUID().UUIDString}")
        scratchDir = Path(workDir, "vault-tmp")
        SystemFileSystem.createDirectories(workDir)
    }

    @AfterTest
    fun tearDown() {
        if (SystemFileSystem.exists(workDir)) deleteRecursively(workDir)
    }

    @Test
    fun a_copied_asset_is_moved_into_the_wiped_scratch_directory() {
        val source = systemHandedFile("holiday.jpg", "some photo bytes")

        val picked = pickedFileFrom(source, isInPlace = false, scratchDirectory = scratchDir)

        assertNotNull(picked)
        val landed = Path(picked.path)
        assertEquals(
            scratchDir.toString(),
            landed.parent?.toString(),
            "the copy has to live where purgeTmp() will find it, not in the system's own tmp",
        )
        assertTrue(SystemFileSystem.exists(landed))
        assertFalse(
            SystemFileSystem.exists(Path(source.path!!)),
            "moved, not copied — one traversal, and nothing left in the system's directory",
        )
        assertEquals("holiday.jpg", picked.name, "the user's own name for the file survives the move")

        picked.close()
        assertTrue(!SystemFileSystem.exists(landed), "close() deletes the scratch copy")
    }

    @Test
    fun a_copy_with_nowhere_wiped_to_land_is_skipped_rather_than_left_unwiped() {
        val source = systemHandedFile("holiday.jpg", "some photo bytes")

        val picked = pickedFileFrom(source, isInPlace = false, scratchDirectory = null)

        assertNull(
            picked,
            "with no purged directory to hold it, losing one photo beats leaving its plaintext behind",
        )
    }

    @Test
    fun an_asset_read_in_place_is_never_copied_anywhere() {
        val source = systemHandedFile("holiday.jpg", "some photo bytes")

        val picked = pickedFileFrom(source, isInPlace = true, scratchDirectory = scratchDir)

        assertNotNull(picked)
        assertEquals(source.path, picked.path, "read where it lies — nothing enters our container")
        assertFalse(
            SystemFileSystem.exists(scratchDir) && SystemFileSystem.list(scratchDir).isNotEmpty(),
            "the in-place path must not write into tmp/ at all",
        )
        picked.close()
        assertTrue(SystemFileSystem.exists(Path(source.path!!)), "close() must not delete the user's original")
    }

    /** Stands in for the URL the system hands our completion handler. */
    private fun systemHandedFile(name: String, contents: String): NSURL {
        val path = Path(workDir, name)
        SystemFileSystem.sink(path).buffered().use { it.writeString(contents) }
        return NSURL.fileURLWithPath(path.toString())
    }

    private fun deleteRecursively(path: Path) {
        val metadata = SystemFileSystem.metadataOrNull(path) ?: return
        if (metadata.isDirectory) SystemFileSystem.list(path).forEach(::deleteRecursively)
        SystemFileSystem.delete(path, mustExist = false)
    }
}

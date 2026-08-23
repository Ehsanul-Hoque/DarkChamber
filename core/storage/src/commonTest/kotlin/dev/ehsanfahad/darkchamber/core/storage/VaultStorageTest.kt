package dev.ehsanfahad.darkchamber.core.storage

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Exercises the real file layer against a fresh temp root (D1) — runs on the JVM host
 * (`testAndroidHostTest`) and iOS sim (`iosSimulatorArm64Test`) alike, no fakes.
 */
class VaultStorageTest {
    private val roots = mutableListOf<Path>()

    private fun freshRoot(): Path {
        val root = Path(SystemTemporaryDirectory, "dc-storage-test-${Random.nextULong()}")
        roots += root
        return root
    }

    @AfterTest
    fun cleanup() {
        for (root in roots) deleteRecursively(root)
    }

    private fun deleteRecursively(path: Path) {
        val md = SystemFileSystem.metadataOrNull(path) ?: return
        if (md.isDirectory) {
            for (child in SystemFileSystem.list(path)) deleteRecursively(child)
        }
        SystemFileSystem.delete(path, mustExist = false)
    }

    private fun isDir(path: Path): Boolean =
        SystemFileSystem.metadataOrNull(path)?.isDirectory == true

    @Test
    fun construction_creates_the_four_layout_dirs() {
        val root = freshRoot()

        VaultStorage(root)

        assertTrue(isDir(Path(root, "objects")), "objects/ created")
        assertTrue(isDir(Path(root, "thumbs")), "thumbs/ created")
        assertTrue(isDir(Path(root, "keys")), "keys/ created")
        assertTrue(isDir(Path(root, "tmp")), "tmp/ created")
    }

    @Test
    fun construction_is_idempotent_on_the_same_root() {
        val root = freshRoot()

        VaultStorage(root)
        // A second construction over an existing layout must not throw.
        VaultStorage(root)

        assertTrue(isDir(Path(root, "objects")))
        assertTrue(isDir(Path(root, "thumbs")))
        assertTrue(isDir(Path(root, "keys")))
        assertTrue(isDir(Path(root, "tmp")))
    }
}

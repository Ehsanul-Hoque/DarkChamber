package dev.ehsanfahad.darkchamber.core.storage

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

    // --- Object store (TASK-002) ---

    @Test
    fun writeObject_then_readObject_round_trips_at_the_sharded_path() {
        val root = freshRoot()
        val storage = VaultStorage(root)
        val id = randomId()
        val payload = Random.nextBytes(4096)

        assertIs<Outcome.Ok<Unit>>(storage.putObject(id, payload))

        // Lands at objects/ab/cd/<hex>.dchm (D7).
        val hex = id.toHexString()
        val onDisk = Path(root, "objects", hex.substring(0, 2), hex.substring(2, 4), "$hex.dchm")
        assertTrue(isRegularFile(onDisk), "object at sharded path")

        val read = storage.getObject(id)
        assertIs<Outcome.Ok<ByteArray>>(read)
        assertContentEquals(payload, read.value)
    }

    @Test
    fun writeObject_that_throws_leaves_no_object_and_no_stray_tmp() {
        val root = freshRoot()
        val storage = VaultStorage(root)
        val id = randomId()

        val result = storage.writeObject(id) { sink ->
            sink.buffered().use { it.write(Random.nextBytes(16)) }
            throw IllegalStateException("boom")
        }

        assertIs<Outcome.Err>(result)
        assertFalse(storage.objectExists(id), "no final object")
        val hex = id.toHexString()
        val shardDir = Path(root, "objects", hex.substring(0, 2), hex.substring(2, 4))
        // No stray .tmp under the shard dir.
        val stray = SystemFileSystem.metadataOrNull(shardDir)
            ?.let { SystemFileSystem.list(shardDir) }
            ?.any { it.name.endsWith(".tmp") } ?: false
        assertFalse(stray, "no stray .tmp")
    }

    @Test
    fun failed_overwrite_leaves_the_prior_object_intact() {
        val root = freshRoot()
        val storage = VaultStorage(root)
        val id = randomId()
        val original = Random.nextBytes(1024)
        assertIs<Outcome.Ok<Unit>>(storage.putObject(id, original))

        val result = storage.writeObject(id) { throw IllegalStateException("boom") }
        assertIs<Outcome.Err>(result)

        val read = storage.getObject(id)
        assertIs<Outcome.Ok<ByteArray>>(read)
        assertContentEquals(original, read.value, "prior object untouched")
    }

    @Test
    fun deleteObject_removes_and_is_idempotent() {
        val root = freshRoot()
        val storage = VaultStorage(root)
        val id = randomId()
        storage.putObject(id, Random.nextBytes(32))

        assertTrue(storage.objectExists(id))
        assertIs<Outcome.Ok<Unit>>(storage.deleteObject(id))
        assertFalse(storage.objectExists(id))
        // Idempotent: deleting an absent id is still Ok.
        assertIs<Outcome.Ok<Unit>>(storage.deleteObject(id))
    }

    @Test
    fun reading_a_missing_object_returns_Err_not_a_throw() {
        val root = freshRoot()
        val storage = VaultStorage(root)

        val read = storage.getObject(randomId())
        assertIs<Outcome.Err>(read)
        assertIs<VaultError.Io>(read.error)
    }

    @Test
    fun an_empty_fileId_is_rejected() {
        val root = freshRoot()
        val storage = VaultStorage(root)
        val empty = ByteArray(0)

        assertIs<Outcome.Err>(storage.writeObject(empty) { })
        assertIs<Outcome.Err>(storage.readObject(empty) { it })
        assertFalse(storage.objectExists(empty))
    }

    private fun randomId(): ByteArray = Random.nextBytes(16)

    private fun isRegularFile(path: Path): Boolean =
        SystemFileSystem.metadataOrNull(path)?.isRegularFile == true

    private fun VaultStorage.putObject(id: ByteArray, bytes: ByteArray): Outcome<Unit> =
        writeObject(id) { sink -> sink.buffered().use { it.write(bytes) } }

    private fun VaultStorage.getObject(id: ByteArray): Outcome<ByteArray> =
        readObject(id) { source -> source.buffered().use { it.readByteArray() } }
}

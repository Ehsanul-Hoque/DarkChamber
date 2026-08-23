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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    // --- Thumbnail store (TASK-003) ---

    @Test
    fun writeThumbnail_then_readThumbnail_round_trips_at_the_flat_path() {
        val root = freshRoot()
        val storage = VaultStorage(root)
        val id = randomId()
        val payload = Random.nextBytes(2048)

        assertIs<Outcome.Ok<Unit>>(
            storage.writeThumbnail(id) { sink -> sink.buffered().use { it.write(payload) } },
        )

        // Flat path: thumbs/<hex>.tdchm (no shard dirs).
        val hex = id.toHexString()
        assertTrue(isRegularFile(Path(root, "thumbs", "$hex.tdchm")), "thumbnail at flat path")

        val read = storage.readThumbnail(id) { source -> source.buffered().use { it.readByteArray() } }
        assertIs<Outcome.Ok<ByteArray>>(read)
        assertContentEquals(payload, read.value)
    }

    @Test
    fun thumbnail_delete_exists_and_atomic_throw_behave() {
        val root = freshRoot()
        val storage = VaultStorage(root)
        val id = randomId()

        assertFalse(storage.thumbnailExists(id))
        storage.writeThumbnail(id) { sink -> sink.buffered().use { it.write(Random.nextBytes(64)) } }
        assertTrue(storage.thumbnailExists(id))

        // A throwing write leaves no stray .tmp under thumbs/ and the store unchanged.
        val result = storage.writeThumbnail(randomId()) { throw IllegalStateException("boom") }
        assertIs<Outcome.Err>(result)
        val stray = SystemFileSystem.list(Path(root, "thumbs")).any { it.name.endsWith(".tmp") }
        assertFalse(stray, "no stray .tmp")

        assertIs<Outcome.Ok<Unit>>(storage.deleteThumbnail(id))
        assertFalse(storage.thumbnailExists(id))
        // Idempotent.
        assertIs<Outcome.Ok<Unit>>(storage.deleteThumbnail(id))
    }

    // --- Key-blob store (TASK-004) ---

    @Test
    fun readKeyStore_on_a_fresh_root_is_Ok_null() {
        val storage = VaultStorage(freshRoot())

        val read = storage.readKeyStore()
        assertIs<Outcome.Ok<ByteArray?>>(read)
        assertNull(read.value, "no vault yet -> null")
    }

    @Test
    fun writeKeyStore_then_readKeyStore_round_trips_and_overwrites_atomically() {
        val root = freshRoot()
        val storage = VaultStorage(root)
        val v1 = Random.nextBytes(96)
        val v2 = Random.nextBytes(128)

        assertIs<Outcome.Ok<Unit>>(storage.writeKeyStore(v1))
        val first = storage.readKeyStore()
        assertIs<Outcome.Ok<ByteArray?>>(first)
        assertContentEquals(v1, first.value)

        // Overwrite: the second write fully replaces the first, leaving no stray .tmp.
        assertIs<Outcome.Ok<Unit>>(storage.writeKeyStore(v2))
        val second = storage.readKeyStore()
        assertIs<Outcome.Ok<ByteArray?>>(second)
        assertContentEquals(v2, second.value)
        val stray = SystemFileSystem.list(Path(root, "keys")).any { it.name.endsWith(".tmp") }
        assertFalse(stray, "no stray .tmp")
    }

    @Test
    fun readKeyStore_surfaces_a_read_error_as_Err_not_a_throw() {
        val root = freshRoot()
        val storage = VaultStorage(root)
        // Force a read error: make keys/mk.wrapped a directory, so opening it as a source fails.
        SystemFileSystem.createDirectories(Path(Path(root, "keys"), "mk.wrapped"))

        val read = storage.readKeyStore()
        assertIs<Outcome.Err>(read)
        assertIs<VaultError.Io>(read.error)
    }

    // --- Tmp scratch (TASK-005) ---

    @Test
    fun useTempFile_deletes_on_normal_return() {
        val storage = VaultStorage(freshRoot())
        var captured: Path? = null

        val result = storage.useTempFile { path ->
            captured = path
            SystemFileSystem.sink(path).buffered().use { it.write(Random.nextBytes(8)) }
            assertTrue(exists(path), "scratch present inside the scope")
            "done"
        }

        assertEquals("done", result)
        assertFalse(exists(captured!!), "scratch deleted after normal return")
    }

    @Test
    fun useTempFile_deletes_when_the_block_throws() {
        val storage = VaultStorage(freshRoot())
        var captured: Path? = null

        assertFailsWith<IllegalStateException> {
            storage.useTempFile { path ->
                captured = path
                SystemFileSystem.sink(path).buffered().use { it.write(Random.nextBytes(8)) }
                throw IllegalStateException("boom")
            }
        }

        assertFalse(exists(captured!!), "scratch deleted even when the block throws")
    }

    @Test
    fun purgeTmp_clears_only_tmp_and_is_ok_when_empty() {
        val root = freshRoot()
        val storage = VaultStorage(root)

        // Seed the other three areas.
        val objId = randomId()
        val thumbId = randomId()
        storage.writeObject(objId) { sink -> sink.buffered().use { it.write(Random.nextBytes(16)) } }
        storage.writeThumbnail(thumbId) { sink -> sink.buffered().use { it.write(Random.nextBytes(16)) } }
        storage.writeKeyStore(Random.nextBytes(32))

        // Empty tmp/ -> Ok.
        assertIs<Outcome.Ok<Unit>>(storage.purgeTmp())

        // Drop a stray file + a stray subdir under tmp/.
        val tmpDir = Path(root, "tmp")
        SystemFileSystem.sink(Path(tmpDir, "stray")).buffered().use { it.write(Random.nextBytes(8)) }
        SystemFileSystem.createDirectories(Path(tmpDir, "sub"))
        SystemFileSystem.sink(Path(Path(tmpDir, "sub"), "nested")).buffered().use { it.write(Random.nextBytes(8)) }

        assertIs<Outcome.Ok<Unit>>(storage.purgeTmp())

        // tmp/ emptied, dir itself kept.
        assertTrue(isDir(tmpDir))
        assertTrue(SystemFileSystem.list(tmpDir).isEmpty(), "tmp/ emptied")

        // The other three areas are untouched.
        assertTrue(storage.objectExists(objId))
        assertTrue(storage.thumbnailExists(thumbId))
        val ks = storage.readKeyStore()
        assertIs<Outcome.Ok<ByteArray?>>(ks)
        assertNotNull(ks.value, "keystore still present")
    }

    private fun randomId(): ByteArray = Random.nextBytes(16)

    private fun isRegularFile(path: Path): Boolean =
        SystemFileSystem.metadataOrNull(path)?.isRegularFile == true

    private fun exists(path: Path): Boolean = SystemFileSystem.exists(path)

    private fun VaultStorage.putObject(id: ByteArray, bytes: ByteArray): Outcome<Unit> =
        writeObject(id) { sink -> sink.buffered().use { it.write(bytes) } }

    private fun VaultStorage.getObject(id: ByteArray): Outcome<ByteArray> =
        readObject(id) { source -> source.buffered().use { it.readByteArray() } }
}

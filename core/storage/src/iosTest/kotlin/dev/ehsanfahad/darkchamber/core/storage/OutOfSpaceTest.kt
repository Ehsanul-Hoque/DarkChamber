package dev.ehsanfahad.darkchamber.core.storage

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import platform.posix.EIO
import platform.posix.ENOSPC
import platform.posix.strerror
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * On Apple platforms kotlinx-io reports a failed native write only as a message — the errno's
 * `strerror` text, in a few shapes. The shapes below are copied from kotlinx-io 0.9.1's own sources
 * (`PathsNative.kt`'s `throwIOExceptionForErrno`, `FileSystemApple.kt`'s mkdir), not from our
 * classifier; re-check them when that dependency moves. The device's own text comes from
 * `strerror`, so no string here is hand-typed.
 */
@OptIn(ExperimentalForeignApi::class)
class OutOfSpaceTest {
    private val noSpace = strerror(ENOSPC)!!.toKString()
    private val ioError = strerror(EIO)!!.toKString()

    private fun shapes(err: Int, text: String) = listOf(
        IOException("file write failed with errno $err ($text)"),
        IOException("fflush failed with errno $err ($text)"),
        IOException("fclose failed with errno $err ($text)"),
        IOException("mkdir failed: $text"),
    )

    @Test
    fun every_shape_of_a_full_disk_is_recognised() {
        for (failure in shapes(ENOSPC, noSpace)) {
            assertTrue(isOutOfSpace(failure), failure.message)
        }
    }

    @Test
    fun a_full_disk_is_recognised_under_a_wrapper() {
        val wrapped = IllegalStateException("encrypt failed", shapes(ENOSPC, noSpace).first())
        assertTrue(isOutOfSpace(wrapped))
    }

    @Test
    fun no_other_failure_is_called_a_full_disk() {
        val others = shapes(EIO, ioError) + listOf(
            IOException("Stream Closed"),
            IOException("Unknown error"),
            IOException(),
            IllegalStateException("closed"),
        )
        for (failure in others) {
            assertFalse(isOutOfSpace(failure), failure.message)
        }
    }

    @Test
    fun a_write_that_ran_out_of_space_is_reported_as_full_and_any_other_as_io() {
        val root = Path(SystemTemporaryDirectory, "dc-nospace-test-${Random.nextULong()}")
        val storage = VaultStorage(root)
        try {
            val full = storage.writeObject(Random.nextBytes(16)) { throw shapes(ENOSPC, noSpace).first() }
            val other = storage.writeObject(Random.nextBytes(16)) { throw shapes(EIO, ioError).first() }

            assertIs<Outcome.Err>(full)
            assertEquals(VaultError.Storage.Full, full.error)
            assertIs<Outcome.Err>(other)
            assertIs<VaultError.Io>(other.error)
        } finally {
            deleteRecursively(root)
        }
    }

    private fun deleteRecursively(path: Path) {
        val md = SystemFileSystem.metadataOrNull(path) ?: return
        if (md.isDirectory) for (child in SystemFileSystem.list(path)) deleteRecursively(child)
        SystemFileSystem.delete(path, mustExist = false)
    }
}

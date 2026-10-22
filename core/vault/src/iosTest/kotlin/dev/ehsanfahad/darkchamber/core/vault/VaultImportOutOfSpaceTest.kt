package dev.ehsanfahad.darkchamber.core.vault

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import platform.posix.EIO
import platform.posix.ENOSPC
import platform.posix.strerror
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A full disk must reach the caller as `Storage.Full` even though it happens **inside the codec**,
 * which reports every exception from its read and write loop as a plain `Io`. Without the
 * reclassification the type `core:storage` learned to produce would never survive the encrypt,
 * which is where nearly every byte of an import is written.
 *
 * The failure is thrown from the payload's stream because that is the one place a test can put it;
 * the codec handles an exception from its sink the same way, in the same `catch`. Apple-only because
 * the failure must be in the platform's own shape — kotlinx-io's `strerror` text — and the Android
 * shape (libcore's `ErrnoException`) cannot be built on the host.
 */
@OptIn(ExperimentalForeignApi::class)
class VaultImportOutOfSpaceTest {

    private class FailingAfter(private val failure: Exception) : RawSource {
        private val bytes = Buffer().apply { write(ByteArray(100) { 1 }) }
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long =
            if (bytes.size > 0) bytes.readAtMostTo(sink, byteCount) else throw failure
        override fun close() = Unit
    }

    private fun AlbumFixture.importFailingWith(failure: Exception): Outcome<ByteArray> =
        objects.importObject(
            kind = VaultItemKind.FILE.code,
            name = "clip.mp4",
            mime = "video/mp4",
            chunkSize = 64,
            openSource = { FailingAfter(failure) },
        )

    private fun errnoFailure(err: Int) =
        IOException("file write failed with errno $err (${strerror(err)!!.toKString()})")

    @Test
    fun a_full_disk_during_the_encrypt_is_reported_as_full_and_stores_nothing() = runTest {
        val fx = AlbumFixture()

        val result = fx.importFailingWith(errnoFailure(ENOSPC))

        assertIs<Outcome.Err>(result)
        assertEquals(VaultError.Storage.Full, result.error)
        assertEquals(0L, (fx.catalog.countItems() as Outcome.Ok).value, "fail-closed as before: no row")
        fx.close()
    }

    @Test
    fun any_other_failure_during_the_encrypt_stays_io() = runTest {
        val fx = AlbumFixture()

        val result = fx.importFailingWith(errnoFailure(EIO))

        assertIs<Outcome.Err>(result)
        assertIs<VaultError.Io>(result.error)
        fx.close()
    }
}

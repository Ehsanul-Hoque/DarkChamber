package dev.ehsanfahad.darkchamber

import android.content.Context
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import kotlinx.io.files.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * A blob's shard directory that cannot be created fails the write with the platform's own errno for
 * the directory — which is how a full disk gets named — and not as the ENOENT of an open beneath a
 * directory that was never made. The JVM's `mkdirs()` reports only a boolean, and kotlinx-io drops
 * it, so this is a device check: a read-only parent stands in for a disk with no room, because
 * filling the disk is not something a test can do.
 *
 * Works in its own directory under the app's cache, never the vault. Run on its own with
 * `am instrument -e class …` — the connected Gradle task uninstalls the app.
 */
@RunWith(AndroidJUnit4::class)
class StorageDirectoryInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = File(context.cacheDir, "storage-dir-test-${Random.nextLong().toULong()}")

    @After
    fun cleanup() {
        root.walkTopDown().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    @Test
    fun a_shard_directory_that_cannot_be_made_fails_with_its_own_errno() {
        val storage = VaultStorage(Path(root.path))
        val objects = File(root, "objects")
        assertTrue(objects.setWritable(false, false))
        var wrote = false

        val result = storage.writeObject(ByteArray(16) { it.toByte() }) { wrote = true }

        val error = (result as Outcome.Err).error
        val errno = ((error as VaultError.Io).cause.causeChain().firstOrNull { it is ErrnoException } as ErrnoException?)?.errno
        assertEquals("the mkdir's own errno, not an open's ENOENT", OsConstants.EACCES, errno)
        assertFalse("nothing is written under a directory that is not there", wrote)
    }

    private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }
}

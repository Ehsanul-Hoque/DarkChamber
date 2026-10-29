package dev.ehsanfahad.darkchamber.core.storage

import dev.ehsanfahad.darkchamber.core.model.Outcome
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.write
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Positioned reads of a stored object, against the real file layer on a fresh temp root — so the
 * JVM actual runs in `testAndroidHostTest` and the POSIX one in `iosSimulatorArm64Test`.
 */
class ObjectReaderTest {
    private val roots = mutableListOf<Path>()

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

    private val id = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D, 0x0E)

    // 0, 1, 2, … 255, 0, 1, … — every byte says where it sits, so a read from the wrong offset shows.
    private fun content(size: Int) = ByteArray(size) { it.toByte() }

    private fun storageHolding(bytes: ByteArray): VaultStorage {
        val root = Path(SystemTemporaryDirectory, "dc-reader-test-${Random.nextULong()}")
        roots += root
        val storage = VaultStorage(root)
        assertIs<Outcome.Ok<Unit>>(storage.writeObject(id) { sink -> sink.buffered().use { it.write(bytes.copyOf()) } })
        return storage
    }

    private fun <T> Outcome<T>.ok(): T = assertIs<Outcome.Ok<T>>(this).value

    @Test
    fun reads_the_bytes_at_a_position_in_the_middle_of_an_object() {
        val bytes = content(1000)
        val reader = storageHolding(bytes).openObjectReader(id).ok()

        val into = ByteArray(10)
        val n = reader.readAt(position = 500, into = into, offset = 0, length = 10).ok()

        assertEquals(10, n)
        assertContentEquals(bytes.copyOfRange(500, 510), into)
        reader.close()
    }

    @Test
    fun size_is_the_stored_length_of_the_object() {
        val reader = storageHolding(content(1234)).openObjectReader(id).ok()

        assertEquals(1234L, reader.size)
        reader.close()
    }

    @Test
    fun a_read_at_or_past_the_end_says_end_rather_than_reading_nothing() {
        val reader = storageHolding(content(100)).openObjectReader(id).ok()
        val into = ByteArray(10)

        assertEquals(-1, reader.readAt(position = 100, into = into, offset = 0, length = 10).ok())
        assertEquals(-1, reader.readAt(position = 5000, into = into, offset = 0, length = 10).ok())
        reader.close()
    }

    @Test
    fun a_read_straddling_the_end_returns_only_the_bytes_that_are_there() {
        val bytes = content(100)
        val reader = storageHolding(bytes).openObjectReader(id).ok()
        val into = ByteArray(10) { 0x55 }

        val n = reader.readAt(position = 96, into = into, offset = 0, length = 10).ok()

        assertEquals(4, n)
        assertContentEquals(bytes.copyOfRange(96, 100), into.copyOfRange(0, 4))
        assertContentEquals(ByteArray(6) { 0x55 }, into.copyOfRange(4, 10)) // nothing written past what was read
        reader.close()
    }

    @Test
    fun reading_from_zero_in_a_loop_returns_the_whole_object_into_the_requested_offset() {
        // Larger than any single platform read is likely to serve, with an odd size so the last read is short.
        val bytes = content(3 * 1024 * 1024 + 17)
        val reader = storageHolding(bytes).openObjectReader(id).ok()
        val into = ByteArray(bytes.size + 5)

        var position = 0L
        while (true) {
            val want = minOf(64 * 1024, into.size - 5 - position.toInt())
            val n = reader.readAt(position, into, offset = 5 + position.toInt(), length = want).ok()
            if (n == -1 || want == 0) break
            position += n
        }

        assertEquals(bytes.size.toLong(), position)
        assertContentEquals(bytes, into.copyOfRange(5, into.size))
        reader.close()
    }

    @Test
    fun a_missing_object_or_an_empty_id_is_an_Err_not_a_throw() {
        val storage = storageHolding(content(10))

        assertIs<Outcome.Err>(storage.openObjectReader(byteArrayOf(0x01, 0x02, 0x03, 0x04)))
        assertIs<Outcome.Err>(storage.openObjectReader(ByteArray(0)))
    }

    @Test
    fun a_read_after_close_is_an_Err_and_never_reads_whatever_file_was_opened_next() {
        // On iOS a closed descriptor's number is handed to the next open, so a read through a
        // closed reader could otherwise return another object's bytes.
        val storage = storageHolding(content(100))
        val other = byteArrayOf(0x0F, 0x0E, 0x0D, 0x0C, 0x0B)
        assertIs<Outcome.Ok<Unit>>(
            storage.writeObject(other) { sink -> sink.buffered().use { it.write(ByteArray(100) { 0x7F }) } },
        )
        val closed = storage.openObjectReader(id).ok()
        closed.close()
        val next = storage.openObjectReader(other).ok()

        val into = ByteArray(10)
        assertIs<Outcome.Err>(closed.readAt(position = 0, into = into, offset = 0, length = 10))
        assertContentEquals(ByteArray(10), into)
        next.close()
    }

    @Test
    fun closing_twice_is_harmless() {
        val reader = storageHolding(content(10)).openObjectReader(id).ok()

        reader.close()
        reader.close()
    }

    // A platform file that counts closes and can fail its reads — what a real file cannot be made to do.
    private class ScriptedFile(override val size: Long, private val failure: Exception? = null) : PlatformFile {
        var closes = 0

        override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int =
            failure?.let { throw it } ?: length

        override fun close() {
            closes++
        }
    }

    @Test
    fun close_releases_the_platform_file_exactly_once() {
        val file = ScriptedFile(size = 10)
        val reader = ObjectReader(file)

        reader.close()
        reader.close()

        assertEquals(1, file.closes)
    }

    @Test
    fun a_failing_platform_read_is_an_Err_not_a_throw() {
        val reader = ObjectReader(ScriptedFile(size = 10, failure = IOException("disk went away")))

        assertIs<Outcome.Err>(reader.readAt(position = 0, into = ByteArray(4), offset = 0, length = 4))
    }
}

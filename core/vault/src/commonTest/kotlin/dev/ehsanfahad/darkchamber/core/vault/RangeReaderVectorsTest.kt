package dev.ehsanfahad.darkchamber.core.vault

import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.crypto.DchmCodec
import dev.ehsanfahad.darkchamber.core.crypto.DchmFormat
import dev.ehsanfahad.darkchamber.core.crypto.DefaultKeyDerivation
import dev.ehsanfahad.darkchamber.core.crypto.PassphraseKdfProfile
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Range reads pinned against the full decrypt: whatever a [RangeReader] returns for a range must be
 * exactly that slice of what [VaultObjects.openObject] decrypts for the whole object — and where the
 * object is damaged, it must say so as the full decrypt does, never as a short read or an end.
 *
 * The committed format vectors' own bytes cannot be stored as vault objects (a session wraps only keys
 * it generated), so their shapes are used here — the same sizes at their chunk size of 16 — and their
 * exact bytes are opened by position at the codec level instead.
 */
class RangeReaderVectorsTest {
    private val testDir = Path(SystemTemporaryDirectory, "rrv-${Random.nextULong()}")
    private val engine = CryptographyKotlinEngine()
    private val cheapKdf = PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1)
    private val session: VaultSession =
        VaultSession.createVault(engine, DefaultKeyDerivation(engine), "correct horse battery".encodeToByteArray(), cheapKdf).session

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    private val storage: VaultStorage = run {
        SystemFileSystem.createDirectories(testDir)
        VaultStorage(testDir)
    }

    private val objects = VaultObjects(
        session,
        storage,
        (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value,
        DchmCodec(engine),
        clock = { 42L },
        random = Random(23).let { rng -> { n -> rng.nextBytes(n) } },
    )

    private fun <T> Outcome<T>.ok(): T = assertIs<Outcome.Ok<T>>(this).value

    private fun content(size: Int) = ByteArray(size) { (it * 13 + 5).toByte() }

    private fun stored(data: ByteArray, chunkSize: Int): ByteArray =
        objects.importObject(kind = 0, name = "n", mime = "application/octet-stream", chunkSize = chunkSize,
            openSource = { Buffer().apply { write(data.copyOf()) } }).ok()

    private fun fullDecrypt(id: ByteArray): ByteArray {
        val out = Buffer()
        objects.openObject(id, out).ok()
        return out.readByteArray()
    }

    @Test
    fun a_read_ending_exactly_at_the_end_returns_the_last_bytes_and_the_next_read_is_END() {
        val data = content(200)
        val reader = objects.openRange(stored(data, chunkSize = 64)).ok()

        val into = ByteArray(8)
        assertEquals(8, reader.readAt(position = 192, into = into, offset = 0, size = 8).ok())
        assertContentEquals(data.copyOfRange(192, 200), into)
        assertEquals(RangeReader.END, reader.readAt(position = 200, into = into, offset = 0, size = 8).ok())
        reader.close()
    }

    // Flips one byte inside chunk [index] of a stored object (its ciphertext, not its tag).
    private fun tamperChunk(id: ByteArray, index: Int, chunkSize: Int) {
        val bytes = storage.readObject(id) { it.buffered().readByteArray() }.ok()
        val headerLen = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
        val at = DchmFormat.PRELUDE_SIZE + headerLen + index * (chunkSize + DchmFormat.GCM_TAG_SIZE) + 3
        bytes[at] = (bytes[at].toInt() xor 0x01).toByte()
        storage.writeObject(id) { sink -> sink.buffered().apply { write(bytes); flush() } }.ok()
    }

    @Test
    fun a_tampered_middle_chunk_fails_any_read_that_touches_it_and_only_those() {
        val data = content(200) // chunks 0..3 at 64; chunk 1 holds 64..127
        val id = stored(data, chunkSize = 64)
        tamperChunk(id, index = 1, chunkSize = 64)
        assertEquals(VaultError.Crypto.IntegrityFailure, assertIs<Outcome.Err>(objects.openObject(id, Buffer())).error)
        val reader = objects.openRange(id).ok() // the open reads only the prelude, header and last chunk

        // Touching chunk 1: tampering, never a short read or an end of object — and still so on a second
        // read, so nothing from the bad chunk was kept.
        repeat(2) {
            val result = reader.readAt(position = 100, into = ByteArray(4), offset = 0, size = 4)
            assertEquals(VaultError.Crypto.IntegrityFailure, assertIs<Outcome.Err>(result).error)
        }
        // A read that crosses into chunk 1 from chunk 0 returns chunk 0's part only; the next one fails.
        val into = ByteArray(10)
        assertEquals(4, reader.readAt(position = 60, into = into, offset = 0, size = 10).ok())
        assertContentEquals(data.copyOfRange(60, 64), into.copyOfRange(0, 4))
        assertEquals(VaultError.Crypto.IntegrityFailure, assertIs<Outcome.Err>(reader.readAt(64, into, 0, 10)).error)
        // Chunks the damage did not reach still read correctly.
        val far = ByteArray(10)
        assertEquals(10, reader.readAt(position = 130, into = far, offset = 0, size = 10).ok())
        assertContentEquals(data.copyOfRange(130, 140), far)
        reader.close()
    }

    @Test
    fun the_chunk_size_comes_from_the_object_not_from_a_default() {
        // 100 is neither the default nor a power of two, so a reader assuming either reads the wrong place.
        val data = content(350)
        val reader = objects.openRange(stored(data, chunkSize = 100)).ok()

        val into = ByteArray(30)
        assertEquals(30, reader.readAt(position = 210, into = into, offset = 0, size = 30).ok())
        assertContentEquals(data.copyOfRange(210, 240), into)
        reader.close()
    }

    @Test
    fun random_reads_return_exactly_the_slices_a_full_decrypt_gives() {
        // The committed vectors' shapes at their chunk size of 16, then larger objects at other sizes.
        val shapes = listOf(11 to 16, 40 to 16, 0 to 16, 32 to 16, 1000 to 64, 1000 to 100, 4097 to 1024)
        val rng = Random(1234)
        for ((size, chunkSize) in shapes) {
            val id = stored(content(size), chunkSize)
            val whole = fullDecrypt(id)
            val reader = objects.openRange(id).ok()
            assertEquals(whole.size.toLong(), reader.length)

            repeat(300) {
                val position = rng.nextLong(0, size + 20L)
                val want = rng.nextInt(1, 2 * chunkSize + 3)
                val into = ByteArray(want + 2)
                val n = reader.readAt(position, into, offset = 2, size = want).ok()

                if (position >= size) {
                    assertEquals(RangeReader.END, n, "size $size @ $position")
                } else {
                    val chunkEnd = (position / chunkSize + 1) * chunkSize
                    val expected = minOf(want.toLong(), chunkEnd - position, size - position).toInt()
                    assertEquals(expected, n, "size $size @ $position for $want")
                    assertContentEquals(whole.copyOfRange(position.toInt(), position.toInt() + n), into.copyOfRange(2, 2 + n))
                }
            }
            reader.close()
        }
    }
}

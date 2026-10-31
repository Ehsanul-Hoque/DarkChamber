package dev.ehsanfahad.darkchamber.core.vault

import dev.ehsanfahad.darkchamber.core.crypto.CryptoEngine
import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.crypto.DchmCodec
import dev.ehsanfahad.darkchamber.core.crypto.DefaultKeyDerivation
import dev.ehsanfahad.darkchamber.core.crypto.PassphraseKdfProfile
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Reading any byte range of a vault object through [VaultObjects.openRange]: real storage on a temp
 * root, a real catalog, a real session. The session's engine counts its `aeadOpen`s — each one is a
 * DEK unwrap, so the count is how many times the reader asked the session for the key.
 */
class RangeReaderTest {
    private val testDir = Path(SystemTemporaryDirectory, "rrt-${Random.nextULong()}")
    private val engine = CryptographyKotlinEngine()
    private val cheapKdf = PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1)

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    private class CountingEngine(private val real: CryptoEngine) : CryptoEngine by real {
        var opens = 0

        override fun aeadOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): Outcome<ByteArray> {
            opens++
            return real.aeadOpen(key, nonce, aad, ciphertext)
        }
    }

    private val sessionEngine = CountingEngine(engine)
    private val session: VaultSession =
        VaultSession.createVault(sessionEngine, DefaultKeyDerivation(engine), "correct horse battery".encodeToByteArray(), cheapKdf).session

    private val catalog: CatalogStore = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value

    private val objects: VaultObjects = run {
        SystemFileSystem.createDirectories(testDir)
        VaultObjects(
            session,
            VaultStorage(testDir),
            catalog,
            DchmCodec(engine),
            clock = { 42L },
            random = Random(11).let { rng -> { n -> rng.nextBytes(n) } },
        )
    }

    private fun <T> Outcome<T>.ok(): T = assertIs<Outcome.Ok<T>>(this).value

    private fun dekWrappedOf(id: ByteArray): ByteArray = catalog.getItem(id).ok()!!.dek_wrapped

    // Every byte says where it sits, so a read from the wrong place shows.
    private fun content(size: Int) = ByteArray(size) { (it * 7 + 3).toByte() }

    private fun stored(data: ByteArray, chunkSize: Int = 64): ByteArray =
        objects.importObject(kind = 0, name = "n", mime = "application/octet-stream", chunkSize = chunkSize,
            openSource = { Buffer().apply { write(data.copyOf()) } }).ok()

    @Test
    fun reads_a_range_inside_one_chunk_and_reports_the_plaintext_length() {
        val data = content(200) // chunks of 64, 64, 64, 8
        val reader = objects.openRange(stored(data)).ok()

        val into = ByteArray(10)
        val n = reader.readAt(position = 70, into = into, offset = 0, size = 10).ok()

        assertEquals(200L, reader.length)
        assertEquals(10, n)
        assertContentEquals(data.copyOfRange(70, 80), into)
        reader.close()
    }

    @Test
    fun a_read_across_a_chunk_boundary_stops_at_it_and_a_loop_rebuilds_the_whole_object() {
        val data = content(200)
        val reader = objects.openRange(stored(data)).ok()

        val into = ByteArray(20)
        assertEquals(4, reader.readAt(position = 60, into = into, offset = 0, size = 20).ok()) // 60..63, then the boundary
        assertContentEquals(data.copyOfRange(60, 64), into.copyOfRange(0, 4))

        val whole = ByteArray(200 + 3)
        var position = 0L
        while (true) {
            val offset = 3 + position.toInt()
            val n = reader.readAt(position, whole, offset, size = minOf(50, whole.size - offset)).ok()
            if (n == RangeReader.END) break
            position += n
        }
        assertEquals(200L, position)
        assertContentEquals(data, whole.copyOfRange(3, whole.size))
        reader.close()
    }

    @Test
    fun a_read_at_or_past_the_end_is_END() {
        val reader = objects.openRange(stored(content(200))).ok()

        assertEquals(RangeReader.END, reader.readAt(position = 200, into = ByteArray(4), offset = 0, size = 4).ok())
        assertEquals(RangeReader.END, reader.readAt(position = 10_000, into = ByteArray(4), offset = 0, size = 4).ok())
        reader.close()
    }

    @Test
    fun a_read_outside_its_own_arguments_is_an_Err_not_a_throw_and_decrypts_nothing() {
        // Only a caller's bug passes these, and the contract is that nothing throws: each is an Err,
        // and none costs a key unwrap. Position 70 is in chunk 1, which the open has not decrypted.
        val reader = objects.openRange(stored(content(200))).ok()
        val into = ByteArray(4)

        for ((position, offset, size) in listOf(
            Triple(-1L, 0, 1),
            Triple(70L, -1, 1),
            Triple(70L, 0, -1),
            Triple(70L, 2, 4),
        )) {
            val unwraps = unwrapsDuring {
                val error = assertIs<Outcome.Err>(reader.readAt(position, into, offset, size)).error
                assertIs<IllegalArgumentException>(assertIs<VaultError.Io>(error).cause)
            }
            assertEquals(0, unwraps, "position=$position offset=$offset size=$size")
        }
        assertEquals(1, reader.readByteAt(70)) // the reader still works after them
        reader.close()
    }

    @Test
    fun an_empty_object_has_no_length_and_its_first_read_is_END() {
        val reader = objects.openRange(stored(ByteArray(0))).ok()

        assertEquals(0L, reader.length)
        assertEquals(RangeReader.END, reader.readAt(position = 0, into = ByteArray(4), offset = 0, size = 4).ok())
        reader.close()
    }

    // How many times the reader asked the session for the key while [block] ran.
    private fun unwrapsDuring(block: () -> Unit): Int {
        val before = sessionEngine.opens
        block()
        return sessionEngine.opens - before
    }

    private fun RangeReader.readByteAt(position: Long) = readAt(position, ByteArray(1), 0, 1).ok()

    @Test
    fun the_key_is_unwrapped_once_at_open_and_the_last_chunk_comes_from_that_open() {
        val id = stored(content(200)) // chunks 0..3, the last holding 192..199
        lateinit var reader: RangeReader

        assertEquals(1, unwrapsDuring { reader = objects.openRange(id).ok() })
        assertEquals(0, unwrapsDuring { reader.readByteAt(195) })
        reader.close()
    }

    @Test
    fun the_key_is_unwrapped_once_per_chunk_decrypted_and_not_for_a_chunk_already_held() {
        val reader = objects.openRange(stored(content(200))).ok()

        assertEquals(1, unwrapsDuring { reader.readByteAt(10) }) // chunk 0
        assertEquals(0, unwrapsDuring { reader.readByteAt(50) }) // chunk 0 again
        reader.close()
    }

    @Test
    fun at_most_two_chunks_are_held_and_the_least_recently_used_goes_first() {
        val reader = objects.openRange(stored(content(200))).ok() // holds chunk 3 from the open

        assertEquals(1, unwrapsDuring { reader.readByteAt(0) }) // holds 3, 0
        assertEquals(1, unwrapsDuring { reader.readByteAt(64) }) // holds 0, 1 — chunk 3 dropped
        assertEquals(0, unwrapsDuring { reader.readByteAt(1) }) // chunk 0 still held, now most recent
        assertEquals(1, unwrapsDuring { reader.readByteAt(195) }) // chunk 3 again: decrypted again; drops 1
        assertEquals(0, unwrapsDuring { reader.readByteAt(2) }) // chunk 0 survived, being the more recent
        reader.close()
    }

    private fun ByteArray.isZeroed() = all { it == 0.toByte() }

    @Test
    fun a_chunk_dropped_from_the_two_held_is_zeroed() {
        val reader = objects.openRange(stored(content(200))).ok()
        reader.readByteAt(0) // holds 3, 0
        val chunk3 = (reader as ChunkRangeReader).heldChunks().first()

        reader.readByteAt(64) // holds 0, 1 — chunk 3 dropped

        assertTrue(chunk3.isZeroed())
        reader.close()
    }

    @Test
    fun close_zeroes_every_held_chunk_and_later_reads_are_refused() {
        val reader = objects.openRange(stored(content(200))).ok()
        reader.readByteAt(0)
        val held = (reader as ChunkRangeReader).heldChunks()
        assertEquals(2, held.size)

        reader.close()
        reader.close() // and a second close is harmless

        assertTrue(held.all { it.isZeroed() })
        assertIs<Outcome.Err>(reader.readAt(position = 0, into = ByteArray(1), offset = 0, size = 1))
    }

    @Test
    fun a_read_after_the_session_locks_is_refused_and_releases_what_was_held() {
        val reader = objects.openRange(stored(content(200))).ok()
        reader.readByteAt(0)
        val held = (reader as ChunkRangeReader).heldChunks()

        session.close()
        // Chunk 0 is still held: a lock must stop the read even when no decrypt is needed.
        val result = reader.readAt(position = 0, into = ByteArray(1), offset = 0, size = 1)

        assertIs<OperationStoppedException>(assertIs<VaultError.Io>(assertIs<Outcome.Err>(result).error).cause)
        assertTrue(held.all { it.isZeroed() })
        reader.close()
    }

    @Test
    fun a_read_after_the_work_is_cancelled_is_refused_and_releases_what_was_held() {
        val id = stored(content(200))
        var wanted = true
        val reader = objects.openRangeOver(id, dekWrappedOf(id), stillWanted = { wanted }).ok()
        reader.readByteAt(0)
        val held = (reader as ChunkRangeReader).heldChunks()

        wanted = false
        val result = reader.readAt(position = 0, into = ByteArray(1), offset = 0, size = 1)

        assertIs<OperationStoppedException>(assertIs<VaultError.Io>(assertIs<Outcome.Err>(result).error).cause)
        assertTrue(held.all { it.isZeroed() })
        assertTrue(session.isOpen) // stopping one reader does not touch the session
        reader.close()
    }

    @Test
    fun a_lock_landing_between_the_check_and_the_key_is_an_Err_not_a_throw() {
        // The predicate says yes, the session is already closed: useDek throws, and the reader must not.
        val id = stored(content(200))
        val reader = objects.openRangeOver(id, dekWrappedOf(id), stillWanted = { true }).ok()

        session.close()
        val result = reader.readAt(position = 0, into = ByteArray(1), offset = 0, size = 1) // chunk 0 is not held

        assertIs<Outcome.Err>(result)
        reader.close()
    }

    @Test
    fun opening_after_the_session_locks_is_an_Err_not_a_throw() {
        val id = stored(content(200))
        session.close()

        val result = objects.openRange(id)

        assertIs<OperationStoppedException>(assertIs<VaultError.Io>(assertIs<Outcome.Err>(result).error).cause)
    }

    @Test
    fun a_file_cut_short_after_the_open_fails_the_read_rather_than_spinning() {
        // The size is taken at open. A file that loses its tail afterwards answers a read below that
        // size with -1 on the JVM and with 0 from `pread`; either way the read must fail, not loop.
        val id = stored(content(200)) // chunks of 64: 0..3
        val reader = objects.openRange(id).ok()
        val file = storedObjectFile()
        val bytes = SystemFileSystem.source(file).buffered().use { it.readByteArray() }
        val headerLen = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
        SystemFileSystem.sink(file).buffered().use { it.write(bytes, 0, 16 + headerLen + 64 + 16) } // chunk 0 only

        assertIs<VaultError.Io>(assertIs<Outcome.Err>(reader.readAt(position = 64, into = ByteArray(1), offset = 0, size = 1)).error)
        reader.close()
    }

    // The one object file in this test's vault.
    private fun storedObjectFile(): Path {
        fun walk(dir: Path): List<Path> = SystemFileSystem.list(dir).flatMap { p ->
            if (SystemFileSystem.metadataOrNull(p)?.isDirectory == true) walk(p) else listOf(p)
        }
        return walk(Path(testDir, "objects")).single { it.name.endsWith(".dchm") }
    }

    @Test
    fun an_unknown_id_is_an_Err() {
        assertIs<Outcome.Err>(objects.openRange(ByteArray(16) { 0x33 }))
    }
}

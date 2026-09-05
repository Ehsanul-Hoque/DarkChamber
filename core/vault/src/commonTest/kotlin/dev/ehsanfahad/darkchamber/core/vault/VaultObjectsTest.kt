package dev.ehsanfahad.darkchamber.core.vault

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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The streaming import → persisted-DEK → stream-decrypt round-trip, against a temp-dir
 * [VaultStorage] + an in-memory [CatalogStore] driver + a live [CryptographyKotlinEngine] session.
 * Covers boundaries (empty / partial-last-chunk), the catalog row, tamper detection, absent ids, and
 * the fail-closed cleanup on a row-insert failure.
 */
class VaultObjectsTest {
    private val testDir = Path(SystemTemporaryDirectory, "vot-${Random.nextULong()}")
    private val engine = CryptographyKotlinEngine()
    private val kdf = DefaultKeyDerivation(engine)

    // Cheap Argon2id for tests (m=256 KiB, t=1, p=1) — matches VaultSessionTest.
    private val cheapKdf = PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1)

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    private fun newStorage(): VaultStorage {
        SystemFileSystem.createDirectories(testDir)
        return VaultStorage(testDir)
    }

    private fun newCatalog(): CatalogStore =
        (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value

    private fun newSession(): VaultSession =
        VaultSession.createVault(engine, kdf, "correct horse battery".encodeToByteArray(), cheapKdf).session

    // Deterministic RNG so fileId/noncePrefix are distinct per import within a test but reproducible.
    private fun objects(
        session: VaultSession,
        storage: VaultStorage,
        catalog: CatalogStore,
        random: (Int) -> ByteArray = Random(7).let { rng -> { n -> rng.nextBytes(n) } },
    ): VaultObjects =
        VaultObjects(session, storage, catalog, DchmCodec(engine), clock = { 42L }, random = random)

    private fun source(data: ByteArray): () -> kotlinx.io.RawSource = { Buffer().apply { write(data) } }

    @Test
    fun import_then_open_round_trips_across_sizes_including_boundaries() {
        val vo = objects(newSession(), newStorage(), newCatalog())
        for (size in listOf(0, 1, 63, 64, 65, 200)) { // chunkSize 64 → empty, partial, exact, multi
            val data = ByteArray(size) { (it * 7 + 1).toByte() }
            val id = (vo.importObject(kind = 0, name = "n$size", mime = "application/octet-stream", chunkSize = 64, openSource = source(data)) as Outcome.Ok).value
            assertEquals(16, id.size, "file_id must be 16 bytes")
            val out = Buffer()
            assertTrue(vo.openObject(id, out) is Outcome.Ok, "size=$size open failed")
            assertContentEquals(data, out.readByteArray(), "size=$size round-trip mismatch")
        }
    }

    @Test
    fun import_writes_a_catalog_row_with_size_kind_and_meta() {
        val catalog = newCatalog()
        val vo = objects(newSession(), newStorage(), catalog)
        val data = ByteArray(130) { it.toByte() }
        val id = (vo.importObject(kind = 3, name = "clip.mp4", mime = "video/mp4", chunkSize = 64, openSource = source(data)) as Outcome.Ok).value

        val row = (catalog.getItem(id) as Outcome.Ok).value!!
        assertEquals(130L, row.size, "row size must be the plaintext byte count")
        assertEquals(3, row.kind)
        assertTrue(row.enc_meta.isNotEmpty(), "enc_meta must be populated")
        assertTrue(row.dek_wrapped.isNotEmpty(), "dek_wrapped must be populated")
    }

    @Test
    fun open_meta_returns_name_and_mime_without_reading_the_object() {
        val storage = newStorage()
        val catalog = newCatalog()
        val vo = objects(newSession(), storage, catalog)
        val id = (vo.importObject(kind = 1, name = "vacation.jpg", mime = "image/jpeg", chunkSize = 64, openSource = source(ByteArray(50))) as Outcome.Ok).value

        // Delete the object bytes — openMeta must still succeed, proving it reads only the row.
        assertTrue(storage.deleteObject(id) is Outcome.Ok)
        val meta = (vo.openMeta(id) as Outcome.Ok).value
        assertEquals(ObjectMeta("vacation.jpg", "image/jpeg"), meta)
    }

    @Test
    fun open_meta_of_an_unknown_id_is_an_io_error() {
        val vo = objects(newSession(), newStorage(), newCatalog())
        val result = vo.openMeta(ByteArray(16) { 5 })
        assertTrue(result is Outcome.Err && result.error is VaultError.Io)
    }

    @Test
    fun open_meta_of_a_tampered_enc_meta_is_a_crypto_error() {
        val catalog = newCatalog()
        val vo = objects(newSession(), newStorage(), catalog)
        val id = (vo.importObject(kind = 0, name = "n", mime = "m", chunkSize = 64, openSource = source(ByteArray(20))) as Outcome.Ok).value

        val row = (catalog.getItem(id) as Outcome.Ok).value!!
        val tampered = row.enc_meta.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertTrue(catalog.upsertItem(row.copy(enc_meta = tampered)) is Outcome.Ok)

        val result = vo.openMeta(id)
        assertTrue(result is Outcome.Err && result.error is VaultError.Crypto, "tampered enc_meta must be a crypto error, was $result")
    }

    @Test
    fun open_of_an_unknown_id_is_an_io_error() {
        val vo = objects(newSession(), newStorage(), newCatalog())
        val result = vo.openObject(ByteArray(16) { 9 }, Buffer())
        assertTrue(result is Outcome.Err)
        assertTrue(result.error is VaultError.Io, "absent id must map to Io")
    }

    @Test
    fun a_tampered_object_fails_integrity_on_open() {
        val storage = newStorage()
        val vo = objects(newSession(), storage, newCatalog())
        val data = ByteArray(100) { it.toByte() }
        val id = (vo.importObject(kind = 0, name = "n", mime = "m", chunkSize = 64, openSource = source(data)) as Outcome.Ok).value

        // Flip the final byte (the last chunk's GCM tag) and write the container back.
        val bytes = (storage.readObject(id) { it.buffered().readByteArray() } as Outcome.Ok).value
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        assertTrue(storage.writeObject(id) { sink -> sink.buffered().apply { write(bytes); flush() } } is Outcome.Ok)

        val result = vo.openObject(id, Buffer())
        assertTrue(result is Outcome.Err && result.error is VaultError.Crypto.IntegrityFailure, "tamper must be IntegrityFailure, was $result")
    }

    @Test
    fun shred_removes_the_row_and_the_object_and_a_reopen_is_absent() {
        val storage = newStorage()
        val catalog = newCatalog()
        val vo = objects(newSession(), storage, catalog)
        val id = (vo.importObject(kind = 0, name = "n", mime = "m", chunkSize = 64, openSource = source(ByteArray(80))) as Outcome.Ok).value

        assertTrue(vo.shredObject(id) is Outcome.Ok)
        assertEquals(null, (catalog.getItem(id) as Outcome.Ok).value, "the row (and wrapped DEK) must be gone")
        assertFalse(storage.objectExists(id), "the object bytes must be gone")
        val reopen = vo.openObject(id, Buffer())
        assertTrue(reopen is Outcome.Err && reopen.error is VaultError.Io, "a reopen after shred is absent")
    }

    @Test
    fun shred_of_an_unknown_id_is_an_io_error() {
        val vo = objects(newSession(), newStorage(), newCatalog())
        val result = vo.shredObject(ByteArray(16) { 7 })
        assertTrue(result is Outcome.Err && result.error is VaultError.Io)
    }

    @Test
    fun a_row_insert_failure_deletes_the_object_no_orphan() {
        val storage = newStorage()
        val catalog = newCatalog()
        catalog.close() // a closed driver makes upsertItem fail with VaultError.Db

        val knownId = ByteArray(16) { 1 }
        val knownPrefix = ByteArray(8) { 2 }
        val fixed: (Int) -> ByteArray = { n -> if (n == 16) knownId.copyOf() else knownPrefix.copyOf() }
        val vo = VaultObjects(newSession(), storage, catalog, DchmCodec(engine), clock = { 42L }, random = fixed)

        val result = vo.importObject(kind = 0, name = "n", mime = "m", openSource = source("hi".encodeToByteArray()))
        assertTrue(result is Outcome.Err && result.error is VaultError.Db, "a failing catalog must abort the import, was $result")
        assertFalse(storage.objectExists(knownId), "the orphaned object must be deleted on a row failure")
    }

}

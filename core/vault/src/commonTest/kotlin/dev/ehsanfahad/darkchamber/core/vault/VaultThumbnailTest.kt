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
import kotlinx.io.RawSource
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Thumbnail generation during import: the happy path, the three ways an encoder can decline to
 * produce one, and the key separation the format spec makes mandatory.
 *
 * The three declining paths are the point of the file. A thumbnail is a **cache** — losing one must
 * never cost the user their file — so every one of them has to land on `Outcome.Ok` with the item
 * fully imported and simply no preview.
 */
class VaultThumbnailTest {
    private val testDir = Path(SystemTemporaryDirectory, "vtt-${Random.nextULong()}")
    private val engine = CryptographyKotlinEngine()
    private val kdf = DefaultKeyDerivation(engine)
    private val codec = DchmCodec(engine)
    private val cheapKdf = PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1)

    private val session: VaultSession =
        VaultSession.createVault(engine, kdf, "correct horse battery".encodeToByteArray(), cheapKdf).session

    private val storage: VaultStorage by lazy {
        SystemFileSystem.createDirectories(testDir)
        VaultStorage(testDir)
    }

    private val catalog: CatalogStore by lazy {
        (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value
    }

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    private fun objects(encoder: ThumbnailEncoder): VaultObjects =
        VaultObjects(
            session,
            storage,
            catalog,
            codec,
            clock = { 42L },
            random = Random(7).let { rng -> { n -> rng.nextBytes(n) } },
            thumbnails = encoder,
        )

    private val payload = ByteArray(200) { (it * 3 + 1).toByte() }

    private fun source(data: ByteArray): () -> RawSource = { Buffer().apply { write(data) } }

    private fun importJpeg(vo: VaultObjects): ByteArray =
        (
            vo.importObject(
                kind = 0,
                name = "holiday.jpg",
                mime = "image/jpeg",
                chunkSize = 64,
                openSource = source(payload),
            ) as Outcome.Ok
            ).value

    private fun metaOf(vo: VaultObjects, id: ByteArray): ObjectMeta =
        (vo.openMeta(id) as Outcome.Ok).value

    @Test
    fun a_successful_import_stores_the_thumbnail_and_the_key_that_opens_it() {
        val thumbBytes = ByteArray(300) { (it * 5).toByte() }
        val encoder = FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Encode, thumbBytes)
        val vo = objects(encoder)

        val id = importJpeg(vo)

        assertTrue(storage.thumbnailExists(id), "the thumbnail blob must be on disk")
        val key = assertNotNull(metaOf(vo, id).thumbnailKey, "the row must record the thumbnail's key")

        // The stored blob is a real .dchm container that this key opens, and it holds exactly what
        // the encoder produced. Asserting the bytes — rather than just that a file exists — is what
        // proves the key recorded belongs to the container written.
        val out = Buffer()
        val opened = session.useDek(key) { raw ->
            storage.readThumbnail(id) { src -> codec.decryptContainer(raw, id, src, out) }
        }
        assertTrue(opened is Outcome.Ok, "the recorded key must open the stored thumbnail")
        assertContentEquals(thumbBytes, out.readByteArray())

        // The encoder was driven with the item's own MIME and the plaintext, at the grid's size.
        assertEquals("image/jpeg", encoder.lastMime)
        assertEquals(THUMBNAIL_MAX_EDGE_PX, encoder.lastMaxEdgePx)
        assertContentEquals(payload, encoder.lastSource, "the encoder must see the plaintext payload")
    }

    @Test
    fun the_thumbnail_uses_a_different_dek_from_the_object() {
        val vo = objects(FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Encode))

        val id = importJpeg(vo)

        val row = assertNotNull((catalog.getItem(id) as Outcome.Ok).value)
        val objectDek = (session.unwrapDek(row.dek_wrapped) as Outcome.Ok).value
        val thumbDek = (session.unwrapDek(assertNotNull(metaOf(vo, id).thumbnailKey)) as Outcome.Ok).value

        // Unwrapped, not as stored: two wraps of the *same* key differ anyway (each wrap draws a
        // fresh nonce), so comparing the stored blobs would pass even if the DEK were reused.
        //
        // FORMAT.md section 2: "a fresh random DEK per object -> no cross-object reuse. Nonce reuse
        // under GCM is catastrophic; all three mechanisms are mandatory." A thumbnail is a separate
        // container with its own nonce_prefix, so this assertion is the only thing standing between
        // that clause and someone reusing the object's DEK for one fewer line of code.
        assertEquals(objectDek.size, thumbDek.size)
        assertFalse(objectDek.contentEquals(thumbDek), "the thumbnail must not reuse the object's DEK")
    }

    @Test
    fun an_encoder_that_declines_still_imports_the_file() {
        val vo = objects(FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Decline))

        val id = importJpeg(vo)

        assertNoThumbnail(vo, id)
    }

    @Test
    fun an_encoder_that_returns_nothing_usable_still_imports_the_file() {
        val vo = objects(FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Empty))

        val id = importJpeg(vo)

        // An empty array would otherwise be sealed into a valid, zero-length container: a thumbnail
        // that exists, opens, and draws nothing.
        assertNoThumbnail(vo, id)
    }

    @Test
    fun an_encoder_that_throws_still_imports_the_file() {
        val vo = objects(FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Throw))

        val id = importJpeg(vo)

        assertNoThumbnail(vo, id)
    }

    @Test
    fun a_non_image_is_imported_with_no_thumbnail_and_no_wasted_read() {
        // The encoder declines by MIME, which is the ordinary case for most of a vault.
        val encoder = FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Decline)
        val vo = objects(encoder)

        val id = (
            vo.importObject(
                kind = 0,
                name = "notes.txt",
                mime = "text/plain",
                chunkSize = 64,
                openSource = source(payload),
            ) as Outcome.Ok
            ).value

        assertNoThumbnail(vo, id)
        assertEquals("text/plain", encoder.lastMime, "the encoder decides by MIME, not the caller")
    }

    @Test
    fun a_cancel_during_the_decode_stops_the_read_and_still_imports() {
        // The encoder starts reading and the user cancels underneath it. Encoding a preview is the
        // import's third read of the payload, and an unguarded one would go on pulling plaintext
        // through a decoder after the work behind it was abandoned — the same shape as the
        // unguarded spill copy an earlier review caught.
        var cancelled = false
        val encoder = object : ThumbnailEncoder {
            override fun encode(mime: String, maxEdgePx: Int, openSource: () -> RawSource): ByteArray {
                cancelled = true
                openSource().use { src ->
                    val sink = Buffer()
                    while (src.readAtMostTo(sink, 64L) >= 0L) sink.clear()
                }
                return ByteArray(8) { 1 } // never reached: the guarded source throws first
            }
        }
        val vo = objects(encoder)

        val id = (
            vo.importObject(
                kind = 0,
                name = "holiday.jpg",
                mime = "image/jpeg",
                chunkSize = 64,
                isActive = { !cancelled },
                openSource = source(payload),
            ) as Outcome.Ok
            ).value

        // The object was already written and its row is what makes the file the user's; a cancel
        // arriving after that point costs the preview only.
        assertNoThumbnail(vo, id)
    }

    @Test
    fun a_row_failure_leaves_no_orphan_thumbnail() {
        // A closed driver makes the row insert fail, which is the one path where a thumbnail has
        // already been written and the key that would have found it never lands anywhere. The blob
        // is unreadable without that key, but it is also invisible to every listing, so nothing
        // would ever clean it up.
        val closedCatalog = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value.also { it.close() }
        val knownId = ByteArray(16) { 1 }
        val vo = VaultObjects(
            session,
            storage,
            closedCatalog,
            codec,
            clock = { 42L },
            random = { n -> if (n == 16) knownId.copyOf() else ByteArray(n) { 2 } },
            thumbnails = FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Encode),
        )

        val result = vo.importObject(kind = 0, name = "holiday.jpg", mime = "image/jpeg", openSource = source(payload))

        assertTrue(result is Outcome.Err, "a failing catalog must abort the import, was $result")
        assertFalse(storage.objectExists(knownId), "the object must not outlive its row")
        assertFalse(storage.thumbnailExists(knownId), "nor may its thumbnail")
    }

    @Test
    fun a_stored_thumbnail_reads_back_byte_identical() {
        val thumbBytes = ByteArray(300) { (it * 5).toByte() }
        val vo = objects(FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Encode, thumbBytes))
        val id = importJpeg(vo)

        val read = vo.openThumbnail(id)

        assertTrue(read is Outcome.Ok, "a stored thumbnail must read back, was $read")
        assertContentEquals(thumbBytes, read.value)
    }

    @Test
    fun an_item_with_no_thumbnail_reads_as_absent_not_as_an_error() {
        val vo = objects(FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Decline))
        val id = importJpeg(vo)

        val read = vo.openThumbnail(id)

        // Absent is the ordinary case for most of a vault — every non-image, every file the decoder
        // declined, everything imported before thumbnails existed. An error here would paint all of
        // them as damaged.
        assertTrue(read is Outcome.Ok, "no thumbnail is not a failure, was $read")
        assertNull(read.value)
    }

    @Test
    fun a_tampered_thumbnail_surfaces_integrity_failure_rather_than_absent() {
        val vo = objects(FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Encode))
        val id = importJpeg(vo)

        // Flip the final byte — the last chunk's GCM tag — and write the container back.
        val bytes = (storage.readThumbnail(id) { it.buffered().readByteArray() } as Outcome.Ok).value
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        assertTrue(storage.writeThumbnail(id) { sink -> sink.buffered().apply { write(bytes); flush() } } is Outcome.Ok)

        val read = vo.openThumbnail(id)

        // This is the distinction the absent case is worth having: a damaged thumbnail is a
        // tampering signal, and quietly drawing the same badge an unsupported type gets would hide
        // it (hard rule: IntegrityFailure is never silently swallowed).
        assertTrue(
            read is Outcome.Err && read.error is VaultError.Crypto.IntegrityFailure,
            "a tampered thumbnail must not read as absent, was $read",
        )
    }

    @Test
    fun a_thumbnail_blob_that_has_gone_missing_reads_as_absent() {
        val vo = objects(FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Encode))
        val id = importJpeg(vo)
        assertTrue(storage.deleteThumbnail(id) is Outcome.Ok)

        val read = vo.openThumbnail(id)

        // The key still says a thumbnail existed, but a missing file proves nothing about tampering
        // — there is no tag to fail. Rendering the badge is the graceful answer; claiming damage on
        // an item whose bytes are perfectly intact is not.
        assertTrue(read is Outcome.Ok, "a missing blob is absent, not damaged, was $read")
        assertNull(read.value)
    }

    @Test
    fun shredding_an_item_takes_its_thumbnail_with_it() {
        val vo = objects(FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Encode))
        val id = importJpeg(vo)
        assertTrue(storage.thumbnailExists(id))

        assertTrue(vo.shredObject(id) is Outcome.Ok)

        // The shred is already effective the moment the row is gone — the thumbnail's key lived in
        // that row's enc_meta, so the blob is unopenable either way. Deleting it stops an orphan
        // that no listing can see, and that therefore nothing else would ever remove.
        assertFalse(storage.thumbnailExists(id), "a shredded item must not leave its preview behind")
    }

    @Test
    fun an_unknown_id_has_no_thumbnail_to_read() {
        val vo = objects(FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Encode))

        val read = vo.openThumbnail(ByteArray(16) { 9 })

        // Same mapping as openObject/openMeta: an id with no row is Io, not a silent absence.
        assertTrue(read is Outcome.Err && read.error is VaultError.Io, "unknown id must map to Io, was $read")
    }

    // The item is fully imported and simply has no preview: bytes readable, no blob, no key.
    private fun assertNoThumbnail(vo: VaultObjects, id: ByteArray) {
        val out = Buffer()
        assertTrue(vo.openObject(id, out) is Outcome.Ok, "the file itself must still import")
        assertContentEquals(payload, out.readByteArray())
        assertFalse(storage.thumbnailExists(id), "no blob may be left behind")
        assertNull(metaOf(vo, id).thumbnailKey, "no key may be recorded without a thumbnail")
    }
}

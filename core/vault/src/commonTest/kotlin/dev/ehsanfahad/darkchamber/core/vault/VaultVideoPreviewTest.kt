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
import kotlinx.io.readByteArray
import kotlinx.io.write
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
 * A video's preview taken from the object import has just written, through a [RangeReader], rather
 * than from the import's source — and the one way a preview is allowed to fail an import: reading back
 * that object fails its own authentication.
 */
class VaultVideoPreviewTest {
    private val testDir = Path(SystemTemporaryDirectory, "vvp-${Random.nextULong()}")
    private val engine = CryptographyKotlinEngine()
    private val cheapKdf = PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1)
    private val session: VaultSession =
        VaultSession.createVault(engine, DefaultKeyDerivation(engine), "correct horse battery".encodeToByteArray(), cheapKdf).session

    private val storage: VaultStorage = run {
        SystemFileSystem.createDirectories(testDir)
        VaultStorage(testDir)
    }
    private val catalog: CatalogStore = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    private fun objects(encoder: ThumbnailEncoder) = VaultObjects(
        session, storage, catalog, DchmCodec(engine),
        clock = { 42L },
        random = Random(31).let { rng -> { n -> rng.nextBytes(n) } },
        thumbnails = encoder,
    )

    private val payload = ByteArray(300) { (it * 11 + 2).toByte() } // chunks of 64: 0..4, the last 44 bytes

    // An opener that counts how often the import opens its source.
    private class CountingOpener(private val data: ByteArray) {
        var opens = 0
        fun open(): RawSource {
            opens++
            return Buffer().apply { write(data.copyOf()) }
        }
    }

    private fun <T> Outcome<T>.ok(): T = assertIs<Outcome.Ok<T>>(this).value

    // Imports [mime] and returns how many times the source was opened once the preview phase began.
    private fun opensDuringThePreview(encoder: ThumbnailEncoder, mime: String, onPreview: () -> Unit = {}): Pair<Outcome<ByteArray>, Int> {
        val opener = CountingOpener(payload)
        var atPreview = -1
        val result = objects(encoder).importObject(
            kind = 0, name = "clip", mime = mime, chunkSize = 64, openSource = opener::open,
            onProgress = { phase, _ ->
                if (phase == ImportPhase.PREVIEWING) {
                    atPreview = opener.opens
                    onPreview()
                }
            },
        )
        return result to (opener.opens - atPreview)
    }

    @Test
    fun a_video_preview_reads_the_stored_object_and_never_reopens_the_source() {
        val encoder = FakeThumbnailEncoder()

        val (result, opens) = opensDuringThePreview(encoder, "video/mp4")

        result.ok()
        assertEquals(1, encoder.rangedCount)
        assertEquals(0, encoder.encodeCount)
        assertEquals("video/mp4", encoder.lastMime)
        assertContentEquals(payload, encoder.lastRanged)
        assertEquals(0, opens)
    }

    @Test
    fun a_video_type_in_capitals_is_still_a_video() {
        // MIME types are case-insensitive, and so is the platform encoders' check: a type the stream
        // path would hand to an encoder that only takes video by reader would lose its preview.
        val encoder = FakeThumbnailEncoder()

        val (result, opens) = opensDuringThePreview(encoder, "Video/MP4")

        result.ok()
        assertEquals(1, encoder.rangedCount)
        assertEquals(0, encoder.encodeCount)
        assertEquals(0, opens)
    }

    @Test
    fun the_reader_is_closed_once_the_preview_is_made_or_the_encoder_fails() {
        // A reader left open would keep decrypted chunks in memory, and a file handle, past the import.
        for (mode in listOf(FakeThumbnailEncoder.Mode.Encode, FakeThumbnailEncoder.Mode.Throw)) {
            val encoder = FakeThumbnailEncoder(mode)
            objects(encoder).importObject(kind = 0, name = "clip", mime = "video/mp4", chunkSize = 64,
                openSource = { Buffer().apply { write(payload.copyOf()) } }).ok()

            val late = encoder.lastReader!!.readAt(position = 0, into = ByteArray(4), offset = 0, size = 4)
            assertIs<Outcome.Err>(late, "the reader handed to a $mode encoder was left open")
        }
    }

    @Test
    fun an_image_preview_still_reads_the_source_and_never_opens_a_reader() {
        val encoder = FakeThumbnailEncoder()

        val (result, opens) = opensDuringThePreview(encoder, "image/jpeg")

        result.ok()
        assertEquals(1, encoder.encodeCount)
        assertEquals(0, encoder.rangedCount)
        assertContentEquals(payload, encoder.lastSource)
        assertEquals(1, opens)
    }

    // The one object file in this test's vault — the one being imported.
    private fun storedObjectFile(): Path {
        fun walk(dir: Path): List<Path> = SystemFileSystem.list(dir).flatMap { p ->
            if (SystemFileSystem.metadataOrNull(p)?.isDirectory == true) walk(p) else listOf(p)
        }
        return walk(Path(testDir, "objects")).single { it.name.endsWith(".dchm") }
    }

    private fun rewriteStoredObject(change: (ByteArray) -> Unit) {
        val path = storedObjectFile()
        val bytes = SystemFileSystem.source(path).buffered().use { it.readByteArray() }
        change(bytes)
        SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
    }

    private fun flip(bytes: ByteArray, at: Int) {
        bytes[at] = (bytes[at].toInt() xor 0x01).toByte()
    }

    private fun assertNoTraceOf(result: Outcome<ByteArray>, expected: VaultError) {
        assertEquals(expected, assertIs<Outcome.Err>(result).error)
        fun files(dir: String) = SystemFileSystem.list(Path(testDir, dir)).size
        assertEquals(0, files("thumbs"), "no thumbnail blob")
        assertTrue(runCatching { storedObjectFile() }.isFailure, "no object file")
        assertEquals(0L, catalog.countItems().ok(), "no row")
    }

    @Test
    fun a_read_back_that_fails_its_tag_at_open_fails_the_import_and_leaves_nothing() {
        // The last byte is the last chunk's tag, which the open authenticates.
        val (result, _) = opensDuringThePreview(FakeThumbnailEncoder(), "video/mp4") {
            rewriteStoredObject { flip(it, it.size - 1) }
        }

        assertNoTraceOf(result, VaultError.Crypto.IntegrityFailure)
    }

    @Test
    fun a_read_back_that_fails_its_tag_on_a_later_read_fails_the_import_and_leaves_nothing() {
        // A byte inside chunk 1's ciphertext: the open does not read it; the encoder's reads do.
        val (result, _) = opensDuringThePreview(FakeThumbnailEncoder(), "video/mp4") {
            rewriteStoredObject { bytes ->
                val headerLen = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
                flip(bytes, 16 + headerLen + (64 + 16) + 5)
            }
        }

        assertNoTraceOf(result, VaultError.Crypto.IntegrityFailure)
    }

    // How an import ended, reduced to what must match between an image and a video import.
    private data class Ending(val ok: Boolean, val error: String?, val rows: Long, val objects: Int, val thumbnails: Int, val hasPreview: Boolean)

    /**
     * Imports [mime] into a vault of its own, running [duringPreview] when the preview phase starts,
     * and reports how it ended — so the same event can be run once as an image and once as a video.
     */
    private fun endingOf(
        mime: String,
        encoder: ThumbnailEncoder,
        duringPreview: (session: VaultSession, dir: Path) -> Unit = { _, _ -> },
        isActive: () -> Boolean = { true },
    ): Ending {
        val dir = Path(testDir, "v-${Random.nextULong()}")
        SystemFileSystem.createDirectories(dir)
        val ownSession = VaultSession.createVault(engine, DefaultKeyDerivation(engine), "correct horse battery".encodeToByteArray(), cheapKdf).session
        val ownStorage = VaultStorage(dir)
        val ownCatalog = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value
        val vo = VaultObjects(ownSession, ownStorage, ownCatalog, DchmCodec(engine), clock = { 42L },
            random = Random(37).let { rng -> { n -> rng.nextBytes(n) } }, thumbnails = encoder)
        val result = vo.importObject(
            kind = 0, name = "x", mime = mime, chunkSize = 64,
            openSource = { Buffer().apply { write(payload.copyOf()) } },
            onProgress = { phase, _ -> if (phase == ImportPhase.PREVIEWING) duringPreview(ownSession, dir) },
            isActive = isActive,
        )
        fun count(sub: String): Int {
            fun walk(p: Path): Int = SystemFileSystem.list(p).sumOf { if (SystemFileSystem.metadataOrNull(it)?.isDirectory == true) walk(it) else 1 }
            return walk(Path(dir, sub))
        }
        val hasPreview = result is Outcome.Ok && ownSession.isOpen && vo.openMeta(result.value).ok().thumbnailKey != null
        return Ending(
            ok = result is Outcome.Ok,
            error = (result as? Outcome.Err)?.error?.let { it::class.simpleName },
            rows = ownCatalog.countItems().ok(),
            objects = count("objects"),
            thumbnails = count("thumbs"),
            hasPreview = hasPreview,
        )
    }

    private fun assertVideoEndsLikeAnImage(
        encoder: () -> ThumbnailEncoder,
        duringPreview: (session: VaultSession, dir: Path) -> Unit = { _, _ -> },
        isActive: () -> Boolean = { true },
        beforeEachImport: () -> Unit = {},
        expected: Ending,
    ) {
        beforeEachImport()
        val image = endingOf("image/jpeg", encoder(), duringPreview, isActive)
        beforeEachImport()
        val video = endingOf("video/mp4", encoder(), duringPreview, isActive)
        assertEquals(expected, image, "what an image import does today changed")
        assertEquals(expected, video)
    }

    private val importedWithoutPreview = Ending(ok = true, error = null, rows = 1, objects = 1, thumbnails = 0, hasPreview = false)

    @Test
    fun an_encoder_that_declines_a_video_still_imports_it_without_a_preview() {
        assertVideoEndsLikeAnImage({ FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Decline) }, expected = importedWithoutPreview)
        assertVideoEndsLikeAnImage({ FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Empty) }, expected = importedWithoutPreview)
    }

    @Test
    fun an_encoder_that_throws_on_a_video_still_imports_it_without_a_preview() {
        assertVideoEndsLikeAnImage({ FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Throw) }, expected = importedWithoutPreview)
    }

    @Test
    fun a_cancel_during_a_video_preview_stops_the_reads_and_still_imports() {
        var wanted = true
        assertVideoEndsLikeAnImage(
            { FakeThumbnailEncoder() },
            duringPreview = { _, _ -> wanted = false },
            isActive = { wanted },
            beforeEachImport = { wanted = true },
            expected = importedWithoutPreview,
        )
    }

    @Test
    fun a_lock_during_a_video_preview_fails_the_import_as_it_does_for_an_image_and_leaves_nothing() {
        assertVideoEndsLikeAnImage(
            { FakeThumbnailEncoder() },
            duringPreview = { session, _ -> session.close() },
            expected = Ending(ok = false, error = "Unexpected", rows = 0, objects = 0, thumbnails = 0, hasPreview = false),
        )
    }

    @Test
    fun a_read_back_that_is_not_a_container_fails_the_import_and_leaves_nothing() {
        val (result, _) = opensDuringThePreview(FakeThumbnailEncoder(), "video/mp4") {
            rewriteStoredObject { it[0] = 'X'.code.toByte() }
        }

        assertNoTraceOf(result, VaultError.Crypto.MalformedContainer)
    }
}

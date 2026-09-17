package dev.ehsanfahad.darkchamber.core.vault

import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.crypto.DchmCodec
import dev.ehsanfahad.darkchamber.core.crypto.DefaultKeyDerivation
import dev.ehsanfahad.darkchamber.core.crypto.PassphraseKdfProfile
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
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
 * Import against sources that will not re-open.
 *
 * The `.dchm` codec reads its payload twice (size+digest, then chunks), but a content-provider or
 * security-scoped stream may only be openable once. Rather than copying every import through disk,
 * `importObject` probes re-openability and spills **only** the one-shot case into app-private
 * `tmp/` — plaintext never reaches shared storage either way (hard rule #1), and the scratch is
 * gone before the call returns.
 */
class VaultImportSpillTest {
    private val testDir = Path(SystemTemporaryDirectory, "vspill-${Random.nextULong()}")
    private val engine = CryptographyKotlinEngine()
    private val kdf = DefaultKeyDerivation(engine)
    private val cheapKdf = PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1)

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    private fun newStorage(): VaultStorage {
        SystemFileSystem.createDirectories(testDir)
        return VaultStorage(testDir)
    }

    private fun newCatalog(): CatalogStore = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value

    private fun newSession(): VaultSession =
        VaultSession.createVault(engine, kdf, "correct horse battery".encodeToByteArray(), cheapKdf).session

    private fun objects(storage: VaultStorage, catalog: CatalogStore): VaultObjects =
        VaultObjects(
            session = newSession(),
            storage = storage,
            catalog = catalog,
            codec = DchmCodec(engine),
            clock = { 42L },
            random = Random(3).let { r -> { n: Int -> r.nextBytes(n) } },
        )

    private fun tmpEntries(): List<Path> = SystemFileSystem.list(Path(testDir, "tmp")).toList()

    private fun VaultObjects.import(name: String, opener: SourceOpener): Outcome<ByteArray> =
        importObject(
            kind = VaultItemKind.FILE.code,
            name = name,
            mime = "application/octet-stream",
            chunkSize = 64,
            openSource = opener::open,
        )

    @Test
    fun a_source_that_cannot_be_reopened_still_imports_byte_identically() {
        val storage = newStorage()
        val vo = objects(storage, newCatalog())
        val data = ByteArray(300) { (it * 5 + 3).toByte() } // multi-chunk at chunkSize 64
        val opener = SourceOpener(data, maxOpens = 1)

        val id = vo.import("one-shot.bin", opener)
        assertIs<Outcome.Ok<ByteArray>>(id, "a one-shot source must still import")
        assertEquals(opener.created, opener.closes, "the spilled source must be closed, not leaked")

        val out = Buffer()
        assertTrue(vo.openObject(id.value, out) is Outcome.Ok, "the stored object must open")
        assertContentEquals(data, out.readByteArray(), "the spilled import must be byte-identical")
    }

    @Test
    fun the_spilled_scratch_does_not_survive_the_import() {
        val storage = newStorage()
        val vo = objects(storage, newCatalog())

        val id = vo.import("one-shot.bin", SourceOpener(ByteArray(200) { it.toByte() }, maxOpens = 1))

        assertIs<Outcome.Ok<ByteArray>>(id)
        assertEquals(emptyList(), tmpEntries(), "no decrypted scratch may outlive the import")
    }

    @Test
    fun the_spilled_scratch_does_not_survive_a_failed_import() {
        val storage = newStorage()
        val catalog = newCatalog()
        val vo = objects(storage, catalog)
        // Pull the items table out from under the store so the row insert fails after the spill.
        catalog.close()

        val result = vo.import("doomed.bin", SourceOpener(ByteArray(200) { it.toByte() }, maxOpens = 1))

        assertIs<Outcome.Err>(result, "a broken catalog must fail the import")
        assertEquals(emptyList(), tmpEntries(), "no decrypted scratch may outlive a failed import")
    }

    @Test
    fun a_source_that_fails_on_the_first_open_is_a_precise_error_and_never_spills() {
        val storage = newStorage()
        val vo = objects(storage, newCatalog())
        val opener = SourceOpener(byteArrayOf(1, 2, 3), maxOpens = 0)

        val result = vo.import("unopenable.bin", opener)

        assertIs<Outcome.Err>(result)
        assertIs<VaultError.Io>(result.error, "an unopenable source is an Io failure")
        assertEquals(emptyList(), tmpEntries(), "nothing is spilled when there was never a source")
        assertEquals(1, opener.opens, "one attempt, not a retry storm")
    }

    @Test
    fun a_reopenable_source_is_imported_without_spilling() {
        val storage = newStorage()
        val vo = objects(storage, newCatalog())
        val data = ByteArray(300) { it.toByte() }
        val opener = SourceOpener(data, maxOpens = Int.MAX_VALUE)

        val id = vo.import("streamed.bin", opener)

        assertIs<Outcome.Ok<ByteArray>>(id)
        // 2 probe opens (no bytes read) + the codec's 2 passes over the original source. The spill
        // path opens the original exactly twice instead, so this count is what distinguishes them —
        // and it pins the cost of the probe, which is the whole trade being made here.
        assertEquals(4, opener.opens, "re-openable payloads stream in place; no copy is made")
        // Including the held probe source, which is unread scratch the import must still close —
        // on Android every one of these is an open provider file descriptor.
        assertEquals(opener.created, opener.closes, "every source the import opened must be closed")
        assertTrue(opener.sourcesFullyRead >= 1, "the original source supplied the bytes")
        assertEquals(emptyList(), tmpEntries(), "the happy path leaves no scratch behind")
    }

    /**
     * A lock can zeroize the session while an import is spilling. The encrypt then throws rather
     * than returning, from inside the spill's block — so the failure must still arrive as an
     * `Outcome` (the function's contract is that no exception crosses the boundary), and the held
     * provider stream, an open file descriptor on Android, must still be released.
     *
     * The lock is timed to land as the copy finishes rather than before it starts, which is the only
     * way to reach the block now that the copy is guarded too: a session that is already closed
     * aborts the copy instead, and that case belongs to `VaultImportAbortTest`.
     */
    @Test
    fun a_throw_from_inside_the_spill_is_an_error_and_still_releases_the_held_source() {
        val session = newSession()
        val vo = VaultObjects(
            session = session,
            storage = newStorage(),
            catalog = newCatalog(),
            codec = DchmCodec(engine),
            clock = { 42L },
            random = Random(5).let { r -> { n: Int -> r.nextBytes(n) } },
        )
        // The lock lands between the copy and the encrypt: the MK is gone by the time the container
        // asks for a DEK.
        val opener = SourceOpener(ByteArray(64) { it.toByte() }, maxOpens = 1, onFullyRead = session::close)

        val result = vo.importObject(
            kind = VaultItemKind.FILE.code,
            name = "doomed.bin",
            mime = "application/octet-stream",
            chunkSize = 64,
            openSource = opener::open,
        )

        assertIs<Outcome.Err>(result, "a dead session must not throw out of importObject")
        assertIs<VaultError.Unexpected>(result.error)
        // At least once: the spill takes ownership and closes it, and the catch closes defensively
        // because it cannot know how far the spill got. Closing twice is benign; leaking is not.
        assertTrue(
            opener.closes >= opener.created,
            "the held provider stream must be released, was ${opener.closes} of ${opener.created}",
        )
        assertEquals(emptyList(), tmpEntries(), "no scratch survives the failure")
    }
}

/**
 * A source factory that stops opening after [maxOpens] — the shape of a provider stream that cannot
 * be re-opened (`maxOpens = 1`) or cannot be opened at all (`maxOpens = 0`).
 */
private class SourceOpener(
    private val data: ByteArray,
    private val maxOpens: Int,
    /** Fires as a source hits end-of-stream — how a test lands a lock at a precise point. */
    private val onFullyRead: () -> Unit = {},
) {
    var opens = 0
        private set
    /** Sources actually handed out (an open that throws produces none). */
    var created = 0
        private set
    var closes = 0
        private set
    var sourcesFullyRead = 0
        private set

    fun open(): RawSource {
        opens++
        if (opens > maxOpens) throw IOException("source cannot be opened again (attempt $opens)")
        created++
        return CountingSource(
            data,
            onExhausted = {
                sourcesFullyRead++
                onFullyRead()
            },
            onClose = { closes++ },
        )
    }
}

private class CountingSource(
    data: ByteArray,
    private val onExhausted: () -> Unit,
    private val onClose: () -> Unit,
) : RawSource {
    private val buffer = Buffer().apply { write(data) }
    private var reported = false
    private var closed = false

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        val read = buffer.readAtMostTo(sink, byteCount)
        if (read == -1L && !reported) {
            reported = true
            onExhausted()
        }
        return read
    }

    override fun close() {
        if (!closed) {
            closed = true
            onClose()
        }
        buffer.close()
    }
}

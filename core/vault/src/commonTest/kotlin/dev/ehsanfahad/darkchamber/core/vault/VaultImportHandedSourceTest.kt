package dev.ehsanfahad.darkchamber.core.vault

import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.crypto.DchmCodec
import dev.ehsanfahad.darkchamber.core.crypto.DefaultKeyDerivation
import dev.ehsanfahad.darkchamber.core.crypto.PassphraseKdfProfile
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.model.Outcome
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
 * Import against a source the **caller** already opened.
 *
 * A provider may block for minutes inside its open — Drive downloads the whole file there — and that
 * wait is the one stretch of an import a user cannot stop, because there are no reads yet for the
 * per-read guard to land on. Abandoning a blocked call needs a second thread, which is not something
 * this module has: it is synchronous and `Outcome`-returning by design.
 *
 * So the first open moves to the caller, which is coroutine-aware, and the opened source is handed
 * in. Only the *first* open needs this: a caching provider serves every later open from its cache,
 * and a streaming provider returns from the open at once.
 *
 * The contract being pinned here is **ownership**: once handed over, the source belongs to this
 * module, which closes it on every path. On Android each one is an open provider file descriptor, so
 * a leak on the failure paths is a real leak rather than a tidiness question.
 */
class VaultImportHandedSourceTest {
    private val testDir = Path(SystemTemporaryDirectory, "vhand-${Random.nextULong()}")
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

    private fun objects(session: VaultSession, storage: VaultStorage, catalog: CatalogStore) =
        VaultObjects(
            session = session,
            storage = storage,
            catalog = catalog,
            codec = DchmCodec(engine),
            clock = { 42L },
            random = Random(11).let { r -> { n: Int -> r.nextBytes(n) } },
        )

    private fun tmpEntries(): List<Path> = SystemFileSystem.list(Path(testDir, "tmp")).toList()

    @Test
    fun a_handed_source_is_used_instead_of_a_first_open() {
        val vo = objects(newSession(), newStorage(), newCatalog())
        val data = ByteArray(300) { (it * 7).toByte() }
        val opener = TrackingOpener(data, maxOpens = Int.MAX_VALUE)
        val handed = TrackedSource(data)

        val result = vo.importObject(
            kind = VaultItemKind.FILE.code,
            name = "handed.bin",
            mime = "application/octet-stream",
            chunkSize = 64,
            first = handed,
            openSource = opener::open,
        )

        assertIs<Outcome.Ok<ByteArray>>(result)
        // Without a handed source this import opens four times: the held first open, the
        // re-openability probe, and the codec's two passes. Handing one in removes exactly the first
        // of those — which is the whole point, since that is the open that blocks.
        assertEquals(3, opener.opens, "the handed source must stand in for the first open")
        assertTrue(handed.closed, "the handed source belongs to the import, which must close it")
    }

    @Test
    fun a_handed_source_is_closed_even_when_the_payload_is_read_from_a_fresh_one() {
        val vo = objects(newSession(), newStorage(), newCatalog())
        val data = ByteArray(300) { it.toByte() }
        val handed = TrackedSource(data)

        val result = vo.importObject(
            kind = VaultItemKind.FILE.code,
            name = "reopenable.bin",
            mime = "application/octet-stream",
            chunkSize = 64,
            first = handed,
            openSource = { TrackedSource(data) },
        )

        assertIs<Outcome.Ok<ByteArray>>(result)
        // The source re-opens, so the payload streams in place and the handed source is never read.
        // It is still an open descriptor, and unread scratch is exactly what gets forgotten.
        assertEquals(0, handed.bytesRead, "a re-openable payload does not read the handed source")
        assertTrue(handed.closed, "an unread handed source must still be closed")
    }

    @Test
    fun a_handed_source_that_cannot_be_reopened_supplies_the_payload_through_the_spill() {
        val storage = newStorage()
        val vo = objects(newSession(), storage, newCatalog())
        val data = ByteArray(300) { (it * 3 + 1).toByte() }
        val handed = TrackedSource(data)

        val id = vo.importObject(
            kind = VaultItemKind.FILE.code,
            name = "one-shot.bin",
            mime = "application/octet-stream",
            chunkSize = 64,
            first = handed,
            // Nothing re-opens: the handed source is the only one there is, so it must be spilled.
            openSource = { throw IOException("this source opens only once") },
        )

        assertIs<Outcome.Ok<ByteArray>>(id, "a handed one-shot source must still import")
        assertTrue(handed.bytesRead > 0, "the handed source supplied the payload")
        assertTrue(handed.closed, "the spill takes ownership and must close it")

        val out = Buffer()
        assertTrue(vo.openObject(id.value, out) is Outcome.Ok)
        assertContentEquals(data, out.readByteArray(), "a handed spilled import must be byte-identical")
        assertEquals(emptyList(), tmpEntries(), "no decrypted scratch may outlive the import")
    }

    @Test
    fun a_handed_source_is_closed_when_the_import_fails() {
        val catalog = newCatalog()
        val vo = objects(newSession(), newStorage(), catalog)
        val data = ByteArray(200) { it.toByte() }
        val handed = TrackedSource(data)
        // Pull the items table out from under the store so the row insert fails after the bytes land.
        catalog.close()

        val result = vo.importObject(
            kind = VaultItemKind.FILE.code,
            name = "doomed.bin",
            mime = "application/octet-stream",
            chunkSize = 64,
            first = handed,
            openSource = { TrackedSource(data) },
        )

        assertIs<Outcome.Err>(result, "a broken catalog must fail the import")
        assertTrue(handed.closed, "a failed import must not leak the descriptor it was handed")
    }

    @Test
    fun a_handed_source_is_closed_when_the_import_throws_from_inside_the_spill() {
        val session = newSession()
        val vo = objects(session, newStorage(), newCatalog())
        val data = ByteArray(64) { it.toByte() }
        // The lock lands as the copy finishes: the master key is gone by the time the container asks
        // for a DEK, so the encrypt throws from inside the spill's block rather than returning.
        val handed = TrackedSource(data, onExhausted = session::close)

        val result = vo.importObject(
            kind = VaultItemKind.FILE.code,
            name = "locked.bin",
            mime = "application/octet-stream",
            chunkSize = 64,
            first = handed,
            openSource = { throw IOException("this source opens only once") },
        )

        assertIs<Outcome.Err>(result, "a dead session must not throw out of importObject")
        assertTrue(handed.closed, "a throw from inside the spill must still release the descriptor")
        assertEquals(emptyList(), tmpEntries(), "no scratch survives the failure")
    }

    @Test
    fun no_handed_source_still_opens_the_payload_itself() {
        val vo = objects(newSession(), newStorage(), newCatalog())
        val data = ByteArray(300) { it.toByte() }
        val opener = TrackingOpener(data, maxOpens = Int.MAX_VALUE)

        val result = vo.importObject(
            kind = VaultItemKind.FILE.code,
            name = "unhanded.bin",
            mime = "application/octet-stream",
            chunkSize = 64,
            openSource = opener::open,
        )

        assertIs<Outcome.Ok<ByteArray>>(result)
        // The default is what every caller but the import use case relies on, including the preview
        // path and the whole of the existing suite: four opens, the first of them this module's own.
        assertEquals(4, opener.opens, "without a handed source the import opens the payload itself")
        assertEquals(opener.created, opener.closed, "every source it opened must be closed")
    }
}

/** A source over fixed bytes that records what was read of it and whether it was closed. */
private class TrackedSource(
    data: ByteArray,
    /** Fires at end-of-stream — how a test lands a lock at a precise point. */
    private val onExhausted: () -> Unit = {},
) : RawSource {
    private val buffer = Buffer().apply { write(data) }
    private var reported = false

    var bytesRead: Long = 0L
        private set
    var closed: Boolean = false
        private set

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        val n = buffer.readAtMostTo(sink, byteCount)
        if (n > 0L) bytesRead += n
        if (n == -1L && !reported) {
            reported = true
            onExhausted()
        }
        return n
    }

    override fun close() {
        closed = true
        buffer.close()
    }
}

/** Counts the opens an import performs, and the closes it balances them with. */
private class TrackingOpener(private val data: ByteArray, private val maxOpens: Int) {
    var opens = 0
        private set
    var created = 0
        private set
    var closed = 0
        private set

    fun open(): RawSource {
        opens++
        if (opens > maxOpens) throw IOException("source cannot be opened again (attempt $opens)")
        created++
        return object : RawSource {
            private val buffer = Buffer().apply { write(data) }

            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = buffer.readAtMostTo(sink, byteCount)

            override fun close() {
                closed++
                buffer.close()
            }
        }
    }
}

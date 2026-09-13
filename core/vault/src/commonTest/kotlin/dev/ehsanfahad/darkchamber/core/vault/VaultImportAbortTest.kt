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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Locking mid-import must stop the encrypt promptly.
 *
 * The encrypt is blocking and non-cancellable — cancelling the coroutine around it does nothing — so
 * without a per-chunk check the app would keep encrypting a large file long after the user locked
 * (a 200 MB import is tens of seconds on a phone). The guard sits on the source the codec reads
 * from, so the abort lands within one chunk.
 */
class VaultImportAbortTest {
    private val testDir = Path(SystemTemporaryDirectory, "vabort-${Random.nextULong()}")
    private val engine = CryptographyKotlinEngine()
    private val kdf = DefaultKeyDerivation(engine)
    private val cheapKdf = PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1)

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    private fun newSession(): VaultSession =
        VaultSession.createVault(engine, kdf, "correct horse battery".encodeToByteArray(), cheapKdf).session

    private fun objects(session: VaultSession, storage: VaultStorage, catalog: CatalogStore) =
        VaultObjects(
            session = session,
            storage = storage,
            catalog = catalog,
            codec = DchmCodec(engine),
            clock = { 42L },
            random = Random(9).let { r -> { n: Int -> r.nextBytes(n) } },
        )

    @Test
    fun a_lock_partway_through_aborts_the_import_within_a_chunk() {
        SystemFileSystem.createDirectories(testDir)
        val storage = VaultStorage(testDir)
        val catalog = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value
        val session = newSession()
        val vo = objects(session, storage, catalog)

        // Half a megabyte: the codec buffers, so an *unguarded* run pulls from the raw source dozens
        // of times. A payload small enough to fit one refill would make this test pass either way.
        val chunkSize = 8192
        val data = ByteArray(512 * 1024) { (it % 251).toByte() }
        val opener = TrippingSourceOpener(data, tripAfterReads = 3) { session.close() }

        val result = vo.importObject(
            kind = VaultItemKind.FILE.code,
            name = "interrupted.bin",
            mime = "application/octet-stream",
            chunkSize = chunkSize,
            openSource = opener::open,
        )

        assertIs<Outcome.Err>(result, "a locked session must fail the import, not finish it")

        // The point of the guard: the codec stopped pulling within a read or two of the lock instead
        // of streaming the rest of the file. Unguarded, this payload takes dozens of reads.
        assertTrue(
            opener.reads <= 5,
            "the import must abort within a read of the lock, but read ${opener.reads} times",
        )

        // Nothing survives: no catalog row, no object bytes, no scratch.
        assertEquals(0L, (catalog.countItems() as Outcome.Ok).value, "no row for an aborted import")
        assertEquals(emptyList(), SystemFileSystem.list(Path(testDir, "tmp")).toList(), "no scratch")
        assertFalse(
            SystemFileSystem.list(Path(testDir, "objects")).any { hasFiles(it) },
            "an aborted import must leave no object bytes behind",
        )
        catalog.close()
    }

    @Test
    fun a_caller_can_abort_an_import_without_locking_the_vault() {
        SystemFileSystem.createDirectories(testDir)
        val storage = VaultStorage(testDir)
        val catalog = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value
        val session = newSession()
        val vo = objects(session, storage, catalog)

        // A user cancel, not a lock: the session stays open throughout, and must still be usable
        // afterwards — cancelling one file in a batch cannot cost the user the rest of the batch.
        var cancelled = false
        val data = ByteArray(512 * 1024) { (it % 251).toByte() }
        val opener = TrippingSourceOpener(data, tripAfterReads = 3) { cancelled = true }

        val result = vo.importObject(
            kind = VaultItemKind.FILE.code,
            name = "abandoned.bin",
            mime = "application/octet-stream",
            chunkSize = 8192,
            isActive = { !cancelled },
            openSource = opener::open,
        )

        assertIs<Outcome.Err>(result, "a cancelled import must not report success")
        assertTrue(session.isOpen, "a cancel is not a lock: the session stays open")

        // Coroutine cancellation cannot interrupt a blocking read, so without the predicate this
        // would run to the end of the file — two minutes for a large video.
        assertTrue(
            opener.reads <= 5,
            "the import must abort within a read of the cancel, but read ${opener.reads} times",
        )

        // Same fail-closed cleanup a lock gets: a cancel is not a licence to leave scratch behind.
        assertEquals(0L, (catalog.countItems() as Outcome.Ok).value, "no row for a cancelled import")
        assertEquals(emptyList(), SystemFileSystem.list(Path(testDir, "tmp")).toList(), "no scratch")
        assertFalse(
            SystemFileSystem.list(Path(testDir, "objects")).any { hasFiles(it) },
            "a cancelled import must leave no object bytes behind",
        )

        // The vault still works: the next file in the batch imports normally.
        val next = vo.importObject(
            kind = VaultItemKind.FILE.code,
            name = "next.bin",
            mime = "application/octet-stream",
            openSource = { Buffer().apply { write(ByteArray(64) { it.toByte() }) } },
        )
        assertIs<Outcome.Ok<ByteArray>>(next, "a cancel must not poison the session for later files")
        catalog.close()
    }

    @Test
    fun an_open_session_reads_the_whole_payload() {
        SystemFileSystem.createDirectories(testDir)
        val storage = VaultStorage(testDir)
        val catalog = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value
        val session = newSession()
        val vo = objects(session, storage, catalog)

        val chunkSize = 64
        val data = ByteArray(chunkSize * 40) { (it % 251).toByte() }
        val opener = TrippingSourceOpener(data, tripAfterReads = Int.MAX_VALUE) { }

        val result = vo.importObject(
            kind = VaultItemKind.FILE.code,
            name = "complete.bin",
            mime = "application/octet-stream",
            chunkSize = chunkSize,
            openSource = opener::open,
        )

        assertIs<Outcome.Ok<ByteArray>>(result)
        // The guard must not truncate a healthy import: the stored object still round-trips to the
        // exact bytes. (Read counts are not the assertion here — the codec buffers, so it pulls from
        // the raw source once per segment refill rather than once per chunk.)
        val out = Buffer()
        assertIs<Outcome.Ok<Unit>>(vo.openObject(result.value, out))
        assertContentEquals(data, out.readByteArray(), "an uninterrupted import is byte-exact")
        catalog.close()
    }

    private fun hasFiles(path: Path): Boolean {
        val md = SystemFileSystem.metadataOrNull(path) ?: return false
        return if (md.isDirectory) SystemFileSystem.list(path).any { hasFiles(it) } else true
    }
}

/** Opens sources that fire [onTrip] once [tripAfterReads] reads have happened across all of them. */
private class TrippingSourceOpener(
    private val data: ByteArray,
    private val tripAfterReads: Int,
    private val onTrip: () -> Unit,
) {
    var reads = 0
        private set

    fun open(): RawSource = object : RawSource {
        private val buffer = Buffer().apply { write(data) }

        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            reads++
            if (reads == tripAfterReads) onTrip()
            return buffer.readAtMostTo(sink, byteCount)
        }

        override fun close() = buffer.close()
    }
}

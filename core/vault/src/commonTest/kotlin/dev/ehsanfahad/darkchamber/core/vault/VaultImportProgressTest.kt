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
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Import progress is reported as **cumulative bytes across both codec passes**.
 *
 * The `.dchm` write reads its payload twice — size + digest, then the chunk encrypt — which is an
 * implementation detail of the container, not something a user should ever see. A counter that
 * restarts at pass 2 draws a bar that fills, empties and fills again; one that reports only pass 2
 * leaves the first half of a long import looking frozen. So the contract is a single monotonic
 * count running 0 → `2 × size`, and the denominator stays with the feature layer that draws it
 * (this module owns no percentages, no totals and no idea what a bar is).
 */
class VaultImportProgressTest {
    private val testDir = Path(SystemTemporaryDirectory, "vprog-${Random.nextULong()}")
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
    fun progress_runs_once_from_zero_to_twice_the_payload() {
        SystemFileSystem.createDirectories(testDir)
        val storage = VaultStorage(testDir)
        val catalog = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value
        val session = newSession()
        val vo = objects(session, storage, catalog)

        // Half a megabyte at an 8 KiB chunk: the codec buffers, so this pulls from the source dozens
        // of times per pass. A payload that fits one buffer refill would report twice and pass
        // whatever the implementation did — the vacuous version of this test.
        val size = 512L * 1024
        val data = ByteArray(size.toInt()) { (it % 251).toByte() }
        val reports = mutableListOf<Long>()

        val result = vo.importObject(
            kind = VaultItemKind.FILE.code,
            name = "big.bin",
            mime = "application/octet-stream",
            chunkSize = 8192,
            onProgress = { reports += it },
            openSource = { ReplayingSource(data) },
        )

        assertIs<Outcome.Ok<ByteArray>>(result)
        assertTrue(reports.size > 20, "expected progress across many reads, got ${reports.size} reports")

        // Monotonic: a count that restarted for pass 2 — or reported per-pass — shows up here as a
        // step backwards, which is the bar visibly emptying.
        reports.zipWithNext { a, b ->
            assertTrue(b >= a, "progress went backwards: $a then $b")
        }

        // Both passes counted, exactly once each. size would mean only one pass reported.
        assertEquals(2 * size, reports.last(), "progress must end at twice the payload size")
        assertTrue(reports.first() in 1..size, "the first report must be a real read, not 0 or a total")

        // The first pass is genuinely visible, not a jump from 0 to the halfway mark once it ends.
        assertTrue(
            reports.count { it < size } > 10,
            "the size+digest pass must report as it goes, not once at the end",
        )
        catalog.close()
    }

    @Test
    fun an_import_with_no_progress_callback_still_completes() {
        SystemFileSystem.createDirectories(testDir)
        val storage = VaultStorage(testDir)
        val catalog = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value
        val session = newSession()
        val vo = objects(session, storage, catalog)

        // Reporting is opt-in: every existing caller omits it, and the parameter must not become a
        // required one on the import path.
        val result = vo.importObject(
            kind = VaultItemKind.FILE.code,
            name = "quiet.bin",
            mime = "application/octet-stream",
            openSource = { ReplayingSource(ByteArray(4096) { it.toByte() }) },
        )

        assertIs<Outcome.Ok<ByteArray>>(result)
        catalog.close()
    }

    @Test
    fun an_empty_payload_reports_no_bytes_and_still_imports() {
        SystemFileSystem.createDirectories(testDir)
        val storage = VaultStorage(testDir)
        val catalog = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value
        val session = newSession()
        val vo = objects(session, storage, catalog)

        val reports = mutableListOf<Long>()
        val result = vo.importObject(
            kind = VaultItemKind.FILE.code,
            name = "empty.bin",
            mime = "application/octet-stream",
            onProgress = { reports += it },
            openSource = { ReplayingSource(ByteArray(0)) },
        )

        assertIs<Outcome.Ok<ByteArray>>(result)
        // Zero bytes read is zero bytes reported — never a synthetic "done" the feature layer would
        // have to tell apart from a real one.
        assertTrue(reports.all { it == 0L }, "an empty payload must not report bytes it never read")
        catalog.close()
    }
}

/** A re-openable source over a fixed payload — each open replays the same bytes from the start. */
private class ReplayingSource(data: ByteArray) : RawSource {
    private val buffer = Buffer().apply { write(data) }

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = buffer.readAtMostTo(sink, byteCount)

    override fun close() = buffer.close()
}

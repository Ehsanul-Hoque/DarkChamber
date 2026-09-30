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
 * Import progress is reported as a **phase plus cumulative bytes within that phase**.
 *
 * The encrypt phase covers both of the codec's passes — size + digest, then the chunk encrypt —
 * which is an implementation detail of the container, not something a user should ever see. A
 * counter that restarts at pass 2 draws a bar that fills, empties and fills again; one that reports
 * only pass 2 leaves the first half of a long import looking frozen. So within the phase the count
 * is a single monotonic run 0 → `2 × size`.
 *
 * The phase is there because the denominator differs between them, and only this module knows which
 * one applies — the payload's route through the import is its business, not the caller's. What has
 * *not* changed is who owns the denominator: this module still reports no percentage, no total and
 * no idea what a bar is.
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

    private fun objects(
        session: VaultSession,
        storage: VaultStorage,
        catalog: CatalogStore,
        thumbnails: ThumbnailEncoder = NoThumbnails,
    ) = VaultObjects(
        session = session,
        storage = storage,
        catalog = catalog,
        codec = DchmCodec(engine),
        clock = { 42L },
        random = Random(9).let { r -> { n: Int -> r.nextBytes(n) } },
        thumbnails = thumbnails,
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
        val phases = mutableListOf<ImportPhase>()
        val reports = mutableListOf<Long>()

        val result = vo.importObject(
            kind = VaultItemKind.FILE.code,
            name = "big.bin",
            mime = "application/octet-stream",
            chunkSize = 8192,
            onProgress = { phase, bytes ->
                phases += phase
                reports += bytes
            },
            openSource = { ReplayingSource(data) },
        )

        assertIs<Outcome.Ok<ByteArray>>(result)
        assertTrue(reports.size > 20, "expected progress across many reads, got ${reports.size} reports")

        // Every counted read of a re-openable source is the encrypt phase. Nothing was spilled, so
        // there is no copy to report. The preview attempt is announced whatever the file is — the
        // import asks about everything and the encoder declines what it cannot handle — but it
        // carries no bytes, so it cannot appear among the counted reads.
        val counted = reports.indices.filter { reports[it] > 0L }.map { phases[it] }
        assertTrue(
            counted.all { it == ImportPhase.ENCRYPTING },
            "counted reads must report the encrypt phase, got ${counted.distinct()}",
        )
        assertTrue(
            ImportPhase.COPYING !in phases,
            "a payload that streamed in place must report no copy phase",
        )

        // Monotonic **within the phase**: a count that restarted for pass 2 — or reported per-pass —
        // shows up here as a step backwards, which is the bar visibly emptying. Across a phase
        // boundary a reset is correct, so the check is scoped to the one phase that carries a count.
        val encrypting = reports.indices.filter { phases[it] == ImportPhase.ENCRYPTING }.map { reports[it] }
        encrypting.zipWithNext { a, b ->
            assertTrue(b >= a, "progress went backwards: $a then $b")
        }

        // Both passes counted, exactly once each. size would mean only one pass reported.
        assertEquals(2 * size, encrypting.last(), "progress must end at twice the payload size")
        assertTrue(encrypting.first() in 1..size, "the first report must be a real read, not 0 or a total")

        // The first pass is genuinely visible, not a jump from 0 to the halfway mark once it ends.
        assertTrue(
            encrypting.count { it < size } > 10,
            "the size+digest pass must report as it goes, not once at the end",
        )
        catalog.close()
    }

    /**
     * The spill copy is the whole of a streaming provider's transfer, and it is the one stretch of
     * such an import where real bytes with a real total exist. Left uncounted it would be a long
     * indeterminate wait in the middle of a bar that otherwise moves.
     *
     * The second half of this test is the part that matters: the encrypt phase must start again from
     * zero. A single counter carried across the boundary would open the encrypt phase at the payload
     * size, and a caller dividing by `2 × size` would draw a bar that begins half full.
     */
    @Test
    fun a_spilled_payload_is_counted_as_its_own_phase_and_the_encrypt_starts_over() {
        SystemFileSystem.createDirectories(testDir)
        val storage = VaultStorage(testDir)
        val catalog = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value
        val session = newSession()
        val vo = objects(session, storage, catalog)

        val size = 512L * 1024
        val data = ByteArray(size.toInt()) { (it % 251).toByte() }
        val reports = mutableListOf<Pair<ImportPhase, Long>>()

        // maxOpens = 1: the re-openability probe fails, so the payload takes the spill path.
        val opener = OneShotOpener(data)
        val result = vo.importObject(
            kind = VaultItemKind.FILE.code,
            name = "one-shot.bin",
            mime = "application/octet-stream",
            chunkSize = 8192,
            onProgress = { phase, bytes -> reports += phase to bytes },
            openSource = opener::open,
        )

        assertIs<Outcome.Ok<ByteArray>>(result)

        val copying = reports.filter { it.first == ImportPhase.COPYING }.map { it.second }
        val encrypting = reports.filter { it.first == ImportPhase.ENCRYPTING }.map { it.second }

        assertTrue(copying.size > 20, "the copy must report as it drains, got ${copying.size} reports")
        assertEquals(size, copying.last(), "the copy phase must end at exactly the payload size")
        copying.zipWithNext { a, b -> assertTrue(b >= a, "the copy went backwards: $a then $b") }

        // The boundary. Not "roughly zero" — the first encrypt report is one buffer refill, which is
        // far below the payload size, and a shared counter would make it exactly `size` plus one.
        assertTrue(
            encrypting.first() in 1..<size,
            "the encrypt phase must start over, not continue the copy's count: got ${encrypting.first()}",
        )
        assertEquals(2 * size, encrypting.last(), "the encrypt phase still ends at twice the payload")

        // Phases do not interleave: everything copied before anything was encrypted, and the preview
        // attempt comes after both.
        val phases = reports.map { it.first }
        assertEquals(
            listOf(ImportPhase.COPYING, ImportPhase.ENCRYPTING, ImportPhase.PREVIEWING),
            phases.distinct(),
            "the copy must finish before the encrypt begins",
        )
        catalog.close()
    }

    /**
     * The preview phase exists to stop the bar sitting full while work continues: the encrypt phase
     * ends at `2 × size` and the encoder then reads the payload again, which used to happen with
     * nothing reported at all.
     *
     * It reports **no bytes**, deliberately. The encoder may pass over the payload as many times as
     * it needs — subsample the header, read an orientation tag, decode — and that count is its own
     * business, so there is nothing to divide by. Counting those reads would also push the encrypt
     * phase past its own denominator, which is the defect this replaces rather than repeats.
     */
    @Test
    fun a_preview_attempt_is_announced_once_with_no_bytes_and_its_reads_go_uncounted() {
        SystemFileSystem.createDirectories(testDir)
        val storage = VaultStorage(testDir)
        val catalog = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value
        val session = newSession()
        val encoder = FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Encode)
        val vo = objects(session, storage, catalog, encoder)

        val size = 64L * 1024
        val reports = mutableListOf<Pair<ImportPhase, Long>>()
        val result = vo.importObject(
            kind = VaultItemKind.FILE.code,
            name = "photo.jpg",
            mime = "image/jpeg",
            chunkSize = 4096,
            onProgress = { phase, bytes -> reports += phase to bytes },
            openSource = { ReplayingSource(ByteArray(size.toInt()) { it.toByte() }) },
        )

        assertIs<Outcome.Ok<ByteArray>>(result)
        assertEquals(1, encoder.encodeCount, "the encoder must have been asked exactly once")

        val previewing = reports.filter { it.first == ImportPhase.PREVIEWING }
        assertEquals(1, previewing.size, "the attempt is one announcement, not a stream of them")
        assertEquals(0L, previewing.single().second, "the preview phase has no bytes to report")

        // The encoder read the payload again; none of it was counted. A count that included those
        // reads would run past the encrypt phase's own denominator.
        val encrypting = reports.filter { it.first == ImportPhase.ENCRYPTING }.map { it.second }
        assertEquals(2 * size, encrypting.max(), "the preview's reads must not be counted")

        // And it comes last: the phase describes work that starts once the payload is sealed.
        assertEquals(
            listOf(ImportPhase.ENCRYPTING, ImportPhase.PREVIEWING),
            reports.map { it.first }.distinct(),
            "the preview attempt follows the encrypt",
        )
        catalog.close()
    }

    /**
     * The phase reports the **attempt**, not its outcome.
     *
     * Whether a file can have a preview is something only the encoder knows — the import asks about
     * everything and the encoder declines what it does not handle. Testing the MIME here instead
     * would copy the encoder's knowledge into the caller and go stale the day another kind gains
     * previews, so a file that gets no preview passes through this phase too.
     */
    @Test
    fun a_file_that_gets_no_preview_still_announces_the_attempt() {
        SystemFileSystem.createDirectories(testDir)
        val storage = VaultStorage(testDir)
        val catalog = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value
        val session = newSession()
        val vo = objects(session, storage, catalog, FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Decline))

        val phases = mutableListOf<ImportPhase>()
        val result = vo.importObject(
            kind = VaultItemKind.FILE.code,
            name = "clip.mp4",
            mime = "video/mp4",
            chunkSize = 4096,
            onProgress = { phase, _ -> phases += phase },
            openSource = { ReplayingSource(ByteArray(8192) { it.toByte() }) },
        )

        assertIs<Outcome.Ok<ByteArray>>(result)
        assertEquals(1, phases.count { it == ImportPhase.PREVIEWING }, "the attempt is announced either way")
        catalog.close()
    }

    /** A decoder that blows up has still been attempted, and the import still succeeds without one. */
    @Test
    fun an_encoder_that_throws_has_still_announced_its_attempt() {
        SystemFileSystem.createDirectories(testDir)
        val storage = VaultStorage(testDir)
        val catalog = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value
        val session = newSession()
        val vo = objects(session, storage, catalog, FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Throw))

        val phases = mutableListOf<ImportPhase>()
        val result = vo.importObject(
            kind = VaultItemKind.FILE.code,
            name = "corrupt.jpg",
            mime = "image/jpeg",
            chunkSize = 4096,
            onProgress = { phase, _ -> phases += phase },
            openSource = { ReplayingSource(ByteArray(8192) { it.toByte() }) },
        )

        assertIs<Outcome.Ok<ByteArray>>(result, "a thumbnail is a cache; losing one cannot fail an import")
        assertEquals(1, phases.count { it == ImportPhase.PREVIEWING }, "the attempt happened, so it is reported")
        catalog.close()
    }

    @Test
    fun a_reopenable_payload_reports_no_copy_phase() {
        SystemFileSystem.createDirectories(testDir)
        val storage = VaultStorage(testDir)
        val catalog = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value
        val session = newSession()
        val vo = objects(session, storage, catalog)

        val phases = mutableListOf<ImportPhase>()
        val result = vo.importObject(
            kind = VaultItemKind.FILE.code,
            name = "streamed.bin",
            mime = "application/octet-stream",
            chunkSize = 8192,
            onProgress = { phase, _ -> phases += phase },
            openSource = { ReplayingSource(ByteArray(64 * 1024) { it.toByte() }) },
        )

        assertIs<Outcome.Ok<ByteArray>>(result)
        // Nothing was copied, so nothing may be reported as copying. A phase reported for work that
        // did not happen is a label on a bar that cannot move.
        assertTrue(
            ImportPhase.COPYING !in phases,
            "a payload that streamed in place must report no copy phase",
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
            onProgress = { _, bytes -> reports += bytes },
            openSource = { ReplayingSource(ByteArray(0)) },
        )

        assertIs<Outcome.Ok<ByteArray>>(result)
        // Zero bytes read is zero bytes reported — never a synthetic "done" the feature layer would
        // have to tell apart from a real one.
        assertTrue(reports.all { it == 0L }, "an empty payload must not report bytes it never read")
        catalog.close()
    }
}

/**
 * Opens once and refuses thereafter — the shape of a provider stream that has to be spilled.
 *
 * The re-openability probe asks for a second source before reading anything, so a factory that
 * allows exactly one open is what drives an import down the spill path.
 */
private class OneShotOpener(private val data: ByteArray) {
    private var opens = 0

    fun open(): RawSource {
        opens++
        if (opens > 1) throw IOException("this source opens only once")
        return ReplayingSource(data)
    }
}

/** A re-openable source over a fixed payload — each open replays the same bytes from the start. */
private class ReplayingSource(data: ByteArray) : RawSource {
    private val buffer = Buffer().apply { write(data) }

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = buffer.readAtMostTo(sink, byteCount)

    override fun close() = buffer.close()
}

package dev.ehsanfahad.darkchamber.core.vault

import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.crypto.DchmCodec
import dev.ehsanfahad.darkchamber.core.crypto.DefaultKeyDerivation
import dev.ehsanfahad.darkchamber.core.crypto.PassphraseKdfProfile
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bounded-RAM streaming proof: push [streamTestBytes] through import → open
 * with a re-openable [GeneratingSource] and a discarding [CountingHashingSink], so the plaintext is
 * never held whole. The default 16 MiB spans 16 chunks; the 2 GiB opt-in
 * (`-Ddchm.stream.bytes=2147483648`) runs the identical path and needs ~size scratch disk for the
 * persisted container. The real cross-platform 2 GiB memory proof is the on-device checklist.
 */
class VaultStreamingTest {
    private val testDir = Path(SystemTemporaryDirectory, "vst-${Random.nextULong()}")
    private val engine = CryptographyKotlinEngine()
    private val kdf = DefaultKeyDerivation(engine)
    private val cheapKdf = PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1)

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    @Test
    fun bounded_ram_streaming_round_trip_matches_size_and_digest() {
        val size = streamTestBytes()
        SystemFileSystem.createDirectories(testDir)
        val storage = VaultStorage(testDir)
        val catalog = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value
        val session = VaultSession.createVault(engine, kdf, "correct horse battery".encodeToByteArray(), cheapKdf).session
        val rng = Random(3)
        val vo = VaultObjects(session, storage, catalog, DchmCodec(engine), clock = { 0L }, random = { n -> rng.nextBytes(n) })

        // The expected plaintext digest, generated the same deterministic way in bounded blocks.
        val expected = engine.newSha256().also { h ->
            var pos = 0L
            val block = ByteArray(1 shl 16)
            while (pos < size) {
                val n = minOf(block.size.toLong(), size - pos).toInt()
                for (i in 0 until n) block[i] = streamByte(pos + i)
                h.update(block, 0, n)
                pos += n
            }
        }.digest()

        // Import at the default 1 MiB chunk size, then stream-decrypt into the discarding sink.
        val id = (vo.importObject(kind = 0, name = "big.bin", mime = "application/octet-stream", openSource = { GeneratingSource(size) }) as Outcome.Ok).value
        val sink = CountingHashingSink(engine.newSha256())
        assertTrue(vo.openObject(id, sink) is Outcome.Ok, "streaming open failed")
        assertEquals(size, sink.bytes, "decrypted byte count must equal the source size")
        assertContentEquals(expected, sink.digest(), "round-trip digest mismatch")

        session.close()
    }

    private fun deleteRecursively(path: Path) {
        val md = SystemFileSystem.metadataOrNull(path) ?: return
        if (md.isDirectory) for (child in SystemFileSystem.list(path)) deleteRecursively(child)
        SystemFileSystem.delete(path, mustExist = false)
    }
}

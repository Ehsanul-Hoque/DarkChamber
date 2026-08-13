package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The `.dchm` format-vector gate (FORMAT.md §7) — the release-blocking crown jewel. Runs on the JVM
 * host, reading the committed vectors under `docs/format-test-vectors/` (located via the
 * `dchm.vectors.dir` system property set by the build). Positives must encode to exactly the
 * committed bytes and decode back to the plaintext; negatives must fail with the stated error;
 * and every supported (version, cipher_id) must have a vector set.
 */
@OptIn(ExperimentalStdlibApi::class)
class FormatVectorTest {
    @Serializable
    data class PositiveVector(
        val name: String, val version: Int, val cipher_id: Int, val dek: String, val nonce_prefix: String,
        val created_at: Long, val file_id: String, val original_name: String, val mime: String,
        val chunk_size: Int, val plaintext: String, val container: String,
    )

    @Serializable
    data class NegativeVector(val name: String, val from: String, val mutation: String, val expected_error: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val codec = DchmCodec(CryptographyKotlinEngine())

    private val vectorsDir: File =
        File(requireNotNull(System.getProperty("dchm.vectors.dir")) { "dchm.vectors.dir not set" })

    private fun cipherDirs(): List<File> =
        vectorsDir.listFiles { f -> f.isDirectory && File(f, "positive.json").exists() }?.toList() ?: emptyList()

    private fun positives(dir: File): List<PositiveVector> =
        json.decodeFromString(File(dir, "positive.json").readText())

    private fun negatives(dir: File): List<NegativeVector> =
        File(dir, "negative.json").takeIf { it.exists() }?.let { json.decodeFromString(it.readText()) } ?: emptyList()

    private fun encode(v: PositiveVector): ByteArray {
        val out = Buffer()
        val result = codec.encryptContainer(
            dek = v.dek.hexToByteArray(), noncePrefix = v.nonce_prefix.hexToByteArray(), createdAt = v.created_at,
            fileId = v.file_id.hexToByteArray(), originalName = v.original_name, mime = v.mime, chunkSize = v.chunk_size,
            openSource = { Buffer().apply { write(v.plaintext.hexToByteArray()) } }, sink = out,
        )
        assertTrue(result is Outcome.Ok, "${v.name}: encode failed: $result")
        return out.readByteArray()
    }

    private fun decode(container: ByteArray, dek: ByteArray, fileId: ByteArray): Outcome<ByteArray> {
        val out = Buffer()
        return when (val r = codec.decryptContainer(dek, fileId, Buffer().apply { write(container) }, out)) {
            is Outcome.Ok -> Outcome.Ok(out.readByteArray())
            is Outcome.Err -> r
        }
    }

    @Test
    fun positive_vectors_encode_to_the_committed_bytes_and_decode_back() {
        val all = cipherDirs().flatMap { positives(it) }
        assertTrue(all.isNotEmpty(), "no positive vectors found under $vectorsDir")
        for (v in all) {
            assertEquals(v.container, encode(v).toHexString(), "${v.name}: container mismatch")
            val decoded = decode(v.container.hexToByteArray(), v.dek.hexToByteArray(), v.file_id.hexToByteArray())
            assertTrue(decoded is Outcome.Ok, "${v.name}: decode failed: $decoded")
            assertEquals(v.plaintext, decoded.value.toHexString(), "${v.name}: plaintext mismatch")
        }
    }

    @Test
    fun negative_vectors_fail_with_the_expected_error() {
        for (dir in cipherDirs()) {
            val byName = positives(dir).associateBy { it.name }
            for (n in negatives(dir)) {
                val base = requireNotNull(byName[n.from]) { "${n.name}: unknown base ${n.from}" }
                val mutated = mutate(base.container.hexToByteArray(), n.mutation, base.chunk_size)
                val result = decode(mutated, base.dek.hexToByteArray(), base.file_id.hexToByteArray())
                assertEquals(Outcome.Err(errorOf(n.expected_error)), result, "${n.name}: wrong outcome")
            }
        }
    }

    @Test
    fun every_supported_version_and_cipher_has_a_vector_set() {
        val covered = cipherDirs().flatMap { positives(it) }.map { it.version to it.cipher_id }.toSet()
        for (pair in DchmFormat.SUPPORTED) {
            assertTrue(pair in covered, "no format vector set for (version, cipher_id) = $pair")
        }
    }

    private fun errorOf(name: String): VaultError = when (name) {
        "IntegrityFailure" -> VaultError.Crypto.IntegrityFailure
        "UnsupportedFormatVersion" -> VaultError.Crypto.UnsupportedFormatVersion
        "MalformedContainer" -> VaultError.Crypto.MalformedContainer
        else -> fail("unknown expected_error $name")
    }

    private fun mutate(container: ByteArray, mutation: String, chunkSize: Int): ByteArray = when {
        mutation.startsWith("set_byte@") -> {
            val (offset, value) = mutation.removePrefix("set_byte@").split("=")
            container.copyOf().also { it[offset.toInt()] = value.toInt().toByte() }
        }
        mutation == "flip_last_byte" ->
            container.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte() }
        mutation == "truncate_one" -> container.copyOf(container.size - 1)
        mutation == "append_zero" -> container + byteArrayOf(0)
        mutation == "flip_first_header_byte" ->
            container.copyOf().also { it[DchmFormat.PRELUDE_SIZE] = (it[DchmFormat.PRELUDE_SIZE].toInt() xor 1).toByte() }
        mutation == "swap_first_two_chunks" -> {
            val headerLen = (Prelude.read(container) as Outcome.Ok).value.headerLen
            val start = DchmFormat.PRELUDE_SIZE + headerLen
            val block = chunkSize + DchmFormat.GCM_TAG_SIZE
            container.copyOf().also {
                container.copyOfRange(start + block, start + 2 * block).copyInto(it, start)
                container.copyOfRange(start, start + block).copyInto(it, start + block)
            }
        }
        else -> fail("unknown mutation $mutation")
    }
}

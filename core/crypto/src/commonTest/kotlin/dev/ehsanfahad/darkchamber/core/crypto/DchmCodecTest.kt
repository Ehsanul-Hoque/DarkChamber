package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DchmCodecTest {
    private val codec = DchmCodec(CryptographyKotlinEngine())
    private val dek = ByteArray(32) { it.toByte() }
    private val fileId = "file-1".encodeToByteArray()

    // Distinct nonce prefix per encode (tag in the first byte) — production never reuses one, and
    // the JDK GCM provider refuses (key, iv) reuse for encryption.
    private fun prefix(tag: Int) = ByteArray(8) { i -> if (i == 0) tag.toByte() else i.toByte() }

    private fun encode(data: ByteArray, tag: Int, chunkSize: Int = 16): ByteArray {
        val out = Buffer()
        val result = codec.encryptContainer(
            dek = dek, noncePrefix = prefix(tag), createdAt = 0L, fileId = fileId,
            originalName = "n", mime = "m", chunkSize = chunkSize,
            openSource = { Buffer().apply { write(data) } }, sink = out,
        )
        assertTrue(result is Outcome.Ok, "encode failed: $result")
        return out.readByteArray()
    }

    private fun decode(container: ByteArray, fileId: ByteArray = this.fileId, dek: ByteArray = this.dek): Outcome<ByteArray> {
        val out = Buffer()
        return when (val r = codec.decryptContainer(dek, fileId, Buffer().apply { write(container) }, out)) {
            is Outcome.Ok -> Outcome.Ok(out.readByteArray())
            is Outcome.Err -> r
        }
    }

    // --- round-trip identity across sizes (chunkSize = 16) ---------------------------------------

    @Test
    fun round_trips_across_sizes_from_empty_to_multi_chunk() {
        val sizes = listOf(0, 1, 15, 16, 17, 32, 48, 100)
        for (size in sizes) {
            val data = ByteArray(size) { (it * 3 + 1).toByte() }
            val decoded = decode(encode(data, tag = size + 1))
            assertTrue(decoded is Outcome.Ok, "size=$size decode failed: $decoded")
            assertContentEquals(data, decoded.value, "size=$size round-trip mismatch")
        }
    }

    @Test
    fun empty_file_encodes_one_chunk_and_round_trips() {
        // prelude(16) + sealed header + one empty final chunk (tag only, 16 bytes).
        val container = encode(ByteArray(0), tag = 200)
        val headerLen = (Prelude.read(container) as Outcome.Ok).value.headerLen
        assertEquals(DchmFormat.PRELUDE_SIZE + headerLen + DchmFormat.GCM_TAG_SIZE, container.size)
        assertContentEquals(ByteArray(0), (decode(container) as Outcome.Ok).value)
    }

    // --- tamper evidence (all -> IntegrityFailure) -----------------------------------------------

    private fun integrityFail(container: ByteArray, fileId: ByteArray = this.fileId, dek: ByteArray = this.dek) =
        assertEquals(Outcome.Err(VaultError.Crypto.IntegrityFailure), decode(container, fileId, dek))

    @Test
    fun flipping_a_chunk_byte_fails_authentication() {
        val container = encode(ByteArray(40) { it.toByte() }, tag = 3)
        container[container.size - 1] = (container[container.size - 1].toInt() xor 0x01).toByte()
        integrityFail(container)
    }

    @Test
    fun flipping_a_sealed_header_byte_fails_authentication() {
        val container = encode(ByteArray(20) { it.toByte() }, tag = 4)
        container[DchmFormat.PRELUDE_SIZE] = (container[DchmFormat.PRELUDE_SIZE].toInt() xor 0x01).toByte()
        integrityFail(container)
    }

    @Test
    fun truncating_the_stream_fails_authentication() {
        val container = encode(ByteArray(40) { it.toByte() }, tag = 5)
        integrityFail(container.copyOf(container.size - 1))
    }

    @Test
    fun appending_trailing_bytes_fails_authentication() {
        val container = encode(ByteArray(40) { it.toByte() }, tag = 6)
        integrityFail(container + byteArrayOf(0x00))
    }

    @Test
    fun reordering_two_chunks_fails_authentication() {
        // 32 bytes @ chunkSize 16 = two full 32-byte ciphertext blocks; swap them.
        val container = encode(ByteArray(32) { it.toByte() }, tag = 7)
        val start = DchmFormat.PRELUDE_SIZE + (Prelude.read(container) as Outcome.Ok).value.headerLen
        val block = 16 + DchmFormat.GCM_TAG_SIZE
        val chunk0 = container.copyOfRange(start, start + block)
        val chunk1 = container.copyOfRange(start + block, start + 2 * block)
        chunk1.copyInto(container, start)
        chunk0.copyInto(container, start + block)
        integrityFail(container)
    }

    @Test
    fun the_wrong_file_id_fails_authentication() {
        val container = encode(ByteArray(20) { it.toByte() }, tag = 8)
        integrityFail(container, fileId = "other".encodeToByteArray())
    }

    @Test
    fun the_wrong_dek_fails_authentication() {
        val container = encode(ByteArray(20) { it.toByte() }, tag = 9)
        integrityFail(container, dek = ByteArray(32) { (it + 1).toByte() })
    }

    // --- nonce discipline ------------------------------------------------------------------------

    @Test
    fun header_and_chunk_nonces_are_disjoint_and_unique() {
        val p = prefix(1)
        val nonces = (0L until 5L).map { chunkNonce(p, it).toList() }.toMutableList()
        nonces.add(headerNonce(p).toList())
        assertEquals(nonces.size, nonces.toSet().size, "nonces must all be distinct")
        assertContentEquals(p + byteArrayOf(-1, -1, -1, -1), headerNonce(p)) // 0xFFFFFFFF counter
    }
}

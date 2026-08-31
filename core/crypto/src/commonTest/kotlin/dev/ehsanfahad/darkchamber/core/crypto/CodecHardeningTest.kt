package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CodecHardeningTest {
    private val codec = DchmCodec(CryptographyKotlinEngine())
    private val dek = ByteArray(32) { it.toByte() }
    private val prefix = ByteArray(8) { it.toByte() }
    private val fileId = "f".encodeToByteArray()

    private fun encrypt(
        dek: ByteArray = this.dek,
        noncePrefix: ByteArray = prefix,
        chunkSize: Int = 16,
        originalName: String = "n",
        data: ByteArray = "hi".encodeToByteArray(),
    ): Outcome<Long> = codec.encryptContainer(
        dek, noncePrefix, 0L, fileId, originalName, "m", chunkSize,
        { Buffer().apply { write(data) } }, Buffer(),
    )

    // --- finding 2: fail-fast input validation ---------------------------------------------------

    @Test
    fun encrypt_rejects_a_wrong_length_dek() {
        assertFailsWith<IllegalArgumentException> { encrypt(dek = ByteArray(31)) }
    }

    @Test
    fun encrypt_rejects_a_wrong_length_nonce_prefix() {
        assertFailsWith<IllegalArgumentException> { encrypt(noncePrefix = ByteArray(7)) }
    }

    @Test
    fun encrypt_rejects_a_non_positive_chunk_size() {
        assertFailsWith<IllegalArgumentException> { encrypt(chunkSize = 0) }
    }

    // --- finding 5: header that overflows the u16 header_len ------------------------------------

    @Test
    fun encrypt_fails_when_the_header_would_exceed_the_u16_length() {
        val result = encrypt(originalName = "a".repeat(70_000))
        assertTrue(result is Outcome.Err && result.error is VaultError.Io, "expected Io error, was $result")
    }

    // --- finding 1: chunk-count arithmetic underpinning the nonce-space guard --------------------

    @Test
    fun chunk_count_matches_ceiling_division_with_empty_as_one() {
        assertEquals(1L, chunkCountFor(0, 16))
        assertEquals(1L, chunkCountFor(1, 16))
        assertEquals(1L, chunkCountFor(16, 16))
        assertEquals(2L, chunkCountFor(17, 16))
        assertEquals(3L, chunkCountFor(48, 16))
        assertEquals(7L, chunkCountFor(100, 16))
    }

    @Test
    fun chunk_count_at_the_nonce_space_boundary_is_recognized() {
        // A plaintext of (2^32) one-byte chunks would need counter 0xFFFFFFFF (the reserved header
        // nonce): the count exceeds the max and must be rejected by the encode/decode guards.
        val max = DchmFormat.HEADER_NONCE_COUNTER.toLong() // 0xFFFFFFFF
        assertTrue(chunkCountFor(max + 1, 1) > max, "count for 2^32 one-byte chunks must exceed the bound")
        assertTrue(chunkCountFor(max, 1) <= max, "count for (2^32 - 1) one-byte chunks must fit")
    }
}

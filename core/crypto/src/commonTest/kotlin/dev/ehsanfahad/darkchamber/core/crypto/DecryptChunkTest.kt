package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecryptChunkTest {
    private val codec = DchmCodec(CryptographyKotlinEngine())
    private val dek = ByteArray(32) { it.toByte() }
    private val fileId = "file-1".encodeToByteArray()
    private val chunkSize = 16

    private fun prefix(tag: Int) = ByteArray(8) { i -> if (i == 0) tag.toByte() else i.toByte() }

    private fun encode(data: ByteArray, tag: Int): ByteArray {
        val out = Buffer()
        val result = codec.encryptContainer(
            dek, prefix(tag), 0L, fileId, "n", "m", chunkSize,
            { Buffer().apply { write(data) } }, out,
        )
        assertTrue(result is Outcome.Ok)
        return out.readByteArray()
    }

    // Byte offset of chunk [index]'s ciphertext block, per the caller formula.
    private fun chunkOffset(container: ByteArray, index: Int): Int {
        val headerLen = (Prelude.read(container) as Outcome.Ok).value.headerLen
        return DchmFormat.PRELUDE_SIZE + headerLen + index * (chunkSize + DchmFormat.GCM_TAG_SIZE)
    }

    @Test
    fun decrypt_chunk_matches_the_container_plaintext_for_every_chunk() {
        val data = ByteArray(40) { it.toByte() } // 40 @ 16 => chunks of 16, 16, 8
        val container = encode(data, tag = 1)
        val noncePrefix = (Prelude.read(container) as Outcome.Ok).value.noncePrefix
        val plainLens = listOf(16, 16, 8)

        var dataOffset = 0
        for (index in plainLens.indices) {
            val plainLen = plainLens[index]
            val offset = chunkOffset(container, index)
            val ciphertext = container.copyOfRange(offset, offset + plainLen + DchmFormat.GCM_TAG_SIZE)
            val isLast = index == plainLens.lastIndex
            val decrypted = codec.decryptChunk(dek, noncePrefix, fileId, index.toLong(), isLast, ciphertext)
            assertContentEquals(
                data.copyOfRange(dataOffset, dataOffset + plainLen),
                (decrypted as Outcome.Ok).value,
                "chunk $index mismatch",
            )
            dataOffset += plainLen
        }
    }

    @Test
    fun decrypt_chunk_with_the_wrong_index_fails_authentication() {
        val container = encode(ByteArray(40) { it.toByte() }, tag = 2)
        val noncePrefix = (Prelude.read(container) as Outcome.Ok).value.noncePrefix
        val offset = chunkOffset(container, 0)
        val chunk0 = container.copyOfRange(offset, offset + chunkSize + DchmFormat.GCM_TAG_SIZE)
        // Claim it is chunk 1 — AAD index mismatch.
        assertEquals(
            Outcome.Err(VaultError.Crypto.IntegrityFailure),
            codec.decryptChunk(dek, noncePrefix, fileId, 1L, isLast = false, chunk0),
        )
    }

    @Test
    fun decrypt_chunk_with_the_wrong_is_last_flag_fails_authentication() {
        val container = encode(ByteArray(40) { it.toByte() }, tag = 3)
        val noncePrefix = (Prelude.read(container) as Outcome.Ok).value.noncePrefix
        val offset = chunkOffset(container, 2)
        val lastChunk = container.copyOfRange(offset, offset + 8 + DchmFormat.GCM_TAG_SIZE)
        // The real last chunk sealed with is_last=1; opening it as non-last must fail.
        assertEquals(
            Outcome.Err(VaultError.Crypto.IntegrityFailure),
            codec.decryptChunk(dek, noncePrefix, fileId, 2L, isLast = false, lastChunk),
        )
    }
}

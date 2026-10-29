package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Opening a container's layout by position: the prelude, the sealed header, the exact length and the
 * last chunk — without reading anything in between. Containers are made by the real codec; every
 * failure must be the same `VaultError` a full [DchmCodec.decryptContainer] gives for it.
 */
class OpenLayoutTest {
    private val codec = DchmCodec(CryptographyKotlinEngine())
    private val dek = ByteArray(32) { it.toByte() }
    private val fileId = "file-1".encodeToByteArray()
    // A fresh prefix for every seal in the run: the JDK provider refuses a second encryption under the
    // same key and nonce, so a reused one fails whichever test happens to come next.
    private val noncePrefix = ByteArray(8) { if (it == 0) nextPrefix++.toByte() else (0x40 + it).toByte() }

    private companion object {
        var nextPrefix = 1
    }

    private fun encode(data: ByteArray, chunkSize: Int = 16): ByteArray {
        val out = Buffer()
        val result = codec.encryptContainer(
            dek.copyOf(), noncePrefix.copyOf(), 0L, fileId.copyOf(), "a-name.bin", "application/octet-stream", chunkSize,
            { Buffer().apply { write(data) } }, out,
        )
        assertIs<Outcome.Ok<Long>>(result)
        return out.readByteArray()
    }

    // Positioned reads over the container's bytes, counting how many bytes were asked for.
    private class Bytes(private val container: ByteArray) {
        var bytesRead = 0L
        val size: Long get() = container.size.toLong()

        fun read(position: Long, length: Int): ByteArray {
            bytesRead += length
            return container.copyOfRange(position.toInt(), position.toInt() + length)
        }
    }

    private fun open(container: ByteArray, key: ByteArray = dek, id: ByteArray = fileId): Outcome<OpenedContainer> {
        val bytes = Bytes(container)
        return codec.openLayout(key.copyOf(), id.copyOf(), bytes.size, bytes::read)
    }

    @Test
    fun a_multi_chunk_container_opens_to_its_layout_and_its_last_chunk() {
        val data = ByteArray(40) { (it * 7).toByte() } // 16 + 16 + 8 at chunk size 16
        val container = encode(data)

        val opened = assertIs<Outcome.Ok<OpenedContainer>>(open(container)).value

        assertContentEquals(noncePrefix, opened.noncePrefix)
        assertEquals(16, opened.layout.chunkSize)
        assertEquals(40L, opened.layout.plaintextSize)
        assertEquals(3L, opened.layout.chunkCount)
        assertEquals(container.size.toLong(), opened.layout.containerLength)
        assertContentEquals(data.copyOfRange(32, 40), opened.lastChunk)
    }

    // What a full decrypt says about the same bytes — the error an open must agree with.
    private fun fullDecryptError(container: ByteArray, key: ByteArray = dek, id: ByteArray = fileId): VaultError =
        assertIs<Outcome.Err>(codec.decryptContainer(key.copyOf(), id.copyOf(), Buffer().apply { write(container) }, Buffer())).error

    private fun assertFailsAtOpenLikeAFullDecrypt(
        expected: VaultError,
        container: ByteArray,
        key: ByteArray = dek,
        id: ByteArray = fileId,
    ) {
        assertEquals(expected, fullDecryptError(container, key, id), "the full decrypt's own answer changed")
        assertEquals(expected, assertIs<Outcome.Err>(open(container, key, id)).error)
    }

    @Test
    fun a_container_one_byte_short_fails_at_open_as_tampering() {
        val container = encode(ByteArray(40) { it.toByte() })

        assertFailsAtOpenLikeAFullDecrypt(VaultError.Crypto.IntegrityFailure, container.copyOf(container.size - 1))
    }

    @Test
    fun a_container_with_one_byte_appended_fails_at_open_as_tampering() {
        val container = encode(ByteArray(40) { it.toByte() })

        assertFailsAtOpenLikeAFullDecrypt(VaultError.Crypto.IntegrityFailure, container + byteArrayOf(0))
    }

    @Test
    fun a_container_opened_under_another_file_id_fails_at_open() {
        // The header does not carry file_id — only chunk AAD does — so this is the last chunk's check.
        val container = encode(ByteArray(40) { it.toByte() })

        assertFailsAtOpenLikeAFullDecrypt(VaultError.Crypto.IntegrityFailure, container, id = "file-2".encodeToByteArray())
    }

    @Test
    fun a_tampered_last_chunk_fails_at_open() {
        val container = encode(ByteArray(40) { it.toByte() })
        container[container.size - 1] = (container[container.size - 1].toInt() xor 0x01).toByte()

        assertFailsAtOpenLikeAFullDecrypt(VaultError.Crypto.IntegrityFailure, container)
    }

    @Test
    fun the_wrong_key_fails_at_open() {
        val container = encode(ByteArray(40) { it.toByte() })

        assertFailsAtOpenLikeAFullDecrypt(VaultError.Crypto.IntegrityFailure, container, key = ByteArray(32) { 0x55 })
    }

    @Test
    fun a_tampered_header_fails_at_open() {
        val container = encode(ByteArray(40) { it.toByte() })
        container[DchmFormat.PRELUDE_SIZE + 3] = (container[DchmFormat.PRELUDE_SIZE + 3].toInt() xor 0x01).toByte()

        assertFailsAtOpenLikeAFullDecrypt(VaultError.Crypto.IntegrityFailure, container)
    }

    @Test
    fun bytes_that_are_not_a_container_are_malformed() {
        val container = encode(ByteArray(40) { it.toByte() })
        container[0] = 'X'.code.toByte()

        assertFailsAtOpenLikeAFullDecrypt(VaultError.Crypto.MalformedContainer, container)
    }

    @Test
    fun a_newer_version_or_an_unknown_cipher_asks_for_an_update() {
        for (at in listOf(4, 5)) { // version byte, cipher_id byte
            val container = encode(ByteArray(40) { it.toByte() })
            container[at] = 0x7F

            assertFailsAtOpenLikeAFullDecrypt(VaultError.Crypto.UnsupportedFormatVersion, container)
        }
    }

    @Test
    fun too_few_bytes_for_a_prelude_is_malformed() {
        val container = encode(ByteArray(40) { it.toByte() })

        assertFailsAtOpenLikeAFullDecrypt(VaultError.Crypto.MalformedContainer, container.copyOf(DchmFormat.PRELUDE_SIZE - 1))
    }

    // A prelude and a header sealed properly under the right key, but carrying values no encoder
    // writes — the case where authentication passes and the numbers still cannot be trusted.
    private fun sealedHeaderOnly(chunkSize: Int, plaintextSize: Long): ByteArray {
        val engine = CryptographyKotlinEngine()
        val prefix = ByteArray(8) { if (it == 0) nextPrefix++.toByte() else 0x60 }
        val cbor = DchmHeaderCodec.encode(DchmHeader("n", "m", plaintextSize, 0L, ByteArray(32), chunkSize))
        val prelude = Prelude(DchmFormat.VERSION, DchmFormat.CIPHER_AES_256_GCM, cbor.size + DchmFormat.GCM_TAG_SIZE, prefix)
        val preludeBytes = prelude.toByteArray()
        return preludeBytes + engine.aeadSeal(dek.copyOf(), headerNonce(prefix.copyOf()), preludeBytes, cbor)
    }

    @Test
    fun an_authenticated_header_with_impossible_numbers_is_malformed() {
        val cases = listOf(
            0 to 40L, // no chunk size
            16 to -1L, // a negative size
            16 to (DchmFormat.HEADER_NONCE_COUNTER.toLong() + 1) * 16, // more chunks than the nonce space allows
        )
        for ((chunkSize, plaintextSize) in cases) {
            assertFailsAtOpenLikeAFullDecrypt(VaultError.Crypto.MalformedContainer, sealedHeaderOnly(chunkSize, plaintextSize))
        }
    }

    @Test
    fun an_empty_object_opens_to_one_empty_last_chunk() {
        val container = encode(ByteArray(0))

        val opened = assertIs<Outcome.Ok<OpenedContainer>>(open(container)).value

        assertEquals(1L, opened.layout.chunkCount)
        assertEquals(0L, opened.layout.plaintextSize)
        assertContentEquals(ByteArray(0), opened.lastChunk)
    }

    @Test
    fun opening_reads_only_the_prelude_the_header_and_the_last_chunk() {
        // Ten chunks of 16; the eight in the middle must not be read.
        val container = encode(ByteArray(160) { it.toByte() })
        val bytes = Bytes(container)
        val headerLen = assertIs<Outcome.Ok<Prelude>>(Prelude.read(container)).value.headerLen

        assertIs<Outcome.Ok<OpenedContainer>>(codec.openLayout(dek.copyOf(), fileId.copyOf(), bytes.size, bytes::read))

        assertEquals((DchmFormat.PRELUDE_SIZE + headerLen + 16 + DchmFormat.GCM_TAG_SIZE).toLong(), bytes.bytesRead)
    }

    @Test
    fun a_read_that_fails_is_an_Io_error_not_a_throw() {
        val container = encode(ByteArray(40) { it.toByte() })

        val result = codec.openLayout(dek.copyOf(), fileId.copyOf(), container.size.toLong()) { _, _ -> throw IllegalStateException("gone") }

        assertIs<VaultError.Io>(assertIs<Outcome.Err>(result).error)
    }

    // The real engine, keeping hold of every plaintext its header open hands out.
    private class RecordingEngine(private val real: CryptoEngine = CryptographyKotlinEngine()) : CryptoEngine by real {
        val opened = mutableListOf<ByteArray>()

        override fun aeadOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): Outcome<ByteArray> =
            real.aeadOpen(key, nonce, aad, ciphertext).also { if (it is Outcome.Ok) opened += it.value }
    }

    @Test
    fun the_decrypted_header_bytes_are_zeroed_once_read() {
        val container = encode(ByteArray(40) { it.toByte() })
        val engine = RecordingEngine()
        val bytes = Bytes(container)

        val opened = assertIs<Outcome.Ok<OpenedContainer>>(DchmCodec(engine).openLayout(dek.copyOf(), fileId.copyOf(), bytes.size, bytes::read)).value

        // The first plaintext out of the engine is the header's CBOR; the second is the last chunk, which the caller now owns.
        assertEquals(2, engine.opened.size)
        assertTrue(engine.opened[0].isNotEmpty() && engine.opened[0].all { it == 0.toByte() })
        assertContentEquals(opened.lastChunk, engine.opened[1])
    }

    @Test
    fun a_prelude_with_its_header_cut_short_fails_as_tampering() {
        val container = encode(ByteArray(40) { it.toByte() })

        assertFailsAtOpenLikeAFullDecrypt(VaultError.Crypto.IntegrityFailure, container.copyOf(DchmFormat.PRELUDE_SIZE + 10))
    }
}

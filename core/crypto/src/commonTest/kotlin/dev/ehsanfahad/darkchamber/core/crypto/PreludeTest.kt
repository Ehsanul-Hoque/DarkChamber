package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PreludeTest {
    private val noncePrefix = ByteArray(8) { (it + 1).toByte() }

    @Test
    fun prelude_round_trips_through_bytes() {
        val prelude = Prelude(
            version = DchmFormat.VERSION,
            cipherId = DchmFormat.CIPHER_AES_256_GCM,
            headerLen = 300,
            noncePrefix = noncePrefix,
        )
        val bytes = prelude.toByteArray()
        assertEquals(DchmFormat.PRELUDE_SIZE, bytes.size)

        val decoded = Prelude.read(bytes)
        assertEquals(Outcome.Ok(prelude), decoded)
    }

    @Test
    fun read_rejects_bad_magic_as_malformed() {
        val bytes = Prelude(DchmFormat.VERSION, DchmFormat.CIPHER_AES_256_GCM, 1, noncePrefix).toByteArray()
        bytes[0] = 'X'.code.toByte()
        assertEquals(Outcome.Err(VaultError.Crypto.MalformedContainer), Prelude.read(bytes))
    }

    @Test
    fun read_rejects_a_truncated_prelude_as_malformed() {
        assertEquals(Outcome.Err(VaultError.Crypto.MalformedContainer), Prelude.read(ByteArray(10)))
    }

    @Test
    fun read_rejects_a_higher_version_as_unsupported() {
        val bytes = Prelude(DchmFormat.VERSION, DchmFormat.CIPHER_AES_256_GCM, 1, noncePrefix).toByteArray()
        bytes[4] = 2 // version byte
        assertEquals(Outcome.Err(VaultError.Crypto.UnsupportedFormatVersion), Prelude.read(bytes))
    }

    @Test
    fun read_rejects_an_unknown_cipher_as_unsupported() {
        val bytes = Prelude(DchmFormat.VERSION, DchmFormat.CIPHER_AES_256_GCM, 1, noncePrefix).toByteArray()
        bytes[5] = 2 // cipher_id byte (0x02 reserved ChaCha, unsupported in 2a)
        assertEquals(Outcome.Err(VaultError.Crypto.UnsupportedFormatVersion), Prelude.read(bytes))
    }

    @Test
    fun prelude_bytes_are_little_endian_for_header_len() {
        // headerLen = 0x0102 → LE bytes at offset 6,7 = 0x02, 0x01
        val bytes = Prelude(DchmFormat.VERSION, DchmFormat.CIPHER_AES_256_GCM, 0x0102, noncePrefix).toByteArray()
        assertContentEquals(byteArrayOf(0x02, 0x01), bytes.copyOfRange(6, 8))
    }
}

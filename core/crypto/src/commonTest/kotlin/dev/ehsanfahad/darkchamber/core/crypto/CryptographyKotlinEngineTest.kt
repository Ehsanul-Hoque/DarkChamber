package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.getOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalStdlibApi::class)
class CryptographyKotlinEngineTest {
    private val engine: CryptoEngine = CryptographyKotlinEngine()
    private val key = ByteArray(32) { it.toByte() }
    private val aad = "aad".encodeToByteArray()

    // A distinct 12-byte nonce per seal. The JDK GCM provider refuses to reuse a (key, iv) pair for
    // encryption — the exact nonce discipline FORMAT.md §2.3 mandates — so tests must not reuse one.
    private fun nonce(tag: Int) = ByteArray(12) { (tag * 16 + it).toByte() }

    @Test
    fun aead_seal_then_open_round_trips() {
        val n = nonce(1)
        val plaintext = "top secret".encodeToByteArray()
        val ciphertext = engine.aeadSeal(key, n, aad, plaintext)
        val opened = engine.aeadOpen(key, n, aad, ciphertext).getOrNull()
        assertContentEquals(plaintext, opened)
    }

    @Test
    fun aead_open_reports_integrity_failure_when_a_ciphertext_byte_is_flipped() {
        val n = nonce(2)
        val ciphertext = engine.aeadSeal(key, n, aad, "secret".encodeToByteArray())
        ciphertext[0] = (ciphertext[0].toInt() xor 0x01).toByte()
        assertEquals(Outcome.Err(VaultError.Crypto.IntegrityFailure), engine.aeadOpen(key, n, aad, ciphertext))
    }

    @Test
    fun aead_open_reports_integrity_failure_under_the_wrong_aad() {
        val n = nonce(3)
        val ciphertext = engine.aeadSeal(key, n, aad, "secret".encodeToByteArray())
        val opened = engine.aeadOpen(key, n, "different".encodeToByteArray(), ciphertext)
        assertEquals(Outcome.Err(VaultError.Crypto.IntegrityFailure), opened)
    }

    @Test
    fun aes_256_gcm_matches_a_published_known_answer() {
        // NIST/McGrew AES-256-GCM KAT: all-zero 32-byte key, all-zero 96-bit IV, empty plaintext and
        // empty AAD produce this authentication tag. Anchors our engine to a published constant,
        // independent of our own codec.
        val ct = engine.aeadSeal(ByteArray(32), ByteArray(12), ByteArray(0), ByteArray(0))
        assertEquals("530f8afbc74536b9a963b4f1c4cb738b", ct.toHexString())
    }

    @Test
    fun sha256_matches_the_known_answer_for_abc() {
        // NIST FIPS-180 KAT: SHA-256("abc").
        val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(expected, engine.sha256("abc".encodeToByteArray()).toHexString())
    }

    @Test
    fun incremental_sha256_matches_the_one_shot_hash() {
        val data = ByteArray(1000) { (it * 7).toByte() }
        val incremental = engine.newSha256()
        incremental.update(data, 0, 400)
        incremental.update(data, 400, data.size)
        assertContentEquals(engine.sha256(data), incremental.digest())
    }

    @Test
    fun random_bytes_returns_the_requested_length_and_varies() {
        val a = engine.randomBytes(32)
        val b = engine.randomBytes(32)
        assertEquals(32, a.size)
        assertFalse(a.contentEquals(b), "two secure-random draws must not collide")
    }
}

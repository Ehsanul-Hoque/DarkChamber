package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.getOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun aead_rejects_a_wrong_length_key_or_nonce_as_misuse_not_tampering() {
        val n = nonce(9)
        assertFailsWith<IllegalArgumentException> { engine.aeadSeal(ByteArray(31), n, aad, "x".encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { engine.aeadOpen(key, ByteArray(11), aad, ByteArray(32)) }
    }

    @Test
    fun hkdf_sha256_matches_rfc5869_test_case_1() {
        // RFC 5869 Appendix A.1 (SHA-256): IKM = 22×0x0b, salt = 000102…0c, info = f0f1…f9, L = 42.
        val ikm = ByteArray(22) { 0x0b }
        val salt = "000102030405060708090a0b0c".hexToByteArray()
        val info = "f0f1f2f3f4f5f6f7f8f9".hexToByteArray()
        val okm = engine.hkdfSha256(ikm, salt, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.toHexString(),
        )
    }

    @Test
    fun pbkdf2_sha256_matches_rfc7914_known_answer() {
        // RFC 7914 §11: PBKDF2-HMAC-SHA256(P="passwd", S="salt", c=1, dkLen=64).
        val dk = engine.pbkdf2Sha256("passwd".encodeToByteArray(), "salt".encodeToByteArray(), iterations = 1, dkLenBytes = 64)
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            dk.toHexString(),
        )
    }

    @Test
    fun kdf_output_length_must_be_positive() {
        assertFailsWith<IllegalArgumentException> { engine.pbkdf2Sha256("p".encodeToByteArray(), "s".encodeToByteArray(), 1, 0) }
        assertFailsWith<IllegalArgumentException> { engine.hkdfSha256("i".encodeToByteArray(), "s".encodeToByteArray(), ByteArray(0), 0) }
        assertFailsWith<IllegalArgumentException> { engine.pbkdf2Sha256("p".encodeToByteArray(), "s".encodeToByteArray(), 0, 32) }
    }

    @Test
    fun argon2id_matches_the_reference_cli_known_answer() {
        // Canonical phc-winner-argon2 CLI vector (Argon2 1.3 / version 0x13, the pinned version):
        //   printf "password" | argon2 somesalt -id -t 2 -m 16 -p 1 -l 32
        // (-m 16 = 2^16 = 65536 KiB). Anchors BOTH actuals — BouncyCastle on the JVM host and the
        // phc cinterop on iOS — to the same published constant.
        val hash = engine.argon2id(
            password = "password".encodeToByteArray(),
            salt = "somesalt".encodeToByteArray(),
            memoryKib = 65536,
            iterations = 2,
            parallelism = 1,
            outLenBytes = 32,
        )
        assertEquals("09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7", hash.toHexString())
    }

    @Test
    fun argon2id_rejects_non_positive_parameters_as_misuse() {
        val pw = "p".encodeToByteArray()
        val salt = "somesalt".encodeToByteArray()
        assertFailsWith<IllegalArgumentException> { engine.argon2id(pw, salt, memoryKib = 0, iterations = 1, parallelism = 1, outLenBytes = 32) }
        assertFailsWith<IllegalArgumentException> { engine.argon2id(pw, salt, memoryKib = 8, iterations = 0, parallelism = 1, outLenBytes = 32) }
        assertFailsWith<IllegalArgumentException> { engine.argon2id(pw, salt, memoryKib = 8, iterations = 1, parallelism = 0, outLenBytes = 32) }
        assertFailsWith<IllegalArgumentException> { engine.argon2id(pw, salt, memoryKib = 8, iterations = 1, parallelism = 1, outLenBytes = 0) }
    }

    @Test
    fun argon2id_rejects_a_short_salt_or_empty_password_as_misuse() {
        // Keep the two actuals consistent: an empty ByteArray would crash the iOS cinterop and phc
        // rejects a <8-byte salt — fail loudly here instead of forwarding to a native precondition.
        val pw = "password".encodeToByteArray()
        assertFailsWith<IllegalArgumentException> { engine.argon2id(pw, ByteArray(7), memoryKib = 8, iterations = 1, parallelism = 1, outLenBytes = 32) }
        assertFailsWith<IllegalArgumentException> { engine.argon2id(pw, ByteArray(0), memoryKib = 8, iterations = 1, parallelism = 1, outLenBytes = 32) }
        assertFailsWith<IllegalArgumentException> { engine.argon2id(ByteArray(0), "somesalt".encodeToByteArray(), memoryKib = 8, iterations = 1, parallelism = 1, outLenBytes = 32) }
    }

    @Test
    fun random_bytes_returns_the_requested_length_and_varies() {
        val a = engine.randomBytes(32)
        val b = engine.randomBytes(32)
        assertEquals(32, a.size)
        assertFalse(a.contentEquals(b), "two secure-random draws must not collide")
    }
}

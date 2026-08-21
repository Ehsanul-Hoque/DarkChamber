package dev.ehsanfahad.darkchamber.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeyDerivationTest {
    private val engine = CryptographyKotlinEngine()
    private val kdf: KeyDerivation = DefaultKeyDerivation(engine)

    @Test
    fun pbkdf2_wrap_key_matches_the_engine_primitive_and_is_32_bytes() {
        val salt = ByteArray(16) { it.toByte() }
        val params = KdfParams.Pbkdf2(salt = salt, iterations = 1000)
        val secret = "correct horse".encodeToByteArray()

        val derived = kdf.deriveWrapKey(secret, params)

        assertEquals(WRAP_KEY_SIZE, derived.size)
        assertContentEquals(engine.pbkdf2Sha256(secret, salt, 1000, WRAP_KEY_SIZE), derived)
    }

    @Test
    fun hkdf_wrap_key_matches_the_engine_primitive_and_is_32_bytes() {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val info = "darkchamber:recovery-wrap:v1".encodeToByteArray()
        val params = KdfParams.Hkdf(salt = salt, info = info)
        val recoveryKey = ByteArray(32) { (it * 3).toByte() }

        val derived = kdf.deriveWrapKey(recoveryKey, params)

        assertEquals(WRAP_KEY_SIZE, derived.size)
        assertContentEquals(engine.hkdfSha256(recoveryKey, salt, info, WRAP_KEY_SIZE), derived)
    }

    @Test
    fun argon2id_wrap_key_matches_the_engine_primitive_and_is_32_bytes() {
        val salt = ByteArray(16) { (it + 5).toByte() }
        // Cheap params — this test derives a real Argon2id key, so keep memory/passes small.
        val params = KdfParams.Argon2id(salt = salt, memoryKib = 256, iterations = 1, parallelism = 1)
        val secret = "correct horse".encodeToByteArray()

        val derived = kdf.deriveWrapKey(secret, params)

        assertEquals(WRAP_KEY_SIZE, derived.size)
        assertContentEquals(engine.argon2id(secret, salt, 256, 1, 1, WRAP_KEY_SIZE), derived)
    }

    @Test
    fun each_params_variant_reports_its_own_kdf_id() {
        assertEquals(KdfId.PBKDF2_SHA256, KdfParams.Pbkdf2(ByteArray(16), 1000).kdfId)
        assertEquals(KdfId.HKDF_SHA256, KdfParams.Hkdf(ByteArray(16), ByteArray(4)).kdfId)
        assertEquals(KdfId.ARGON2ID, KdfParams.Argon2id(ByteArray(16), 256, 1, 1).kdfId)
    }

    @Test
    fun argon2id_params_have_value_equality() {
        assertEquals(KdfParams.Argon2id(ByteArray(16) { 7 }, 65536, 3, 2), KdfParams.Argon2id(ByteArray(16) { 7 }, 65536, 3, 2))
        assertTrue(KdfParams.Argon2id(ByteArray(16) { 7 }, 65536, 3, 2) != KdfParams.Argon2id(ByteArray(16) { 7 }, 65536, 3, 1))
    }

    @Test
    fun kdf_defaults_are_the_production_targets() {
        assertEquals(600_000, PBKDF2_DEFAULT_ITERS)
        assertEquals(65536, ARGON2ID_DEFAULT_MEMORY_KIB)
        assertEquals(3, ARGON2ID_DEFAULT_ITERATIONS)
        assertEquals(2, ARGON2ID_DEFAULT_PARALLELISM)
        assertEquals(0x13, ARGON2ID_VERSION)
    }
}

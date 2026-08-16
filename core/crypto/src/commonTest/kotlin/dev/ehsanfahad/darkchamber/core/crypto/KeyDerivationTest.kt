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
    fun each_params_variant_reports_its_own_kdf_id() {
        assertEquals(KdfId.PBKDF2_SHA256, KdfParams.Pbkdf2(ByteArray(16), 1000).kdfId)
        assertEquals(KdfId.HKDF_SHA256, KdfParams.Hkdf(ByteArray(16), ByteArray(4)).kdfId)
    }

    @Test
    fun argon2id_is_reserved_in_the_registry_but_has_no_derivable_params_yet() {
        // ARGON2ID is registered (the passphrase-slot upgrade for cycle 2c) but no KdfParams variant
        // maps to it — so a wrap key for Argon2id cannot be silently produced before the KDF exists.
        assertTrue(KdfId.ARGON2ID in KdfId.entries)
        val supportedIds = listOf(KdfParams.Pbkdf2(ByteArray(16), 1000).kdfId, KdfParams.Hkdf(ByteArray(16), ByteArray(4)).kdfId)
        assertTrue(KdfId.ARGON2ID !in supportedIds, "no params variant may claim ARGON2ID until 2c wires the KDF")
    }

    @Test
    fun default_pbkdf2_iteration_count_is_the_production_target() {
        assertEquals(600_000, PBKDF2_DEFAULT_ITERS)
    }
}

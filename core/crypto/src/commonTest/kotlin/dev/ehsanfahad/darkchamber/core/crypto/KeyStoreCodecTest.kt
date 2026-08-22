package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.getOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyStoreCodecTest {
    private fun pbkdf2Wrap() = WrapEntry(
        slot = Slot.PASSPHRASE,
        kdfId = KdfId.PBKDF2_SHA256,
        kdfParams = KdfParams.Pbkdf2(ByteArray(16) { it.toByte() }, 1000),
        nonce = ByteArray(12) { (it + 1).toByte() },
        wrappedMk = ByteArray(48) { (it + 2).toByte() },
    )

    private fun recoveryWrap() = WrapEntry(
        slot = Slot.RECOVERY,
        kdfId = KdfId.HKDF_SHA256,
        kdfParams = KdfParams.Hkdf(ByteArray(16) { (it + 5).toByte() }, RECOVERY_HKDF_INFO.encodeToByteArray()),
        nonce = ByteArray(12) { (it + 9).toByte() },
        wrappedMk = ByteArray(48) { (it + 7).toByte() },
    )

    private fun argon2idWrap() = WrapEntry(
        slot = Slot.PASSPHRASE,
        kdfId = KdfId.ARGON2ID,
        kdfParams = KdfParams.Argon2id(ByteArray(16) { (it + 3).toByte() }, memoryKib = 65536, iterations = 3, parallelism = 2),
        nonce = ByteArray(12) { (it + 4).toByte() },
        wrappedMk = ByteArray(48) { (it + 6).toByte() },
    )

    @Test
    fun encode_then_decode_round_trips_a_multi_slot_blob() {
        val blob = KeyStoreBlob(KEYSTORE_VERSION, listOf(pbkdf2Wrap(), recoveryWrap()))
        val decoded = KeyStoreCodec.decode(KeyStoreCodec.encode(blob)).getOrNull()
        assertEquals(blob, decoded)
    }

    @Test
    fun malformed_cbor_is_rejected_as_a_malformed_container() {
        assertEquals(Outcome.Err(VaultError.Crypto.MalformedContainer), KeyStoreCodec.decode(byteArrayOf(0, 1, 2, 3, 4)))
        assertEquals(Outcome.Err(VaultError.Crypto.MalformedContainer), KeyStoreCodec.decode(ByteArray(0)))
    }

    @Test
    fun a_format_version_above_supported_is_update_the_app() {
        val blob = KeyStoreBlob(KEYSTORE_VERSION + 1, listOf(pbkdf2Wrap()))
        assertEquals(Outcome.Err(VaultError.Crypto.UnsupportedFormatVersion), KeyStoreCodec.decode(KeyStoreCodec.encode(blob)))
    }

    @Test
    fun an_argon2id_wrap_round_trips() {
        // Argon2id is a supported KDF as of cycle 2c: a wrap naming it must decode, not report
        // "update the app" (its previous reserved-id behaviour).
        val blob = KeyStoreBlob(KEYSTORE_VERSION, listOf(argon2idWrap(), recoveryWrap()))
        val decoded = KeyStoreCodec.decode(KeyStoreCodec.encode(blob)).getOrNull()
        assertEquals(blob, decoded)
    }

    @Test
    fun non_positive_argon2id_params_are_malformed_not_a_crash() {
        // Mirror the PBKDF2 iterations<=0 guard: a crafted blob with any non-positive m/t/p would
        // otherwise reach the engine's require(... > 0) at unlock and throw uncaught. Reject up front.
        val bad = listOf(
            KdfParams.Argon2id(ByteArray(16), memoryKib = 0, iterations = 3, parallelism = 2),
            KdfParams.Argon2id(ByteArray(16), memoryKib = 65536, iterations = 0, parallelism = 2),
            KdfParams.Argon2id(ByteArray(16), memoryKib = 65536, iterations = 3, parallelism = 0),
        )
        for (params in bad) {
            val wrap = WrapEntry(Slot.PASSPHRASE, KdfId.ARGON2ID, params, ByteArray(12), ByteArray(48))
            val blob = KeyStoreBlob(KEYSTORE_VERSION, listOf(wrap))
            assertEquals(
                Outcome.Err(VaultError.Crypto.MalformedContainer),
                KeyStoreCodec.decode(KeyStoreCodec.encode(blob)),
                "m=${params.memoryKib} t=${params.iterations} p=${params.parallelism} must be rejected as malformed",
            )
        }
    }

    @Test
    fun a_too_short_argon2id_salt_is_malformed_not_a_crash() {
        // A crafted blob with valid m/t/p but a 0–7-byte salt would otherwise reach the derive path
        // and crash the iOS actual (empty array / phc ARGON2_SALT_TOO_SHORT). Reject at decode.
        for (saltLen in intArrayOf(0, 7)) {
            val params = KdfParams.Argon2id(ByteArray(saltLen), memoryKib = 65536, iterations = 3, parallelism = 2)
            val wrap = WrapEntry(Slot.PASSPHRASE, KdfId.ARGON2ID, params, ByteArray(12), ByteArray(48))
            val blob = KeyStoreBlob(KEYSTORE_VERSION, listOf(wrap))
            assertEquals(
                Outcome.Err(VaultError.Crypto.MalformedContainer),
                KeyStoreCodec.decode(KeyStoreCodec.encode(blob)),
                "salt length $saltLen must be rejected as malformed",
            )
        }
    }

    @Test
    fun a_kdf_id_that_disagrees_with_its_params_is_malformed() {
        val wrap = WrapEntry(Slot.PASSPHRASE, KdfId.PBKDF2_SHA256, KdfParams.Hkdf(ByteArray(16), ByteArray(4)), ByteArray(12), ByteArray(48))
        val blob = KeyStoreBlob(KEYSTORE_VERSION, listOf(wrap))
        assertEquals(Outcome.Err(VaultError.Crypto.MalformedContainer), KeyStoreCodec.decode(KeyStoreCodec.encode(blob)))
    }

    @Test
    fun a_wrong_size_nonce_is_malformed() {
        val wrap = WrapEntry(Slot.PASSPHRASE, KdfId.PBKDF2_SHA256, KdfParams.Pbkdf2(ByteArray(16), 1000), ByteArray(11), ByteArray(48))
        val blob = KeyStoreBlob(KEYSTORE_VERSION, listOf(wrap))
        assertEquals(Outcome.Err(VaultError.Crypto.MalformedContainer), KeyStoreCodec.decode(KeyStoreCodec.encode(blob)))
    }

    @Test
    fun non_positive_pbkdf2_iterations_are_malformed_not_a_crash() {
        // A crafted/corrupt blob with iterations <= 0 would otherwise reach the engine's
        // require(iterations > 0) at unlock time and throw an uncaught IllegalArgumentException.
        // The codec must reject it up front as a malformed container (D10 / FORMAT §6).
        for (iters in intArrayOf(0, -1)) {
            val wrap = WrapEntry(Slot.PASSPHRASE, KdfId.PBKDF2_SHA256, KdfParams.Pbkdf2(ByteArray(16), iters), ByteArray(12), ByteArray(48))
            val blob = KeyStoreBlob(KEYSTORE_VERSION, listOf(wrap))
            assertEquals(
                Outcome.Err(VaultError.Crypto.MalformedContainer),
                KeyStoreCodec.decode(KeyStoreCodec.encode(blob)),
                "iterations=$iters must be rejected as malformed",
            )
        }
    }

    @Test
    fun a_format_version_below_one_is_malformed() {
        val blob = KeyStoreBlob(0, listOf(pbkdf2Wrap()))
        assertEquals(Outcome.Err(VaultError.Crypto.MalformedContainer), KeyStoreCodec.decode(KeyStoreCodec.encode(blob)))
    }

    @Test
    fun an_empty_wrap_list_is_malformed() {
        val blob = KeyStoreBlob(KEYSTORE_VERSION, emptyList())
        assertEquals(Outcome.Err(VaultError.Crypto.MalformedContainer), KeyStoreCodec.decode(KeyStoreCodec.encode(blob)))
    }
}

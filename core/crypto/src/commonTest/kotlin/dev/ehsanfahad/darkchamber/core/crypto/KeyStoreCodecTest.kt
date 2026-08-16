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
    fun a_reserved_kdf_id_is_update_the_app() {
        // ARGON2ID is a known-but-not-yet-supported id (cycle 2c). A blob naming it must say
        // "update the app", not "tampering" — and this is detectable without an Argon2id params type.
        val wrap = WrapEntry(Slot.PASSPHRASE, KdfId.ARGON2ID, KdfParams.Pbkdf2(ByteArray(16), 1000), ByteArray(12), ByteArray(48))
        val blob = KeyStoreBlob(KEYSTORE_VERSION, listOf(wrap))
        assertEquals(Outcome.Err(VaultError.Crypto.UnsupportedFormatVersion), KeyStoreCodec.decode(KeyStoreCodec.encode(blob)))
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
    fun an_empty_wrap_list_is_malformed() {
        val blob = KeyStoreBlob(KEYSTORE_VERSION, emptyList())
        assertEquals(Outcome.Err(VaultError.Crypto.MalformedContainer), KeyStoreCodec.decode(KeyStoreCodec.encode(blob)))
    }
}

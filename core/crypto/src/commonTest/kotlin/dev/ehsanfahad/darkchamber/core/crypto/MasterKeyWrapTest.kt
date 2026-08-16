package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.getOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class MasterKeyWrapTest {
    private val engine = CryptographyKotlinEngine()
    private val kdf = DefaultKeyDerivation(engine)
    private val mk = ByteArray(MK_SIZE) { it.toByte() }
    private val passphrase = "correct horse battery staple".encodeToByteArray()
    private val pbkdf2 = KdfParams.Pbkdf2(ByteArray(16) { it.toByte() }, 1000)

    // A fresh nonce per wrap — the JDK GCM provider refuses to reuse a (key, iv) pair for encryption,
    // exactly the discipline production follows (VaultSession draws a random nonce per wrap).
    private fun passphraseWrap() =
        MasterKeyWrap.wrap(engine, kdf, mk, passphrase, Slot.PASSPHRASE, pbkdf2, engine.randomBytes(WRAP_NONCE_SIZE))

    @Test
    fun passphrase_wrap_then_unwrap_recovers_the_master_key() {
        val entry = passphraseWrap()
        assertContentEquals(mk, MasterKeyWrap.unwrap(engine, kdf, entry, passphrase).getOrNull())
    }

    @Test
    fun recovery_wrap_then_unwrap_recovers_the_master_key() {
        val recoveryKey = engine.randomBytes(RECOVERY_KEY_SIZE)
        val hkdf = KdfParams.Hkdf(ByteArray(16) { (it + 1).toByte() }, RECOVERY_HKDF_INFO.encodeToByteArray())
        val entry = MasterKeyWrap.wrap(engine, kdf, mk, recoveryKey, Slot.RECOVERY, hkdf, engine.randomBytes(WRAP_NONCE_SIZE))
        assertContentEquals(mk, MasterKeyWrap.unwrap(engine, kdf, entry, recoveryKey).getOrNull())
    }

    @Test
    fun a_wrong_secret_fails_with_integrity_failure() {
        val entry = passphraseWrap()
        val wrong = "wrong passphrase".encodeToByteArray()
        assertEquals(Outcome.Err(VaultError.Crypto.IntegrityFailure), MasterKeyWrap.unwrap(engine, kdf, entry, wrong))
    }

    @Test
    fun a_flipped_ciphertext_byte_fails_with_integrity_failure() {
        val entry = passphraseWrap()
        val tampered = entry.wrappedMk.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val bad = WrapEntry(entry.slot, entry.kdfId, entry.kdfParams, entry.nonce, tampered)
        assertEquals(Outcome.Err(VaultError.Crypto.IntegrityFailure), MasterKeyWrap.unwrap(engine, kdf, bad, passphrase))
    }

    @Test
    fun a_tampered_nonce_fails_with_integrity_failure() {
        val entry = passphraseWrap()
        val badNonce = entry.nonce.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val bad = WrapEntry(entry.slot, entry.kdfId, entry.kdfParams, badNonce, entry.wrappedMk)
        assertEquals(Outcome.Err(VaultError.Crypto.IntegrityFailure), MasterKeyWrap.unwrap(engine, kdf, bad, passphrase))
    }

    @Test
    fun repointing_the_slot_fails_because_it_is_bound_as_aad() {
        // Same secret and KDF params → identical wrap key, so only the AAD binding of `slot` can catch
        // this. Proves the metadata is authenticated, not just the ciphertext.
        val entry = passphraseWrap()
        val repointed = WrapEntry(Slot.RECOVERY, entry.kdfId, entry.kdfParams, entry.nonce, entry.wrappedMk)
        assertEquals(Outcome.Err(VaultError.Crypto.IntegrityFailure), MasterKeyWrap.unwrap(engine, kdf, repointed, passphrase))
    }
}

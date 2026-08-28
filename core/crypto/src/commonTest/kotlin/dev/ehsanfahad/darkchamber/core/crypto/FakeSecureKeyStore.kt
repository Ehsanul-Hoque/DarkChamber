package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.flatMap

/**
 * Host test double for [SecureKeyStore]. Stands in for a platform Keystore/Keychain precisely
 * enough to drive every HARDWARE-slot integration path on the JVM host (and the iOS simulator),
 * where no real secure hardware is reachable.
 *
 * The "hardware key" is an in-memory random 32-byte AES key held only in this object; [wrap] seals
 * under it via [CryptoEngine] with a fresh nonce (blob = `nonce ‖ ciphertext`), [unwrap] reverses
 * it, and a GCM tag mismatch or a deleted key maps to
 * [VaultError.Crypto.IntegrityFailure] — the same contract the real actuals must honour.
 * [deleteHardwareKey] drops the key (idempotent); a later [wrap] regenerates a fresh one, so a
 * remove-then-re-add cycle produces a genuinely new key, matching platform behaviour.
 *
 * @param available what [isAvailable] reports (a device without secure hardware sets this false).
 */
class FakeSecureKeyStore(
    private val engine: CryptoEngine = CryptographyKotlinEngine(),
    private val available: Boolean = true,
) : SecureKeyStore {
    private var key: ByteArray? = engine.randomBytes(MK_SIZE)

    override fun isAvailable(): Boolean = available

    override suspend fun wrap(mk: ByteArray, authenticator: BiometricAuthenticator): Outcome<ByteArray> =
        // The prompt gates use of the key (as a biometric-required platform key does); on approve
        // seal under it, on cancel/invalidation propagate the authenticator's error unchanged.
        authenticator.authenticate(AUTH_REQUEST).flatMap {
            val k = key ?: engine.randomBytes(MK_SIZE).also { key = it }
            val nonce = engine.randomBytes(WRAP_NONCE_SIZE)
            Outcome.Ok(nonce + engine.aeadSeal(k, nonce, ByteArray(0), mk))
        }

    override suspend fun unwrap(blob: ByteArray, authenticator: BiometricAuthenticator): Outcome<ByteArray> =
        authenticator.authenticate(AUTH_REQUEST).flatMap {
            val k = key ?: return@flatMap Outcome.Err(VaultError.Crypto.IntegrityFailure)
            if (blob.size < WRAP_NONCE_SIZE) return@flatMap Outcome.Err(VaultError.Crypto.IntegrityFailure)
            val nonce = blob.copyOfRange(0, WRAP_NONCE_SIZE)
            val ciphertext = blob.copyOfRange(WRAP_NONCE_SIZE, blob.size)
            engine.aeadOpen(k, nonce, ByteArray(0), ciphertext)
        }

    override fun deleteHardwareKey() {
        key = null
    }

    private companion object {
        val AUTH_REQUEST = BiometricRequest(title = "Unlock vault", cancelLabel = "Cancel")
    }
}

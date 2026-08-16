package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

/**
 * Wraps and unwraps the MasterKey under a user secret (FORMAT.md §3.1). One [WrapEntry] is
 * `AES-256-GCM(deriveWrapKey(secret, params), MK)` with the wrap's own metadata bound as AAD, so an
 * attacker cannot swap the KDF or its parameters (e.g. lower the iteration count, or repoint the
 * slot) without breaking authentication — a tamper surfaces as
 * [dev.ehsanfahad.darkchamber.core.model.VaultError.Crypto.IntegrityFailure], never a silent
 * downgrade. All cipher work goes through [CryptoEngine] (hard rule #2); the derived wrap key never
 * escapes and is zeroized on every path.
 */
object MasterKeyWrap {
    private val cbor = Cbor { }

    @Serializable
    private class WrapAad(val slot: Slot, val kdfId: KdfId, val kdfParams: KdfParams)

    private fun aad(slot: Slot, kdfId: KdfId, params: KdfParams): ByteArray =
        cbor.encodeToByteArray(WrapAad.serializer(), WrapAad(slot, kdfId, params))

    /** Seal [mk] for [slot] with the key derived from [secret]; [nonce] must be freshly random. */
    fun wrap(
        engine: CryptoEngine,
        kdf: KeyDerivation,
        mk: ByteArray,
        secret: ByteArray,
        slot: Slot,
        params: KdfParams,
        nonce: ByteArray,
    ): WrapEntry {
        require(mk.size == MK_SIZE) { "MasterKey must be $MK_SIZE bytes, was ${mk.size}" }
        require(nonce.size == WRAP_NONCE_SIZE) { "wrap nonce must be $WRAP_NONCE_SIZE bytes, was ${nonce.size}" }
        val wrapKey = kdf.deriveWrapKey(secret, params)
        return try {
            val wrappedMk = engine.aeadSeal(wrapKey, nonce, aad(slot, params.kdfId, params), mk)
            WrapEntry(slot, params.kdfId, params, nonce, wrappedMk)
        } finally {
            wrapKey.fill(0)
        }
    }

    /** Recover MK from [entry] using [secret]. A wrong secret or any tamper → `IntegrityFailure`. */
    fun unwrap(engine: CryptoEngine, kdf: KeyDerivation, entry: WrapEntry, secret: ByteArray): Outcome<ByteArray> {
        val wrapKey = kdf.deriveWrapKey(secret, entry.kdfParams)
        return try {
            engine.aeadOpen(wrapKey, entry.nonce, aad(entry.slot, entry.kdfId, entry.kdfParams), entry.wrappedMk)
        } finally {
            wrapKey.fill(0)
        }
    }
}

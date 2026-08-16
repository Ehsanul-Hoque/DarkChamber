package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.serialization.cbor.Cbor

/** Current KeyStore blob format version. Readers reject anything higher (FORMAT.md §3.1 / §6). */
const val KEYSTORE_VERSION = 1

/** MasterKey length, in bytes (256-bit). */
const val MK_SIZE = 32

/** GCM nonce length for every MK wrap. */
const val WRAP_NONCE_SIZE = 12

/** KDFs this version can actually derive a wrap key with. `ARGON2ID` is reserved → not yet here. */
val SUPPORTED_KDFS = setOf(KdfId.PBKDF2_SHA256, KdfId.HKDF_SHA256)

/**
 * CBOR (de)serialization of the [KeyStoreBlob] with the FORMAT.md §3.1 / §6 reject taxonomy. Reuses
 * the existing [VaultError] classes (no new error types):
 * - malformed/undecodable bytes, structural violations, or a `kdfId`/`kdfParams` mismatch →
 *   [VaultError.Crypto.MalformedContainer]
 * - a `formatVersion` above [KEYSTORE_VERSION], or an unsupported/reserved `kdfId` →
 *   [VaultError.Crypto.UnsupportedFormatVersion] (the "update the app" path, distinct from tampering)
 */
object KeyStoreCodec {
    private val cbor = Cbor { }

    fun encode(blob: KeyStoreBlob): ByteArray = cbor.encodeToByteArray(KeyStoreBlob.serializer(), blob)

    fun decode(bytes: ByteArray): Outcome<KeyStoreBlob> {
        val blob = try {
            cbor.decodeFromByteArray(KeyStoreBlob.serializer(), bytes)
        } catch (_: Throwable) {
            return Outcome.Err(VaultError.Crypto.MalformedContainer)
        }

        if (blob.formatVersion > KEYSTORE_VERSION) return Outcome.Err(VaultError.Crypto.UnsupportedFormatVersion)
        if (blob.wraps.isEmpty()) return Outcome.Err(VaultError.Crypto.MalformedContainer)

        for (wrap in blob.wraps) {
            // An unsupported/reserved KDF is an "update the app" condition, checked before the
            // structural consistency guard so a reserved Argon2id wrap reports the right reason.
            if (wrap.kdfId !in SUPPORTED_KDFS) return Outcome.Err(VaultError.Crypto.UnsupportedFormatVersion)
            if (wrap.kdfId != wrap.kdfParams.kdfId) return Outcome.Err(VaultError.Crypto.MalformedContainer)
            if (wrap.nonce.size != WRAP_NONCE_SIZE) return Outcome.Err(VaultError.Crypto.MalformedContainer)
            if (wrap.wrappedMk.isEmpty()) return Outcome.Err(VaultError.Crypto.MalformedContainer)
            // Reject KDF parameters the derivation would otherwise trip an uncaught precondition on
            // at unlock time (the engine requires PBKDF2 iterations > 0). A crafted/corrupt blob must
            // surface as MalformedContainer here — never crash the unlock path (D10 / §6).
            val params = wrap.kdfParams
            if (params is KdfParams.Pbkdf2 && params.iterations <= 0) {
                return Outcome.Err(VaultError.Crypto.MalformedContainer)
            }
        }
        return Outcome.Ok(blob)
    }
}
